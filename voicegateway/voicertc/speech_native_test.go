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
	"slices"
	"testing"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genai/providers/groq"
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
				if in.Input != "Hello." || in.Voice != "female" || !in.Stream || in.ResponseFormat != llamacpp.SpeechPCM {
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
				if !in.Stream || in.Input[0].Text != "Hello." || in.GenerationConfig.SpeechConfig[0].Voice != "Kore" {
					t.Errorf("native speech request %+v", in)
				}
				w.Header().Set("Content-Type", "text/event-stream")
				if _, err := io.WriteString(w, "data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"mime_type\":\"audio/l16\",\"data\":\"AQ==\"}}\n\ndata: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"AA==\"}}\n\ndata: {\"event_type\":\"interaction.completed\"}\n\n"); err != nil {
					t.Error(err)
				}
			}
		})
		var pcm []byte
		for chunk, err := range a.synthesize(t.Context(), "Hello.") {
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
				for _, err := range a.synthesize(t.Context(), "Hello.") {
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
		for pcm, err := range a.synthesize(t.Context(), "Hello.") {
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
		for _, err := range a.synthesize(ctx, "Hello.") {
			failure = err
		}
		if !errors.Is(failure, context.Canceled) {
			t.Fatalf("cancellation %v", failure)
		}
	})
}

func TestGeminiASRAdapter(t *testing.T) {
	t.Parallel()
	t.Run("transcribe", func(t *testing.T) {
		t.Run("error", func(t *testing.T) {
			for name, body := range map[string]string{
				"status":         `{"status":"cancelled"}`,
				"provider error": `{"status":"completed","errors":[{"code":"FAILED","message":"recognition failed"}]}`,
			} {
				t.Run(name, func(t *testing.T) {
					t.Parallel()
					srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
						w.Header().Set("Content-Type", "application/json")
						if _, err := io.WriteString(w, body); err != nil {
							t.Error(err)
						}
					}))
					t.Cleanup(srv.Close)
					target, err := url.Parse(srv.URL)
					if err != nil {
						t.Fatal(err)
					}
					p, err := gemini.New(t.Context(), genai.ProviderOptionAPIKey("test-key"), genai.ProviderOptionModel("gemini-3.5-transcribe"), genai.ProviderOptionTransportWrapper(func(next http.RoundTripper) http.RoundTripper {
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
					text, err := (&geminiASRAdapter{client: p}).transcribe(t.Context(), []byte{1, 0})
					if err == nil || text != "" {
						t.Fatalf("failed native recognition: text %q err %v", text, err)
					}
				})
			}
		})
	})
}

func TestGroqASRAdapter(t *testing.T) {
	t.Parallel()
	t.Run("transcribe", func(t *testing.T) {
		t.Run("error", func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				if _, err := io.WriteString(w, `{}`); err != nil {
					t.Error(err)
				}
			}))
			t.Cleanup(srv.Close)
			target, err := url.Parse(srv.URL)
			if err != nil {
				t.Fatal(err)
			}
			p, err := groq.New(t.Context(), genai.ProviderOptionAPIKey("test-key"), genai.ProviderOptionModel("whisper-large-v3"), genai.ProviderOptionTransportWrapper(func(next http.RoundTripper) http.RoundTripper {
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
			text, err := (&groqASRAdapter{client: p}).transcribe(t.Context(), []byte{1, 0})
			if err == nil || text != "" {
				t.Fatalf("missing native text: text %q err %v", text, err)
			}
		})
	})
}

func TestChatASRAdapter(t *testing.T) {
	t.Parallel()
	// chatRequest is the part of a chat completion request the adapters own.
	type chatRequest struct {
		Messages []struct {
			Role    string          `json:"role"`
			Content json.RawMessage `json:"content"`
		} `json:"messages"`
	}
	for _, tc := range []struct {
		name, model, reply string
		// roles and parts list each message's role and its content part types;
		// a string content is the part "text".
		roles []string
		parts [][]string
	}{
		{"Qwen3-ASR", "ggml-org/Qwen3-ASR-0.6B-GGUF:Q8_0", "language English<asr_text> Turn left. ", []string{"user"}, [][]string{{"input_audio"}}},
		{"audio chat", "LiquidAI/LFM3-Audio-2B-GGUF:Q4_K_M", " Turn left. ", []string{"system", "user"}, [][]string{{"text"}, {"text", "input_audio"}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			got := make(chan chatRequest, 1)
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var req chatRequest
				if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
					t.Error(err)
				}
				got <- req
				w.Header().Set("Content-Type", "application/json")
				reply, _ := json.Marshal(tc.reply)
				_, _ = io.WriteString(w, `{"choices":[{"index":0,"message":{"role":"assistant","content":`+string(reply)+`},"finish_reason":"stop"}]}`)
			}))
			t.Cleanup(srv.Close)
			p, err := llamacpp.New(t.Context(), genai.ProviderOptionRemote(srv.URL))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := p.Close(); err != nil {
					t.Error(err)
				}
			})
			a := newChatASRAdapter(p, tc.model)
			if text, err := a.transcribe(t.Context(), []byte{1, 0, 2, 0}); err != nil || text != "Turn left." {
				t.Fatalf("transcribe = %q, %v", text, err)
			}
			req := <-got
			var roles []string
			var parts [][]string
			for _, m := range req.Messages {
				roles = append(roles, m.Role)
				var typed []struct {
					Type string `json:"type"`
				}
				if json.Unmarshal(m.Content, &typed) != nil {
					parts = append(parts, []string{"text"})
					continue
				}
				types := make([]string, 0, len(typed))
				for _, p := range typed {
					types = append(types, p.Type)
				}
				parts = append(parts, types)
			}
			if !slices.Equal(roles, tc.roles) || !slices.EqualFunc(parts, tc.parts, slices.Equal) {
				t.Errorf("messages = %v %v, want %v %v", roles, parts, tc.roles, tc.parts)
			}
			if text, err := a.transcribe(t.Context(), nil); err != nil || text != "" {
				t.Errorf("empty audio = %q, %v", text, err)
			}
		})
	}
}
