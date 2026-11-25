// Utterance-owned live transcription with bounded microphone queues and ephemeral drafts.

package voicertc

import (
	"context"
	"errors"
	"strconv"
	"sync/atomic"
	"time"

	voicev2 "github.com/gomodedev/gomode/voicegateway/api/v2"
)

// At most four recognizers may be connecting, receiving audio or awaiting
// their final transcript. An utterance whose transcript waits only for an
// earlier utterance's commit holds no recognizer and does not count, so a slow
// recognition cannot exhaust the bound with finished successors. The manual
// gate ends an utterance after 200ms of quiet, so pauses within one sentence
// start successive recognizers. realtimeAudioGate normalizes every queued chunk
// to 20ms except the first, bounded to 200ms of preroll. Thus 512 queued chunks
// retain at most 10.42s of mono PCM16 at the recognizer's rate per utterance,
// 1000320 bytes at 48 kHz, including while a managed llama.cpp server holds
// every slot for other utterances.
const liveASRQueueFrames = 512
const liveASRMaxRecognizing = 4

type liveASRAudio struct {
	pcm []byte
	end bool
}

type liveUtterance struct {
	id       string
	ctx      context.Context //nolint:containedctx // Recognition identity and cancellation belong to this utterance.
	cancel   context.CancelFunc
	started  time.Time
	audio    chan liveASRAudio
	previous <-chan struct{}
	done     chan struct{}
	// connected is set once the recognizer accepted the session. A queue that
	// fills before then waited on the recognizer, not on recognition speed.
	connected atomic.Bool
}

func (s *localStackSession) acceptLiveMicPCM(pcm []byte) error {
	s.micMu.Lock()
	defer s.micMu.Unlock()
	if s.liveCapture != nil && s.liveCapture.ctx.Err() != nil {
		s.liveCapture = nil
		s.liveGate.finish()
	}
	for chunk := range s.liveGate.push(pcm) {
		if chunk.start {
			if s.clientOutput || s.isSpeaking() {
				s.bargeIn(voicev2.InterruptSourceUser, "")
			}
			s.liveCapture = s.startLiveUtterance()
		}
		u := s.liveCapture
		if u == nil {
			continue
		}
		select {
		case u.audio <- liveASRAudio{pcm: append([]byte(nil), chunk.audio...), end: chunk.end}:
			if chunk.end {
				s.liveCapture = nil
			}
		default:
			u.cancel()
			s.clearLiveDraft(u.id)
			connected := u.connected.Load()
			s.log.WarnContext(s.baseCtx, "voicertc: live ASR queue full", "session", s.id, "utterance", u.id, "connected", connected, "age", time.Since(u.started))
			err := errors.New("speech recognition fell behind; please repeat")
			if !connected {
				err = errors.New("no speech recognizer became available; please repeat")
			}
			s.reportTurnError("asr", err)
			s.liveCapture = nil
			s.liveGate.finish()
			return nil
		}
	}
	return nil
}

func (s *localStackSession) startLiveUtterance() *liveUtterance {
	s.events.Lock()
	defer s.events.Unlock()
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.conv == nil {
		return nil
	}
	if s.liveRecognizing >= liveASRMaxRecognizing {
		s.log.WarnContext(s.baseCtx, "voicertc: live ASR recognizers exhausted", "session", s.id, "recognizing", s.liveRecognizing, "pending", len(s.livePending))
		s.reportTurnError("asr", errors.New("speech recognition is busy with earlier utterances; please repeat"))
		return nil
	}
	if s.turnCancel != nil {
		s.turnCancel()
		s.turnCancel = nil
	}
	s.turnGeneration++
	generation := s.turnGeneration
	s.liveSequence++
	ctx, cancel := context.WithTimeout(s.baseCtx, time.Minute)
	u := &liveUtterance{id: s.id + "-asr-" + strconv.FormatUint(s.liveSequence, 10), ctx: ctx, cancel: cancel, started: time.Now(), audio: make(chan liveASRAudio, liveASRQueueFrames), previous: s.transcriptionTail, done: make(chan struct{})}
	s.transcriptionTail = u.done
	s.livePending[u.id] = u
	s.liveRecognizing++
	if s.liveDraftID != "" {
		s.emit(&voicev2.TranscriptDraft{Kind: voicev2.MessageKindTranscriptDraft, ID: s.liveDraftID})
	}
	s.liveDraftID = u.id
	s.emit(&voicev2.UserSpeechStarted{Kind: voicev2.MessageKindUserSpeechStarted})
	s.emit(&voicev2.TranscriptDraft{Kind: voicev2.MessageKindTranscriptDraft, ID: u.id})
	s.emit(&voicev2.TurnStatus{Kind: voicev2.MessageKindTurnStatus, State: voicev2.TurnStateIdle})
	lang := s.language
	s.turns.Go(func() error {
		defer s.finishTurn(generation)
		defer s.clearLiveDraft(u.id)
		defer func() {
			if errors.Is(u.ctx.Err(), context.DeadlineExceeded) && s.baseCtx.Err() == nil {
				s.reportTurnError("asr", errors.New("speech recognition timed out; please repeat"))
			}
			cancel()
			// Retirement carries the predecessor barrier even when this input
			// failed or was canceled. A successor cannot overtake an older turn.
			if u.previous != nil {
				select {
				case <-u.previous:
				case <-s.baseCtx.Done():
				}
			}
			close(u.done)
			s.mu.Lock()
			delete(s.livePending, u.id)
			s.mu.Unlock()
		}()
		text, err := s.liveASR.transcribe(s, u, lang)
		s.mu.Lock()
		s.liveRecognizing--
		s.mu.Unlock()
		if err != nil {
			s.clearLiveDraft(u.id)
			s.warnTurn(u.ctx, "asr", err)
			return nil
		}
		if u.previous != nil {
			select {
			case <-u.previous:
			case <-u.ctx.Done():
				return nil
			}
		}
		s.commitLiveUtterance(u, text)
		return nil
	})
	return u
}

func (s *localStackSession) publishLiveDraft(u *liveUtterance, text string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.closed && u.ctx.Err() == nil && s.liveDraftID == u.id {
		s.emit(&voicev2.TranscriptDraft{Kind: voicev2.MessageKindTranscriptDraft, ID: u.id, Text: text})
	}
}

func (s *localStackSession) sendLiveAudio(ctx context.Context, u *liveUtterance, send func(context.Context, []byte) error, finish func(context.Context) error) error {
	for {
		select {
		case chunk := <-u.audio:
			if len(chunk.pcm) == 0 || len(chunk.pcm)%2 != 0 || len(chunk.pcm) > s.liveASR.rate*2 {
				return errors.New("live speech requires 1..1000ms of aligned PCM16")
			}
			if err := send(ctx, chunk.pcm); err != nil {
				return err
			}
			if chunk.end {
				s.mu.Lock()
				if s.liveDraftID == u.id && u.ctx.Err() == nil {
					s.emit(&voicev2.TurnStatus{Kind: voicev2.MessageKindTurnStatus, State: voicev2.TurnStateTranscribing})
				}
				s.mu.Unlock()
				return finish(ctx)
			}
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}

func (s *localStackSession) clearLiveDraft(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.closed && s.liveDraftID == id {
		s.liveDraftID = ""
		s.emit(&voicev2.TranscriptDraft{Kind: voicev2.MessageKindTranscriptDraft, ID: id})
	}
}

func (s *localStackSession) cancelLiveTranscriptions() {
	s.events.Lock()
	defer s.events.Unlock()
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, u := range s.livePending {
		u.cancel()
	}
	if s.liveDraftID != "" {
		id := s.liveDraftID
		s.liveDraftID = ""
		s.emit(&voicev2.TranscriptDraft{Kind: voicev2.MessageKindTranscriptDraft, ID: id})
	}
}

func (s *localStackSession) commitLiveUtterance(u *liveUtterance, text string) {
	s.events.Lock()
	defer s.events.Unlock()
	s.mu.Lock()
	if s.closed || u.ctx.Err() != nil {
		s.mu.Unlock()
		return
	}
	if s.liveDraftID == u.id {
		s.liveDraftID = ""
	}
	newer := s.liveDraftID != ""
	conv := s.conv
	s.emit(&voicev2.TranscriptDelta{Kind: voicev2.MessageKindTranscriptDelta, Speaker: voicev2.SpeakerUser, Text: text, DraftID: u.id})
	s.mu.Unlock()
	if text == "" {
		return
	}
	ctx, cancel := context.WithCancel(s.baseCtx)
	step, err := conv.user(ctx, text)
	if err != nil {
		cancel()
		s.reportTurnError("llm", err)
		return
	}
	if newer {
		cancel()
	}
	s.startAdmittedStep(ctx, conv, step, cancel, !newer)
}
