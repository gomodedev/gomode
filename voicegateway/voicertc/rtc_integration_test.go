// Tests real peer connections, paced RTP, and local backend behavior over WebRTC.

//go:build integration

package voicertc

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"

	"github.com/gomodedev/gomode/internal/testlog"
	"github.com/gomodedev/gomode/voicegateway"
	voicev2 "github.com/gomodedev/gomode/voicegateway/api/v2"
	configdata "github.com/gomodedev/gomode/voicegateway/data/v1"
)

func TestIntegrationVoiceRTCLocalStackPlaceholders(t *testing.T) {
	t.Parallel()
	t.Run("choices", testBridgeChoiceRuntime)

	t.Run("ASR", func(t *testing.T) {
		t.Parallel()
		ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
		t.Cleanup(cancel)
		s := newVoiceRTCTestSession(ctx, t, newTestLocalStackBackend(t, newFixedLiveASR("smoke transcript"), &smokeLLM{text: "ack"}, placeholderTTS{}))
		s.writeMicAudio(ctx, t, 24)
		data := s.waitMessage(ctx, t, voicev2.MessageKindTranscriptDelta)
		var msg voicev2.TranscriptDelta
		if err := json.Unmarshal(data, &msg); err != nil {
			t.Fatal(err)
		}
		if msg.Speaker != voicev2.SpeakerUser || msg.Text != "smoke transcript" {
			t.Fatalf("transcript = %+v, want user smoke transcript", msg)
		}
	})

	t.Run("LLM", func(t *testing.T) {
		t.Parallel()
		ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
		t.Cleanup(cancel)
		s := newVoiceRTCTestSession(ctx, t, newTestLocalStackBackend(t, newFixedLiveASR("hello"), &smokeLLM{text: "smoke llm response"}, placeholderTTS{}))
		s.writeMicAudio(ctx, t, 24)
		data := s.waitMessage(ctx, t, voicev2.MessageKindAssistantTextDelta)
		var msg voicev2.AssistantTextDelta
		if err := json.Unmarshal(data, &msg); err != nil {
			t.Fatal(err)
		}
		if msg.Text != "smoke llm response" {
			t.Fatalf("assistant text = %q, want smoke llm response", msg.Text)
		}
	})

	t.Run("TTS", func(t *testing.T) {
		t.Parallel()
		ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
		t.Cleanup(cancel)
		s := newVoiceRTCTestSession(ctx, t, newTestLocalStackBackend(t, newFixedLiveASR("hello"), &smokeLLM{text: "smoke tts response"}, placeholderTTS{}))
		s.writeMicAudio(ctx, t, 24)
		s.waitMessage(ctx, t, voicev2.MessageKindSpeechStarted)
		select {
		case energy := <-s.remoteAudioEnergy:
			if energy < 1_000 {
				t.Fatalf("assistant RTP audio energy = %.0f, want audible signal", energy)
			}
		case err := <-s.mediaErrs:
			t.Fatal(err)
		case err := <-s.signalErrs:
			t.Fatal(err)
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		}
	})
	t.Run("speech with no trailing packets", func(t *testing.T) {
		t.Parallel()
		ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
		t.Cleanup(cancel)
		s := newVoiceRTCTestSession(ctx, t, newTestLocalStackBackend(t, newFixedLiveASR("DTX turn"), &smokeLLM{text: "ack"}, placeholderTTS{}))
		s.writeMicAudio(ctx, t, 10)
		started := time.Now()
		s.waitMessage(ctx, t, voicev2.MessageKindTranscriptDelta)
		if elapsed := time.Since(started); elapsed > time.Second {
			t.Fatalf("turn ending took %s after the microphone stopped sending", elapsed)
		}
		// Speech can resume on the same track after idle synthesis.
		s.writeMicAudio(ctx, t, 10)
		s.waitMessage(ctx, t, voicev2.MessageKindTranscriptDelta)
	})
}

func TestIntegrationVoiceRTCClientClose(t *testing.T) {
	t.Parallel()
	b := &sessionContextConnector{ctx: make(chan context.Context, 1)}
	bridge := newTestBridge(t.Context(), t, b)
	client := dialVoiceRTCClient(t.Context(), t, bridge.HandleOffer)
	client.send(t.Context(), t, &voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, Voice: voicev2.VoiceConfig{Name: "local", Language: "en"}})
	ctx := <-b.ctx
	if err := client.pc.Close(); err != nil {
		t.Fatal(err)
	}
	<-ctx.Done()
	if got := context.Cause(ctx); got == nil || got.Error() != "client closed the WebRTC connection" {
		t.Fatalf("cause = %v, want the client close", got)
	}
}

func TestIntegrationStagedLanguageReachesClient(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	t.Cleanup(cancel)
	// The contributed staged choice; setup fails before any model request.
	b, err := newLlamaRealtimeBackend(ctx, testlog.New(t), voicegateway.RealtimeConfig{
		ID:          "lfm3-audio-staged",
		ModelConfig: voicegateway.ModelConfig{Provider: "llamacpp", Remote: "http://127.0.0.1:1", Model: "LiquidAI/LFM3-Audio-2B-GGUF/LFM3-Audio-2B-Q4_K_M.gguf"},
		Slots:       1,
		Staged:      true,
	})
	if err != nil {
		t.Fatal(err)
	}
	// CloseAll closes b only once the bridge exists.
	br, err := newBridgeWithBackend(ctx, testlog.New(t), b, configdata.WebRTC{DisableUPnP: true}, t.TempDir())
	if err != nil {
		t.Fatal(errors.Join(err, b.Close()))
	}
	t.Cleanup(func() { br.CloseAll(ctx) })
	s := dialVoiceRTCClient(ctx, t, br.HandleOffer)
	s.send(ctx, t, &voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup, Voice: voicev2.VoiceConfig{Language: "fr-FR"}})
	for {
		select {
		case m := <-s.messages:
			if m.kind == voicev2.MessageKindSessionReady {
				t.Fatal("staged session accepted French")
			}
			if m.kind != voicev2.MessageKindError {
				continue
			}
			var msg voicev2.Error
			if err := json.Unmarshal(m.data, &msg); err != nil {
				t.Fatal(err)
			}
			if msg.Message != "session: LiquidAI/LFM3-Audio-2B-GGUF does not support French" {
				t.Fatalf("client error %q, want the model repository and language", msg.Message)
			}
			return
		case err := <-s.signalErrs:
			t.Fatal(err)
		case <-ctx.Done():
			t.Fatal("no client error for an unsupported staged language")
		}
	}
}

// sessionContextConnector exposes the session context each connection receives.
type sessionContextConnector struct {
	fakeBackendConnector
	ctx chan context.Context
}

func (b *sessionContextConnector) connect(ctx context.Context, id string, sink backendSink) (backendSession, error) {
	b.ctx <- ctx
	return b.fakeBackendConnector.connect(ctx, id, sink)
}

func testBridgeChoiceRuntime(t *testing.T) {
	t.Parallel()
	first := &fakeBackendConnector{}
	second := &fakeBackendConnector{}
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	t.Cleanup(cancel)
	b, err := newBridgeWithBackend(ctx, testlog.New(t), first, configdata.WebRTC{DisableUPnP: true}, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	var firstID, secondID string
	t.Cleanup(func() {
		b.CloseAll(ctx)
		if first.session == nil || second.session == nil || first.session.id != firstID || second.session.id != secondID {
			t.Error("sessions were not routed to their selected backends")
		}
		if !first.closed || !second.closed {
			t.Fatal("shutdown did not close all choice backends")
		}
	})
	b.backends["second"] = second
	b.choices.Choices = append(b.choices.Choices, voicev2.VoiceChoice{ID: "second", Label: "Second", Transports: []voicev2.VoiceTransport{voicev2.VoiceTransportWebRTC}})
	if _, _, err := b.HandleChoiceOffer(ctx, "", "missing"); err == nil {
		t.Fatal("unknown choice accepted")
	}
	one := connectVoiceRTCClient(ctx, t, b.HandleOffer, &voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup})
	two := connectVoiceRTCClient(ctx, t, func(ctx context.Context, sdp string) (string, string, error) {
		return b.HandleChoiceOffer(ctx, sdp, "second")
	}, &voicev2.SessionSetup{Kind: voicev2.MessageKindSessionSetup})
	firstID, secondID = one.sessionID, two.sessionID
	b.Close(one.sessionID)
	diagnostics := b.DiagnoseVoiceRTC(ctx, two.sessionID, &voicev2.VoiceRTCClientDiagnostics{})
	if !diagnostics.Server.SessionFound {
		t.Fatal("closing first choice closed second session")
	}
}

func TestIntegrationVoiceRTCRenegotiation(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	t.Cleanup(cancel)
	backend := &kindRecordingConnector{kinds: make(chan voicev2.MessageKind, 8)}
	b := newTestBridge(ctx, t, backend)
	client := connectVoiceRTCClient(ctx, t, b.HandleOffer, &voicev2.SessionSetup{
		Kind: voicev2.MessageKindSessionSetup, Voice: voicev2.VoiceConfig{Name: "local", Language: "en"}, Renegotiation: true,
	})
	var offer voicev2.RTCOffer
	if err := json.Unmarshal(client.waitMessage(ctx, t, voicev2.MessageKindRTCOffer), &offer); err != nil {
		t.Fatal(err)
	}
	// The fake backend takes 16 kHz microphone audio.
	if got, ok := opusFmtp(offer.SDP); !ok || got != "useinbandfec=1;usedtx=1;maxplaybackrate=16000" {
		t.Fatalf("rtc.offer opus fmtp = %q", got)
	}
	if err := client.pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: offer.SDP}); err != nil {
		t.Fatal(err)
	}
	answer, err := client.pc.CreateAnswer(nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.pc.SetLocalDescription(answer); err != nil {
		t.Fatal(err)
	}
	client.send(ctx, t, &voicev2.RTCAnswer{Kind: voicev2.MessageKindRTCAnswer, SDP: client.pc.LocalDescription().SDP})
	// The data channel delivers in order, so the gateway has applied the
	// answer once the backend sees the next message.
	client.send(ctx, t, &voicev2.ContextUpdate{Kind: voicev2.MessageKindContextUpdate})
	for _, want := range []voicev2.MessageKind{voicev2.MessageKindSessionSetup, voicev2.MessageKindContextUpdate} {
		select {
		case got := <-backend.kinds:
			if got != want {
				t.Fatalf("backend received %s, want %s", got, want)
			}
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		}
	}
	// Pion updates the cached diagnostics through an asynchronous callback.
	ticker := time.NewTicker(time.Millisecond)
	t.Cleanup(ticker.Stop)
	for {
		got := b.DiagnoseVoiceRTC(ctx, client.sessionID, nil).Server.SignalingState
		if got == voicev2.VoiceRTCSignalingState(webrtc.SignalingStateStable.String()) {
			break
		}
		select {
		case <-ctx.Done():
			t.Fatalf("gateway signaling state %q after rtc.answer", got)
		case <-ticker.C:
		}
	}
	// The fake backend echoes microphone PCM, so audio survives renegotiation.
	client.writeMicAudio(ctx, t, 25)
	select {
	case <-client.remoteAudioEnergy:
	case err := <-client.mediaErrs:
		t.Fatal(err)
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
}

// kindRecordingConnector reports the kind of each client message its sessions
// receive.
type kindRecordingConnector struct {
	fakeBackendConnector
	kinds chan voicev2.MessageKind
}

func (b *kindRecordingConnector) connect(ctx context.Context, id string, sink backendSink) (backendSession, error) {
	s, err := b.fakeBackendConnector.connect(ctx, id, sink)
	if err != nil {
		return nil, err
	}
	return &kindRecordingSession{fakeBackendSession: s.(*fakeBackendSession), kinds: b.kinds}, nil
}

type kindRecordingSession struct {
	*fakeBackendSession
	kinds chan voicev2.MessageKind
}

func (s *kindRecordingSession) acceptClientMessage(ctx context.Context, data []byte) error {
	var env voicev2.MessageEnvelope
	if err := json.Unmarshal(data, &env); err != nil {
		return err
	}
	s.kinds <- env.Kind
	return s.fakeBackendSession.acceptClientMessage(ctx, data)
}
