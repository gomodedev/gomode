// Real synthesis of a French reply per TTS engine, transcribed back by live ASR.

//go:build smoke

package voicertc

import (
	"strings"
	"testing"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genaipy/providers/kittentts/kittenttssrv"
	"github.com/maruel/genaipy/providers/nemospeech"
)

// TestSmokeTTSLanguage synthesizes a French sentence in a French session with
// KittenTTS 2, whose voice the session language selects, and both Gemini TTS
// tiers, which receive the language, then transcribes each through NeMo
// Parakeet TDT in French. GEMINI_API_KEY is required; the models download on first use.
func TestSmokeTTSLanguage(t *testing.T) {
	isolateSmokeHost(t)
	const text = "Je voudrais réserver une table pour deux ce soir."
	endpoint, err := nemoSpeechEndpoint(t.Context(), "", nemospeech.ParakeetTDT)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := endpoint.runtime.Close(); err != nil {
			t.Error(err)
		}
	})
	asr := newLiveASR(endpoint.provider, string(nemospeech.ParakeetTDT))
	for _, row := range []struct {
		name string
		tts  func(t *testing.T) ttsAdapter
	}{
		{"KittenML/kitten-tts-2", func(t *testing.T) ttsAdapter {
			srv, err := kittenttssrv.New(t.Context(), kittenttssrv.Config{Model: "KittenML/kitten-tts-2"})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := srv.Close(); err != nil {
					t.Error(err)
				}
			})
			// The configured English voice does not serve a French session.
			a, err := newKittenTTSAdapter(srv.URL(), "KittenML/kitten-tts-2", "Jasper")
			if err != nil {
				t.Fatal(err)
			}
			return a
		}},
		{"gemini-3.8-flash-lite-tts", geminiSmokeTTS("gemini-3.8-flash-lite-tts")},
		{"gemini-3.8-flash-tts", geminiSmokeTTS("gemini-3.8-flash-tts")},
	} {
		t.Run(row.name, func(t *testing.T) {
			tts := row.tts(t)
			if err := tts.checkLanguage("fr"); err != nil {
				t.Fatal(err)
			}
			voice, err := tts.sessionVoice("fr", "")
			if err != nil {
				t.Fatal(err)
			}
			var pcm []byte
			for chunk, err := range tts.synthesize(t.Context(), "fr", voice, text) {
				if err != nil {
					t.Fatal(err)
				}
				pcm = append(pcm, chunk...)
			}
			_, final, failed := smokeLiveUtterance(t, asr, "fr", downsample24to16(pcm))
			if failed != "" {
				t.Fatalf("utterance failed: %s", failed)
			}
			t.Logf("voice %q, %d PCM bytes, transcript %q", voice, len(pcm), final)
			if !strings.Contains(strings.ToLower(final), "table") {
				t.Errorf("transcript %q lacks %q", final, "table")
			}
		})
	}
}

// geminiSmokeTTS synthesizes through Gemini TTS model with the Kore voice.
func geminiSmokeTTS(model string) func(t *testing.T) ttsAdapter {
	return func(t *testing.T) ttsAdapter {
		c, err := gemini.New(t.Context(), genai.ProviderOptionModel(model), genai.ProviderOptionModalities{genai.ModalityAudio})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := c.Close(); err != nil {
				t.Error(err)
			}
		})
		return &geminiTTSAdapter{client: c, voice: "Kore"}
	}
}
