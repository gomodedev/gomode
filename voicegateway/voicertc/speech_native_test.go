// Tests concrete gateway synthesis adapters through native provider HTTP transports.

package voicertc

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genai/providers/llamacpp"
)

func nativeTTSClient(t *testing.T, provider string, handler http.HandlerFunc) ttsAdapter {
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	if provider == "llama" {
		p, err := llamacpp.New(t.Context(), genai.ProviderOptionRemote(srv.URL))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := p.Close(); err != nil {
				t.Error(err)
			}
		})
		return &llamaTTSAdapter{client: p, voice: "female"}
	}
	target, err := url.Parse(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	p, err := gemini.New(t.Context(), genai.ProviderOptionAPIKey("test-key"), genai.ProviderOptionModel("gemini-3.8-flash-lite-tts"), genai.ProviderOptionTransportWrapper(func(next http.RoundTripper) http.RoundTripper {
		return &geminiTestTransport{base: next, endpoint: target}
	}))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := p.Close(); err != nil {
			t.Error(err)
		}
	})
	return &geminiTTSAdapter{client: p, voice: "Kore"}
}

func TestLlamaTTSAdapter(t *testing.T) {
	t.Parallel()
	t.Run("synthesize", func(t *testing.T) { testNativeTTS(t, "llama") })
}
func TestGeminiTTSAdapter(t *testing.T) {
	t.Parallel()
	t.Run("synthesize", func(t *testing.T) { testNativeTTS(t, "gemini") })
}

func testNativeTTS(t *testing.T, provider string) {
	t.Parallel()
	t.Run("valid", func(t *testing.T) {
		t.Parallel()
		a := nativeTTSClient(t, provider, func(w http.ResponseWriter, r *http.Request) {
			if provider == "llama" {
				var in llamacpp.SpeechRequest
				if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
					t.Error(err)
					return
				}
				if in.Input != "Hello." || in.Voice != "female" || in.Lang != "fr" || !in.Stream || in.ResponseFormat != llamacpp.SpeechPCM {
					t.Errorf("native speech request %+v", in)
				}
				w.Header().Set("Content-Type", "audio/pcm;rate=12000;encoding=signed-int;bits=16")
				if _, err := w.Write(pcm16Samples(0, 16384)); err != nil {
					t.Error(err)
				}
			} else {
				var in gemini.InteractionRequest
				var body io.Reader = r.Body
				if r.Header.Get("Content-Encoding") == "gzip" {
					z, err := gzip.NewReader(r.Body)
					if err != nil {
						t.Error(err)
						return
					}
					t.Cleanup(func() {
						if err := z.Close(); err != nil {
							t.Error(err)
						}
					})
					body = z
				}
				if err := json.NewDecoder(body).Decode(&in); err != nil {
					t.Error(err)
					return
				}
				if c := in.GenerationConfig.SpeechConfig[0]; !in.Stream || in.Input[0].Text != "Hello." || c.Voice != "Kore" || c.Language != "fr" {
					t.Errorf("native speech request %+v", in)
				}
				w.Header().Set("Content-Type", "text/event-stream")
				if _, err := io.WriteString(w, "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"mime_type\":\"audio/l16\",\"data\":\"AQ==\"}}\n\ndata: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"AA==\"}}\n\ndata: {\"event_type\":\"interaction.completed\"}\n\n"); err != nil {
					t.Error(err)
				}
			}
		})
		var pcm []byte
		for chunk, err := range a.synthesize(t.Context(), "fr", "", "Hello.") {
			if err != nil {
				t.Fatal(err)
			}
			pcm = append(pcm, chunk...)
		}
		want := []byte{0, 0, 0, 32, 0, 64, 0, 64}
		if provider == "gemini" {
			want = []byte{1, 0}
		}
		if !bytes.Equal(pcm, want) {
			t.Fatalf("PCM %v, want %v", pcm, want)
		}
	})
	t.Run("error", func(t *testing.T) {
		t.Parallel()
		cases := []struct{ name, mime, body string }{{"container", "audio/wav", "RIFF"}, {"incomplete sample", "audio/pcm;rate=24000;encoding=signed-int;bits=16", "x"}}
		if provider == "gemini" {
			cases = []struct{ name, mime, body string }{
				{"format", "text/event-stream", "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"sample_rate\":16000,\"data\":\"AAA=\"}}\n\n"},
				{"base64", "text/event-stream", "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"!\"}}\n\n"},
				{"no audio", "text/event-stream", "data: {\"event_type\":\"interaction.completed\"}\n\n"},
				{"incomplete sample", "text/event-stream", "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"AQ==\"}}\n\ndata: {\"event_type\":\"interaction.completed\"}\n\n"},
				{"provider failure", "text/event-stream", "data: {\"event_type\":\"interaction.completed\",\"interaction\":{\"status\":\"failed\"}}\n\n"},
				{"truncated", "text/event-stream", "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"AAA=\"}}\n\n"},
			}
		}
		for _, tc := range cases {
			t.Run(tc.name, func(t *testing.T) {
				t.Parallel()
				a := nativeTTSClient(t, provider, func(w http.ResponseWriter, _ *http.Request) {
					w.Header().Set("Content-Type", tc.mime)
					if _, err := io.WriteString(w, tc.body); err != nil {
						t.Error(err)
					}
				})
				var failure error
				for _, err := range a.synthesize(t.Context(), "en", "", "Hello.") {
					if err != nil {
						failure = err
					}
				}
				if failure == nil {
					t.Fatal("invalid native speech succeeded")
				}
			})
		}
	})
	t.Run("early stop cancels request", func(t *testing.T) {
		t.Parallel()
		stopped := make(chan struct{})
		a := nativeTTSClient(t, provider, func(w http.ResponseWriter, r *http.Request) {
			if provider == "llama" {
				w.Header().Set("Content-Type", "audio/pcm;rate=24000;encoding=signed-int;bits=16")
				if _, err := w.Write(pcm16Samples(16384)); err != nil {
					t.Error(err)
				}
			} else {
				w.Header().Set("Content-Type", "text/event-stream")
				if _, err := io.WriteString(w, "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"AAA=\"}}\n\n"); err != nil {
					t.Error(err)
				}
			}
			if err := http.NewResponseController(w).Flush(); err != nil {
				t.Error(err)
			}
			<-r.Context().Done()
			close(stopped)
		})
		for pcm, err := range a.synthesize(t.Context(), "en", "", "Hello.") {
			if err != nil {
				t.Fatal(err)
			}
			if len(pcm) == 0 {
				t.Fatal("empty first audio")
			}
			break
		}
		ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
		t.Cleanup(cancel)
		select {
		case <-stopped:
		case <-ctx.Done():
			t.Fatal("request not canceled")
		}
	})
	t.Run("canceled context", func(t *testing.T) {
		t.Parallel()
		a := nativeTTSClient(t, provider, func(http.ResponseWriter, *http.Request) { t.Error("canceled request reached provider") })
		ctx, cancel := context.WithCancel(t.Context())
		t.Cleanup(cancel)
		cancel()
		var failure error
		for _, err := range a.synthesize(ctx, "en", "", "Hello.") {
			failure = err
		}
		if !errors.Is(failure, context.Canceled) {
			t.Fatalf("cancellation %v", failure)
		}
	})
}
