// Tests microphone-owned live hypotheses and chronological committed turns.

package voicertc

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"testing/synctest"
	"time"

	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
)

func testLocalStackSessionLiveTranscription(t *testing.T) {
	t.Parallel()
	t.Run("partial precedes end and delayed final retains newer draft", func(t *testing.T) {
		t.Parallel()
		a, b := newFakeTranscription(), newFakeTranscription()
		sessions := make(chan *fakeTranscription, 2)
		sessions <- a
		sessions <- b
		sink := &captureSink{changed: make(chan struct{}, 1)}
		conv := &fakeConversation{}
		runtime := &localStackRuntime{liveASR: newFakeLiveASR(sessions)}
		s := runtime.newSession(t.Context(), "live", sink, true)
		s.conv = conv
		t.Cleanup(func() {
			if err := s.close(); err != nil {
				t.Error(err)
			}
		})
		if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
			t.Fatal(err)
		}
		a.interim("First hypothesis")
		waitLiveDraft(t, sink, "First hypothesis")
		if conv.userCalls() != 0 {
			t.Fatal("partial reached LLM")
		}
		select {
		case <-a.committed:
			t.Fatal("input ended before partial")
		default:
		}
		if err := s.acceptMicPCM(t.Context(), silencePCM(300)); err != nil {
			t.Fatal(err)
		}
		select {
		case <-a.committed:
		case <-time.After(3 * time.Second):
			t.Fatal("missing finish")
		}
		if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
			t.Fatal(err)
		}
		b.interim("Second hypothesis")
		waitLiveDraft(t, sink, "Second hypothesis")
		a.final("First committed.", true)
		waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, "", 1)
		s.mu.Lock()
		draftID := s.liveDraftID
		s.mu.Unlock()
		if draftID != "live-asr-2" {
			t.Fatalf("older final cleared new draft: %q", draftID)
		}
		if err := s.acceptMicPCM(t.Context(), silencePCM(300)); err != nil {
			t.Fatal(err)
		}
		select {
		case <-b.committed:
		case <-time.After(3 * time.Second):
			t.Fatal("missing second finish")
		}
		b.final("Second committed.", true)
		waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDelta, "", 2)
		sink.mu.Lock()
		var texts []string
		for _, raw := range sink.msgs {
			var msg voicev2.TranscriptDelta
			if err := json.Unmarshal(raw, &msg); err != nil {
				t.Error(err)
			}
			if msg.Kind == voicev2.MessageKindTranscriptDelta {
				texts = append(texts, msg.Text)
			}
		}
		sink.mu.Unlock()
		if len(texts) != 2 || texts[0] != "First committed." || texts[1] != "Second committed." {
			t.Fatal(texts)
		}
	})
	t.Run("cancel clears draft without committing", func(t *testing.T) {
		t.Parallel()
		a := newFakeTranscription()
		sessions := make(chan *fakeTranscription, 1)
		sessions <- a
		sink := &captureSink{changed: make(chan struct{}, 1)}
		conv := &fakeConversation{}
		s := (&localStackRuntime{liveASR: newFakeLiveASR(sessions)}).newSession(t.Context(), "cancel", sink, true)
		s.conv = conv
		t.Cleanup(func() {
			if err := s.close(); err != nil {
				t.Error(err)
			}
		})
		if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
			t.Fatal(err)
		}
		a.interim("Do not commit")
		waitLiveDraft(t, sink, "Do not commit")
		s.cancelLiveTranscriptions()
		a.final("Late final", true)
		<-a.closed
		if conv.userCalls() != 0 {
			t.Fatal("canceled transcript reached LLM")
		}
		sink.mu.Lock()
		for _, raw := range sink.msgs {
			var msg voicev2.TranscriptDelta
			if err := json.Unmarshal(raw, &msg); err != nil {
				t.Error(err)
			}
			if msg.Kind == voicev2.MessageKindTranscriptDelta || msg.Kind == voicev2.MessageKindError {
				t.Errorf("canceled input published %s", raw)
			}
		}
		sink.mu.Unlock()
	})
	t.Run("retirement preserves predecessor barrier", func(t *testing.T) {
		t.Parallel()
		for name, retire := range map[string]func(*liveUtterance, *fakeTranscription){
			"provider failure": func(_ *liveUtterance, s *fakeTranscription) { s.failure <- errors.New("recognizer failed") },
			"canceled input":   func(u *liveUtterance, _ *fakeTranscription) { u.cancel() },
		} {
			t.Run(name, func(t *testing.T) {
				t.Parallel()
				synctest.Test(t, func(t *testing.T) {
					a, b, c := newFakeTranscription(), newFakeTranscription(), newFakeTranscription()
					sessions := make(chan *fakeTranscription, 3)
					sessions <- a
					sessions <- b
					sessions <- c
					sink := &captureSink{changed: make(chan struct{}, 1)}
					s := (&localStackRuntime{liveASR: newFakeLiveASR(sessions)}).newSession(t.Context(), "ordered", sink, true)
					s.conv = &fakeConversation{}
					t.Cleanup(func() {
						if err := s.close(); err != nil {
							t.Error(err)
						}
					})
					ua := s.startLiveUtterance()
					ua.audio <- liveASRAudio{pcm: []byte{0, 0}, end: true}
					<-a.committed
					ub := s.startLiveUtterance()
					ub.audio <- liveASRAudio{pcm: []byte{0, 0}, end: true}
					<-b.committed
					uc := s.startLiveUtterance()
					uc.audio <- liveASRAudio{pcm: []byte{0, 0}, end: true}
					<-c.committed
					retire(ub, b)
					<-b.closed
					c.final("Third.", true)
					synctest.Wait()
					select {
					case <-ub.done:
						t.Fatal("failed middle turn retired ahead of predecessor")
					default:
					}
					sink.mu.Lock()
					for _, raw := range sink.msgs {
						var msg voicev2.TranscriptDelta
						if err := json.Unmarshal(raw, &msg); err != nil {
							t.Error(err)
						}
						if msg.Kind == voicev2.MessageKindTranscriptDelta {
							t.Error("successor committed ahead of first utterance")
						}
					}
					sink.mu.Unlock()
					a.final("First.", true)
					<-uc.done
					sink.mu.Lock()
					var texts []string
					for _, raw := range sink.msgs {
						var msg voicev2.TranscriptDelta
						if err := json.Unmarshal(raw, &msg); err != nil {
							t.Error(err)
						}
						if msg.Kind == voicev2.MessageKindTranscriptDelta {
							texts = append(texts, msg.Text)
						}
					}
					sink.mu.Unlock()
					if len(texts) != 2 || texts[0] != "First." || texts[1] != "Third." {
						t.Fatal(texts)
					}
				})
			})
		}
	})
	t.Run("deadline reports recoverable repeat request", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			a := newFakeTranscription()
			sessions := make(chan *fakeTranscription, 1)
			sessions <- a
			sink := &captureSink{changed: make(chan struct{}, 1)}
			conv := &fakeConversation{}
			s := (&localStackRuntime{liveASR: newFakeLiveASR(sessions)}).newSession(t.Context(), "timeout", sink, true)
			s.conv = conv
			t.Cleanup(func() {
				if err := s.close(); err != nil {
					t.Error(err)
				}
			})
			if err := s.acceptMicPCM(t.Context(), loudPCM(200)); err != nil {
				t.Fatal(err)
			}
			a.interim("Unfinished hypothesis")
			waitLiveDraft(t, sink, "Unfinished hypothesis")
			time.Sleep(time.Minute)
			synctest.Wait()
			waitLiveMessage(t, sink, voicev2.MessageKindError, "", 1)
			sink.mu.Lock()
			var report voicev2.Error
			for _, raw := range sink.msgs {
				var msg voicev2.Error
				if err := json.Unmarshal(raw, &msg); err != nil {
					t.Error(err)
				}
				if msg.Kind == voicev2.MessageKindError {
					report = msg
				}
			}
			sink.mu.Unlock()
			if !report.Recoverable || report.Message != "asr: speech recognition timed out; please repeat" {
				t.Fatalf("%+v", report)
			}
			s.mu.Lock()
			draft := s.liveDraftID
			s.mu.Unlock()
			if draft != "" || conv.userCalls() != 0 {
				t.Fatal("deadline retained draft or committed input")
			}
		})
	})

	t.Run("stalled connection never blocks microphone", func(t *testing.T) {
		t.Parallel()
		synctest.Test(t, func(t *testing.T) {
			sessions := make(chan *fakeTranscription)
			sink := &captureSink{changed: make(chan struct{}, 1)}
			s := (&localStackRuntime{liveASR: newFakeLiveASR(sessions)}).newSession(t.Context(), "bounded", sink, true)
			s.conv = &fakeConversation{}
			t.Cleanup(func() {
				if err := s.close(); err != nil {
					t.Error(err)
				}
			})
			start := time.Now()
			for range liveASRQueueFrames + 20 {
				if err := s.acceptMicPCM(t.Context(), loudPCM(20)); err != nil {
					t.Fatal(err)
				}
			}
			if time.Since(start) > time.Second {
				t.Fatal("RTP capture blocked on network")
			}
			waitLiveMessage(t, sink, voicev2.MessageKindError, "", 1)
		})
	})
}

func waitLiveDraft(t *testing.T, sink *captureSink, text string) {
	waitLiveMessage(t, sink, voicev2.MessageKindTranscriptDraft, text, 1)
}

func waitLiveMessage(t *testing.T, sink *captureSink, kind voicev2.MessageKind, text string, count int) {
	ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
	t.Cleanup(cancel)
	for {
		sink.mu.Lock()
		found := 0
		for _, raw := range sink.msgs {
			var msg struct {
				Kind voicev2.MessageKind
				Text string
			}
			if err := json.Unmarshal(raw, &msg); err != nil {
				sink.mu.Unlock()
				t.Fatal(err)
			}
			if msg.Kind == kind && (text == "" || msg.Text == text) {
				found++
			}
		}
		sink.mu.Unlock()
		if found >= count {
			return
		}
		select {
		case <-sink.changed:
		case <-ctx.Done():
			t.Fatalf("missing %s %q count%d", kind, text, count)
		}
	}
}
