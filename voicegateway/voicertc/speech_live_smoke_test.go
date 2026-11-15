// Real live transcription of English and French speech in English and French sessions, per model.

//go:build smoke

package voicertc

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genaipy/providers/nemospeech"

	"github.com/maruel/gomode/voicegateway"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// TestSmokeLiveASR logs, per model, whether the session language is sent, the
// drafts and the final transcript of English and French speech in matching
// and mismatched sessions. Gemini TTS synthesizes the speech, so
// GEMINI_API_KEY is required; managed llama.cpp and NeMo-Speech.cpp models
// download on first use.
func TestSmokeLiveASR(t *testing.T) {
	isolateSmokeHost(t)
	tts, err := gemini.New(t.Context(), genai.ProviderOptionModel("gemini-3.8-flash-lite-tts"), genai.ProviderOptionModalities{genai.ModalityAudio})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := tts.Close(); err != nil {
			t.Error(err)
		}
	})
	// Both sentences contain "table", which a transcript in either language
	// keeps.
	speech := map[string][]byte{}
	for lang, text := range map[string]string{
		"en": "I would like to book a table for two tonight.",
		"fr": "Je voudrais réserver une table pour deux ce soir.",
	} {
		var pcm []byte
		for chunk, err := range (&geminiTTSAdapter{client: tts, voice: "Kore"}).synthesize(t.Context(), lang, "", text) {
			if err != nil {
				t.Fatal(err)
			}
			pcm = append(pcm, chunk...)
		}
		speech[lang] = downsample24to16(pcm)
	}
	resolve := func(provider, model string) func(context.Context) (localStackEndpoint, error) {
		return func(ctx context.Context) (localStackEndpoint, error) {
			return resolveLocalStackEndpoint(ctx, voicegateway.ModelConfig{Provider: provider, Model: model}, 32768, startManagedLlamaServer)
		}
	}
	nemo := func(model nemospeech.Model) func(context.Context) (localStackEndpoint, error) {
		return func(ctx context.Context) (localStackEndpoint, error) {
			return nemoSpeechEndpoint(ctx, "", model)
		}
	}
	for _, row := range []struct {
		model    string
		endpoint func(context.Context) (localStackEndpoint, error)
		// finalOnly models yield no drafts.
		finalOnly bool
		// Speech in a language other than the session's is only logged for
		// mismatched models: the session language is forced on them, which
		// degrades other speech, or they transcribe English only.
		mismatched bool
		// englishOnly models fail each utterance of a French session at connect.
		englishOnly bool
	}{
		{model: "gemini-3.5-transcribe-live", endpoint: resolve("gemini", "gemini-3.5-transcribe-live")},
		{model: "ggml-org/Voxtral-Mini-4B-Realtime-2602-GGUF:Q4_K_M", endpoint: resolve("llamacpp", "ggml-org/Voxtral-Mini-4B-Realtime-2602-GGUF:Q4_K_M")},
		{model: "LiquidAI/LFM3-Audio-2B-GGUF:Q4_K_M", endpoint: resolve("llamacpp", "LiquidAI/LFM3-Audio-2B-GGUF:Q4_K_M")},
		{model: string(nemospeech.NemotronASR), endpoint: nemo(nemospeech.NemotronASR), mismatched: true},
		{model: string(nemospeech.NemotronASREnglish), endpoint: nemo(nemospeech.NemotronASREnglish), mismatched: true, englishOnly: true},
		{model: string(nemospeech.ParakeetCTC), endpoint: nemo(nemospeech.ParakeetCTC), mismatched: true, englishOnly: true},
		{model: string(nemospeech.ParakeetTDT), endpoint: nemo(nemospeech.ParakeetTDT), finalOnly: true},
	} {
		t.Run(row.model, func(t *testing.T) {
			endpoint, err := row.endpoint(t.Context())
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := endpoint.runtime.Close(); err != nil {
					t.Error(err)
				}
			})
			a := newLiveASR(endpoint.provider, row.model)
			for _, tc := range []struct{ speech, session string }{{"en", "en"}, {"fr", "fr"}, {"fr", "en"}, {"en", "fr"}} {
				if err := a.checkLanguage(tc.session); err != nil {
					t.Logf("%s speech, %s session: rejected at setup: %v", tc.speech, tc.session, err)
					continue
				}
				t.Run(tc.speech+" speech in "+tc.session, func(t *testing.T) {
					start := time.Now()
					drafts, final, failed := smokeLiveUtterance(t, a, tc.session, speech[tc.speech])
					if row.englishOnly && tc.session == "fr" {
						if want := "asr: " + row.model + " does not support French"; failed != want {
							t.Fatalf("utterance error %q, want %q", failed, want)
						}
						t.Logf("%s speech, %s session: rejected at connect: %s", tc.speech, tc.session, failed)
						return
					}
					if failed != "" {
						t.Fatalf("utterance failed: %s", failed)
					}
					t.Logf("%s speech, %s session: language sent %t, %d drafts, first %q, last %q, final %q after %s", tc.speech, tc.session, a.inputLanguage, len(drafts), firstOr(drafts), lastOr(drafts), final, time.Since(start).Round(time.Millisecond))
					if row.mismatched && tc.speech != tc.session {
						return
					}
					if !strings.Contains(strings.ToLower(final), "table") {
						t.Errorf("final %q lacks %q", final, "table")
					}
					if row.finalOnly != (len(drafts) == 0) {
						t.Errorf("%d drafts; final only: %t", len(drafts), row.finalOnly)
					}
				})
			}
		})
	}
}

// smokeLiveUtterance speaks pcm at real time through a live session in lang
// and returns its drafts and final transcript, or the client error that
// rejected the utterance. The session closes when t ends.
func smokeLiveUtterance(t *testing.T, a *liveASR, lang string, pcm []byte) ([]string, string, string) {
	sink := &captureSink{changed: make(chan struct{}, 1)}
	s := (&localStackRuntime{liveASR: a}).newSession(t.Context(), "smoke", sink, true)
	s.conv = &fakeConversation{}
	s.language = lang
	t.Cleanup(func() {
		if err := s.close(); err != nil {
			t.Error(err)
		}
	})
	const frame = micSampleRate * 2 * 20 / 1000
	speak := func(pcm []byte) {
		for len(pcm) != 0 {
			n := min(frame, len(pcm))
			if err := s.acceptMicPCM(t.Context(), pcm[:n]); err != nil {
				t.Fatal(err)
			}
			pcm = pcm[n:]
			time.Sleep(20 * time.Millisecond)
		}
	}
	speak(pcm)
	speak(silencePCM(vadSilenceHangoverMS + 10*vadFrameMS))
	deadline := time.After(2 * time.Minute)
	for {
		var drafts []string
		final, done, failed := "", false, ""
		sink.mu.Lock()
		for _, raw := range sink.msgs {
			var msg struct {
				Kind    voicev2.MessageKind
				Text    string
				Message string
			}
			if err := json.Unmarshal(raw, &msg); err != nil {
				t.Fatal(err)
			}
			switch msg.Kind {
			case voicev2.MessageKindTranscriptDraft:
				if msg.Text != "" {
					drafts = append(drafts, msg.Text)
				}
			case voicev2.MessageKindTranscriptDelta:
				final, done = msg.Text, true
			case voicev2.MessageKindError:
				failed = msg.Message
			default:
			}
		}
		sink.mu.Unlock()
		if failed != "" || done {
			return drafts, final, failed
		}
		select {
		case <-sink.changed:
		case <-deadline:
			t.Fatalf("no final transcript; drafts %q", drafts)
		}
	}
}

func firstOr(v []string) string {
	if len(v) == 0 {
		return ""
	}
	return v[0]
}

func lastOr(v []string) string {
	if len(v) == 0 {
		return ""
	}
	return v[len(v)-1]
}
