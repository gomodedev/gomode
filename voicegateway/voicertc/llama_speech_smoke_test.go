// Real managed llama.cpp ASR, language generation, and separate speech synthesis.

//go:build smoke

package voicertc

import (
	"strings"
	"testing"
	"time"

	"github.com/maruel/gomode/voicegateway"
)

// TestSmokeLlamaSpeech exercises separate llama.cpp live ASR and TTS around the
// production language adapter.
func TestSmokeLlamaSpeech(t *testing.T) {
	isolateSmokeHost(t)
	cfg := voicegateway.PipelineConfig{
		Label: "Managed llama speech",
		ASR:   voicegateway.ASRConfig{Engine: voicegateway.ASREngineGenAI, Provider: "llamacpp", Model: "ggml-org/Voxtral-Mini-4B-Realtime-2602-GGUF:Q4_K_M", ContextSize: 32768},
		LLM:   voicegateway.LLMConfig{ModelConfig: voicegateway.ModelConfig{Provider: "llamacpp", LlamaCPPServer: true, Model: "unsloth/gemma-4-E2B-it-GGUF:UD-Q4_K_XL"}, ContextSize: 65536},
		TTS:   voicegateway.TTSConfig{Engine: voicegateway.TTSEngineGenAI, Provider: "llamacpp", Model: "LiquidAI/LFM3-Audio-2B-GGUF:Q4_K_M", Voice: "female"},
	}
	startup := time.Now()
	runtime, err := localStackRuntimeForConfig(t.Context(), &cfg)
	t.Logf("managed speech stack setup: %s", time.Since(startup))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := runtime.Close(); err != nil {
			t.Error(err)
		}
	})
	start := time.Now()
	var pcm []byte
	for chunk, err := range runtime.tts.synthesize(t.Context(), "en", "", "I love bananas.") {
		if err != nil {
			t.Fatal(err)
		}
		if len(pcm) == 0 {
			t.Logf("first speech audio: %s", time.Since(start))
		}
		pcm = append(pcm, chunk...)
	}
	t.Logf("known utterance synthesis: %s, %d PCM bytes", time.Since(start), len(pcm))
	if len(pcm) < 24000 || pcmS16LEEnergy(pcm) < 1000 {
		t.Fatal("synthesis did not produce audible speech")
	}
	// The utterance is spoken at real time; latency includes its duration.
	start = time.Now()
	_, text, failed := smokeLiveUtterance(t, runtime.liveASR, "en", downsample24to16(pcm))
	t.Logf("recognition: %s, transcript %q", time.Since(start), text)
	if failed != "" {
		t.Fatal(failed)
	}
	if smokeWords(text) != "i love bananas" {
		t.Fatalf("unexpected transcript %q", text)
	}
	conv := runtime.llm.newConversation("Answer in one short plain text sentence.", nil, false)
	start = time.Now()
	step, err := conv.user(t.Context(), text)
	if err != nil {
		t.Fatal(err)
	}
	var reply strings.Builder
	for fragment := range step.stream {
		reply.WriteString(fragment.text)
	}
	result, err := step.finish()
	if err != nil {
		t.Fatal(err)
	}
	if reply.Len() == 0 {
		reply.WriteString(result.text)
	}
	t.Logf("language response: %s, %q", time.Since(start), reply.String())
	if reply.Len() == 0 {
		t.Fatal("empty language response")
	}
	start = time.Now()
	var bytes int
	for chunk, err := range runtime.tts.synthesize(t.Context(), "en", "", reply.String()) {
		if err != nil {
			t.Fatal(err)
		}
		bytes += len(chunk)
	}
	t.Logf("response synthesis: %s, %d PCM bytes", time.Since(start), bytes)
	if bytes == 0 {
		t.Fatal("response synthesis produced no speech")
	}
}
