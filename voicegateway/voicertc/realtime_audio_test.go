// Tests bounded incremental audio onset, preroll and tail detection.

package voicertc

import (
	"bytes"
	"encoding/binary"
	"testing"
)

const realtimeFrameBytes = defaultInputSampleRate * 2 / 50

func TestRealtimeAudioGate(t *testing.T) {
	t.Run("silenceTurn", func(t *testing.T) {
		t.Run("manual turn ends inside a packet", func(t *testing.T) {
			g := newRealtimeAudioGate(defaultInputSampleRate, (&localStackSession{}).microphoneAudioMode())
			pcm := testPCM(240, 2000)
			if g.silenceTurn(testPCM(1500, 0)) || g.silenceTurn(pcm[:17]) || g.silenceTurn(pcm[17:]) {
				t.Fatal("mute ended before the first utterance finished")
			}
			if !bytes.Equal(pcm, testPCM(240, 0)) {
				t.Fatal("ignored speech reached the backend")
			}
			if g.silenceTurn(testPCM(180, 0)) {
				t.Fatal("mute ended within the quiet tail")
			}
			next := append(testPCM(20, 0), testPCM(40, 1500)...)
			if !g.silenceTurn(next) || !bytes.Equal(next, append(testPCM(20, 0), testPCM(40, 1500)...)) {
				t.Fatal("mute did not release at the boundary before the next utterance")
			}
		})
		t.Run("realtime provider quiet speech and mid-turn pause", func(t *testing.T) {
			g := newRealtimeAudioGate(defaultInputSampleRate, (&realtimeBridgeSession{}).microphoneAudioMode())
			pcm := append(testPCM(60, 200), testPCM(400, 0)...)
			pcm = append(pcm, testPCM(60, 200)...)
			if g.silenceTurn(pcm) || !bytes.Equal(pcm, testPCM(520, 0)) {
				t.Fatal("mute released during a pause in a quiet utterance")
			}
			if !g.silenceTurn(testPCM(1000, 0)) {
				t.Fatal("quiet utterance did not consume the pending mute")
			}
		})
	})
	t.Run("activity policies", func(t *testing.T) {
		sparse := testPCM(100, 0)
		for i := 0; i < len(sparse); i += 40 {
			binary.LittleEndian.PutUint16(sparse[i:], 1000)
		}
		for _, tc := range []struct {
			name   string
			mode   realtimeAudioMode
			pcm    []byte
			active bool
		}{
			{"manual below threshold", realtimeAudioManual, testPCM(100, 499), false},
			{"manual threshold", realtimeAudioManual, testPCM(100, 500), true},
			{"manual sparse noise", realtimeAudioManual, sparse, false},
			{"server threshold", realtimeAudioServer, testPCM(100, 100), false},
			{"server quiet speech", realtimeAudioServer, testPCM(100, 101), true},
			{"server sparse noise", realtimeAudioServer, sparse, true},
		} {
			t.Run(tc.name, func(t *testing.T) {
				g := newRealtimeAudioGate(defaultInputSampleRate, tc.mode)
				var starts int
				for chunk := range g.push(tc.pcm) {
					if chunk.start {
						starts++
					}
				}
				if g.active != tc.active || (starts == 1) != tc.active || starts > 1 {
					t.Fatalf("active/starts = %v/%d, want activity %v", g.active, starts, tc.active)
				}
			})
		}
	})
	t.Run("push", func(t *testing.T) {
		g := newRealtimeAudioGate(defaultInputSampleRate, realtimeAudioManual)
		for range g.push(testPCM(2000, 0)) {
			t.Fatal("silence opened a turn")
		}
		if len(g.preroll) > 10*realtimeFrameBytes || len(g.partial) >= realtimeFrameBytes {
			t.Fatal("unbounded idle audio")
		}
		for range g.push(testPCM(80, 2000)) {
			t.Fatal("brief noise opened a turn")
		}
		for range g.push(testPCM(20, 0)) {
			t.Fatal("noise reset opened a turn")
		}
		pcm := testPCM(100, 2000)
		// Odd capture boundaries must preserve complete PCM samples across calls.
		for range g.push(pcm[:17]) {
			t.Fatal("partial frame opened a turn")
		}
		starts := 0
		for chunk := range g.push(pcm[17:]) {
			if !chunk.start || chunk.end || len(chunk.audio) != 10*realtimeFrameBytes {
				t.Fatalf("preroll/start: %+v", chunk)
			}
			starts++
		}
		if starts != 1 {
			t.Fatal("missing onset")
		}
		frames, ends := 0, 0
		for chunk := range g.push(testPCM(200, 0)) {
			frames++
			if chunk.end {
				ends++
			}
			if chunk.start {
				t.Fatal("duplicate start")
			}
		}
		if frames != 10 || ends != 1 || g.active {
			t.Fatalf("tail: frames %d, ends %d, active %v", frames, ends, g.active)
		}
		for range g.push(testPCM(2000, 0)) {
			t.Fatal("repeated silence ended another turn")
		}
	})
}
