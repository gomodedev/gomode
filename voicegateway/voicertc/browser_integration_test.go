// Tests real Chromium Opus DTX behavior and the lifetime of its DevTools connection.

//go:build integration

package voicertc

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// TestIntegrationBrowserOpusDTX measures Chromium's real libwebrtc encoder.
func TestIntegrationBrowserOpusDTX(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithTimeout(t.Context(), 45*time.Second)
	t.Cleanup(cancel)
	b := newTestBridge(ctx, t, &fakeBackendConnector{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet {
			w.Header().Set("Content-Type", "text/html")
			if _, err := w.Write([]byte("<!doctype html><title>Opus DTX test</title>")); err != nil {
				t.Error(err)
			}
			return
		}
		var req struct{ SDP string }
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
			t.Error(err)
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		sdp, _, err := b.HandleOffer(r.Context(), req.SDP)
		if err != nil {
			t.Error(err)
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if got, ok := opusFmtp(sdp); !ok || got != "useinbandfec=1;usedtx=1" {
			t.Errorf("answer opus fmtp = %q, want \"useinbandfec=1;usedtx=1\"", got)
		}
		// The control changes only the receive preference, keeping the same audio path.
		if r.URL.Query().Get("dtx") == "0" {
			sdp = strings.ReplaceAll(sdp, "usedtx=1", "usedtx=0")
		}
		if err := json.NewEncoder(w).Encode(map[string]string{"sdp": sdp}); err != nil {
			t.Error(err)
		}
	}))
	t.Cleanup(srv.Close)

	c := newDTXBrowser(ctx, t)
	c.navigate(srv.URL)
	var res struct {
		Result           struct{ Value []browserDTXResult }
		ExceptionDetails json.RawMessage
	}
	c.call("Runtime.evaluate", map[string]any{
		"expression": browserDTXMeasurement, "awaitPromise": true, "returnByValue": true,
	}, c.session, &res)
	if len(res.ExceptionDetails) != 0 || len(res.Result.Value) != 2 {
		t.Fatalf("browser measurement failed: %+v", res)
	}
	ctl, dtx := res.Result.Value[0], res.Result.Value[1]
	t.Logf("continuous: %+v; DTX: %+v", ctl, dtx)
	if ctl.Quiet.Packets < 30 || dtx.Quiet.Packets*2 >= ctl.Quiet.Packets || dtx.Quiet.Bytes*2 >= ctl.Quiet.Bytes {
		t.Fatal("DTX did not halve quiet-uplink packets and bytes")
	}
	if dtx.Speech.Packets < 15 || dtx.Resumed.Packets < 15 {
		t.Fatal("DTX suppressed active or resumed speech")
	}
}

// TestIntegrationBrowserOpusMaxPlaybackRate measures Chromium's real libwebrtc encoder
// after the gateway renegotiates its 16 kHz microphone rate.
func TestIntegrationBrowserOpusMaxPlaybackRate(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithTimeout(t.Context(), 45*time.Second)
	t.Cleanup(cancel)
	b := newTestBridge(ctx, t, &fakeBackendConnector{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet {
			w.Header().Set("Content-Type", "text/html")
			if _, err := w.Write([]byte("<!doctype html><title>Opus maxplaybackrate test</title>")); err != nil {
				t.Error(err)
			}
			return
		}
		var req struct{ SDP string }
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
			t.Error(err)
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		sdp, _, err := b.HandleOffer(r.Context(), req.SDP)
		if err != nil {
			t.Error(err)
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if err := json.NewEncoder(w).Encode(map[string]string{"sdp": sdp}); err != nil {
			t.Error(err)
		}
	}))
	t.Cleanup(srv.Close)

	c := newDTXBrowser(ctx, t)
	c.navigate(srv.URL)
	var res struct {
		Result           struct{ Value []uint64 }
		ExceptionDetails json.RawMessage
	}
	c.call("Runtime.evaluate", map[string]any{
		"expression": browserMaxPlaybackRateMeasurement, "awaitPromise": true, "returnByValue": true,
	}, c.session, &res)
	if len(res.ExceptionDetails) != 0 || len(res.Result.Value) != 2 {
		t.Fatalf("browser measurement failed: %+v", res)
	}
	full, capped := res.Result.Value[0], res.Result.Value[1]
	t.Logf("uplink over 1.5s: 48 kHz %d bytes; maxplaybackrate=16000 %d bytes", full, capped)
	// libwebrtc's default Opus bitrate drops from 32 to 20 kbit/s.
	if full == 0 || capped*4 >= full*3 {
		t.Fatal("maxplaybackrate did not cut uplink bytes by a quarter")
	}
}

// browserMaxPlaybackRateMeasurement sends white noise, which needs every bit the
// encoder allows, and returns the Opus payload bytes sent over 1.5 seconds
// without and with renegotiation.
const browserMaxPlaybackRateMeasurement = `(async () => {
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  async function run(renegotiation) {
    const ac = new AudioContext({sampleRate: 48000});
    const noise = ac.createBuffer(1, 48000, 48000);
    const samples = noise.getChannelData(0);
    for (let i = 0; i < samples.length; i++) samples[i] = (Math.random() * 2 - 1) * 0.3;
    const source = ac.createBufferSource();
    source.buffer = noise;
    source.loop = true;
    const dest = ac.createMediaStreamDestination();
    dest.channelCount = 1;
    source.connect(dest);
    const pc = new RTCPeerConnection();
    try {
      pc.addTrack(dest.stream.getAudioTracks()[0]);
      const dc = pc.createDataChannel('voice-gateway');
      const ready = new Promise((resolve, reject) => {
        let sessionReady = false;
        dc.onopen = () => dc.send(JSON.stringify({kind: 'session.setup', voice: {name: 'local', language: 'en'}, renegotiation}));
        dc.onmessage = async ev => {
          const msg = JSON.parse(ev.data);
          if (msg.kind === 'error') reject(new Error(msg.message));
          if (msg.kind === 'session.ready') {
            sessionReady = true;
            if (!renegotiation) resolve();
          }
          if (msg.kind === 'rtc.offer' && sessionReady) {
            await pc.setRemoteDescription({type: 'offer', sdp: msg.sdp});
            await pc.setLocalDescription(await pc.createAnswer());
            dc.send(JSON.stringify({kind: 'rtc.answer', sdp: pc.localDescription.sdp}));
            resolve();
          }
        };
      });
      source.start();
      await ac.resume();
      await pc.setLocalDescription(await pc.createOffer());
      if (pc.iceGatheringState !== 'complete') await new Promise(resolve => {
        pc.onicegatheringstatechange = () => { if (pc.iceGatheringState === 'complete') resolve(); };
      });
      const res = await fetch('/offer', {method: 'POST', body: JSON.stringify({sdp: pc.localDescription.sdp})});
      if (!res.ok) throw new Error(await res.text());
      await pc.setRemoteDescription({type: 'answer', sdp: (await res.json()).sdp});
      await ready;
      async function bytes() {
        const reports = await pc.getStats();
        const r = [...reports.values()].find(r => r.type === 'outbound-rtp' && r.kind === 'audio');
        if (!r) throw new Error('No outbound audio stats');
        return r.bytesSent;
      }
      await sleep(500);
      const start = await bytes();
      await sleep(1500);
      return (await bytes()) - start;
    } finally {
      pc.close(); source.stop(); await ac.close();
    }
  }
  return [await run(false), await run(true)];
})()`

type browserDTXResult struct {
	Speech, Quiet, Resumed struct{ Packets, Bytes uint64 }
}

func TestIntegrationDTXBrowser(t *testing.T) {
	c := newDTXBrowser(t.Context(), t)
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, _, err := c.ws.Read(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled DevTools read: %v", err)
	}
}

// dtxBrowser owns an isolated Chromium process and its synchronous DevTools connection.
type dtxBrowser struct {
	t       *testing.T
	ctx     context.Context
	cmd     *exec.Cmd
	ws      *websocket.Conn
	addr    string
	session string
	id      int
	loaded  bool
	wg      sync.WaitGroup
	exited  chan struct{}
	exitErr error
}

func newDTXBrowser(ctx context.Context, t *testing.T) *dtxBrowser {
	exe := os.Getenv("CHROME_BIN")
	if exe == "" {
		var err error
		exe, err = exec.LookPath("chromium")
		if err != nil {
			t.Skip("Chromium is not installed; set CHROME_BIN or run this test on a browser-equipped host")
		}
	}
	t.Logf("browser executable: %s", exe)
	dir := t.TempDir()
	// Cleanup owns shutdown; test cancellation must not kill Chromium before its children exit.
	// Tests never reach external services: the resolver rule fails every name
	// lookup, so Chromium's update, variations, and sign-in fetches cannot leave
	// the host; only the loopback gateway stays reachable.
	cmd := exec.Command(exe, "--headless", "--no-sandbox", "--disable-gpu", //nolint:gosec // Test executable from CHROME_BIN or LookPath, with fixed flags and a test-owned profile.
		"--disable-background-networking", "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1",
		"--autoplay-policy=no-user-gesture-required", "--remote-debugging-port=0", "--user-data-dir="+dir, "about:blank")
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	c := &dtxBrowser{t: t, ctx: ctx, cmd: cmd, exited: make(chan struct{})}
	c.wg.Go(func() { c.exitErr = cmd.Wait(); close(c.exited) })
	t.Cleanup(c.close)
	for c.addr == "" {
		data, err := os.ReadFile(filepath.Join(dir, "DevToolsActivePort")) //nolint:gosec // Chromium writes this fixed filename inside the test-owned temporary profile.
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			t.Fatal(err)
		}
		if lines := strings.Fields(string(data)); len(lines) == 2 {
			c.addr = "ws://127.0.0.1:" + lines[0] + lines[1]
			break
		}
		select {
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		case <-c.exited:
			t.Fatalf("Chromium exited before opening DevTools: %v", c.exitErr)
		case <-time.After(20 * time.Millisecond):
		}
	}
	ws, res, err := websocket.Dial(ctx, c.addr, nil)
	if res != nil && res.Body != nil {
		if err := res.Body.Close(); err != nil {
			t.Error(err)
		}
	}
	if err != nil {
		t.Fatal(err)
	}
	c.ws = ws
	ws.SetReadLimit(1 << 20)
	var dst struct{ TargetID string }
	c.call("Target.createTarget", map[string]string{"url": "about:blank"}, "", &dst)
	var att struct{ SessionID string }
	c.call("Target.attachToTarget", map[string]any{"targetId": dst.TargetID, "flatten": true}, "", &att)
	c.session = att.SessionID
	return c
}

func (c *dtxBrowser) close() {
	if c.addr != "" {
		// A cancelled DevTools read closes its connection. Shutdown needs its own
		// connection and deadline so a failed measurement can still stop Chromium.
		ctx, cancel := context.WithTimeout(context.WithoutCancel(c.ctx), time.Second)
		ws, res, err := websocket.Dial(ctx, c.addr, nil)
		if res != nil && res.Body != nil {
			if err := res.Body.Close(); err != nil {
				c.t.Error(err)
			}
		}
		if err == nil {
			err = ws.Write(ctx, websocket.MessageText, []byte(`{"id":1000000,"method":"Browser.close"}`))
			if err := ws.CloseNow(); err != nil {
				c.t.Errorf("close shutdown DevTools: %v", err)
			}
		}
		cancel()
		if err != nil {
			c.t.Errorf("request Chromium shutdown: %v", err)
		}
	}
	if c.ws != nil {
		if err := c.ws.CloseNow(); err != nil {
			c.t.Errorf("close DevTools: %v", err)
		}
	}
	select {
	case <-c.exited:
		if c.exitErr != nil {
			c.t.Errorf("Chromium exit: %v", c.exitErr)
		}
	case <-time.After(5 * time.Second):
		c.t.Error("Chromium did not stop within five seconds")
		if err := c.cmd.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
			c.t.Error(err)
		}
	}
	c.wg.Wait()
}

func (c *dtxBrowser) navigate(u string) {
	c.loaded = false
	c.call("Page.enable", nil, c.session, nil)
	c.call("Page.navigate", map[string]string{"url": u}, c.session, nil)
	for !c.loaded {
		c.call("Runtime.evaluate", map[string]string{"expression": "document.readyState"}, c.session, nil)
	}
}

func (c *dtxBrowser) call(m string, p any, sid string, v any) {
	c.id++
	data, err := json.Marshal(struct {
		ID        int    `json:"id"`
		Method    string `json:"method"`
		Params    any    `json:"params,omitempty"`
		SessionID string `json:"sessionId,omitempty"`
	}{c.id, m, p, sid})
	if err != nil {
		c.t.Fatal(err)
	}
	if err := c.ws.Write(c.ctx, websocket.MessageText, data); err != nil {
		c.t.Fatalf("DevTools %s request: %v", m, err)
	}
	for {
		_, data, err := c.ws.Read(c.ctx)
		if err != nil {
			c.t.Fatalf("DevTools %s response: %v", m, err)
		}
		var msg struct {
			ID        int
			Method    string
			SessionID string
			Result    json.RawMessage
			Error     json.RawMessage
		}
		if err := json.Unmarshal(data, &msg); err != nil {
			c.t.Fatal(err)
		}
		if msg.Method == "Page.loadEventFired" && msg.SessionID == c.session {
			c.loaded = true
		}
		if msg.ID != c.id {
			continue
		}
		if len(msg.Error) != 0 {
			c.t.Fatalf("DevTools %s: %s", m, msg.Error)
		}
		if v != nil {
			if err := json.Unmarshal(msg.Result, v); err != nil {
				c.t.Fatal(err, string(msg.Result))
			}
		}
		return
	}
}

const browserDTXMeasurement = `(async () => {
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  async function run(dtx) {
    const ac = new AudioContext({sampleRate: 48000});
    const source = ac.createOscillator();
    const gain = ac.createGain();
    const dest = ac.createMediaStreamDestination();
    dest.channelCount = 1;
    source.frequency.value = 440;
    gain.gain.value = 0.2;
    source.connect(gain).connect(dest);
    const pc = new RTCPeerConnection();
    try {
      pc.addTrack(dest.stream.getAudioTracks()[0]);
      const dc = pc.createDataChannel('voice-gateway');
      const ready = new Promise((resolve, reject) => {
        dc.onopen = () => dc.send(JSON.stringify({kind: 'session.setup', voice: {name: 'local', language: 'en'}}));
        dc.onmessage = ev => {
          const msg = JSON.parse(ev.data);
          if (msg.kind === 'session.ready') resolve();
          if (msg.kind === 'error') reject(new Error(msg.message));
        };
      });
      source.start();
      await ac.resume();
      await pc.setLocalDescription(await pc.createOffer());
      if (pc.iceGatheringState !== 'complete') await new Promise(resolve => {
        pc.onicegatheringstatechange = () => { if (pc.iceGatheringState === 'complete') resolve(); };
      });
      const res = await fetch('/offer?dtx=' + (dtx ? '1' : '0'), {
        method: 'POST', body: JSON.stringify({sdp: pc.localDescription.sdp})
      });
      if (!res.ok) throw new Error(await res.text());
      const answer = await res.json();
      await pc.setRemoteDescription({type: 'answer', sdp: answer.sdp});
      await ready;
      async function stats() {
        const reports = await pc.getStats();
        const r = [...reports.values()].find(r => r.type === 'outbound-rtp' && r.kind === 'audio');
        if (!r) throw new Error('No outbound audio stats');
        return {packets: r.packetsSent, bytes: r.bytesSent};
      }
      async function measure(ms) {
        const a = await stats(); await sleep(ms); const b = await stats();
        return {packets: b.packets-a.packets, bytes: b.bytes-a.bytes};
      }
      await sleep(300);
      const speech = await measure(600);
      gain.gain.value = 0;
      await sleep(400);
      const quiet = await measure(1200);
      gain.gain.value = 0.2;
      const resumed = await measure(600);
      return {speech, quiet, resumed};
    } finally {
      pc.close(); source.stop(); await ac.close();
    }
  }
  return Promise.all([run(false), run(true)]);
})()`
