// Real live transcription of English and French speech in English and French sessions, per model.

//go:build smoke

package voicertc

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/gemini"

	"github.com/maruel/gomode/voicegateway"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

// TestSmokeLiveASR logs, per model, whether the session language is sent, the
// drafts and the final transcript of English and French speech in matching
// and mismatched sessions. Gemini TTS synthesizes the speech, so
// GEMINI_API_KEY is required; managed llama.cpp models download on first use.
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
		for chunk, err := range (&geminiTTSAdapter{client: tts, voice: "Kore"}).synthesize(t.Context(), lang, text) {
			if err != nil {
				t.Fatal(err)
			}
			pcm = append(pcm, chunk...)
		}
		speech[lang] = downsample24to16(pcm)
	}
	for _, model := range []voicegateway.ModelConfig{
		{Provider: "gemini", Model: "gemini-3.5-transcribe-live"},
		{Provider: "llamacpp", Model: "ggml-org/Voxtral-Mini-4B-Realtime-2602-GGUF:Q4_K_M"},
		{Provider: "llamacpp", Model: "LiquidAI/LFM3-Audio-2B-GGUF:Q4_K_M"},
	} {
		t.Run(model.Model, func(t *testing.T) {
			endpoint, err := resolveLocalStackEndpoint(t.Context(), model, 32768, startManagedLlamaServer)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := endpoint.runtime.Close(); err != nil {
					t.Error(err)
				}
			})
			a := newLiveASR(endpoint.provider, model.Model)
			for _, tc := range []struct{ speech, session string }{{"en", "en"}, {"fr", "fr"}, {"fr", "en"}, {"en", "fr"}} {
				if err := a.checkLanguage(tc.session); err != nil {
					t.Logf("%s speech, %s session: rejected at setup: %v", tc.speech, tc.session, err)
					continue
				}
				t.Run(tc.speech+" speech in "+tc.session, func(t *testing.T) {
					drafts, final := smokeLiveUtterance(t, a, tc.session, speech[tc.speech])
					t.Logf("%s speech, %s session: language sent %t, %d drafts, last %q, final %q", tc.speech, tc.session, a.inputLanguage, len(drafts), lastOr(drafts), final)
					if !strings.Contains(strings.ToLower(final), "table") {
						t.Errorf("final %q lacks %q", final, "table")
					}
				})
			}
		})
	}
}

// smokeLiveUtterance speaks pcm at real time through a live session in lang
// and returns its drafts and final transcript. The session closes when t ends.
func smokeLiveUtterance(t *testing.T, a *liveASR, lang string, pcm []byte) ([]string, string) {
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
		if failed != "" {
			t.Fatalf("utterance failed: %s", failed)
		}
		if done {
			return drafts, final
		}
		select {
		case <-sink.changed:
		case <-deadline:
			t.Fatalf("no final transcript; drafts %q", drafts)
		}
	}
}

func lastOr(v []string) string {
	if len(v) == 0 {
		return ""
	}
	return v[len(v)-1]
}
