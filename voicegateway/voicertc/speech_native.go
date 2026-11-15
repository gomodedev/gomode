// Provider-specific file recognition and streamed synthesis for gateway voice stages.

package voicertc

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"iter"
	"log/slog"
	"strings"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genai/providers/groq"
	"github.com/maruel/genai/providers/llamacpp"
)

// groqASRAdapter transcribes through Groq's Whisper endpoint, which detects
// the language when TranscriptionRequest.Language (ISO 639-1) is empty.
// Measured with whisper-large-v3-turbo on 4 s English and French requests:
// detection is correct, and a mismatched Language is ignored rather than
// translated.
//
// TODO: Set Language from the session.
type groqASRAdapter struct {
	anyLanguage
	client *groq.Client
}

func (a *groqASRAdapter) transcribe(ctx context.Context, _ string, pcm []byte) (string, error) {
	if len(pcm) == 0 {
		return "", nil
	}
	wav, err := speechWAV(pcm, micSampleRate)
	if err != nil {
		return "", err
	}
	var out groq.TranscriptionResponse
	err = a.client.TranscriptionRaw(ctx, &groq.TranscriptionRequest{Model: a.client.ModelID(), File: bytes.NewReader(wav), Filename: "speech.wav"}, &out)
	if err != nil {
		return "", err
	}
	if out.Text == nil {
		return "", errors.New("transcription response has no text")
	}
	return strings.TrimSpace(*out.Text), nil
}

// geminiASRAdapter transcribes through a Gemini transcription model, which
// detects the language without hints. Measured with gemini-3.5-transcribe on
// 4 s English and French requests: detection is correct, and a mismatched
// GenerationConfig.TranscriptionConfig.LanguageCodes hint does not change the
// words; an English hint on French speech drops the space before "?".
//
// TODO: Send the session language as LanguageCodes.
type geminiASRAdapter struct {
	anyLanguage
	client *gemini.Client
}

func (a *geminiASRAdapter) transcribe(ctx context.Context, _ string, pcm []byte) (string, error) {
	if len(pcm) == 0 {
		return "", nil
	}
	wav, err := speechWAV(pcm, micSampleRate)
	if err != nil {
		return "", err
	}
	var out gemini.InteractionResponse
	err = a.client.InteractionRaw(ctx, &gemini.InteractionRequest{Model: a.client.ModelID(), Store: new(false), Input: []gemini.InteractionContent{{Type: gemini.InteractionAudio, MIMEType: "audio/wav", Source: bytes.NewReader(wav)}}}, &out)
	if err != nil {
		return "", err
	}
	if len(out.Errors) != 0 {
		var failures []error
		for i := range out.Errors {
			failures = append(failures, &out.Errors[i])
		}
		return "", errors.Join(failures...)
	}
	if out.Status != gemini.InteractionCompleted {
		return "", fmt.Errorf("gemini transcription ended with status %q", out.Status)
	}
	var text strings.Builder
	for _, step := range out.Steps {
		if step.Type == gemini.InteractionModelOutput {
			for _, content := range step.Content {
				if content.Type == gemini.InteractionText {
					text.WriteString(content.Text)
				}
			}
		}
	}
	return strings.TrimSpace(text.String()), nil
}

// newChatASRAdapter transcribes through a chat model that accepts audio,
// using the request format and language mechanism of the configured model.
func newChatASRAdapter(p genai.Provider, model string) asrAdapter {
	if strings.Contains(strings.ToLower(model), "qwen3-asr") {
		return &qwen3ASRAdapter{provider: p}
	}
	return &audioChatASRAdapter{provider: p}
}

// qwen3ASRAdapter sends the format Qwen3-ASR was trained on: no system
// message, which the model reads as recognition context, and a user message
// carrying only audio. The reply is "language <Name><asr_text><transcript>"
// (_build_text_prompt in QwenLM/Qwen3-ASR qwen_asr/inference/qwen3_asr.py).
//
// TODO: Force the session language with the assistant prefill
// "language <Name><asr_text>", which llama-server continues, for the 30
// SUPPORTED_LANGUAGES of qwen_asr/inference/utils.py, and reject the others
// at session.setup with a checkLanguage replacing anyLanguage. Measured with
// ggml-org/Qwen3-ASR-0.6B-GGUF:Q8_0 on llama-server b12110: a forced
// language that differs from the speech yields a translation. llama.cpp's own
// transcription language hint is appended to the user text, which this model
// was not trained to read.
//
// TODO: Stream drafts.
// llama.cpp has no live path for this GGUF: it needs a streaming audio encoder
// or an "asr" chat template (server_chat_get_asr_prompt). streaming_transcribe
// in qwen_asr/inference/qwen3_asr.py instead decodes all audio every 2
// seconds and, from the third decode on, continues the previous transcript
// minus its last 5 tokens. On CPU, each step took 0.35 to 0.68 s for 2 to 11.5 s of
// audio, and an 11.5 s utterance's final transcript arrived 1.6 s after the
// end of speech.
type qwen3ASRAdapter struct {
	anyLanguage
	provider genai.Provider
}

func (a *qwen3ASRAdapter) transcribe(ctx context.Context, _ string, pcm []byte) (string, error) {
	if len(pcm) == 0 {
		return "", nil
	}
	wav, err := speechWAV(pcm, micSampleRate)
	if err != nil {
		return "", err
	}
	res, err := a.provider.GenSync(ctx, genai.Messages{{Requests: []genai.Request{{Doc: genai.Doc{Filename: "speech.wav", Src: bytes.NewReader(wav)}}}}})
	if err != nil {
		return "", err
	}
	// parse_asr_output in qwen_asr/inference/utils.py separates the language
	// metadata at the tag; "language None<asr_text>" is silence.
	raw := res.String()
	if _, text, ok := strings.Cut(raw, "<asr_text>"); ok {
		raw = text
	}
	return strings.TrimSpace(raw), nil
}

// audioChatASRAdapter instructs an audio-capable chat model to transcribe.
//
// TODO: Name the session language in the transcription instruction, the only
// mechanism a generic chat model has; today the request carries no language
// and the model detects it. Whether an instruction forces the language, or
// translates mismatched speech as Qwen3-ASR does, is not measured.
type audioChatASRAdapter struct {
	anyLanguage
	provider genai.Provider
}

func (a *audioChatASRAdapter) transcribe(ctx context.Context, _ string, pcm []byte) (string, error) {
	if len(pcm) == 0 {
		return "", nil
	}
	wav, err := speechWAV(pcm, micSampleRate)
	if err != nil {
		return "", err
	}
	msg := genai.Message{Requests: []genai.Request{
		{Text: "Transcribe the attached audio. Return only the transcript text, with no commentary."},
		{Doc: genai.Doc{Filename: "speech.wav", Src: bytes.NewReader(wav)}},
	}}
	res, err := a.provider.GenSync(ctx, genai.Messages{msg}, &genai.GenOptionText{SystemPrompt: "You are a speech recognition engine. Return only the spoken words."})
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(res.String()), nil
}

// llamaTTSAdapter synthesizes through llama-server's /v1/audio/speech. A
// non-empty lang is a fixed SpeechRequest.Lang and the only session language
// the adapter accepts; staged LFM3 sessions fix "en". model names the
// configured model in errors.
//
// TODO: Send the session language as SpeechRequest.Lang when lang is empty;
// staged LFM3 keeps its fixed English. llama-server b12110
// passes "lang" to the model's "tts" chat template. LFM3-Audio's template maps
// 20 lowercase codes, such as "fr" and "en-gb", to a language name and uses any
// other value verbatim; without one it speaks American English. Not measured.
type llamaTTSAdapter struct {
	client *llamacpp.Client
	model  string
	voice  string
	lang   string
}

func (a *llamaTTSAdapter) checkLanguage(lang string) error {
	if a.lang != "" && lang != a.lang {
		return newUnsupportedLanguageError(a.model, lang)
	}
	return nil
}

func (a *llamaTTSAdapter) synthesize(ctx context.Context, _, text string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		res, err := a.client.SpeechStreamRaw(ctx, &llamacpp.SpeechRequest{Input: text, Voice: a.voice, Lang: a.lang, ResponseFormat: llamacpp.SpeechPCM, Stream: true}) //nolint:bodyclose // Iteration owns and closes the native body below.
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

// geminiTTSAdapter synthesizes through a Gemini speech model.
//
// TODO: Send the session language as InteractionSpeechConfig.Language in
// GenerationConfig.SpeechConfig, and declare the model's languages with a
// checkLanguage replacing anyLanguage; today the request sets only Voice, and
// only when one is configured. Not measured.
type geminiTTSAdapter struct {
	anyLanguage
	client *gemini.Client
	voice  string
}

func (a *geminiTTSAdapter) synthesize(ctx context.Context, _, text string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		req := &gemini.InteractionRequest{Model: a.client.ModelID(), Store: new(false), Input: []gemini.InteractionContent{{Type: gemini.InteractionText, Text: text}}, ResponseFormat: &gemini.InteractionResponseFormat{Type: gemini.InteractionAudio, MIMEType: "audio/l16", SampleRate: 24000}}
		if a.voice != "" {
			req.GenerationConfig = &gemini.InteractionGenerationConfig{SpeechConfig: []gemini.InteractionSpeechConfig{{Voice: a.voice}}}
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
