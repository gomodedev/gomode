// Local stack model adapters with chronological conversation events and session-owned tools.

package voicertc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"iter"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"

	"github.com/maruel/genaipy/providers/kittentts"
	"github.com/maruel/genaipy/providers/kittentts/kittenttssrv"
	"github.com/maruel/genaipy/providers/nemospeech"
	"github.com/maruel/genaipy/providers/nemospeech/nemospeechsrv"
	"github.com/maruel/genaipy/providers/vosk"
	"github.com/maruel/genaipy/providers/vosk/vosksrv"
	"github.com/maruel/genaipy/providers/whistle"
	"github.com/maruel/genaipy/providers/whistle/whistlesrv"

	"github.com/maruel/genai"
	"github.com/maruel/genai/providers/gemini"
	"github.com/maruel/genai/providers/llamacpp"
	"github.com/maruel/genai/providers/llamacpp/llamacppsrv"
	"github.com/maruel/genai/providers/openrouter"

	"github.com/gomodedev/gomode/voicegateway"
	voicev2 "github.com/gomodedev/gomode/voicegateway/api/v2"
)

// localStackRuntimeForConfig builds gateway-side ASR, LLM, and TTS adapters.
// Only selected gateway stages are present in cfg. Client-owned stages are
// omitted before acquisition and never provision a speech model.
// External HTTP speech engines share no health or model readiness endpoint;
// provider-backed stages use their provider's health check. Operators must warm
// external speech models before latency-sensitive use, since the first request
// may otherwise load or download a model.
func localStackRuntimeForConfig(ctx context.Context, cfg *voicegateway.PipelineConfig, launch serverLauncher) (*localStackRuntime, error) {
	models, err := localStackModelsForConfigWithStarter(ctx, cfg, launch)
	if err != nil {
		return nil, err
	}
	b := &localStackRuntime{
		liveASR: models.liveASR,
		llm:     models.llm,
		tts:     models.tts,
		runtime: models.runtime,
	}
	return b, nil
}

type localStackModels struct {
	liveASR *liveASR
	llm     llmAdapter
	tts     ttsAdapter
	runtime io.Closer
}

// localStackEndpoint owns its provider and any managed server. Its runtime
// closes the provider before the server.
type localStackEndpoint struct {
	provider genai.Provider
	runtime  io.Closer
}

func localStackModelsForConfigWithStarter(
	ctx context.Context,
	cfg *voicegateway.PipelineConfig,
	launch serverLauncher,
) (localStackModels, error) {
	// Resolve credentials before starting speech models. The LLM is owned here
	// until all ASR initialization succeeds and ownership transfers to the runtime.
	llm, err := resolveLocalStackEndpoint(ctx, cfg.LLM.ModelConfig, cfg.LLM.ContextSize, launch)
	if err != nil {
		return localStackModels{}, err
	}
	var asr localStackEndpoint
	var liveASR *liveASR
	if !cfg.TextOnly && cfg.ASR != (voicegateway.ASRConfig{}) {
		switch cfg.ASR.Engine {
		case voicegateway.ASREngineWhistle:
			// genaipy owns the isolated uv/Python 3.12 Needle worker and disables
			// telemetry. Needle downloads a prebuilt native engine and weights
			// into its own cache, rather than building the runtime from source.
			// Its sessions buffer the utterance until Commit and yield only the
			// final transcript, with automatic language detection; session.setup
			// rejects a language outside whistle.Languages. The worker
			// normalizes 16 kHz PCM to float samples, serializes native calls,
			// and splits utterances into consecutive 30-second windows; words
			// crossing a window boundary may lose accuracy. Cancellation
			// preserves the model for subsequent requests.
			srv, err := launch(ctx, serverSpec{engine: engineWhistle, model: cfg.ASR.Model})
			if err != nil {
				return localStackModels{}, errors.Join(err, llm.runtime.Close())
			}
			c, err := whistle.New(http.DefaultClient, srv.URL(), cfg.ASR.Model)
			if err != nil {
				return localStackModels{}, errors.Join(err, srv.Close(), llm.runtime.Close())
			}
			asr.runtime = srv
			liveASR = newLiveASR(c, c.ModelID())
		case voicegateway.ASREngineNemoSpeech:
			var err error
			asr, err = nemoSpeechEndpoint(ctx, cfg.ASR.Remote, nemospeech.Model(cfg.ASR.Model), launch)
			if err != nil {
				return localStackModels{}, errors.Join(err, llm.runtime.Close())
			}
			liveASR = newLiveASR(asr.provider, cfg.ASR.Model)
		case voicegateway.ASREngineVosk:
			var err error
			asr, err = voskEndpoint(ctx, cfg.ASR.Remote, vosk.Model(cfg.ASR.Model), launch)
			if err != nil {
				return localStackModels{}, errors.Join(err, llm.runtime.Close())
			}
			liveASR = newLiveASR(asr.provider, cfg.ASR.Model)
		default:
			var err error
			asr, err = resolveLocalStackEndpoint(ctx, voicegateway.ModelConfig{Provider: cfg.ASR.Provider, Remote: cfg.ASR.Remote, Model: cfg.ASR.Model}, cfg.ASR.ContextSize, launch)
			if err != nil {
				return localStackModels{}, errors.Join(err, llm.runtime.Close())
			}
			liveASR = newLiveASR(asr.provider, cfg.ASR.Model)
		}
	}
	runtime := joinClosers(asr.runtime, llm.runtime)
	var tts ttsAdapter
	var ttsRuntime io.Closer
	if !cfg.TextOnly && cfg.TTS != (voicegateway.TTSConfig{}) {
		switch cfg.TTS.Engine {
		case voicegateway.TTSEngineGenAI:
			endpoint, err := resolveLocalStackEndpoint(ctx, voicegateway.ModelConfig{Provider: cfg.TTS.Provider, Remote: cfg.TTS.Remote, Model: cfg.TTS.Model}, cfg.TTS.ContextSize, launch)
			if err != nil {
				return localStackModels{}, errors.Join(err, runtime.Close())
			}
			switch p := endpoint.provider.(type) {
			case *llamacpp.Client:
				a, err := newLlamaTTSAdapter(ctx, p, cfg.TTS.Model, cfg.TTS.Voice)
				if err != nil {
					return localStackModels{}, errors.Join(err, endpoint.runtime.Close(), runtime.Close())
				}
				tts = a
			case *gemini.Client:
				tts = &geminiTTSAdapter{client: p, voice: cfg.TTS.Voice}
			default:
				return localStackModels{}, errors.Join(errors.New("provider does not support dedicated speech synthesis"), endpoint.runtime.Close(), runtime.Close())
			}
			ttsRuntime = endpoint.runtime
		case voicegateway.TTSEngineKittenTTS:
			// genaipy owns Python setup and caches. Cancellation preserves its
			// worker for later fragments; runtime eviction closes the process.
			srv, err := launch(ctx, serverSpec{engine: engineKittenTTS, model: cfg.TTS.Model})
			if err != nil {
				return localStackModels{}, errors.Join(err, runtime.Close())
			}
			kitten, err := newKittenTTSAdapter(srv.URL(), cfg.TTS.Model, cfg.TTS.Voice)
			if err != nil {
				return localStackModels{}, errors.Join(err, srv.Close(), runtime.Close())
			}
			tts, ttsRuntime = kitten, srv
		}
	}
	return localStackModels{
		liveASR: liveASR,
		llm:     &genaiLLMAdapter{provider: llm.provider, effort: cfg.LLM.Effort, openRouterSort: cfg.LLM.OpenRouterSort},
		tts:     tts,
		runtime: joinClosers(runtime, ttsRuntime),
	}, nil
}

// nemoSpeechEndpoint recognizes with model on the NeMo-Speech.cpp server at
// remote. Without remote, it installs the pinned NeMo-Speech.cpp release into
// the gateway cache and starts a dedicated loopback server, which downloads the
// model into NeMo-Speech.cpp's own cache ($NEMO_SPEECH_MODEL_DIR, else
// $XDG_CACHE_HOME/nemo-speech/models); its runtime closes the server.
//
// Sessions are genai transcription sessions over nemospeech.Client. Nemotron
// and Parakeet CTC stream the server's partials as drafts; Parakeet TDT buffers
// the utterance until Commit and yields only the final transcript. The client
// has no qualified scoreboard, so liveASR sends the session language: Nemotron
// is forced to the closest of its 32 locales and the others detect the
// language. session.setup rejects a language outside nemospeech.Model.Languages.
//
// Measured on 2026-11-15 with TestSmokeLiveASR on the managed NeMo-Speech.cpp
// 0.3.0 Linux x86-64 CPU build with q8_0 models, in two runs: English and
// French sentences from gemini-3.8-flash-lite-tts, paced at real time, in
// English and French sessions. Each final transcript arrived 3.3 to 3.9 s
// after the utterance started (3.5 to 6.3 s for parakeet-ctc).
//
//   - nemotron-3.5 streamed 14 to 17 drafts, the first after one or two
//     words, and transcribed matching sessions' words exactly, without final
//     punctuation. Forced to en-US, French speech stayed French without
//     accents ("reserver"). Forced to fr-FR, English speech returned "" with
//     no drafts in one run and the exact English words in the other.
//   - nemotron-en streamed 15 drafts and transcribed English words exactly;
//     French speech in an English session returned "" in both runs.
//   - parakeet-ctc streamed 4 drafts and transcribed English words exactly,
//     lowercase without punctuation; French speech in an English session
//     became different English-sounding fragments in each run ("thevoutre
//     reserv in table poreaux soir", "j wouldre reser vent table or do as
//     war").
//   - parakeet-tdt yielded no drafts and transcribed each sentence exactly in
//     its spoken language, with punctuation, in either session.
func nemoSpeechEndpoint(ctx context.Context, remote string, model nemospeech.Model, launch serverLauncher) (localStackEndpoint, error) {
	var runtime io.Closer
	if remote == "" {
		srv, err := launch(ctx, serverSpec{engine: engineNemoSpeech, model: string(model)})
		if err != nil {
			return localStackEndpoint{}, err
		}
		remote, runtime = srv.URL(), srv
	}
	c, err := nemospeech.New(http.DefaultClient, remote, model)
	if err != nil {
		return localStackEndpoint{}, errors.Join(err, joinClosers(runtime).Close())
	}
	return localStackEndpoint{provider: c, runtime: joinClosers(c, runtime)}, nil
}

// voskEndpoint recognizes with model on the vosk-server at remote. Without
// remote, it starts a dedicated genaipy worker, which downloads the model into
// the genaipy cache; its runtime closes the worker.
//
// Sessions are genai transcription sessions over vosk.Client: each 20 ms
// message yields a draft or, at a pause Vosk detects, a phrase. The client has
// no qualified scoreboard, so liveASR feeds micSampleRate and sends the
// session language, which the model's single language must match.
func voskEndpoint(ctx context.Context, remote string, model vosk.Model, launch serverLauncher) (localStackEndpoint, error) {
	var runtime io.Closer
	if remote == "" {
		srv, err := launch(ctx, serverSpec{engine: engineVosk, model: string(model)})
		if err != nil {
			return localStackEndpoint{}, err
		}
		remote, runtime = srv.URL(), srv
	}
	c, err := vosk.New(http.DefaultClient, remote, model)
	if err != nil {
		return localStackEndpoint{}, errors.Join(err, joinClosers(runtime).Close())
	}
	return localStackEndpoint{provider: c, runtime: joinClosers(c, runtime)}, nil
}

// resolveLocalStackEndpoint wires one local stack adapter to its configured
// provider. contextSize sizes a managed llama.cpp server.
func resolveLocalStackEndpoint(ctx context.Context, cfg voicegateway.ModelConfig, contextSize int, launch serverLauncher) (localStackEndpoint, error) {
	if cfg.Provider == "llamacpp" {
		return localStackLlamaEndpoint(ctx, cfg.Remote, cfg.Model, contextSize, launch)
	}
	return localStackGenAIEndpoint(ctx, cfg)
}

// localStackLlamaEndpoint wires the managed (or remote) llama.cpp runtime. A
// managed server was just started by us, so it is known to be reachable; a
// user-supplied remote is pinged to fail fast on misconfiguration.
func localStackLlamaEndpoint(ctx context.Context, remote, model string, contextSize int, launch serverLauncher) (localStackEndpoint, error) {
	var runtime io.Closer
	if remote == "" {
		srv, err := launch(ctx, serverSpec{engine: engineLlamaCPP, model: model, contextSize: contextSize})
		if err != nil {
			return localStackEndpoint{}, err
		}
		remote = srv.URL()
		runtime = srv
		model = "" // The managed server is already pinned to the configured model.
	}
	cfg := voicegateway.ModelConfig{Provider: "llamacpp", Remote: remote, Model: model}
	p, err := cfg.NewProvider(ctx)
	if err != nil {
		return localStackEndpoint{}, errors.Join(err, joinClosers(runtime).Close())
	}
	if runtime == nil {
		if err := pingLocalStackProvider(ctx, p); err != nil {
			return localStackEndpoint{}, errors.Join(err, p.Close())
		}
	}
	return localStackEndpoint{provider: p, runtime: joinClosers(p, runtime)}, nil
}

// localStackGenAIEndpoint wires any provider registered in providers.All (e.g.
// ollama, openaicompatible pointed at a local server). We don't manage the
// provider's process, so ping it when loading the choice to detect unreachable endpoints.
func localStackGenAIEndpoint(ctx context.Context, cfg voicegateway.ModelConfig) (localStackEndpoint, error) {
	p, err := cfg.NewProvider(ctx)
	if err != nil {
		return localStackEndpoint{}, fmt.Errorf("create %s provider: %w", cfg.Provider, err)
	}
	if err := pingLocalStackProvider(ctx, p); err != nil {
		return localStackEndpoint{}, errors.Join(err, p.Close())
	}
	return localStackEndpoint{provider: p, runtime: p}, nil
}

// joinClosers combines closers into one that closes all of them, joining any
// errors. Nil entries are skipped.
func joinClosers(closers ...io.Closer) io.Closer {
	var live []io.Closer
	for _, c := range closers {
		if c != nil {
			live = append(live, c)
		}
	}
	return multiCloser(live)
}

type multiCloser []io.Closer

func (m multiCloser) Close() error {
	errs := make([]error, len(m))
	for i, c := range m {
		errs[i] = c.Close()
	}
	return errors.Join(errs...)
}

// managedServers lists the managed server processes c owns, such as
// llama-server or the genaipy speech workers.
func managedServers(c io.Closer) []genai.Server {
	switch c := c.(type) {
	case multiCloser:
		servers := make([]genai.Server, 0, len(c))
		for _, e := range c {
			servers = append(servers, managedServers(e)...)
		}
		return servers
	case genai.Server:
		return []genai.Server{c}
	}
	return nil
}

func pingLocalStackProvider(ctx context.Context, p genai.Provider) error {
	pinger, ok := p.(genai.ProviderPing)
	if !ok {
		return nil
	}
	if err := pinger.Ping(ctx); err != nil {
		return fmt.Errorf("ping %s provider: %w", p.Name(), err)
	}
	return nil
}

// managedServer is a model server process started by the gateway.
type managedServer interface {
	io.Closer
	URL() string
}

// serverEngine names a managed server implementation.
type serverEngine string

const (
	engineKittenTTS  serverEngine = "kittentts"
	engineLlamaCPP   serverEngine = "llamacpp"
	engineNemoSpeech serverEngine = "nemospeech"
	engineVosk       serverEngine = "vosk"
	engineWhistle    serverEngine = "whistle"
)

// serverSpec identifies a managed server: stacks needing equal specs can share
// one process. contextSize applies to llama.cpp only.
type serverSpec struct {
	engine      serverEngine
	model       string
	contextSize int
}

// serverLauncher starts the managed server a spec names. The process lives
// until the returned server is closed, not until ctx is canceled.
type serverLauncher func(ctx context.Context, spec serverSpec) (managedServer, error)

// startServer is the production serverLauncher.
func startServer(ctx context.Context, spec serverSpec) (managedServer, error) {
	switch spec.engine {
	case engineLlamaCPP:
		return startManagedLlamaServer(ctx, spec.model, spec.contextSize)
	case engineWhistle:
		// genaipy owns the isolated uv/Python 3.12 Needle worker and disables
		// telemetry. Needle downloads a prebuilt native engine and weights
		// into its own cache, rather than building the runtime from source.
		// Its sessions buffer the utterance until Commit and yield only the
		// final transcript, with automatic language detection; session.setup
		// rejects a language outside whistle.Languages. The worker
		// normalizes 16 kHz PCM to float samples, serializes native calls,
		// and splits utterances into consecutive 30-second windows; words
		// crossing a window boundary may lose accuracy. Cancellation
		// preserves the model for subsequent requests.
		reportStartupStage(ctx, "Server is starting Whistle "+spec.model+"…")
		srv, err := whistlesrv.New(ctx, spec.model)
		if err != nil {
			return nil, err
		}
		return srv, nil
	case engineKittenTTS:
		// genaipy owns Python setup and caches. Cancellation preserves its
		// worker for later fragments; closing the server stops the process.
		reportStartupStage(ctx, "Server is starting KittenTTS "+spec.model+"…")
		srv, err := kittenttssrv.New(ctx, kittenttssrv.Config{Model: spec.model})
		if err != nil {
			return nil, err
		}
		return srv, nil
	case engineNemoSpeech:
		return startNemoSpeechServer(ctx, nemospeech.Model(spec.model))
	case engineVosk:
		// genaipy owns the model download, the uv/Python 3.12 worker and its
		// vosk package. Each session decodes with its own recognizer over the
		// shared model; cancellation leaves the model loaded.
		reportStartupStage(ctx, "Server is downloading or loading Vosk "+spec.model+"…")
		srv, err := vosksrv.New(ctx, vosk.Model(spec.model))
		if err != nil {
			return nil, err
		}
		return srv, nil
	}
	return nil, fmt.Errorf("unknown managed server engine %q", spec.engine)
}

func startNemoSpeechServer(ctx context.Context, model nemospeech.Model) (managedServer, error) {
	cacheBase, err := os.UserCacheDir()
	if err != nil {
		return nil, fmt.Errorf("get user cache dir: %w", err)
	}
	reportStartupStage(ctx, "Server is preparing NeMo-Speech.cpp "+nemospeechsrv.Version+"…")
	exe, err := nemospeechsrv.Download(ctx, filepath.Join(cacheBase, "gomode", "nemo-speech"), "")
	if err != nil {
		return nil, fmt.Errorf("install NeMo-Speech.cpp: %w", err)
	}
	slog.InfoContext(ctx, "voicertc: starting managed NeMo-Speech.cpp", "model", model, "version", nemospeechsrv.Version)
	reportStartupStage(ctx, "Server is downloading or loading "+string(model)+"…")
	srv, err := nemospeechsrv.New(ctx, exe, model)
	if err != nil {
		return nil, fmt.Errorf("start managed NeMo-Speech.cpp: %w", err)
	}
	return srv, nil
}

type managedLlamaReleaseCache struct {
	gate  chan struct{}
	cache string
	exe   string
}

var managedLlamaRelease = newManagedLlamaReleaseCache()

func newManagedLlamaReleaseCache() *managedLlamaReleaseCache {
	return &managedLlamaReleaseCache{gate: make(chan struct{}, 1)}
}

func startManagedLlamaServer(ctx context.Context, model string, contextSize int) (managedServer, error) {
	version := llamacppsrv.Version
	cache, err := localStackLlamaCacheDir()
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(cache, 0o750); err != nil {
		return nil, fmt.Errorf("create llama.cpp cache dir: %w", err)
	}
	reportStartupStage(ctx, "Server is preparing llama.cpp "+version+"…")
	exe, err := managedLlamaRelease.executable(ctx, cache, version)
	if err != nil {
		return nil, err
	}
	hostPort, err := localStackLlamaHostPort(ctx)
	if err != nil {
		return nil, err
	}
	// Gateway sessions share each managed server and queue concurrent turns
	// through one slot.
	args := []string{"-hf", model, "--no-warmup", "--no-log-timestamps"}
	slog.InfoContext(ctx, "voicertc: starting managed llama.cpp", "model", model, "version", version, "hostPort", hostPort, "context_size", contextSize)
	// llama-server downloads a missing model before loading it.
	reportStartupStage(ctx, "Server is downloading or loading "+model+"…")
	logger := slog.NewLogLogger(slog.Default().Handler(), slog.LevelInfo)
	srv, err := llamacppsrv.New(ctx, exe, &llamacppsrv.Config{HostPort: hostPort, Slots: 1, ContextSize: contextSize, Args: args, LogOutput: logger.Writer()})
	if err != nil {
		return nil, fmt.Errorf("start managed llama.cpp: %w", err)
	}
	slog.InfoContext(ctx, "voicertc: managed llama.cpp ready", "url", srv.URL())
	return srv, nil
}

func (r *managedLlamaReleaseCache) executable(ctx context.Context, cache, version string) (string, error) {
	select {
	case r.gate <- struct{}{}:
		defer func() { <-r.gate }()
	case <-ctx.Done():
		return "", ctx.Err()
	}
	if r.cache == cache && r.exe != "" {
		return r.exe, nil
	}
	// DownloadVersion may overwrite the executable even when it is already
	// running. Reuse the release for both managed model servers in this process.
	exe, err := llamacppsrv.DownloadVersion(ctx, cache, version)
	if err != nil {
		return "", err
	}
	r.cache = cache
	r.exe = exe
	return exe, nil
}

func localStackLlamaHostPort(ctx context.Context) (string, error) {
	const host = "127.0.0.1"
	var cfg net.ListenConfig
	l, err := cfg.Listen(ctx, "tcp", net.JoinHostPort(host, "0"))
	if err != nil {
		return "", fmt.Errorf("select llama.cpp port: %w", err)
	}
	addr, ok := l.Addr().(*net.TCPAddr)
	if !ok {
		_ = l.Close()
		return "", fmt.Errorf("selected llama.cpp listener has address %T, want *net.TCPAddr", l.Addr())
	}
	if err := l.Close(); err != nil {
		return "", fmt.Errorf("release llama.cpp port probe: %w", err)
	}
	return net.JoinHostPort(host, strconv.Itoa(addr.Port)), nil
}

func localStackLlamaCacheDir() (string, error) {
	cacheBase, err := os.UserCacheDir()
	if err != nil {
		return "", fmt.Errorf("get user cache dir: %w", err)
	}
	return filepath.Join(cacheBase, "gomode", "llama-server"), nil
}

// kittenTTSAdapter speaks through KittenTTS, whose voices each speak one
// language. A session language is supported when a voice speaks it. A session
// without a requested voice uses the configured voice when it speaks the
// session language, and otherwise the first voice that does. genaipy receives
// the language beside the voice: KittenTTS 2 disables English text
// normalization for other languages, and nano, pico and mini select their
// language model with it.
//
// Measured with genaipy v0.1.2, which normalized every language as English:
// the English voice Luna read a 4 s French request intelligibly, as Whisper
// transcribed it exactly, but in 17.9 s with a 6.8 s pause mid-sentence; the
// French voice read it in 5.7 s.
type kittenTTSAdapter struct {
	synthesizer *kittentts.Client
	model       string
	// voices is in name order.
	voices []kittentts.Voice
	voice  string
}

// newKittenTTSAdapter speaks through the KittenTTS worker at remote, which
// loaded model, with voice for sessions in its language.
func newKittenTTSAdapter(remote, model, voice string) (*kittenTTSAdapter, error) {
	voices, err := kittentts.Voices(model)
	if err != nil {
		return nil, err
	}
	c, err := kittentts.New(http.DefaultClient, remote)
	if err != nil {
		return nil, err
	}
	return &kittenTTSAdapter{synthesizer: c, model: model, voices: voices, voice: voice}, nil
}

func (a *kittenTTSAdapter) checkLanguage(lang string) error {
	if !slices.ContainsFunc(a.voices, func(v kittentts.Voice) bool { return v.Language == lang }) {
		return newUnsupportedLanguageError(a.model, lang)
	}
	return nil
}

func (a *kittenTTSAdapter) sessionVoice(lang, requested string) (string, error) {
	if requested != "" {
		i := slices.IndexFunc(a.voices, func(v kittentts.Voice) bool { return v.Name == requested })
		if i < 0 {
			return "", fmt.Errorf("%s has no voice %s", a.model, requested)
		}
		if a.voices[i].Language != lang {
			return "", fmt.Errorf("%s voice %s does not speak %s", a.model, requested, languageName(lang))
		}
		return requested, nil
	}
	return kittenDefaultVoice(a.voices, a.voice, lang), nil
}

// kittenDefaultVoice returns the voice of a session in lang that names none:
// configured when it speaks lang, otherwise the first of voices that does.
// voices is in name order and covers lang.
func kittenDefaultVoice(voices []kittentts.Voice, configured, lang string) string {
	if slices.Contains(voices, kittentts.Voice{Name: configured, Language: lang}) {
		return configured
	}
	return voices[slices.IndexFunc(voices, func(v kittentts.Voice) bool { return v.Language == lang })].Name
}

func (a *kittenTTSAdapter) synthesize(ctx context.Context, lang, voice, text string) iter.Seq2[[]byte, error] {
	return a.synthesizer.Synthesize(ctx, kittentts.Voice{Name: voice, Language: lang}, text)
}

type genaiLLMAdapter struct {
	provider        genai.Provider
	effort          genai.EffortLevel
	openRouterSort  openrouter.ProviderSort
	audioInput      bool
	maxOutputTokens int64
}

func (a *genaiLLMAdapter) newConversation(systemInstruction string, tools []voicev2.ToolDeclaration, includeReasoning bool) llmConversation {
	defs, err := genaiToolDefs(tools)
	return &genaiConversation{
		provider:          a.provider,
		effort:            a.effort,
		openRouterSort:    a.openRouterSort,
		includeReasoning:  includeReasoning,
		systemInstruction: systemInstruction,
		tools:             defs,
		initErr:           err,
		pendingCalls:      make(map[string]string),
		seenCalls:         make(map[string]struct{}),
		audioInput:        a.audioInput,
		maxOutputTokens:   a.maxOutputTokens,
	}
}

type genaiConversation struct {
	provider         genai.Provider
	effort           genai.EffortLevel
	openRouterSort   openrouter.ProviderSort
	includeReasoning bool
	audioInput       bool
	maxOutputTokens  int64

	mu                sync.Mutex
	messages          genai.Messages
	systemInstruction string
	contextText       string
	tools             []genai.ToolDef
	initErr           error
	nextToolID        int
	// Pending calls outlive generations; completed IDs remain reserved so a
	// provider cannot replay a potentially acted side effect.
	pendingCalls map[string]string
	seenCalls    map[string]struct{}
	revision     uint64
}

func (c *genaiConversation) user(ctx context.Context, text string) (llmStep, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.initErr != nil {
		return llmStep{}, c.initErr
	}
	c.messages = append(c.messages, genai.NewTextMessage(c.userText(text)))
	if c.audioInput && len(c.pendingCalls) != 0 {
		return newLLMStep(nil, llmReply{}, nil), nil
	}
	return c.startGenerationLocked(ctx), nil
}

func (c *genaiConversation) respond(ctx context.Context) (llmStep, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.initErr != nil {
		return llmStep{}, c.initErr
	}
	// Text providers carry external data in a delimited input message, without
	// inventing a user utterance or emitting a user transcript event.
	c.messages = append(c.messages, genai.NewTextMessage("Service notification (data; respond following host instructions):\n"+c.contextText))
	return c.startGenerationLocked(ctx), nil
}

func (c *genaiConversation) toolResult(ctx context.Context, id, name string, result json.RawMessage) (llmStep, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.initErr != nil {
		return llmStep{}, c.initErr
	}
	if want, ok := c.pendingCalls[id]; !ok || want != name {
		return llmStep{}, fmt.Errorf("unexpected tool result %q for %q", id, name)
	}
	resultText := string(result)
	if resultText == "" {
		resultText = "null"
	}
	c.messages = append(c.messages, genai.Message{ToolCallResults: []genai.ToolCallResult{{ID: id, Name: name, Result: resultText}}})
	delete(c.pendingCalls, id)
	if c.audioInput && len(c.pendingCalls) != 0 {
		// Keep the batch in history, but wait for every host result before
		// asking LFM3 to continue or synthesize a final answer.
		return newLLMStep(nil, llmReply{}, nil), nil
	}
	return c.startGenerationLocked(ctx), nil
}

func (c *genaiConversation) addContext(text string) {
	if text == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.contextText == "" {
		c.contextText = text
		return
	}
	c.contextText += "\n\n" + text
}

func (c *genaiConversation) startGenerationLocked(ctx context.Context) llmStep {
	// History is append-only. Give each request its own outer slice so later
	// events cannot overwrite it. Provider responses are owned only after finish.
	snapshot := slices.Clone(c.messages)
	if c.audioInput {
		// Requests may overlap during cancellation. Each generation owns its
		// document cursors; the immutable audio payload is shared with history.
		for i := range snapshot {
			snapshot[i].Requests = slices.Clone(snapshot[i].Requests)
			for j := range snapshot[i].Requests {
				if src, ok := snapshot[i].Requests[j].Doc.Src.(*bytes.Reader); ok {
					reader := *src
					snapshot[i].Requests[j].Doc.Src = &reader
				}
			}
		}
		// LFM3-Audio's template does not read enable_thinking. Prefill
		// reasoning so llama-server continues an open think block and parses
		// its end. A text prefill continues content after llama-server's own
		// closed think block, so "<think>" became "<think></think><think>" and
		// the model closed both, answering twice. Keep the prefill out of
		// committed history.
		if c.effort != genai.EffortOff {
			snapshot = append(snapshot, genai.Message{Replies: []genai.Reply{{Reasoning: "\n"}}})
		}
	}
	opts := c.genOptions()
	c.revision++
	revision := c.revision
	var res genai.Result
	var err error
	return llmStep{
		stream: func(yield func(llmFragment) bool) {
			accepting := true
			replies, done := c.provider.GenStream(ctx, snapshot, opts...)
			for reply := range replies {
				reasoning := reply.Reasoning
				if c.audioInput && !c.includeReasoning {
					reasoning = ""
				}
				if (reply.Text != "" || reasoning != "") && !yield(llmFragment{text: reply.Text, reasoning: reasoning}) {
					accepting = false
					break
				}
			}
			res, err = done()
			// Usage belongs to the completed request, including tool rounds and
			// cancelled turns whose result cannot be committed to history.
			u := &res.Usage
			if accepting && (err == nil || u.InputTokens > 0 || u.OutputTokens > 0 || u.TotalTokens > 0 ||
				u.InputCachedTokens > 0 || u.ReasoningTokens > 0 || u.ContextTokens > 0 || u.ContextWindow > 0) {
				yield(llmFragment{usage: &usageObservation{provider: c.provider.Name(), model: c.provider.ModelID(), counts: res.Usage}})
			}
		},
		finish: func() (llmReply, error) {
			c.mu.Lock()
			defer c.mu.Unlock()
			if err != nil {
				return llmReply{}, err
			}
			if c.audioInput && res.Usage.FinishReason == genai.FinishedLength {
				return llmReply{}, errors.New("staged generation reached its token limit; increase max_output_tokens")
			}
			if ctx.Err() != nil {
				return llmReply{}, ctx.Err()
			}
			if revision != c.revision {
				return llmReply{}, context.Canceled
			}
			reply, err := c.toReplyLocked(&res.Message)
			if err != nil {
				return llmReply{}, err
			}
			c.messages = append(c.messages, res.Message)
			return reply, nil
		},
	}
}

func (c *genaiConversation) genOptions() []genai.GenOption {
	system := c.systemInstruction
	if c.audioInput {
		system += "\n\nRespond in text or function calls only.\n\nList of tools:"
	}
	text := &genai.GenOptionText{SystemPrompt: system, MaxTokens: c.maxOutputTokens}
	if c.audioInput {
		text.Stop = []string{"<|mixed_start|>", "<|audio_start|>"}
	}
	opts := []genai.GenOption{text}
	if c.effort != "" {
		opts = append(opts, c.effort)
	}
	if c.openRouterSort != "" {
		opts = append(opts, &openrouter.GenOption{Provider: &openrouter.ProviderPreferences{Sort: c.openRouterSort}})
	}
	if c.includeReasoning || c.audioInput {
		opts = append(opts, genai.IncludeReasoning)
	}
	if len(c.tools) != 0 {
		opts = append(opts, &genai.GenOptionTools{Tools: c.tools})
	}
	return opts
}

func (c *genaiConversation) toReplyLocked(msg *genai.Message) (llmReply, error) {
	text := strings.Builder{}
	var calls []llmToolCall
	ids := make(map[string]struct{})
	for i := range msg.Replies {
		reply := &msg.Replies[i]
		if !reply.ToolCall.IsZero() {
			if reply.ToolCall.ID == "" {
				for {
					c.nextToolID++
					reply.ToolCall.ID = fmt.Sprintf("local-call-%d", c.nextToolID)
					if _, exists := c.seenCalls[reply.ToolCall.ID]; !exists {
						break
					}
				}
			}
			id := reply.ToolCall.ID
			if _, exists := c.seenCalls[id]; exists {
				return llmReply{}, fmt.Errorf("reused tool call ID %q", id)
			}
			if _, exists := ids[id]; exists {
				return llmReply{}, fmt.Errorf("duplicate tool call ID %q", id)
			}
			ids[id] = struct{}{}
			if reply.ToolCall.Arguments == "" {
				reply.ToolCall.Arguments = "{}"
			}
			calls = append(calls, llmToolCall{id: id, name: reply.ToolCall.Name, args: json.RawMessage(reply.ToolCall.Arguments)})
		}
		text.WriteString(reply.Text)
	}
	answer := text.String()
	if c.audioInput && answer == "" && len(calls) == 0 {
		return llmReply{}, errors.New("staged generation returned no text or tool calls")
	}
	// llama-server parses LFM3 reasoning into reasoning_content. A tag left in
	// the text means the model left or reopened a think block, possibly
	// repeating its answer; speak none of it.
	if c.audioInput && (strings.Contains(answer, "<think>") || strings.Contains(answer, "</think>")) {
		return llmReply{}, errors.New("staged generation returned unparsed reasoning; refusing to synthesize it")
	}
	for _, call := range calls {
		c.seenCalls[call.id] = struct{}{}
		c.pendingCalls[call.id] = call.name
	}
	return llmReply{text: answer, toolCalls: calls}, nil
}

func (c *genaiConversation) userText(text string) string {
	if c.contextText == "" {
		return text
	}
	return "Current context:\n" + c.contextText + "\n\nUser said:\n" + text
}
