// Native llama.cpp and Gemini live transcription policy for gateway-owned utterances.

package voicertc

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync/atomic"

	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genai/providers/llamacpp"
	"golang.org/x/sync/errgroup"
)

// llamaLiveASR streams through llama-server's realtime transcription session.
//
// TODO: The session language reaches no model. ConnectTranscriptionRaw opens
// /v1/realtime?intent=transcription. On llama-server b12110, any model with a
// streaming audio encoder or an "asr" chat template, including Voxtral
// Realtime and LFM3-Audio, gets a fixed session in tools/server/server-context.cpp
// that answers session.update with its unchanged configuration. The prompt is the
// "asr" template rendered once at load; LFM3-Audio's is "Perform live ASR."
// The language-aware prompt in tools/server/server-realtime.cpp
// (common_chat_get_asr_prompt) serves only conversation sessions. Measured on
// 4 s English and French requests with the update below sent or omitted:
// session.updated echoes an empty language every time; Voxtral-Mini-4B-Realtime-2602
// Q4_K_M transcribes both languages correctly; LFM3-Audio-2B Q4_K_M translates
// French speech into English. By contrast, LFM3-Audio on the batch
// /v1/audio/transcriptions endpoint detects French without a language, keeps
// it with "fr", and translates it into English with "en".
type llamaLiveASR struct{ client *llamacpp.Client }

func (a *llamaLiveASR) transcribe(s *localStackSession, u *liveUtterance) (string, error) {
	conn, err := a.client.ConnectTranscriptionRaw(u.ctx)
	if err != nil {
		return "", err
	}
	format := conn.Initial.Session.Audio.Input.Format
	if format.Type != llamacpp.AudioPCM || format.Rate != micSampleRate {
		return "", errors.Join(fmt.Errorf("live ASR input format %s/%d is unsupported; capture is PCM16/%d Hz", format.Type, format.Rate, micSampleRate), conn.Close())
	}
	// The update echoes the negotiated session and gives tools an explicit
	// empty list because the server rejects a null one.
	s.mu.Lock()
	lang := s.language
	s.mu.Unlock()
	if lang != "" {
		cfg := *conn.Initial.Session
		cfg.Tools = llamacpp.ToolList{}
		cfg.Audio.Input.Transcription = &llamacpp.Transcription{Model: cfg.Model, Language: lang}
		if err := conn.Send(u.ctx, &llamacpp.ClientMessage{Type: llamacpp.EventSessionUpdate, Session: &cfg}); err != nil {
			return "", errors.Join(err, conn.Close())
		}
	}
	ctx, cancel := context.WithCancel(u.ctx)
	workers, workerCtx := errgroup.WithContext(ctx)
	var finishing atomic.Bool
	workers.Go(func() error {
		return s.sendLiveAudio(workerCtx, u, func(ctx context.Context, pcm []byte) error {
			return conn.Send(ctx, &llamacpp.ClientMessage{Type: llamacpp.EventAudioAppend, Audio: pcm})
		}, func(ctx context.Context) error {
			finishing.Store(true)
			return conn.Send(ctx, &llamacpp.ClientMessage{Type: llamacpp.EventAudioCommit, Final: true})
		})
	})
	text, item := "", ""
	final := ""
	for {
		var msg llamacpp.ServerMessage
		msg, err = conn.Receive(workerCtx)
		if err != nil {
			if errors.Is(err, io.EOF) {
				err = io.ErrUnexpectedEOF
			}
			break
		}
		if msg.Type == llamacpp.EventError || msg.Type == llamacpp.EventInputTranscriptFailed {
			err = errors.New("llama.cpp live transcription rejected by server")
			break
		}
		if msg.Type != llamacpp.EventInputTranscriptDelta && msg.Type != llamacpp.EventInputTranscriptDone {
			continue
		}
		if msg.ItemID == "" || item != "" && item != msg.ItemID {
			err = errors.New("llama.cpp transcription changed utterance identity")
			break
		}
		item = msg.ItemID
		if msg.Type == llamacpp.EventInputTranscriptDone {
			if !finishing.Load() {
				err = errors.New("llama.cpp transcription completed before input ended")
				break
			}
			if len(msg.Transcript) > 64<<10 {
				err = errors.New("transcription text exceeds 64 KiB")
				break
			}
			final = msg.Transcript
			break
		}
		if len(text)+len(msg.Delta) > 64<<10 {
			err = errors.New("transcription text exceeds 64 KiB")
			break
		}
		text += msg.Delta
		s.publishLiveDraft(u, text)
	}
	if err != nil {
		cancel()
	}
	sendErr := workers.Wait()
	cancel()
	return final, errors.Join(err, sendErr, conn.Close())
}

// geminiLiveASR streams through a Gemini Live transcription model, which
// detects the language without hints. Measured with gemini-3.5-transcribe-live
// on 4 s English and French requests: detection is correct, and a mismatched
// LiveTranscriptionConfig.LanguageCodes hint does not change the transcript.
//
// TODO: Replace with the genai transcription-session adapter, which sends the
// session language as genai.RealtimeOptionAudio.InputLanguage.
type geminiLiveASR struct{ client *gemini.Client }

func (a *geminiLiveASR) transcribe(s *localStackSession, u *liveUtterance) (string, error) {
	setup := &gemini.LiveSetup{Model: "models/" + strings.TrimPrefix(a.client.ModelID(), "models/"), GenerationConfig: gemini.LiveGenerationConfig{ResponseModalities: []gemini.Modality{gemini.ModalityText}}, InputAudioTranscription: &gemini.LiveTranscriptionConfig{}, RealtimeInputConfig: gemini.LiveRealtimeInputConfig{AutomaticActivityDetection: &gemini.LiveAutomaticActivityDetection{Disabled: true}, ActivityHandling: gemini.LiveActivityNoInterruption, TurnCoverage: gemini.LiveTurnOnlyActivity}}
	conn, err := a.client.ConnectLiveRaw(u.ctx, setup)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithCancel(u.ctx)
	workers, workerCtx := errgroup.WithContext(ctx)
	var finishing atomic.Bool
	workers.Go(func() error {
		started := false
		return s.sendLiveAudio(workerCtx, u, func(ctx context.Context, pcm []byte) error {
			if !started {
				if err := conn.Send(ctx, &gemini.LiveClientMessage{RealtimeInput: &gemini.LiveRealtimeInput{ActivityStart: &gemini.LiveActivity{}}}); err != nil {
					return err
				}
				started = true
			}
			return conn.Send(ctx, &gemini.LiveClientMessage{RealtimeInput: &gemini.LiveRealtimeInput{Audio: &gemini.LiveBlob{MIMEType: "audio/pcm;rate=16000", Data: pcm}}})
		}, func(ctx context.Context) error {
			finishing.Store(true)
			return conn.Send(ctx, &gemini.LiveClientMessage{RealtimeInput: &gemini.LiveRealtimeInput{ActivityEnd: &gemini.LiveActivity{}}})
		})
	})
	committed, partial := "", ""
	final := ""
	for {
		var msg gemini.LiveServerMessage
		msg, err = conn.Receive(workerCtx)
		if err != nil {
			if errors.Is(err, io.EOF) {
				err = io.ErrUnexpectedEOF
			}
			break
		}
		if msg.Error != nil || msg.GoAway != nil {
			err = errors.New("gemini live transcription terminated")
			break
		}
		content := msg.ServerContent
		if content == nil {
			continue
		}
		changed := false
		if t := content.InterimInputTranscription; t != nil {
			partial = t.Text
			changed = true
		}
		if t := content.InputTranscription; t != nil {
			if len(committed)+len(t.Text) > 64<<10 {
				err = errors.New("transcription text exceeds 64 KiB")
				break
			}
			committed += t.Text
			partial = ""
			changed = true
		}
		if len(committed)+len(partial) > 64<<10 {
			err = errors.New("transcription text exceeds 64 KiB")
			break
		}
		if content.GenerationComplete || content.TurnComplete {
			if !finishing.Load() {
				err = errors.New("gemini transcription completed before input ended")
				break
			}
			final = committed
			break
		}
		if changed {
			s.publishLiveDraft(u, committed+partial)
		}
	}
	if err != nil {
		cancel()
	}
	sendErr := workers.Wait()
	cancel()
	return final, errors.Join(err, sendErr, conn.Close())
}
