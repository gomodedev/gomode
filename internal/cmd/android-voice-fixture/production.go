// Serves the production voice gateway over fake model endpoints for Android emulator tests.

// Copyright 2026 Marc-Antoine Ruel. All Rights Reserved. Use of this
// source code is governed by the GNU Affero General Public License v3 that
// can be found in the LICENSE file.

package main

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"log/slog"
	"math"
	"net"
	"net/http"
	"os"
	"time"

	configdata "github.com/gomodedev/gomode/voicegateway/data/v1"

	"github.com/gomodedev/gomode/voicegateway"
	"github.com/gomodedev/gomode/voicegateway/voicertc"
)

// productionReply is the fake language model's answer to every turn.
const productionReply = "Production gateway reply."

// productionGateway runs voicertc's bridge and local stack against a fake
// llama.cpp and OpenAI-compatible model server, so an emulator session crosses
// the production WebRTC bridge, session protocol and synthesis path without
// model weights. Its recognizer resolves against the same server, which serves
// no transcription session: gateway recognition connects and renegotiates the
// microphone rate, but recognizing speech fails. The synthetic gateway on the
// main listener stays in control of scripted media and failures; this one
// proves production behavior.
type productionGateway struct {
	log    *slog.Logger
	bridge *voicertc.Bridge
	model  *http.Server
	logDir string
}

func newProductionGateway(ctx context.Context, f *fixture) (*productionGateway, error) {
	var lc net.ListenConfig
	listener, err := lc.Listen(ctx, "tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	model := &http.Server{Handler: f.fakeModelRoutes(), ReadHeaderTimeout: 5 * time.Second}
	go func() { _ = model.Serve(listener) }()
	origin := "http://" + listener.Addr().String()
	cfg := voicegateway.Config{
		ASR: []voicegateway.ASRConfig{{ID: "production-asr", Label: "Fixture recognition", Provider: "llamacpp", Remote: origin, Model: "fixture/asr"}},
		LLM: []voicegateway.LLMConfig{{ID: "production", Label: "Production gateway", ModelConfig: voicegateway.ModelConfig{
			Provider: "openaicompatible", Remote: origin + "/v1/chat/completions", Model: "fixture-llm",
		}}},
		TTS: []voicegateway.TTSConfig{{
			ID: "production-tts", Label: "Fixture speech", Engine: voicegateway.TTSEngineGenAI,
			Provider: "llamacpp", Remote: origin, Model: "fixture/tts",
		}},
	}
	resolved, err := cfg.Resolve()
	if err != nil {
		return nil, errors.Join(err, model.Close())
	}
	logDir, err := os.MkdirTemp("", "android-voice-fixture-activity-")
	if err != nil {
		return nil, errors.Join(err, model.Close())
	}
	// Host candidates only: the emulator's user-mode network reaches the host's
	// interface addresses, and the gateway learns the client's from its checks.
	// The emulator needs no router mapping, so startup skips the SSDP search.
	bridge, err := voicertc.NewBridge(ctx, f.log, &resolved, configdata.WebRTC{DisableUPnP: true}, logDir)
	if err != nil {
		return nil, errors.Join(err, model.Close(), os.RemoveAll(logDir))
	}
	return &productionGateway{log: f.log, bridge: bridge, model: model, logDir: logDir}, nil
}

// routes serves the production gateway API and the fixture's service routes, so
// the app discovers this listener as a complete service with the same page,
// settings and MCP endpoint.
func (g *productionGateway) routes(service http.Handler) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("/api/voicegateway/", voicegateway.NewEmbeddedHandler(g.log, func() voicegateway.MediaBridge { return g.bridge }, g.bridge))
	mux.Handle("/", service)
	return mux
}

func (g *productionGateway) close(ctx context.Context) error {
	g.bridge.CloseAll(ctx)
	return errors.Join(g.model.Close(), os.RemoveAll(g.logDir))
}

// fakeModelRoutes answers the requests the local stack makes: the llama.cpp
// health ping and vocabulary probe at load, one streamed synthesis per reply
// sentence, and an OpenAI-compatible chat completion per turn.
func (f *fixture) fakeModelRoutes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) {
		f.writeJSON(w, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("POST /tokenize", func(w http.ResponseWriter, _ *http.Request) {
		// A vocabulary without Qwen3-TTS language tokens accepts every language.
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"tokens":[{"id":1,"piece":"<|"}]}`)
	})
	mux.HandleFunc("POST /v1/audio/speech", func(w http.ResponseWriter, _ *http.Request) {
		f.mu.Lock()
		f.stats.ProductionSpeech++
		f.mu.Unlock()
		w.Header().Set("Content-Type", "audio/pcm;rate=24000;encoding=signed-int;bits=16")
		_ = binary.Write(w, binary.LittleEndian, productionTone())
	})
	mux.HandleFunc("POST /v1/chat/completions", func(w http.ResponseWriter, _ *http.Request) {
		f.mu.Lock()
		f.stats.ProductionTurns++
		f.mu.Unlock()
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, `data: {"choices":[{"index":0,"delta":{"content":"`+productionReply+`"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}

data: [DONE]

`)
	})
	return mux
}

// productionTone is 300 ms of a quiet 440 Hz tone at 24 kHz, enough RTP for the
// app to play the reply.
func productionTone() []int16 {
	const rate = 24000
	out := make([]int16, rate*3/10)
	for i := range out {
		out[i] = int16(2000 * math.Sin(2*math.Pi*440*float64(i)/rate))
	}
	return out
}

// productionRecorder forwards records to its handler and keeps the cause of
// each voicertc session cleanup and the count of renegotiations, which tests
// read from /fixture/stats. Recording does not depend on the handler's level.
type productionRecorder struct {
	slog.Handler
	fixture *fixture
}

func (h productionRecorder) Enabled(ctx context.Context, level slog.Level) bool {
	return level >= slog.LevelInfo || h.Handler.Enabled(ctx, level)
}

func (h productionRecorder) Handle(ctx context.Context, r slog.Record) error {
	if r.Message == "voicertc: microphone rate renegotiated" {
		h.fixture.mu.Lock()
		h.fixture.stats.ProductionRenegotiations++
		h.fixture.mu.Unlock()
	}
	if r.Message == "voicertc: session cleaned up" {
		r.Attrs(func(a slog.Attr) bool {
			if a.Key == "cause" {
				h.fixture.mu.Lock()
				h.fixture.stats.ProductionCleanups = append(h.fixture.stats.ProductionCleanups, a.Value.String())
				h.fixture.mu.Unlock()
			}
			return true
		})
	}
	if !h.Handler.Enabled(ctx, r.Level) {
		return nil
	}
	return h.Handler.Handle(ctx, r)
}

func (h productionRecorder) WithAttrs(attrs []slog.Attr) slog.Handler {
	return productionRecorder{Handler: h.Handler.WithAttrs(attrs), fixture: h.fixture}
}

func (h productionRecorder) WithGroup(name string) slog.Handler {
	return productionRecorder{Handler: h.Handler.WithGroup(name), fixture: h.fixture}
}
