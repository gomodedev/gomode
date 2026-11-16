// Tests the genai live recognizer: drafts, final transcripts, language selection and rejected utterances.

package voicertc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"iter"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strings"
	"sync"
	"testing"

	"github.com/coder/websocket"
	"github.com/maruel/genai"
	"github.com/maruel/genai/base"
	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genai/providers/llamacpp"
	"github.com/maruel/genai/providers/mistral"
	"github.com/maruel/genai/scoreboard"
	"github.com/maruel/genaipy/providers/nemospeech"
	"github.com/maruel/genaipy/providers/whistle"

	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// fakeTranscriber is a genai provider whose transcription sessions are handed
// out in connect order. ConnectRealtime waits for the next session.
type fakeTranscriber struct {
	base.NotImplemented

	score    scoreboard.Score
	sessions chan *fakeTranscription
}

func newFakeLiveASR(sessions chan *fakeTranscription) *liveASR {
	return newLiveASR(&fakeTranscriber{sessions: sessions}, "fake-model")
}

func (p *fakeTranscriber) ConnectRealtime(ctx context.Context, opts ...genai.RealtimeOption) (genai.RealtimeSession, error) {
	select {
	case s := <-p.sessions:
		s.opts = opts
		close(s.connected)
		return s, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (p *fakeTranscriber) Close() error { return nil }

func (p *fakeTranscriber) Name() string { return "fake" }

func (p *fakeTranscriber) ModelID() string { return "fake-model" }

func (p *fakeTranscriber) OutputModalities() genai.Modalities {
	return genai.Modalities{genai.ModalityText}
}

func (p *fakeTranscriber) Capabilities() genai.ProviderCapabilities {
	return genai.ProviderCapabilities{Realtime: true}
}

func (p *fakeTranscriber) Scoreboard() scoreboard.Score { return p.score }

func (p *fakeTranscriber) HTTPClient() *http.Client { return nil }

// fixedTranscriber is a genai provider whose transcription sessions each
// return text as the final transcript of the committed utterance.
type fixedTranscriber struct {
	fakeTranscriber

	text string
}

func newFixedLiveASR(text string) *liveASR {
	return newLiveASR(&fixedTranscriber{text: text}, "fake-model")
}

func (p *fixedTranscriber) ConnectRealtime(context.Context, ...genai.RealtimeOption) (genai.RealtimeSession, error) {
	s := newFakeTranscription()
	close(s.connected)
	return fixedTranscription{fakeTranscription: s, text: p.text}, nil
}

// fixedTranscription answers Commit with its final transcript and TurnDone.
type fixedTranscription struct {
	*fakeTranscription

	text string
}

func (s fixedTranscription) Receive(ctx context.Context) iter.Seq2[genai.RealtimeEvent, error] {
	return func(yield func(genai.RealtimeEvent, error) bool) {
		select {
		case <-s.committed:
		case <-ctx.Done():
			yield(genai.RealtimeEvent{}, ctx.Err())
			return
		case <-s.closed:
			yield(genai.RealtimeEvent{}, net.ErrClosed)
			return
		}
		if s.text != "" && !yield(genai.RealtimeEvent{Kind: genai.RealtimeEventTranscriptInput, Content: genai.RealtimeContent{Text: s.text, Final: true}}, nil) {
			return
		}
		yield(genai.RealtimeEvent{Kind: genai.RealtimeEventTurnDone}, nil)
	}
}

// fakeTranscription is a transcription session whose events the test supplies.
// connected, committed and closed close at ConnectRealtime, Commit and Close.
type fakeTranscription struct {
	opts      []genai.RealtimeOption
	connected chan struct{}
	events    chan genai.RealtimeEvent
	failure   chan error
	committed chan struct{}
	closed    chan struct{}

	mu    sync.Mutex
	audio []byte
}

func newFakeTranscription() *fakeTranscription {
	return &fakeTranscription{connected: make(chan struct{}), events: make(chan genai.RealtimeEvent, 8), failure: make(chan error, 1), committed: make(chan struct{}), closed: make(chan struct{})}
}

func (s *fakeTranscription) Info() genai.RealtimeSessionInfo {
	return genai.RealtimeSessionInfo{InputFormat: genai.AudioFormat{SampleRate: micSampleRate}, InputModalities: []genai.Modality{genai.ModalityAudio}, TurnDetection: genai.RealtimeTurnManual, Capabilities: genai.RealtimeCapabilities{AudioCommit: true}}
}

func (s *fakeTranscription) Send(_ context.Context, in genai.RealtimeInput) error {
	v, ok := in.(genai.RealtimeInputContent)
	if !ok || v.Validate() != nil || v.Audio == nil && !v.Commit {
		return fmt.Errorf("transcription input %+v", in)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.audio = append(s.audio, v.Audio...)
	if v.Commit {
		close(s.committed)
	}
	return nil
}

func (s *fakeTranscription) Receive(ctx context.Context) iter.Seq2[genai.RealtimeEvent, error] {
	return func(yield func(genai.RealtimeEvent, error) bool) {
		for {
			select {
			case err := <-s.failure:
				yield(genai.RealtimeEvent{}, err)
				return
			case e := <-s.events:
				if !yield(e, nil) {
					return
				}
			case <-ctx.Done():
				yield(genai.RealtimeEvent{}, ctx.Err())
				return
			case <-s.closed:
				yield(genai.RealtimeEvent{}, net.ErrClosed)
				return
			}
		}
	}
}

func (s *fakeTranscription) Close() error {
	close(s.closed)
	return nil
}

// interim replaces the provider's revisable hypothesis.
func (s *fakeTranscription) interim(text string) {
	s.events <- genai.RealtimeEvent{Kind: genai.RealtimeEventTranscriptInputInterim, Content: genai.RealtimeContent{Text: text}}
}

// delta appends to the transcript of the current segment.
func (s *fakeTranscription) delta(text string) {
	s.events <- genai.RealtimeEvent{Kind: genai.RealtimeEventTranscriptInput, Content: genai.RealtimeContent{Text: text}}
}

// final completes the segment and, when done, the turn. Empty text sends no
// transcript, as genai does for silence.
func (s *fakeTranscription) final(text string, done bool) {
	if text != "" {
		s.events <- genai.RealtimeEvent{Kind: genai.RealtimeEventTranscriptInput, Content: genai.RealtimeContent{Text: text, Final: true}}
	}
	if done {
		s.events <- genai.RealtimeEvent{Kind: genai.RealtimeEventTurnDone}
	}
}

// audioOption returns the session's RealtimeOptionAudio after connect.
func (s *fakeTranscription) audioOption(t *testing.T) *genai.RealtimeOptionAudio {
	<-s.connected
	if !slices.Contains(s.opts, genai.RealtimeOption(genai.RealtimeOptionTranscription{})) {
		t.Fatalf("options %v open no transcription session", s.opts)
	}
	for _, o := range s.opts {
		if v, ok := o.(*genai.RealtimeOptionAudio); ok {
			return v
		}
	}
	t.Fatalf("options %v carry no audio settings", s.opts)
	return nil
}

func newLiveTestSession(t *testing.T, a *liveASR, lang string) (*localStackSession, *captureSink, *fakeConversation) {
	sink := &captureSink{changed: make(chan struct{}, 1)}
	conv := &fakeConversation{}
	s := (&localStackRuntime{liveASR: a}).newSession(t.Context(), "live", sink, true)
	s.conv = conv
	s.language = lang
	t.Cleanup(func() {
		if err := s.close(); err != nil {
			t.Error(err)
		}
	})
	return s, sink, conv
}

func startSpeech(t *testing.T, s *localStackSession) {
	if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
		t.Fatal(err)
	}
}

func endSpeech(t *testing.T, s *localStackSession) {
	if err := s.acceptMicPCM(t.Context(), silencePCM(vadSilenceHangoverMS+vadFrameMS)); err != nil {
		t.Fatal(err)
	}
}

// liveTexts returns the nonempty texts of the captured messages of kind.
func liveTexts(t *testing.T, sink *captureSink, kind voicev2.MessageKind) []string {
	sink.mu.Lock()
	defer sink.mu.Unlock()
	var out []string
	for _, raw := range sink.msgs {
		var msg struct {
			Kind voicev2.MessageKind
			Text string
		}
		if err := json.Unmarshal(raw, &msg); err != nil {
			t.Fatal(err)
		}
		if msg.Kind == kind && msg.Text != "" {
			out = append(out, msg.Text)
		}
	}
	return out
}

func TestLiveASR(t *testing.T) {
	t.Parallel()
	t.Run("checkLanguage", func(t *testing.T) {
		t.Parallel()
		p := &fakeTranscriber{}
		t.Run("valid", func(t *testing.T) {
			t.Parallel()
			for _, tc := range []struct{ model, lang string }{
				{"ggml-org/Voxtral-Mini-4B-Realtime-2602-GGUF:Q4_K_M", "fr"},
				{"LiquidAI/LFM3-Audio-2B-GGUF:Q4_K_M", "en"},
				// Without a declaration, the provider rejects at connect.
				{"Cactus-Compute/whistle", "ja"},
			} {
				if err := newLiveASR(p, tc.model).checkLanguage(tc.lang); err != nil {
					t.Errorf("%s %s: %v", tc.model, tc.lang, err)
				}
			}
		})
		t.Run("error", func(t *testing.T) {
			t.Parallel()
			for _, tc := range []struct{ model, lang, want string }{
				{"ggml-org/Voxtral-Mini-4B-Realtime-2602-GGUF:Q4_K_M", "th", "ggml-org/Voxtral-Mini-4B-Realtime-2602-GGUF does not support Thai"},
				{"LiquidAI/LFM3-Audio-2B-GGUF:Q4_K_M", "fr", "LiquidAI/LFM3-Audio-2B-GGUF does not support French"},
				{"LiquidAI/LFM3-Audio-2B-GGUF/LFM3-Audio-2B-Q4_K_M.gguf", "fr", "LiquidAI/LFM3-Audio-2B-GGUF does not support French"},
			} {
				err := newLiveASR(p, tc.model).checkLanguage(tc.lang)
				if _, ok := errors.AsType[*unsupportedLanguageError](err); !ok || err.Error() != tc.want {
					t.Errorf("%s %s: %v, want %q", tc.model, tc.lang, err, tc.want)
				}
			}
		})
	})
	t.Run("input language", func(t *testing.T) {
		t.Parallel()
		for _, tc := range []struct {
			name  string
			score scoreboard.Score
			want  string
		}{
			{"llama.cpp declines", llamacpp.Scoreboard(), ""},
			{"gemini accepts", gemini.Scoreboard(), "fr"},
			{"unqualified provider receives it", scoreboard.Score{}, "fr"},
		} {
			t.Run(tc.name, func(t *testing.T) {
				t.Parallel()
				session := newFakeTranscription()
				sessions := make(chan *fakeTranscription, 1)
				sessions <- session
				s, _, _ := newLiveTestSession(t, newLiveASR(&fakeTranscriber{score: tc.score, sessions: sessions}, "model"), "fr")
				startSpeech(t, s)
				audio := session.audioOption(t)
				if audio.InputLanguage != tc.want || audio.InputFormat.SampleRate != micSampleRate {
					t.Fatalf("audio option %+v, want language %q at %d Hz", audio, tc.want, micSampleRate)
				}
			})
		}
	})
	t.Run("transcribe", func(t *testing.T) {
		t.Parallel()
		t.Run("valid", func(t *testing.T) {
			t.Parallel()
			for _, tc := range []struct {
				name string
				// speak runs while the user speaks; finish after Commit.
				speak, finish func(*fakeTranscription)
				drafts        []string
				want          string
			}{
				{
					name: "drafts precede the final transcript",
					speak: func(s *fakeTranscription) {
						s.interim("Hel")
						s.delta("Hello ")
						s.interim("wor")
					},
					finish: func(s *fakeTranscription) { s.final("Hello world.", true) },
					drafts: []string{"Hel", "Hello", "Hello wor"},
					want:   "Hello world.",
				},
				{
					name:   "a session without drafts yields only the final transcript",
					speak:  func(*fakeTranscription) {},
					finish: func(s *fakeTranscription) { s.final("Only final.", true) },
					want:   "Only final.",
				},
				{
					name: "a revising final segment refreshes the draft and accumulates",
					speak: func(s *fakeTranscription) {
						s.delta(" One")
						s.final(" One. ", false)
						s.delta("Two")
					},
					finish: func(s *fakeTranscription) { s.final("Two. ", true) },
					drafts: []string{"One", "One.", "One. Two"},
					want:   "One. Two.",
				},
			} {
				t.Run(tc.name, func(t *testing.T) {
					t.Parallel()
					session := newFakeTranscription()
					sessions := make(chan *fakeTranscription, 1)
					sessions <- session
					s, sink, conv := newLiveTestSession(t, newFakeLiveASR(sessions), "en")
					startSpeech(t, s)
					<-session.connected
					tc.speak(session)
					if n := len(tc.drafts); n != 0 {
						waitLiveDraft(t, sink, tc.drafts[n-1])
					}
					if conv.userCalls() != 0 {
						t.Fatal("a draft reached the LLM")
					}
					endSpeech(t, s)
					<-session.committed
					tc.finish(session)
					waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, tc.want, 1)
					if err := s.turns.Wait(); err != nil {
						t.Fatal(err)
					}
					<-session.closed
					if got := liveTexts(t, sink, voicev2.MessageKindTranscriptDraft); !slices.Equal(got, tc.drafts) {
						t.Errorf("drafts %q, want %q", got, tc.drafts)
					}
					if got := liveTexts(t, sink, voicev2.MessageKindTranscriptDelta); !slices.Equal(got, []string{tc.want}) {
						t.Errorf("transcripts %q, want %q", got, tc.want)
					}
					if conv.userCalls() != 1 {
						t.Errorf("committed turns: %d", conv.userCalls())
					}
				})
			}
		})
		t.Run("error", func(t *testing.T) {
			t.Parallel()
			for name, fail := range map[string]func(*fakeTranscription){
				"turn done before input ended": func(s *fakeTranscription) { s.final("early", true) },
				"provider error": func(s *fakeTranscription) {
					s.events <- genai.RealtimeEvent{Kind: genai.RealtimeEventError, Error: &genai.RealtimeError{Message: "failed"}}
				},
				"transport":  func(s *fakeTranscription) { s.failure <- errors.New("connection reset") },
				"peer close": func(s *fakeTranscription) { s.failure <- io.EOF },
				"oversize":   func(s *fakeTranscription) { s.interim(strings.Repeat("x", 65537)) },
			} {
				t.Run(name, func(t *testing.T) {
					t.Parallel()
					session := newFakeTranscription()
					sessions := make(chan *fakeTranscription, 1)
					sessions <- session
					s, sink, conv := newLiveTestSession(t, newFakeLiveASR(sessions), "en")
					startSpeech(t, s)
					<-session.connected
					fail(session)
					waitLiveMessage(t, sink, voicev2.MessageKindError, "", 1)
					<-session.closed
					if err := s.turns.Wait(); err != nil {
						t.Fatal(err)
					}
					if conv.userCalls() != 0 || len(liveTexts(t, sink, voicev2.MessageKindTranscriptDelta)) != 0 {
						t.Fatal("failed input reached the conversation")
					}
				})
			}
		})
	})
	t.Run("providers", func(t *testing.T) {
		t.Parallel()
		t.Run("whistle", func(t *testing.T) {
			t.Parallel()
			// worker is a fake Whistle worker: raw 16 kHz PCM in, JSON out.
			worker := func(t *testing.T, status int, body string) (*liveASR, <-chan int) {
				received := make(chan int, 1)
				srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					pcm, err := io.ReadAll(r.Body)
					if err != nil {
						t.Error(err)
					}
					received <- len(pcm)
					w.WriteHeader(status)
					if _, err := io.WriteString(w, body); err != nil {
						t.Error(err)
					}
				}))
				t.Cleanup(srv.Close)
				c, err := whistle.New(srv.Client(), srv.URL, "")
				if err != nil {
					t.Fatal(err)
				}
				return newLiveASR(c, c.ModelID()), received
			}
			t.Run("valid", func(t *testing.T) {
				t.Parallel()
				a, received := worker(t, http.StatusOK, `{"text":" hello there "}`)
				s, sink, conv := newLiveTestSession(t, a, "fr")
				startSpeech(t, s)
				endSpeech(t, s)
				waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, "hello there", 1)
				if err := s.turns.Wait(); err != nil {
					t.Fatal(err)
				}
				if n := <-received; n == 0 {
					t.Error("worker received no audio")
				}
				if drafts := liveTexts(t, sink, voicev2.MessageKindTranscriptDraft); len(drafts) != 0 {
					t.Errorf("final-only session published drafts %q", drafts)
				}
				if conv.userCalls() != 1 {
					t.Errorf("committed turns: %d", conv.userCalls())
				}
			})
			t.Run("error", func(t *testing.T) {
				t.Parallel()
				a, _ := worker(t, http.StatusInternalServerError, `{"error":"engine failed"}`)
				s, sink, conv := newLiveTestSession(t, a, "en")
				startSpeech(t, s)
				endSpeech(t, s)
				waitLiveMessage(t, sink, voicev2.MessageKindError, "", 1)
				if err := s.turns.Wait(); err != nil {
					t.Fatal(err)
				}
				if conv.userCalls() != 0 {
					t.Fatal("failed recognition reached the LLM")
				}
			})
		})
		// The native peers prove that each concrete client accepts the
		// adapter's options and delivers its transcript.
		peer := func(t *testing.T, run func(context.Context, *websocket.Conn)) string {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				ws, err := websocket.Accept(w, r, nil)
				if err != nil {
					t.Error(err)
					return
				}
				t.Cleanup(func() {
					if err := ws.CloseNow(); err != nil && !errors.Is(err, net.ErrClosed) {
						t.Error(err)
					}
				})
				run(r.Context(), ws)
			}))
			t.Cleanup(srv.Close)
			return srv.URL
		}
		t.Run("llama.cpp", func(t *testing.T) {
			t.Parallel()
			remote := peer(t, func(ctx context.Context, ws *websocket.Conn) {
				writeGeminiFixture(t, ctx, ws, `{"type":"session.created","session":{"type":"transcription","audio":{"input":{"format":{"type":"audio/pcm","rate":16000}}}}}`)
				for msg := readGeminiFixture(t, ctx, ws); msg != nil; msg = readGeminiFixture(t, ctx, ws) {
					if msg["type"] == "input_audio_buffer.append" {
						break
					}
					t.Errorf("unexpected %v before audio", msg["type"])
				}
				writeGeminiFixture(t, ctx, ws, `{"type":"conversation.item.input_audio_transcription.delta","item_id":"u","delta":"Revised draft"}`)
				for msg := readGeminiFixture(t, ctx, ws); msg != nil; msg = readGeminiFixture(t, ctx, ws) {
					if msg["type"] == "input_audio_buffer.commit" {
						break
					}
				}
				writeGeminiFixture(t, ctx, ws, `{"type":"conversation.item.input_audio_transcription.completed","item_id":"u","transcript":"Final words."}`)
				_, _, _ = ws.Read(ctx)
			})
			p, err := llamacpp.New(t.Context(), genai.ProviderOptionRemote(remote))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := p.Close(); err != nil {
					t.Error(err)
				}
			})
			s, sink, _ := newLiveTestSession(t, newLiveASR(p, "ggml-org/Voxtral-Mini-4B-Realtime-2602-GGUF:Q4_K_M"), "fr")
			startSpeech(t, s)
			waitLiveDraft(t, sink, "Revised draft")
			endSpeech(t, s)
			waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, "Final words.", 1)
			if err := s.turns.Wait(); err != nil {
				t.Fatal(err)
			}
		})
		t.Run("nemospeech", func(t *testing.T) {
			t.Parallel()
			t.Run("streaming", func(t *testing.T) {
				t.Parallel()
				// The peer follows NeMo-Speech.cpp's realtime protocol: JSON
				// control messages and binary PCM frames.
				remote := peer(t, func(ctx context.Context, ws *websocket.Conn) {
					writeGeminiFixture(t, ctx, ws, `{"type":"session.created"}`)
					update := readGeminiFixture(t, ctx, ws)
					if session, _ := update["session"].(map[string]any); session["language"] != "fr-FR" || session["sample_rate"] != float64(micSampleRate) {
						t.Errorf("session.update %v, want fr-FR at %d Hz", update, micSampleRate)
					}
					writeGeminiFixture(t, ctx, ws, `{"type":"session.updated"}`)
					if typ, _, err := ws.Read(ctx); err != nil || typ != websocket.MessageBinary {
						t.Errorf("first input %v, %v; want binary audio", typ, err)
						return
					}
					writeGeminiFixture(t, ctx, ws, `{"type":"conversation.item.input_audio_transcription.delta","delta":"Bonjour"}`)
					for {
						typ, data, err := ws.Read(ctx)
						if err != nil {
							t.Error(err)
							return
						}
						if typ == websocket.MessageText && strings.Contains(string(data), "input_audio_buffer.commit") {
							break
						}
					}
					writeGeminiFixture(t, ctx, ws, `{"type":"conversation.item.input_audio_transcription.completed","transcript":"Bonjour tout le monde."}`)
					writeGeminiFixture(t, ctx, ws, `{"type":"input_audio_buffer.committed"}`)
					_, _, _ = ws.Read(ctx)
				})
				c, err := nemospeech.New(http.DefaultClient, remote, nemospeech.NemotronASR)
				if err != nil {
					t.Fatal(err)
				}
				s, sink, _ := newLiveTestSession(t, newLiveASR(c, string(nemospeech.NemotronASR)), "fr")
				startSpeech(t, s)
				waitLiveDraft(t, sink, "Bonjour")
				endSpeech(t, s)
				waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, "Bonjour tout le monde.", 1)
				if err := s.turns.Wait(); err != nil {
					t.Fatal(err)
				}
			})
			t.Run("final only", func(t *testing.T) {
				t.Parallel()
				srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					if r.URL.Path != "/v1/audio/transcriptions" {
						t.Errorf("unexpected request %s", r.URL.Path)
					}
					form, err := r.MultipartReader()
					if err != nil {
						t.Error(err)
						return
					}
					var fields []string
					for part, err := form.NextPart(); err == nil; part, err = form.NextPart() {
						fields = append(fields, part.FormName())
					}
					// Parakeet TDT detects the language; none is sent.
					if !slices.Equal(fields, []string{"file", "response_format"}) {
						t.Errorf("form fields %q, want audio without a language", fields)
					}
					_, _ = io.WriteString(w, `{"text":" Bonjour. "}`)
				}))
				t.Cleanup(srv.Close)
				c, err := nemospeech.New(srv.Client(), srv.URL, nemospeech.ParakeetTDT)
				if err != nil {
					t.Fatal(err)
				}
				s, sink, _ := newLiveTestSession(t, newLiveASR(c, string(nemospeech.ParakeetTDT)), "fr")
				startSpeech(t, s)
				endSpeech(t, s)
				waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, "Bonjour.", 1)
				if err := s.turns.Wait(); err != nil {
					t.Fatal(err)
				}
				if drafts := liveTexts(t, sink, voicev2.MessageKindTranscriptDraft); len(drafts) != 0 {
					t.Errorf("final-only session published drafts %q", drafts)
				}
			})
		})
		t.Run("mistral", func(t *testing.T) {
			t.Parallel()
			const session = `{"request_id":"r","model":"voxtral-mini-transcribe-realtime-3","audio_format":{"encoding":"pcm_s16le","sample_rate":16000}}`
			remote := peer(t, func(ctx context.Context, ws *websocket.Conn) {
				writeGeminiFixture(t, ctx, ws, `{"type":"session.created","session":`+session+`}`)
				update := readGeminiFixture(t, ctx, ws)
				if cfg, _ := update["session"].(map[string]any); cfg["language"] != "fr" || cfg["audio_format"].(map[string]any)["sample_rate"] != float64(micSampleRate) {
					t.Errorf("session.update %v, want French at %d Hz", update, micSampleRate)
				}
				writeGeminiFixture(t, ctx, ws, `{"type":"session.updated","session":`+session+`}`)
				for msg := readGeminiFixture(t, ctx, ws); msg != nil && msg["type"] != "input_audio.append"; msg = readGeminiFixture(t, ctx, ws) {
				}
				writeGeminiFixture(t, ctx, ws, `{"type":"transcription.text.delta","text":"Initial draft"}`)
				for msg := readGeminiFixture(t, ctx, ws); msg != nil && msg["type"] != "input_audio.end"; msg = readGeminiFixture(t, ctx, ws) {
				}
				writeGeminiFixture(t, ctx, ws, `{"type":"transcription.done","text":"Final words."}`)
				_, _, _ = ws.Read(ctx)
			})
			target, err := url.Parse(remote)
			if err != nil {
				t.Fatal(err)
			}
			p, err := mistral.New(t.Context(), genai.ProviderOptionAPIKey("test-key"), genai.ProviderOptionModel("voxtral-mini-transcribe-realtime-3"), genai.ProviderOptionTransportWrapper(func(next http.RoundTripper) http.RoundTripper {
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
			s, sink, _ := newLiveTestSession(t, newLiveASR(p, p.ModelID()), "fr")
			startSpeech(t, s)
			waitLiveDraft(t, sink, "Initial draft")
			endSpeech(t, s)
			waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, "Final words.", 1)
			if err := s.turns.Wait(); err != nil {
				t.Fatal(err)
			}
		})
		t.Run("gemini", func(t *testing.T) {
			t.Parallel()
			remote := peer(t, func(ctx context.Context, ws *websocket.Conn) {
				setup := readGeminiFixture(t, ctx, ws)
				transcription, _ := setup["setup"].(map[string]any)["inputAudioTranscription"].(map[string]any)
				if codes, _ := transcription["languageCodes"].([]any); !slices.Equal(codes, []any{"fr"}) {
					t.Errorf("input transcription %v, want languageCodes [fr]", transcription)
				}
				writeGeminiFixture(t, ctx, ws, `{"setupComplete":{}}`)
				for msg := readGeminiFixture(t, ctx, ws); msg != nil; msg = readGeminiFixture(t, ctx, ws) {
					if msg["realtimeInput"].(map[string]any)["audio"] != nil {
						break
					}
				}
				writeGeminiFixture(t, ctx, ws, `{"serverContent":{"interimInputTranscription":{"text":"Initial draft"}}}`)
				for msg := readGeminiFixture(t, ctx, ws); msg != nil; msg = readGeminiFixture(t, ctx, ws) {
					if msg["realtimeInput"].(map[string]any)["activityEnd"] != nil {
						break
					}
				}
				writeGeminiFixture(t, ctx, ws, `{"serverContent":{"inputTranscription":{"text":"Final words."}}}`)
				writeGeminiFixture(t, ctx, ws, `{"serverContent":{"generationComplete":true}}`)
				_, _, _ = ws.Read(ctx)
			})
			target, err := url.Parse(remote)
			if err != nil {
				t.Fatal(err)
			}
			p, err := gemini.New(t.Context(), genai.ProviderOptionAPIKey("test-key"), genai.ProviderOptionModel("gemini-3.5-transcribe-live"), genai.ProviderOptionTransportWrapper(func(next http.RoundTripper) http.RoundTripper {
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
			s, sink, _ := newLiveTestSession(t, newLiveASR(p, p.ModelID()), "fr")
			startSpeech(t, s)
			waitLiveDraft(t, sink, "Initial draft")
			endSpeech(t, s)
			waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, "Final words.", 1)
			if err := s.turns.Wait(); err != nil {
				t.Fatal(err)
			}
		})
	})
}
