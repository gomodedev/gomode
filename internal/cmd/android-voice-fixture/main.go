// Serves a local Go Mode service and voice gateway for Android instrumented tests.

// Copyright 2026 Marc-Antoine Ruel. All Rights Reserved. Use of this
// source code is governed by the GNU Affero General Public License v3 that
// can be found in the LICENSE file.

package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"log/slog"
	"net"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/gomodedev/gomode"
	"github.com/gomodedev/gomode/internal/voicetest"
	"github.com/gomodedev/gomode/mcp"
	voicev2 "github.com/gomodedev/gomode/voicegateway/api/v2"
)

// fixture serves the root settings document, a minimal MCP endpoint, and the
// voice gateway routes the Android app calls. Its synthetic assistant supports
// real text WebSockets and bidirectional Opus media over WebRTC.
type fixture struct {
	log      *slog.Logger
	rtcIP    string
	mu       sync.Mutex
	stats    fixtureStats
	peers    map[string]*mediaPeer
	settings gate
	frontend gate
	textPeer *websocket.Conn
}

// gate holds requests until a test opens it through /fixture/config, so a test
// observes an exact startup stage instead of racing a fixed delay. The fixture
// mutex guards it.
type gate struct {
	closed  bool
	changed chan struct{}
}

func newGate() gate {
	return gate{changed: make(chan struct{})}
}

func (g *gate) setLocked(closed bool) {
	if g.closed != closed {
		g.closed = closed
		close(g.changed)
		g.changed = make(chan struct{})
	}
}

// waitLocked returns with f.mu held, reporting false when the client abandons
// the request first.
func (f *fixture) waitLocked(r *http.Request, g *gate) bool {
	for g.closed {
		changed := g.changed
		f.mu.Unlock()
		select {
		case <-changed:
		case <-r.Context().Done():
			f.mu.Lock()
			return false
		}
		f.mu.Lock()
	}
	return true
}

func (f *fixture) routes() http.Handler {
	f.settings = newGate()
	f.frontend = newGate()
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(`<!doctype html><html lang="en"><title>Voice fixture</title>
<h1>Voice fixture server</h1><button onclick="revokeFrontend()">Fixture sign out</button>
<p id="counter">Tool count: 0</p><script>
document.body.append(document.createTextNode(location.href));
const binding = window.goModeFrontendDocument;
let documentID = '', token = null, dataReady = false, count = 0, request = 0;
const tools = ['fixture_echo','fixture_logout','fixture_reload','fixture_state'].map(name =>
  ({name,description:'Exercise the hosted document',parameters:{type:'object',properties:{}}}));
function publishPreparation() {
  if (token) gomodeFrontendReadiness.postMessage(JSON.stringify({document:documentID,token,scope:String(request),ready:dataReady,tools:dataReady?tools:[]}));
}
function revokeFrontend() {
  dataReady = false; publishPreparation();
  const frontendRequest = String(++request);
  binding.then(document => gomodeAuth.postMessage(JSON.stringify({authChanging:true,frontendDocument:document,frontendRequest})));
}
window.dispatchGoModeVoiceTool = call => {
  if (call.document !== documentID || call.token !== token || call.scope !== String(request)) return;
  Promise.resolve().then(() => executeTool(call.name,call.args)).then(result =>
    gomodeFrontendToolResult.postMessage(JSON.stringify({...call,result})));
};
window.cancelGoModeVoiceTool = () => {};
function executeTool(name, args) {
  if (name === 'fixture_logout') revokeFrontend();
  if (name === 'fixture_reload') location.reload();
  if (name !== 'fixture_state') document.getElementById('counter').textContent = 'Tool count: ' + (++count);
  return {url:location.href, document:documentID, count, visible:goModeHost.isPageVisible(), connected:goModeHost.isVoiceConnected()};
};
binding.then(document => {
  documentID = document;
  const frontendRequest = String(++request);
  gomodeAuth.onmessage = event => {
    const value = JSON.parse(event.data);
    if (value.frontendDocument !== document || value.frontendRequest !== frontendRequest || request !== Number(frontendRequest)) return;
    token = value.frontendToken;
    publishPreparation();
    fetch('/fixture/frontend').then(response => response.json()).then(() => {
      if (request !== Number(frontendRequest)) return;
      dataReady = true; publishPreparation();
      return fetch('/fixture/frontend-published', {method:'POST'});
    }).catch(error => console.error('Fixture preparation failed', error));
  };
  gomodeAuth.postMessage(JSON.stringify({authChanged:true,nativeAccess:true,frontendDocument:document,frontendRequest}));
});
</script></html>`))
	})
	mux.HandleFunc("GET /.well-known/gomode.json", f.handleSettings)
	mux.HandleFunc("POST /api/mcp", f.handleMCP)
	mux.HandleFunc("GET /api/voicegateway/v2/voice/choices", f.handleChoices)
	mux.HandleFunc("GET /api/voicegateway/v2/voice/health", f.handleHealth)
	mux.HandleFunc("GET /api/voicegateway/v2/voice/text", f.handleTextSession)
	mux.HandleFunc("POST /api/voicegateway/v2/voice/rtc/offer", f.handleOffer)
	mux.HandleFunc("POST /api/voicegateway/v2/voice/rtc/{sessionID}", f.handleClose)
	mux.HandleFunc("GET /fixture/stats", f.handleStats)
	mux.HandleFunc("POST /fixture/config", f.handleConfig)
	mux.HandleFunc("POST /fixture/tool", f.handleTool)
	mux.HandleFunc("POST /fixture/interrupt", f.handleInterrupt)
	mux.HandleFunc("POST /fixture/drop", f.handleDrop)
	mux.HandleFunc("GET /fixture/frontend", f.handleFrontend)
	mux.HandleFunc("POST /fixture/frontend-published", func(w http.ResponseWriter, _ *http.Request) {
		f.mu.Lock()
		f.stats.FrontendPublished++
		f.mu.Unlock()
		writeJSON(w, map[string]bool{"ok": true})
	})
	return mux
}

// settingsDocument is the manifest the app fetches. The voice gateway URL is a
// same-origin path, so the app resolves an embedded gateway with no token
// endpoint and sends no service authorization.
func (f *fixture) settingsDocument() gomode.Settings {
	return gomode.Settings{
		Service:        "gomode-fixture",
		ServiceVersion: "1.0.0",
		APIVersion:     1,
		WebShell: gomode.WebShellSettings{
			BridgeVersion: 3,
			ToolGroups: []gomode.ToolGroup{{
				Name:            "fixture",
				Description:     "Fixture task tools.",
				Endpoint:        "/api/mcp",
				ProtocolVersion: mcp.ProtocolVersion,
			}},
			VoiceGateway: gomode.VoiceGatewaySettings{URL: "/"},
		},
	}
}

func (f *fixture) handleSettings(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	f.stats.SettingsRequests++
	served := f.waitLocked(r, &f.settings)
	if served {
		f.stats.SettingsServed++
	} else {
		f.stats.SettingsAbandoned++
	}
	f.mu.Unlock()
	if !served {
		return
	}
	f.log.InfoContext(r.Context(), "settings", "path", r.URL.Path)
	writeJSON(w, f.settingsDocument())
}

// mcpRequest is the subset of a JSON-RPC 2.0 request the fixture answers.
type mcpRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
}

// handleMCP answers the MCP calls that voice setup makes. Resource calls return
// an empty list: the app treats service context as advisory.
func (f *fixture) handleMCP(w http.ResponseWriter, r *http.Request) {
	var req mcpRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid JSON-RPC request", http.StatusBadRequest)
		return
	}
	f.log.InfoContext(r.Context(), "mcp", "method", req.Method)
	var result any
	switch req.Method {
	case "server/discover":
		result = mcp.ServerDiscoverResult{
			ResultType:        mcp.ResultTypeComplete,
			SupportedVersions: []string{mcp.ProtocolVersion},
			ServerInfo:        mcp.Implementation{Name: "android-voice-fixture", Version: "1.0.0"},
			Instructions:      "Fixture service. Answer in one word.",
			TTLMS:             mcp.DefaultTTLMS,
			CacheScope:        mcp.CacheScopePrivate,
		}
	case "tools/list":
		f.mu.Lock()
		f.stats.MCPToolLists++
		f.mu.Unlock()
		result = mcp.ToolsListResult{
			ResultType: mcp.ResultTypeComplete,
			Tools:      []mcp.ToolDescriptor{},
			TTLMS:      mcp.DefaultTTLMS,
			CacheScope: mcp.CacheScopePrivate,
		}
	case "resources/list":
		result = mcp.ResourcesListResult{
			ResultType: mcp.ResultTypeComplete,
			Resources:  []mcp.ResourceDescriptor{},
			TTLMS:      mcp.DefaultTTLMS,
			CacheScope: mcp.CacheScopePrivate,
		}
	default:
		writeJSON(w, map[string]any{
			"jsonrpc": "2.0",
			"id":      req.ID,
			"error":   map[string]any{"code": -32601, "message": "method not found: " + req.Method},
		})
		return
	}
	writeJSON(w, map[string]any{"jsonrpc": "2.0", "id": req.ID, "result": result})
}

func (f *fixture) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, map[string]any{"status": "ok"})
}

// handleChoices advertises one composable choice with every stage ownership.
func (f *fixture) handleChoices(w http.ResponseWriter, _ *http.Request) {
	client, gateway := voicev2.SpeechLocationClient, voicev2.SpeechLocationGateway
	writeJSON(w, voicev2.VoiceChoicesResp{
		Choices: []voicev2.VoiceChoice{{
			ID:         "fixture",
			Label:      "Fixture text",
			Transports: []voicev2.VoiceTransport{voicev2.VoiceTransportText, voicev2.VoiceTransportWebRTC},
			SpeechModes: []voicev2.SpeechConfig{
				{Input: client, Output: client},
				{Input: client, Output: gateway},
				{Input: gateway, Output: client},
				{Input: gateway, Output: gateway},
			},
			Composable: true,
		}},
		RecognitionModels: []voicev2.RecognitionModel{{ID: "fixture-asr", Label: "Fixture recognition"}},
		SynthesisModels:   []voicev2.SynthesisModel{{ID: "fixture-tts", Label: "Fixture speech"}},
		Defaults: []voicev2.VoiceChoiceDefault{
			{Transport: voicev2.VoiceTransportText, ID: "fixture"},
			{Transport: voicev2.VoiceTransportWebRTC, ID: "fixture"},
		},
	})
}

// handleTextSession answers one text session: setup, then a canned assistant
// turn for every user message.
func (f *fixture) handleTextSession(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		f.log.WarnContext(r.Context(), "accept text session", "err", err)
		return
	}
	defer func() { _ = conn.CloseNow() }()
	f.mu.Lock()
	f.stats.TextActive++
	f.textPeer = conn
	f.mu.Unlock()
	defer func() {
		f.mu.Lock()
		f.stats.TextActive--
		if f.textPeer == conn {
			f.textPeer = nil
		}
		f.mu.Unlock()
	}()
	ctx := r.Context()
	f.log.InfoContext(ctx, "text session open")
	for {
		_, data, err := conn.Read(ctx)
		if err != nil {
			f.log.InfoContext(ctx, "text session closed", "err", err)
			return
		}
		var env voicev2.MessageEnvelope
		if err := json.Unmarshal(data, &env); err != nil {
			f.log.WarnContext(ctx, "decode client message", "err", err)
			continue
		}
		f.log.InfoContext(ctx, "client message", "kind", env.Kind)
		switch env.Kind {
		case voicev2.MessageKindSessionSetup:
			f.mu.Lock()
			f.stats.TextSetups++
			f.mu.Unlock()
			for _, reply := range voicetest.GatewayReplies(env.Kind) {
				if !writeMessage(ctx, f.log, conn, reply) {
					return
				}
			}
		case voicev2.MessageKindUserMessage:
			for _, reply := range voicetest.GatewayReplies(env.Kind) {
				if !writeMessage(ctx, f.log, conn, reply) {
					return
				}
			}
			f.mu.Lock()
			f.stats.TextTurns++
			f.mu.Unlock()
		case voicev2.MessageKindSessionClose:
			_ = conn.Close(websocket.StatusNormalClosure, "closed")
			return
		case voicev2.MessageKindToolResult:
			f.recordToolResult(data)
		case voicev2.MessageKindContextUpdate, voicev2.MessageKindTurnCancel:
			// Valid client messages the fixture does not act on: it never calls
			// tools or runs a cancellable turn. The "client message" log above
			// records each one.
		default:
			// Gateway-to-client kinds are invalid from a client.
			f.log.WarnContext(ctx, "unexpected client message kind", "kind", env.Kind)
		}
	}
}

func writeMessage(ctx context.Context, log *slog.Logger, conn *websocket.Conn, msg any) bool {
	data, err := json.Marshal(msg)
	if err != nil {
		log.ErrorContext(ctx, "encode gateway message", "err", err)
		return false
	}
	if err := conn.Write(ctx, websocket.MessageText, data); err != nil {
		log.WarnContext(ctx, "write gateway message", "err", err)
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, body any) {
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(body); err != nil {
		slog.Error("write JSON response", "err", err)
	}
}

// run serves the synthetic gateway on addr and the production gateway on
// productionAddr until ctx ends or a server fails.
func run(ctx context.Context, f *fixture, addr, productionAddr string) (err error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var lc net.ListenConfig
	listener, err := lc.Listen(ctx, "tcp", addr)
	if err != nil {
		return err
	}
	productionListener, err := lc.Listen(ctx, "tcp", productionAddr)
	if err != nil {
		return errors.Join(err, listener.Close())
	}
	production, err := newProductionGateway(ctx, f)
	if err != nil {
		return errors.Join(err, listener.Close(), productionListener.Close())
	}
	defer func() { err = errors.Join(err, production.close(context.WithoutCancel(ctx))) }()
	service := f.routes()
	servers := []*http.Server{
		{Handler: service, ReadHeaderTimeout: 5 * time.Second, BaseContext: func(net.Listener) context.Context { return ctx }},
		{Handler: production.routes(service), ReadHeaderTimeout: 5 * time.Second, BaseContext: func(net.Listener) context.Context { return ctx }},
	}
	// The runner reads these lines to learn the ports when the addresses use port 0.
	f.log.InfoContext(ctx, "listening", "addr", listener.Addr().String())
	f.log.InfoContext(ctx, "production listening", "addr", productionListener.Addr().String())
	errs := make(chan error, 2)
	for i, l := range []net.Listener{listener, productionListener} {
		go func() { errs <- servers[i].Serve(l) }()
	}
	select {
	case err = <-errs:
	case <-ctx.Done():
	}
	for _, srv := range servers {
		err = errors.Join(err, srv.Close())
	}
	return err
}

func main() {
	addr := flag.String("addr", "127.0.0.1:0", "listen address")
	productionAddr := flag.String("production-addr", "127.0.0.1:0", "production gateway listen address")
	rtcIP := flag.String("rtc-ip", "", "host candidate address visible from the emulator (10.0.2.2)")
	flag.Parse()
	f := &fixture{rtcIP: *rtcIP}
	// voicertc logs through the default logger; the recorder exposes its
	// session cleanup causes to tests.
	log := slog.New(cleanupRecorder{Handler: slog.NewTextHandler(os.Stderr, nil), fixture: f})
	slog.SetDefault(log)
	f.log = log
	if err := run(context.Background(), f, *addr, *productionAddr); err != nil {
		log.Error("android-voice-fixture", "err", err)
		os.Exit(1)
	}
}
