// Single-model LFM3 audio input, text reasoning and tools for staged sessions.

package voicertc

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"

	"github.com/maruel/genai"
)

// stagedLlamaSession reserves the same operator-declared session capacity as
// native realtime. Chat and TTS share the loaded model but never hold a slot
// while waiting on host tools.
type stagedLlamaSession struct {
	backendSession
	slots chan struct{}
}

func (s *stagedLlamaSession) close() error {
	err := s.backendSession.close()
	<-s.slots
	return err
}

// userAudio sends pcm, mono PCM16LE at rate, as a WAV message; llama.cpp
// resamples it to the model's audio encoder rate.
func (c *genaiConversation) userAudio(ctx context.Context, pcm []byte, rate int) (llmStep, error) {
	// Whole-utterance inference is bounded to thirty seconds per user message.
	if len(pcm) == 0 || len(pcm)%2 != 0 || len(pcm) > 30*rate*2 {
		return llmStep{}, errors.New("staged audio input must contain at most 30 seconds of mono PCM16")
	}
	wav := make([]byte, 44+len(pcm))
	copy(wav, "RIFF")
	binary.LittleEndian.PutUint32(wav[4:], uint32(len(wav)-8)) //nolint:gosec // bounded above
	copy(wav[8:], "WAVEfmt ")
	binary.LittleEndian.PutUint32(wav[16:], 16)
	binary.LittleEndian.PutUint16(wav[20:], 1)
	binary.LittleEndian.PutUint16(wav[22:], 1)
	binary.LittleEndian.PutUint32(wav[24:], uint32(rate))   //nolint:gosec // an Opus decoder rate
	binary.LittleEndian.PutUint32(wav[28:], uint32(rate*2)) //nolint:gosec // an Opus decoder rate
	binary.LittleEndian.PutUint16(wav[32:], 2)
	binary.LittleEndian.PutUint16(wav[34:], 16)
	copy(wav[36:], "data")
	binary.LittleEndian.PutUint32(wav[40:], uint32(len(pcm))) //nolint:gosec // bounded above
	copy(wav[44:], pcm)
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.initErr != nil {
		return llmStep{}, c.initErr
	}
	// Bound the retained audio independently of the model's token context. Do
	// not evict conversation turns: the client must start a fresh session.
	size := int64(len(wav))
	for i := range c.messages {
		for j := range c.messages[i].Requests {
			if src, ok := c.messages[i].Requests[j].Doc.Src.(*bytes.Reader); ok {
				size += src.Size()
			}
		}
	}
	if size > 8<<20 {
		return llmStep{}, errors.New("staged audio history reached 8 MiB; start a new session")
	}
	msg := genai.Message{Requests: []genai.Request{}}
	if c.contextText != "" {
		msg.Requests = append(msg.Requests, genai.Request{Text: "Current context:\n" + c.contextText})
	}
	msg.Requests = append(msg.Requests, genai.Request{Doc: genai.Doc{Filename: "speech.wav", Src: bytes.NewReader(wav)}})
	c.messages = append(c.messages, msg)
	if len(c.pendingCalls) != 0 {
		return newLLMStep(nil, llmReply{}, nil), nil
	}
	return c.startGenerationLocked(ctx), nil
}
