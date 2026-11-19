// Live transcription of gateway-owned utterances through genai transcription sessions.

package voicertc

import (
	"context"
	"errors"
	"fmt"
	"io"
	"slices"
	"strings"
	"sync/atomic"

	"github.com/maruel/genai"
	"github.com/maruel/genai/base"
	"github.com/maruel/genai/scoreboard"
	"github.com/maruel/genaipy/providers/nemospeech"
	"github.com/maruel/genaipy/providers/vosk"
	"github.com/maruel/genaipy/providers/whistle"
	"golang.org/x/sync/errgroup"

	"github.com/gomodedev/gomode/voicegateway"
)

// liveASR recognizes each utterance in its own genai transcription session,
// opened with genai.RealtimeOptionTranscription at rate. Input transcript
// deltas and interim hypotheses become drafts while the user speaks; the final
// transcript arrives at TurnDone. Both are trimmed. A session that yields no
// drafts, such as Whistle's, publishes only the final transcript.
//
// Measured on 2026-11-15 with TestSmokeLiveASR: English and French sentences
// from gemini-3.8-flash-lite-tts, paced at real time, in English and French
// sessions. Each streamed 6 to 13 drafts before its final transcript.
//
//   - gemini-3.5-transcribe-live receives the session language as a
//     languageCodes hint, which does not change the words: each sentence is
//     transcribed exactly in its spoken language in either session.
//   - ggml-org/Voxtral-Mini-4B-Realtime-2602-GGUF:Q4_K_M on managed
//     llama-server v1.0.0 (b12359) receives no language and transcribes each
//     sentence exactly in its spoken language in either session.
//   - LiquidAI/LFM3-Audio-2B-GGUF:Q4_K_M on the same server receives no
//     language. It transcribes English exactly and translates French speech
//     into English ("I would like to reserve a table for two this evening.").
//   - gpt-live-transcribe, measured on 2026-11-16 at its 24 kHz rate, receives
//     the session language and transcribes each sentence exactly in its
//     spoken language in either session, after 10 or 11 drafts.
//
// Immutable: one instance serves every session of its runtime.
type liveASR struct {
	provider genai.Provider
	// model names the configured model in errors and selects its language
	// declaration. A managed llama.cpp provider reports no ModelID.
	model string
	// languages are the ISO 639 base languages the model accepts at
	// session.setup; nil accepts any. With inputLanguage, a provider without
	// a declaration rejects an unsupported language at connect. Without it, as
	// on llama.cpp, the model detects the language and nothing checks it, so a
	// llama.cpp live model is checked only through its liveASRLanguages row.
	languages []string
	// inputLanguage sends the session language as
	// genai.RealtimeOptionAudio.InputLanguage.
	inputLanguage bool
	// rate is the mono PCM16LE input rate of the transcription session. The
	// microphone is decoded at rate, so no stage resamples.
	rate int
}

// newLiveASR transcribes through p's transcription sessions with model.
//
// The provider's scoreboard decides whether the session language is sent.
// genai's transcription qualification records a provider that rejects
// RealtimeOptionAudio.InputLanguage with base.ErrNotSupported as
// RealtimeTranscription.InputLanguage false. Providers decide this in their
// client, not per model, so one declining row stands for every model of the
// provider: genai's llama.cpp client rejects any language for both session
// types, and its Voxtral Realtime row records it. A connect rejection cannot
// decide instead, since Whistle and the OpenAI and Mistral clients use the same
// rejection for one unsupported language. A provider without a qualified
// transcription session, such as Whistle, receives the language.
//
// The rate comes from voicegateway.TranscriptionSampleRate. A provider without
// a qualified transcription session, such as Whistle or NeMo-Speech, receives
// micSampleRate, which both accept.
func newLiveASR(p genai.Provider, model string) *liveASR {
	score := p.Scoreboard()
	declined := slices.ContainsFunc(score.Realtime, func(r scoreboard.Realtime) bool {
		f := r.Realtime
		return f != nil && f.InputSampleRate != 0 && f.Transcription != nil && !f.Transcription.InputLanguage
	})
	rate := voicegateway.TranscriptionSampleRate(score, model)
	if rate == 0 {
		rate = micSampleRate
	}
	return &liveASR{provider: p, model: model, languages: liveASRLanguages(p, model), inputLanguage: !declined, rate: rate}
}

// liveASRLanguages declares the session languages of model on p. Whistle,
// NeMo-Speech and Vosk export theirs. llama.cpp models, which receive none, are declared
// by Hugging Face repository. nil accepts any language: an unlisted llama.cpp
// model is treated as detecting every language, and other providers reject an
// unsupported one at connect.
//
// Voxtral Realtime detects the 13 languages its model card lists
// (https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602).
// LFM3-Audio translates non-English speech into English on this path, as
// measured on liveASR, so it supports English only.
func liveASRLanguages(p genai.Provider, model string) []string {
	switch c := p.(type) {
	case *whistle.Client:
		return whistle.Languages
	case *nemospeech.Client:
		return nemospeech.Model(c.ModelID()).Languages()
	case *vosk.Client:
		return vosk.Model(c.ModelID()).Languages()
	}
	switch modelRepository(model) {
	case "ggml-org/Voxtral-Mini-4B-Realtime-2602-GGUF":
		return []string{"ar", "de", "en", "es", "fr", "hi", "it", "ja", "ko", "nl", "pt", "ru", "zh"}
	case "LiquidAI/LFM3-Audio-2B-GGUF":
		return []string{"en"}
	default:
		return nil
	}
}

func (a *liveASR) checkLanguage(lang string) error {
	if a.languages != nil && !slices.Contains(a.languages, lang) {
		return newUnsupportedLanguageError(a.model, lang)
	}
	return nil
}

// transcribe recognizes u and returns its final transcript. lang is the
// session's ISO 639 base language; see localStackSession.language.
func (a *liveASR) transcribe(s *localStackSession, u *liveUtterance, lang string) (string, error) {
	audio := &genai.RealtimeOptionAudio{InputFormat: genai.AudioFormat{SampleRate: a.rate}}
	if a.inputLanguage {
		audio.InputLanguage = lang
	}
	rt, err := a.provider.ConnectRealtime(u.ctx, genai.RealtimeOptionTranscription{}, audio)
	if err != nil {
		// genai providers name a rejected input language as an
		// "input language" option of base.ErrNotSupported.
		if e, ok := errors.AsType[*base.ErrNotSupported](err); ok && audio.InputLanguage != "" && slices.ContainsFunc(e.Options, func(o string) bool { return strings.HasPrefix(o, "input language") }) {
			return "", newUnsupportedLanguageError(a.model, lang)
		}
		return "", err
	}
	ctx, cancel := context.WithCancel(u.ctx)
	defer cancel()
	workers, workerCtx := errgroup.WithContext(ctx)
	var committed atomic.Bool
	workers.Go(func() error {
		return s.sendLiveAudio(workerCtx, u, func(ctx context.Context, pcm []byte) error {
			return rt.Send(ctx, genai.RealtimeInputContent{Audio: pcm})
		}, func(ctx context.Context) error {
			committed.Store(true)
			return rt.Send(ctx, genai.RealtimeInputContent{Commit: true})
		})
	})
	text, err := a.receive(workerCtx, s, u, rt, &committed)
	if err != nil {
		cancel()
	}
	return text, errors.Join(err, workers.Wait(), rt.Close())
}

// receive publishes drafts until TurnDone and returns the final transcript.
// A final transcript replaces the deltas since the previous one, so done
// accumulates finished segments, segment the deltas after them, and interim
// the provider's revisable hypothesis for speech not yet in a delta.
func (a *liveASR) receive(ctx context.Context, s *localStackSession, u *liveUtterance, rt genai.RealtimeSession, committed *atomic.Bool) (string, error) {
	done, segment, interim := "", "", ""
	for e, err := range rt.Receive(ctx) {
		if err != nil {
			if errors.Is(err, io.EOF) {
				err = io.ErrUnexpectedEOF
			}
			return "", err
		}
		draft := false
		switch e.Kind {
		case genai.RealtimeEventTranscriptInput:
			if e.Content.Final {
				done += e.Content.Text
				segment = ""
				// A Final may revise its segment. While input continues, show
				// it; after Commit, TurnDone delivers it.
				draft = !committed.Load()
			} else {
				segment += e.Content.Text
				draft = true
			}
			interim = ""
		case genai.RealtimeEventTranscriptInputInterim:
			interim = e.Content.Text
			draft = true
		case genai.RealtimeEventError:
			return "", fmt.Errorf("%s transcription: %s", a.provider.Name(), e.Error.Message)
		case genai.RealtimeEventTurnDone:
			if !committed.Load() {
				return "", fmt.Errorf("%s transcription completed before input ended", a.provider.Name())
			}
			return strings.TrimSpace(done + segment), nil
		default:
			continue
		}
		if len(done)+len(segment)+len(interim) > 64<<10 {
			return "", errors.New("transcription text exceeds 64 KiB")
		}
		if draft {
			s.publishLiveDraft(u, strings.TrimSpace(done+segment+interim))
		}
	}
	return "", io.ErrUnexpectedEOF
}
