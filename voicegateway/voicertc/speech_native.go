// Provider-specific streamed synthesis for gateway voice stages.

package voicertc

import (
	"cmp"
	"context"
	"encoding/base64"
	"errors"
	"io"
	"iter"
	"log/slog"
	"slices"

	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genai/providers/llamacpp"
)

// llamaTTSAdapter synthesizes through llama-server's /v1/audio/speech, sending
// the session language as SpeechRequest.Lang. A non-empty lang is instead a
// fixed SpeechRequest.Lang and the only session language the adapter accepts;
// staged LFM3 sessions fix "en", which declares that model English only.
// Otherwise llamaTTSLanguages declares the accepted languages. model names the
// configured model in errors and selects its language declaration.
//
// In llama.cpp v1.0.0 (tools/server/server-context.cpp handle_tts_impl),
// "lang" renders the model's "tts" chat template and reaches the model's
// audio pipeline (tools/mtmd/mtmd-helper-gen.cpp).
type llamaTTSAdapter struct {
	noVoiceSelection
	client *llamacpp.Client
	model  string
	voice  string
	lang   string
}

// llamaTTSLanguages declares the session languages of llama.cpp TTS models by
// Hugging Face repository, from llama.cpp v1.0.0 tools/tts/README.md and the
// sources it describes. nil accepts any language.
//
// Qwen3-TTS maps 10 codes to <|codec_language_*|> tokens and fails generation
// for any other language (mtmd-helper-gen.cpp tts_lang_codes and
// qwen3tts_gen_audio_pipeline::set_input). LFM3-Audio's template names 18
// codes and passes any other value verbatim as the language name
// (common/chat.cpp lfm3_language_name), which nothing documents the model
// speaking.
//
// An unlisted model accepts every language because llama.cpp does not report
// one: Pocket TTS speaks the language of its weights, Soprano English, and
// Drama 3 the language of the text, and all three ignore "lang". Chatterbox
// Multilingual, whose "lang" prefixes the text, needs classifier-free guidance
// that llama-server does not run yet. The model, not the adapter, owns the
// language of these.
func llamaTTSLanguages(model string) []string {
	switch modelRepository(model) {
	case "ggml-org/Qwen3-TTS-12Hz-1.7B-Base-GGUF":
		return []string{"de", "en", "es", "fr", "it", "ja", "ko", "pt", "ru", "zh"}
	case "LiquidAI/LFM3-Audio-2B-GGUF":
		return []string{"ar", "da", "de", "el", "en", "es", "fi", "fr", "he", "it", "ja", "ko", "nl", "pl", "pt", "sv", "tr", "zh"}
	default:
		return nil
	}
}

func (a *llamaTTSAdapter) checkLanguage(lang string) error {
	if a.lang != "" {
		if lang != a.lang {
			return newUnsupportedLanguageError(a.model, lang)
		}
		return nil
	}
	if languages := llamaTTSLanguages(a.model); languages != nil && !slices.Contains(languages, lang) {
		return newUnsupportedLanguageError(a.model, lang)
	}
	return nil
}

func (a *llamaTTSAdapter) synthesize(ctx context.Context, lang, _, text string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		req := &llamacpp.SpeechRequest{Input: text, Voice: a.voice, Lang: cmp.Or(a.lang, lang), ResponseFormat: llamacpp.SpeechPCM, Stream: true}
		res, err := a.client.SpeechStreamRaw(ctx, req) //nolint:bodyclose // Iteration owns and closes the native body below.
		if err != nil {
			yield(nil, err)
			return
		}
		for pcm, err := range normalizePCM(res.Body, res.Header.Get("Content-Type")) {
			if err != nil {
				yield(nil, errors.Join(err, res.Body.Close()))
				return
			}
			if !yield(pcm, nil) {
				cancel()
				if err := res.Body.Close(); err != nil {
					slog.WarnContext(ctx, "close speech response", "err", err)
				}
				return
			}
		}
		if err := res.Body.Close(); err != nil {
			yield(nil, err)
		}
	}
}

// geminiTTSAdapter synthesizes through a Gemini speech model, sending the
// session language as InteractionSpeechConfig.Language ("The language of the
// speech" in googleapis/go-genai interactions/models/interactions
// speechconfig.go) beside the configured voice.
//
// Every session language passes setup: Gemini's speech generation guide
// (https://ai.google.dev/gemini-api/docs/speech-generation, "Supported
// languages") says the models detect the input language and lists over 100
// by name only, with no codes to declare, and states no outcome for an
// unsupported one.
type geminiTTSAdapter struct {
	anyLanguage
	noVoiceSelection
	client *gemini.Client
	voice  string
}

func (a *geminiTTSAdapter) synthesize(ctx context.Context, lang, _, text string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		req := &gemini.InteractionRequest{
			Model: a.client.ModelID(), Store: new(false), Input: []gemini.InteractionContent{{Type: gemini.InteractionText, Text: text}},
			ResponseFormat:   &gemini.InteractionResponseFormat{Type: gemini.InteractionAudio, MIMEType: "audio/l16", SampleRate: 24000},
			GenerationConfig: &gemini.InteractionGenerationConfig{SpeechConfig: []gemini.InteractionSpeechConfig{{Voice: a.voice, Language: lang}}},
		}
		events, finish := a.client.InteractionStreamRaw(ctx, req)
		seen := false
		pending := []byte(nil)
		var err error
		stopped := false
		for event := range events {
			if event.EventType != gemini.InteractionEventDelta {
				continue
			}
			d := &event.Delta
			if d.Type != gemini.InteractionAudio || d.URI != "" || d.MIMEType != "" && d.MIMEType != "audio/l16" || d.SampleRate != 0 && d.SampleRate != 24000 || d.Channels != 0 && d.Channels != 1 {
				err = errors.New("gemini speech returned an incompatible audio format")
				break
			}
			var data []byte
			data, err = base64.StdEncoding.DecodeString(d.Data)
			if err != nil {
				break
			}
			seen = seen || len(data) != 0
			data = append(pending, data...)
			end := len(data) / 2 * 2
			pending = append([]byte(nil), data[end:]...)
			// Gemini explicitly defines its audio/l16 as little-endian mono PCM16.
			if end != 0 && !yield(data[:end], nil) {
				stopped = true
				cancel()
				break
			}
		}
		if err != nil {
			cancel()
		}
		err = errors.Join(err, finish())
		if stopped {
			if err != nil && !errors.Is(err, context.Canceled) {
				slog.WarnContext(ctx, "finish Gemini speech", "err", err)
			}
			return
		}
		if err == nil && !seen {
			err = errors.New("gemini speech completed without audio")
		}
		if err == nil && len(pending) != 0 {
			err = io.ErrUnexpectedEOF
		}
		if err != nil {
			yield(nil, err)
		}
	}
}
