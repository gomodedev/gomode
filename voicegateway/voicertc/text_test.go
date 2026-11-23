// WebSocket text session behavior tests.

package voicertc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/gomodedev/gomode/internal/testlog"
	"github.com/gomodedev/gomode/internal/voicetest"
	voicev2 "github.com/gomodedev/gomode/voicegateway/api/v2"
)

func TestServeTextSessionCloseLogging(t *testing.T) { //nolint:paralleltest // The cases replace the process logger.
	// These cases change the process logger, so do not run them in parallel.
	for _, tc := range []struct { //nolint:paralleltest // The cases replace the process logger.
		name    string
		status  websocket.StatusCode
		abrupt  bool
		wantLog bool
	}{
		{name: "normal closure", status: websocket.StatusNormalClosure},
		{name: "going away", status: websocket.StatusGoingAway},
		{name: "policy violation", status: websocket.StatusPolicyViolation, wantLog: true},
		{name: "abrupt disconnect", abrupt: true, wantLog: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var logs bytes.Buffer
			bridge := newTestTextBridge(t, fixedConversationLLM{conv: &fakeConversation{}})
			bridge.log = slog.New(slog.NewTextHandler(&logs, nil))
			done := make(chan error, 1)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				done <- bridge.ServeTextSession(r.Context(), w, r)
			}))
			t.Cleanup(server.Close)
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			t.Cleanup(cancel)
			conn, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(server.URL, "http"), nil)
			if resp != nil && resp.Body != nil {
				_ = resp.Body.Close()
			}
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = conn.CloseNow() })
			// Wait until the server owns the socket: an immediate disconnect
			// can cancel the HTTP request context before Hijack completes.
			writeMessage(t, ctx, conn, voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup})
			expectKind(t, ctx, conn, voicev2.MessageKindSessionReady)
			if tc.abrupt {
				err = conn.CloseNow()
			} else {
				err = conn.Close(tc.status, "")
			}
			if err != nil {
				t.Fatal(err)
			}
			select {
			case err := <-done:
				if err != nil {
					t.Fatal(err)
				}
			case <-ctx.Done():
				t.Fatal("text session did not end")
			}
			if got := strings.Contains(logs.String(), "voicertc: text session closed"); got != tc.wantLog {
				t.Fatalf("disconnect log = %t, want %t; logs: %s", got, tc.wantLog, logs.String())
			}
		})
	}
}

var update = flag.Bool("update", false, "rewrite internal/voicetest/testdata/text_session.json")

// TestServeTextSessionTrace records a production text session for the client
// tests and fixtures that replay internal/voicetest/testdata/text_session.json.
func TestServeTextSessionTrace(t *testing.T) {
	t.Parallel()
	conv := &fakeConversation{userStep: fakeLLMStep{deltas: []string{"Ready."}, reply: llmReply{text: "Ready."}}}
	conn := dialTextSession(t, newTestTextBridge(t, fixedConversationLLM{conv: conv}))
	ctx := t.Context()
	var got []voicetest.Step
	responseIDs := map[string]string{}
	for _, msg := range []any{
		voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup},
		voicev2.UserMessage{Kind: voicev2.MessageKindUserMessage, Text: "Hello"},
		voicev2.ContextUpdate{Kind: voicev2.MessageKindContextUpdate, Context: voicev2.Context{Text: "Build is waiting"}, Generate: true},
	} {
		writeMessage(t, ctx, conn, msg)
		data, err := json.Marshal(msg)
		if err != nil {
			t.Fatal(err)
		}
		got = append(got, voicetest.Step{From: voicetest.Client, Message: data})
		// Each client message ends at readiness or at the turn it starts.
		for done := false; !done; {
			kind, data := readMessage(t, ctx, conn)
			switch kind {
			case voicev2.MessageKindSessionReady:
				done = true
			case voicev2.MessageKindTurnStatus:
				var status voicev2.TurnStatus
				if err := json.Unmarshal(data, &status); err != nil {
					t.Fatal(err)
				}
				done = status.State == voicev2.TurnStateIdle
			case voicev2.MessageKindAssistantTextDelta:
				// Response IDs are random; keep only their identity.
				var delta voicev2.AssistantTextDelta
				if err := json.Unmarshal(data, &delta); err != nil {
					t.Fatal(err)
				}
				id, ok := responseIDs[delta.ResponseID]
				if !ok {
					id = fmt.Sprintf("response-%d", len(responseIDs)+1)
					responseIDs[delta.ResponseID] = id
				}
				delta.ResponseID = id
				if data, err = json.Marshal(delta); err != nil {
					t.Fatal(err)
				}
			default:
			}
			got = append(got, voicetest.Step{From: voicetest.Gateway, Message: data})
		}
	}
	var b bytes.Buffer
	b.WriteString("[\n")
	for i, s := range got {
		line, err := json.Marshal(s)
		if err != nil {
			t.Fatal(err)
		}
		b.WriteString("  ")
		b.Write(line)
		if i < len(got)-1 {
			b.WriteString(",")
		}
		b.WriteString("\n")
	}
	b.WriteString("]\n")
	const path = "../../internal/voicetest/testdata/text_session.json"
	if *update {
		if err := os.WriteFile(path, b.Bytes(), 0o600); err != nil {
			t.Fatal(err)
		}
		return
	}
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := b.String(); got != string(want) {
		t.Fatalf("text session differs from %s; rerun with -update and review the diff\nwant:\n%s\ngot:\n%s", path, want, got)
	}
}

// lifecycleRecorder records ordered lifecycle events from test doubles.
type lifecycleRecorder struct {
	mu     sync.Mutex
	events []string
}

func (r *lifecycleRecorder) add(event string) {
	r.mu.Lock()
	r.events = append(r.events, event)
	r.mu.Unlock()
}

func (r *lifecycleRecorder) snapshot() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return slices.Clone(r.events)
}

type recordingBackendRuntime struct {
	rec *lifecycleRecorder
}

func (r recordingBackendRuntime) Close() error {
	r.rec.add("backend-close")
	return nil
}

// blockingTurnConversation blocks its turn until the turn context is cancelled.
type blockingTurnConversation struct {
	rec      *lifecycleRecorder
	entered  chan struct{}
	canceled chan struct{}
	release  chan struct{}
}

func (c *blockingTurnConversation) user(ctx context.Context, _ string) (llmStep, error) {
	close(c.entered)
	<-ctx.Done()
	close(c.canceled)
	<-c.release
	c.rec.add("turn-returned")
	return llmStep{}, ctx.Err()
}

func (c *blockingTurnConversation) toolResult(context.Context, string, string, json.RawMessage) (llmStep, error) {
	return llmStep{}, nil
}

func (c *blockingTurnConversation) addContext(string) {}

// blockingFirstConversation blocks its first turn until that turn's context is
// cancelled, then answers later turns with text.
type blockingFirstConversation struct {
	mu            sync.Mutex
	calls         int
	finished      atomic.Int32
	started       chan struct{}
	finishedFirst chan struct{}
	startOnce     sync.Once
	finishOnce    sync.Once
}

func (c *blockingFirstConversation) user(ctx context.Context, _ string) (llmStep, error) {
	c.mu.Lock()
	c.calls++
	call := c.calls
	c.mu.Unlock()
	if call == 1 {
		c.startOnce.Do(func() { close(c.started) })
		return llmStep{
			stream: func(func(llmFragment) bool) {
				<-ctx.Done()
			},
			finish: func() (llmReply, error) {
				c.finished.Add(1)
				c.finishOnce.Do(func() { close(c.finishedFirst) })
				return llmReply{text: "cancelled"}, nil
			},
		}, nil
	}
	return newLLMStep([]string{"second"}, llmReply{text: "second"}, nil), nil
}

func (c *blockingFirstConversation) toolResult(context.Context, string, string, json.RawMessage) (llmStep, error) {
	return llmStep{}, nil
}

func (c *blockingFirstConversation) addContext(string) {}

// newTestTextBridge closes the bridge at cleanup, which waits for its text
// sessions: httptest does not wait for hijacked WebSocket handlers. Tests that
// call CloseAll themselves use buildTestTextBridge.
func newTestTextBridge(t *testing.T, llm llmAdapter) *Bridge {
	b := buildTestTextBridge(t, llm)
	t.Cleanup(func() { b.CloseAll(t.Context()) })
	return b
}

func buildTestTextBridge(t *testing.T, llm llmAdapter) *Bridge {
	textCtx, textCancel := context.WithCancel(t.Context())
	t.Cleanup(textCancel)
	backend := newTestLocalStackBackend(t, nil, llm, nil)
	choices, choiceErr := defaultBackendChoices(t.Context(), backend)
	if choiceErr != nil {
		t.Fatal(choiceErr)
	}
	return &Bridge{
		log:            testlog.New(t),
		backends:       map[string]backendConnector{"default": backend},
		choices:        choices,
		activityLogDir: t.TempDir(),
		textCtx:        textCtx,
		textCancel:     textCancel,
	}
}

type textlessBackend struct{}

func (textlessBackend) Close() error { return nil }

func (textlessBackend) connect(context.Context, string, backendSink) (backendSession, error) {
	return nil, errors.New("backend does not connect")
}

func TestTextSinkEscalatesLogFailure(t *testing.T) {
	t.Parallel()
	log, err := openActivityLog(t.TempDir(), "closed")
	if err != nil {
		t.Fatal(err)
	}
	if err := log.close(); err != nil {
		t.Fatal(err)
	}
	accepted := make(chan *websocket.Conn, 1)
	release := make(chan struct{})
	handlerDone := make(chan struct{})
	var handlerErr error
	httpServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer close(handlerDone)
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			handlerErr = err
			return
		}
		defer func() { _ = conn.CloseNow() }()
		accepted <- conn
		<-release
	}))
	t.Cleanup(httpServer.Close)
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	t.Cleanup(cancel)
	client, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(httpServer.URL, "http"), nil)
	if resp != nil && resp.Body != nil {
		_ = resp.Body.Close()
	}
	if err != nil {
		t.Fatal(err)
	}
	conn := <-accepted
	sessionCtx, sessionCancel := context.WithCancel(t.Context())
	sink := &textSink{log: testlog.New(t), conn: conn, activityLog: log, cancel: sessionCancel}
	t.Cleanup(func() {
		sessionCancel()
		_ = client.CloseNow()
		close(release)
		<-handlerDone
		if handlerErr != nil {
			t.Error(handlerErr)
		}
	})

	sink.sendTurnError(sessionCtx, "llm: out of memory")

	_, data, err := client.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var msg voicev2.Error
	if err := json.Unmarshal(data, &msg); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(msg.Message, "activity log: ") || msg.Recoverable {
		t.Fatalf("error = %+v, want a fatal activity-log failure", msg)
	}
	if sessionCtx.Err() == nil {
		t.Fatal("log failure left the session running")
	}
}

func dialTextSession(t *testing.T, bridge *Bridge) *websocket.Conn {
	httpServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := bridge.ServeTextSession(r.Context(), w, r); err != nil {
			t.Errorf("ServeTextSession() error = %v", err)
		}
	}))
	t.Cleanup(httpServer.Close)
	return dialTextTestWebSocket(t, "ws"+strings.TrimPrefix(httpServer.URL, "http"), nil)
}

// A net.Pipe keeps the HTTP upgrade and WebSocket I/O inside the virtual clock.
func dialTextSessionInMemory(t *testing.T, bridge *Bridge) *websocket.Conn {
	client, server := net.Pipe()
	ln := &textTestListener{incoming: make(chan net.Conn, 1), closed: make(chan struct{})}
	ln.incoming <- server
	srv := &http.Server{
		ReadHeaderTimeout: 5 * time.Second,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if err := bridge.ServeTextSession(r.Context(), w, r); err != nil {
				t.Errorf("ServeTextSession() error = %v", err)
			}
		}),
	}
	var wg sync.WaitGroup
	wg.Go(func() {
		if err := srv.Serve(ln); !errors.Is(err, http.ErrServerClosed) {
			t.Errorf("Serve() error = %v", err)
		}
	})
	t.Cleanup(func() {
		_ = client.Close()
		_ = srv.Close()
		wg.Wait()
	})
	var dialed atomic.Bool
	transport := &http.Transport{DialContext: func(context.Context, string, string) (net.Conn, error) {
		if dialed.Swap(true) {
			return nil, errors.New("unexpected second text test connection")
		}
		return client, nil
	}}
	return dialTextTestWebSocket(t, "ws://text.test", &websocket.DialOptions{HTTPClient: &http.Client{Transport: transport}})
}

type textTestListener struct {
	incoming chan net.Conn
	closed   chan struct{}
}

func (l *textTestListener) Accept() (net.Conn, error) {
	select {
	case c := <-l.incoming:
		return c, nil
	case <-l.closed:
		return nil, net.ErrClosed
	}
}

func (l *textTestListener) Close() error {
	close(l.closed)
	return nil
}

func (l *textTestListener) Addr() net.Addr { return &net.TCPAddr{} }

func dialTextTestWebSocket(t *testing.T, url string, opts *websocket.DialOptions) *websocket.Conn {
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	t.Cleanup(cancel)
	conn, resp, err := websocket.Dial(ctx, url, opts)
	if resp != nil && resp.Body != nil {
		_ = resp.Body.Close()
	}
	if err != nil {
		t.Fatalf("dial text session: %v", err)
	}
	t.Cleanup(func() { _ = conn.CloseNow() })
	return conn
}

func writeMessage(t *testing.T, ctx context.Context, conn *websocket.Conn, msg any) {
	data, err := json.Marshal(msg)
	if err != nil {
		t.Fatalf("marshal message: %v", err)
	}
	if err := conn.Write(ctx, websocket.MessageText, data); err != nil {
		t.Fatalf("write message: %v", err)
	}
}

func readMessage(t *testing.T, ctx context.Context, conn *websocket.Conn) (kind voicev2.MessageKind, data []byte) {
	_, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read message: %v", err)
	}
	var env voicev2.MessageEnvelope
	if err := json.Unmarshal(data, &env); err != nil {
		t.Fatalf("decode message envelope: %v", err)
	}
	return env.Kind, data
}

func expectKind(t *testing.T, ctx context.Context, conn *websocket.Conn, want voicev2.MessageKind) []byte {
	kind, data := readMessage(t, ctx, conn)
	for kind == voicev2.MessageKindSessionStatus && want == voicev2.MessageKindSessionReady {
		var status voicev2.SessionStatus
		if err := json.Unmarshal(data, &status); err != nil || status.Message == "" {
			t.Fatalf("invalid startup status: %s (%v)", data, err)
		}
		kind, data = readMessage(t, ctx, conn)
	}
	if kind != want {
		t.Fatalf("message kind = %q, want %q (%s)", kind, want, data)
	}
	return data
}

func expectTurnStatus(t *testing.T, ctx context.Context, conn *websocket.Conn, want voicev2.TurnState) {
	data := expectKind(t, ctx, conn, voicev2.MessageKindTurnStatus)
	var msg voicev2.TurnStatus
	if err := json.Unmarshal(data, &msg); err != nil {
		t.Fatal(err)
	}
	if msg.State != want {
		t.Fatalf("turn state = %q, want %q", msg.State, want)
	}
}

func expectAssistantText(t *testing.T, ctx context.Context, conn *websocket.Conn, want string) {
	data := expectKind(t, ctx, conn, voicev2.MessageKindAssistantTextDelta)
	var msg voicev2.AssistantTextDelta
	if err := json.Unmarshal(data, &msg); err != nil {
		t.Fatal(err)
	}
	if msg.Text != want {
		t.Fatalf("assistant text = %q, want %q", msg.Text, want)
	}
}

// expectAssistantTextSkippingStatus returns the next assistant text, ignoring
// turn-status and interrupted notices from a cancelled turn.
func expectAssistantTextSkippingStatus(t *testing.T, ctx context.Context, conn *websocket.Conn) string {
	for {
		kind, data := readMessage(t, ctx, conn)
		switch kind {
		case voicev2.MessageKindAssistantTextDelta:
			var msg voicev2.AssistantTextDelta
			if err := json.Unmarshal(data, &msg); err != nil {
				t.Fatal(err)
			}
			return msg.Text
		case voicev2.MessageKindTurnStatus, voicev2.MessageKindInterrupted:
			continue
		default:
			t.Fatalf("unexpected message kind %q (%s)", kind, data)
		}
	}
}

func expectToolCall(t *testing.T, ctx context.Context, conn *websocket.Conn) voicev2.ToolCall {
	data := expectKind(t, ctx, conn, voicev2.MessageKindToolCall)
	var msg voicev2.ToolCall
	if err := json.Unmarshal(data, &msg); err != nil {
		t.Fatal(err)
	}
	return msg
}

func (c *blockingTurnConversation) respond(ctx context.Context) (llmStep, error) {
	return c.user(ctx, "")
}

func (c *blockingFirstConversation) respond(ctx context.Context) (llmStep, error) {
	return c.user(ctx, "")
}
