// Lazy local-stack choices share bounded, session-pinned runtime ownership.

package voicertc

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"sync"
	"time"

	"github.com/maruel/genai"

	"github.com/gomodedev/gomode/voicegateway"
	voicev2 "github.com/gomodedev/gomode/voicegateway/api/v2"
)

type localStackPool struct {
	limits      voicegateway.RuntimeConfig
	recognition []voicegateway.ASRConfig
	synthesis   []voicegateway.TTSConfig

	// servers shares managed servers among entries.
	servers *sharedServers

	mu      sync.Mutex
	entries []*lazyLocalStack
	changed chan struct{}
}

type lazyLocalStack struct {
	pool *localStackPool
	cfg  voicegateway.Choice
	log  *slog.Logger

	// workers joins the goroutines watching loaded stacks' managed workers.
	workers sync.WaitGroup

	// All mutable state belongs to pool.mu. Loading reserves capacity before
	// releasing the lock so concurrent cold choices cannot exceed the limit.
	loaded    backendConnector
	loading   *stackLoad
	stage     string // current startup step while loading
	unloading chan struct{}
	// cancel stops the loading or loaded runtime. Close, unload, and a choice
	// preempting an unattended startup call it.
	cancel context.CancelCauseFunc
	// users counts unreleased acquisitions, including sessions waiting on a
	// load and sessions of a stack unloaded after its worker exited, until they
	// close. A waiting session pins the stack it is about to use.
	users int
	// leases are the sessions on loaded, which a worker exit ends.
	leases     []*leasedLocalStackSession
	idleSince  time.Time
	timer      *time.Timer
	closed     bool
	owner      *localStackBackend
	references int
}

// stackLoad is one runtime startup. Startup outlives the session that began it:
// models take minutes to download and start, and a client that disconnects
// early usually reconnects. Only closing the stack cancels it.
type stackLoad struct {
	done chan struct{}
	err  error // set before done closes
	// preempted marks a startup canceled for another choice while no session
	// waited on it.
	preempted bool
}

var (
	errStackClosed = errors.New("local stack is closed")
	// errStartupPreempted cancels an unattended startup when another choice
	// needs its slot.
	errStartupPreempted = errors.New("local stack startup canceled for another choice")
)

// errWorkerExited ends sessions whose stack lost a managed worker.
var errWorkerExited = errors.New("local stack: model worker exited unexpectedly")

func newLocalStackPool(limits voicegateway.RuntimeConfig) *localStackPool {
	return &localStackPool{limits: limits, servers: newSharedServers(startServer), changed: make(chan struct{})}
}

func (p *localStackPool) backend(cfg voicegateway.PipelineConfig) *localStackBackend {
	b := &localStackBackend{defaults: clientSpeechConfig()}
	if !cfg.TextOnly && cfg.ASR != (voicegateway.ASRConfig{}) {
		b.defaults.Input = voicev2.SpeechLocationGateway
	}
	if !cfg.TextOnly && cfg.TTS != (voicegateway.TTSConfig{}) {
		b.defaults.Output = voicev2.SpeechLocationGateway
	}
	for _, input := range []voicev2.SpeechLocation{voicev2.SpeechLocationGateway, voicev2.SpeechLocationClient} {
		for _, output := range []voicev2.SpeechLocation{voicev2.SpeechLocationGateway, voicev2.SpeechLocationClient} {
			if input == voicev2.SpeechLocationGateway && (cfg.TextOnly || cfg.UnavailableASR || cfg.ASR == (voicegateway.ASRConfig{})) {
				continue
			}
			if output == voicev2.SpeechLocationGateway && (cfg.TextOnly || cfg.UnavailableTTS || cfg.TTS == (voicegateway.TTSConfig{})) {
				continue
			}
			variant := cfg
			if input == voicev2.SpeechLocationClient {
				variant.ASR = voicegateway.ASRConfig{}
			}
			if output == voicev2.SpeechLocationClient {
				variant.TTS = voicegateway.TTSConfig{}
			}
			variant.TextOnly = input == voicev2.SpeechLocationClient && output == voicev2.SpeechLocationClient
			l := &lazyLocalStack{pool: p, cfg: &variant, log: slog.With("provider", cfg.LLM.Provider, "model", cfg.LLM.Model)}
			p.entries = append(p.entries, l)
			b.variants = append(b.variants, speechStackVariant{speech: voicev2.SpeechConfig{Input: input, Output: output}, lazy: l})
		}
	}
	return b
}

// llmBackend keeps one LLM selectable independently of configured speech stages.
func (p *localStackPool) llmBackend(cfg voicegateway.LLMConfig) *localStackBackend {
	b := p.backend(voicegateway.PipelineConfig{TextOnly: true, LLM: cfg})
	b.composition = &speechComposition{pool: p, llm: cfg}
	if len(p.recognition) > 0 {
		b.defaults.Input = voicev2.SpeechLocationGateway
	}
	if len(p.synthesis) > 0 {
		b.defaults.Output = voicev2.SpeechLocationGateway
	}
	return b
}

type speechComposition struct {
	pool *localStackPool
	llm  voicegateway.LLMConfig
}

func (b *localStackBackend) selectStages(speech voicev2.SpeechConfig, setup *voicev2.SessionSetup) (*lazyLocalStack, error) {
	c := b.composition
	cfg := voicegateway.PipelineConfig{LLM: c.llm, TextOnly: speech == clientSpeechConfig()}
	if setup.RecognitionModelID != "" && speech.Input != voicev2.SpeechLocationGateway {
		return nil, errors.New("recognitionModelID requires gateway recognition")
	}
	if setup.SynthesisModelID != "" && speech.Output != voicev2.SpeechLocationGateway {
		return nil, errors.New("synthesisModelID requires gateway synthesis")
	}
	if speech.Input == voicev2.SpeechLocationGateway {
		found := false
		for _, a := range c.pool.recognition {
			if setup.RecognitionModelID == "" || setup.RecognitionModelID == a.ID {
				cfg.ASR = a
				found = true
				break
			}
		}
		if !found {
			return nil, fmt.Errorf("recognition model %q is unavailable", setup.RecognitionModelID)
		}
	}
	if speech.Output == voicev2.SpeechLocationGateway {
		found := false
		for _, a := range c.pool.synthesis {
			if setup.SynthesisModelID == "" || setup.SynthesisModelID == a.ID {
				cfg.TTS = a
				found = true
				break
			}
		}
		if !found {
			return nil, fmt.Errorf("synthesis model %q is unavailable", setup.SynthesisModelID)
		}
	}
	if cfg.TextOnly {
		return b.variants[0].lazy, nil
	}
	p := c.pool
	p.mu.Lock()
	defer p.mu.Unlock()
	if b.closed {
		return nil, errStackClosed
	}
	for _, l := range p.entries {
		if l.owner == b && *l.cfg.(*voicegateway.PipelineConfig) == cfg {
			l.references++
			return l, nil
		}
	}
	l := &lazyLocalStack{pool: p, cfg: &cfg, log: slog.With("provider", cfg.LLM.Provider, "model", cfg.LLM.Model), owner: b, references: 1}
	p.entries = append(p.entries, l)
	return l, nil
}

// Dynamic combinations survive only while referenced or resident. Loaded idle
// combinations are bounded by MaxInstances; failed and evicted entries do not
// accumulate a cache proportional to the Cartesian product of stage catalogs.
func (l *lazyLocalStack) pruneLocked() {
	if l.owner != nil && l.references == 0 && l.loaded == nil && l.loading == nil && l.unloading == nil {
		l.pool.entries = slices.DeleteFunc(l.pool.entries, func(e *lazyLocalStack) bool { return e == l })
	}
}

func (l *lazyLocalStack) releaseReference() {
	if l.owner == nil {
		return
	}
	l.pool.mu.Lock()
	defer l.pool.mu.Unlock()
	l.references--
	l.pruneLocked()
}

type speechStackVariant struct {
	speech voicev2.SpeechConfig
	lazy   *lazyLocalStack
}

// negotiatedStackSession admits setup before acquiring any runtime. Each mode
// has its own pool entry so a client-owned stage cannot pin or initialize an
// unused worker, even when another session selected gateway speech.
type negotiatedStackSession struct {
	backend *localStackBackend
	native  *lazyLocalStack
	ctx     context.Context //nolint:containedctx // Negotiation and the loaded lease share this cancellable session lifetime.
	cancel  context.CancelFunc
	id      string
	sink    backendSink
	text    bool

	op      sync.Mutex
	session backendSession
	dynamic *lazyLocalStack
	closed  bool
}

func newNegotiatedStackSession(ctx context.Context, backend *localStackBackend, native *lazyLocalStack, id string, sink backendSink, text bool) *negotiatedStackSession {
	ctx, cancel := context.WithCancel(ctx)
	return &negotiatedStackSession{backend: backend, native: native, ctx: ctx, cancel: cancel, id: id, sink: sink, text: text}
}

func (s *negotiatedStackSession) acceptClientMessage(_ context.Context, data []byte) error {
	s.op.Lock()
	defer s.op.Unlock()
	if s.closed {
		return errSessionClosed
	}
	if s.session != nil {
		return s.session.acceptClientMessage(s.ctx, data)
	}
	var setup voicev2.SessionSetup
	if err := decodeGatewayMessage(data, voicev2.MessageKindSessionSetup, &setup); err != nil {
		return err
	}
	defaults := gatewaySpeechConfig()
	if s.backend != nil {
		defaults = s.backend.defaults
	}
	if s.text {
		defaults = clientSpeechConfig()
	}
	speech, err := resolveSpeech(setup.Speech, defaults)
	if err != nil {
		return err
	}
	if s.text && speech != clientSpeechConfig() {
		return errors.New("text transport requires client recognition and synthesis")
	}
	selected := s.native
	if s.backend == nil || s.backend.composition == nil {
		if setup.RecognitionModelID != "" || setup.SynthesisModelID != "" {
			return errors.New("independent speech models require a composable LLM choice")
		}
	}
	switch {
	case s.backend != nil && s.backend.composition != nil:
		selected, err = s.backend.selectStages(speech, &setup)
		if err != nil {
			return err
		}
	case s.backend != nil:
		for _, v := range s.backend.variants {
			if v.speech == speech {
				selected = v.lazy
				break
			}
		}
	case speech != gatewaySpeechConfig():
		return errors.New("native audio requires gateway recognition and synthesis")
	}
	if selected == nil {
		return errors.New("selected speech combination is unavailable for this choice")
	}
	if err := applySessionPolicy(&setup); err != nil {
		selected.releaseReference()
		return err
	}
	// Validation used a copy. The backend applies session policy once to the original setup.
	active, err := selected.connect(s.ctx, s.id, s.sink, s.text)
	if err != nil {
		selected.releaseReference()
		return err
	}
	if selected.owner != nil {
		s.dynamic = selected
	}
	s.session = active
	return active.acceptClientMessage(s.ctx, data)
}

func (s *negotiatedStackSession) inputSampleRate() int {
	s.op.Lock()
	defer s.op.Unlock()
	if s.session == nil {
		return 0
	}
	return s.session.inputSampleRate()
}

func (s *negotiatedStackSession) acceptMicPCM(_ context.Context, pcm []byte) error {
	s.op.Lock()
	defer s.op.Unlock()
	if s.session == nil || s.closed {
		return errors.New("session.setup is required before microphone audio")
	}
	return s.session.acceptMicPCM(s.ctx, pcm)
}

func (s *negotiatedStackSession) close() error {
	s.cancel()
	s.op.Lock()
	defer s.op.Unlock()
	s.closed = true
	if s.session != nil {
		err := s.session.close()
		if s.dynamic != nil {
			s.dynamic.releaseReference()
		}
		return err
	}
	return nil
}

func (p *localStackPool) realtimeBackend(cfg voicegateway.RealtimeConfig) *lazyLlamaRealtimeBackend {
	l := &lazyLocalStack{pool: p, cfg: &cfg, log: slog.With("provider", cfg.Provider, "model", cfg.Model)}
	p.entries = append(p.entries, l)
	return &lazyLlamaRealtimeBackend{lazy: l}
}

func (p *localStackPool) notifyLocked() {
	close(p.changed)
	p.changed = make(chan struct{})
}

// unloadLocked closes clients before their managed processes and only then
// releases capacity. External server processes are never owned here. After a
// worker exited, attached sessions end before the surviving workers stop.
// Shared servers no stack holds stop with the unload, unless handoff is set
// because the caller loads another stack next and sweeps when that load ends.
func (l *lazyLocalStack) unloadLocked(exited, handoff bool) error {
	if l.timer != nil {
		l.timer.Stop()
		l.timer = nil
	}
	b, cancel := l.loaded, l.cancel
	l.loaded = nil
	l.cancel = nil
	l.unloading = make(chan struct{})
	var ended []*leasedLocalStackSession
	if exited {
		ended, l.leases = l.leases, nil
	}
	// Keep capacity reserved during shutdown, but let other choices report
	// progress and release sessions while processes are being reaped.
	l.pool.mu.Unlock()
	for _, s := range ended {
		s.sink.sendGatewayError(s.ctx, errWorkerExited.Error())
		s.sink.cancelSession()
	}
	// Close reports the worker's exit, joined with any failure to stop the
	// others.
	err := b.Close()
	cancel(nil)
	if !handoff {
		l.pool.servers.stop(l.pool.servers.takeIdle()...)
	}
	switch {
	case exited:
		l.log.Error("voicertc: local stack worker exited", "err", err)
	case err != nil:
		l.log.Error("voicertc: stop local stack", "err", err)
	default:
		l.log.Info("voicertc: local stack stopped")
	}
	l.pool.mu.Lock()
	close(l.unloading)
	l.unloading = nil
	l.pool.notifyLocked()
	l.pruneLocked()
	return err
}

func (l *lazyLocalStack) acquire(ctx context.Context) (backendConnector, error) {
	p := l.pool
	for {
		p.mu.Lock()
		if err := ctx.Err(); err != nil {
			p.mu.Unlock()
			return nil, err
		}
		if l.closed {
			p.mu.Unlock()
			return nil, errStackClosed
		}
		if l.loaded != nil {
			if l.timer != nil {
				l.timer.Stop()
				l.timer = nil
			}
			l.users++
			b := l.loaded
			p.mu.Unlock()
			return b, nil
		}
		count := 0
		var oldest *lazyLocalStack
		for _, e := range p.entries {
			if e.loaded != nil || e.loading != nil || e.unloading != nil {
				count++
			}
			if e.loaded != nil && e.users == 0 && (oldest == nil || e.idleSince.Before(oldest.idleSince)) {
				oldest = e
			}
		}
		load := l.loading
		if load == nil && l.unloading == nil && count >= p.limits.MaxInstances && oldest == nil {
			l.preemptUnattendedLoadLocked(ctx)
		}
		if load == nil && l.unloading == nil && (count < p.limits.MaxInstances || oldest != nil) {
			if count >= p.limits.MaxInstances {
				// unloadLocked logs a failed stop. The evicted processes are reaped
				// and the slot released either way, so the failure belongs to the
				// evicted choice, not to this session.
				_ = oldest.unloadLocked(false, true)
				p.mu.Unlock()
				continue
			}
			load = &stackLoad{done: make(chan struct{})}
			l.loading = load
			// Keep the session's values for logging, not its cancellation.
			runCtx, cancel := context.WithCancelCause(context.WithoutCancel(ctx))
			l.cancel = cancel
			go l.load(context.WithValue(runCtx, startupStageKey{}, l.setStage), load)
		}
		if load == nil {
			changed := p.changed
			p.mu.Unlock()
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-changed:
			}
			continue
		}
		l.users++
		p.mu.Unlock()
		select {
		case <-ctx.Done():
			l.log.InfoContext(ctx, "voicertc: session left while the local stack starts; startup continues", "cause", context.Cause(ctx))
			l.release()
			return nil, ctx.Err()
		case <-load.done:
		}
		if errors.Is(load.err, errStartupPreempted) {
			// This session arrived after the preemption; compete for a slot anew.
			l.release()
			continue
		}
		if load.err != nil {
			l.release()
			return nil, load.err
		}
		p.mu.Lock()
		b := l.loaded
		p.mu.Unlock()
		if b != nil {
			return b, nil
		}
		// A worker exited between startup and this session's turn.
		l.release()
	}
}

// load starts the runtime and publishes it, idle when every waiting session
// left meanwhile. ctx is independent of those sessions.
func (l *lazyLocalStack) load(ctx context.Context, load *stackLoad) {
	p := l.pool
	l.log.InfoContext(ctx, "voicertc: starting local stack")
	var b backendConnector
	var servers []genai.Server
	var err error
	switch cfg := l.cfg.(type) {
	case *voicegateway.PipelineConfig:
		var runtime *localStackRuntime
		runtime, err = localStackRuntimeForConfig(ctx, cfg, p.servers.acquire)
		if runtime != nil {
			b, servers = runtime, managedServers(runtime.runtime)
		}
	case *voicegateway.RealtimeConfig:
		var runtime *llamaRealtimeBackend
		runtime, err = newLlamaRealtimeBackend(ctx, *cfg)
		if runtime != nil {
			b, servers = runtime, managedServers(runtime.runtime)
		}
	}
	p.mu.Lock()
	if err == nil && l.closed {
		err = errStackClosed
	}
	if err != nil {
		// Keep the loading reservation until cleanup finishes, without
		// blocking other choices' leases or heartbeat observations.
		cancel := l.cancel
		p.mu.Unlock()
		if b != nil {
			err = errors.Join(err, b.Close())
		}
		if cause := context.Cause(ctx); cause != nil {
			l.log.InfoContext(ctx, "voicertc: local stack startup canceled", "cause", cause, "err", err)
			err = cause
		} else {
			l.log.WarnContext(ctx, "voicertc: local stack startup failed", "err", err)
		}
		cancel(nil)
		p.mu.Lock()
		l.cancel = nil
	} else {
		l.log.InfoContext(ctx, "voicertc: local stack started")
		l.watchLocked(b, servers)
		if l.users == 0 {
			l.idleLocked()
		}
	}
	load.err = err
	close(load.done)
	l.loading = nil
	l.stage = ""
	p.notifyLocked()
	l.pruneLocked()
	p.mu.Unlock()
	// Servers a handoff left idle stop now that the load has taken what it needs.
	p.servers.stop(p.servers.takeIdle()...)
}

// preemptUnattendedLoadLocked cancels another choice's startup that no session
// waits on, so a session does not wait for a model nobody uses. The canceled
// load frees its slot when it finishes.
func (l *lazyLocalStack) preemptUnattendedLoadLocked(ctx context.Context) {
	for _, e := range l.pool.entries {
		if e != l && e.loading != nil && e.users == 0 && !e.loading.preempted {
			e.loading.preempted = true
			e.log.InfoContext(ctx, "voicertc: canceling unattended local stack startup for another choice")
			e.cancel(errStartupPreempted)
			return
		}
	}
}

func (l *lazyLocalStack) release() {
	p := l.pool
	p.mu.Lock()
	defer p.mu.Unlock()
	l.users--
	if l.users == 0 && !l.closed && l.loaded != nil {
		l.idleLocked()
	}
	p.notifyLocked()
}

// idleLocked unloads the stack after the idle timeout unless a session
// acquires it first.
func (l *lazyLocalStack) idleLocked() {
	p := l.pool
	l.idleSince = time.Now()
	since := l.idleSince
	l.timer = time.AfterFunc(time.Duration(p.limits.IdleTimeoutSeconds)*time.Second, func() {
		p.mu.Lock()
		defer p.mu.Unlock()
		if l.loaded != nil && l.users == 0 && l.idleSince.Equal(since) {
			if err := l.unloadLocked(false, false); err != nil {
				slog.Error("voicertc: unload idle local stack", "err", err)
			}
		}
	})
}

func (l *lazyLocalStack) close() error {
	p := l.pool
	p.mu.Lock()
	l.closed = true
	if l.loading != nil {
		done := l.loading.done
		l.cancel(errStackClosed)
		p.mu.Unlock()
		<-done
		p.mu.Lock()
	}
	if l.unloading != nil {
		done := l.unloading
		p.mu.Unlock()
		<-done
		p.mu.Lock()
	}
	p.notifyLocked()
	var err error
	if l.loaded != nil {
		err = l.unloadLocked(false, false)
	}
	p.mu.Unlock()
	// Unloading stopped every worker, so each watcher returns.
	l.workers.Wait()
	return err
}

// watchLocked publishes b as loaded and unloads it as soon as one of its
// managed servers exits.
func (l *lazyLocalStack) watchLocked(b backendConnector, servers []genai.Server) {
	l.loaded = b
	for _, srv := range servers {
		l.workers.Go(func() {
			<-srv.Done()
			l.workerExited(b)
		})
	}
}

// workerExited unloads b if it is still loaded and ends its sessions. A
// requested unload clears loaded before stopping the workers, so their exits
// are ignored. The next session loads a fresh stack.
func (l *lazyLocalStack) workerExited(b backendConnector) {
	p := l.pool
	p.mu.Lock()
	defer p.mu.Unlock()
	if l.loaded == b {
		// unloadLocked logs the exit.
		_ = l.unloadLocked(true, false)
	}
}

func (l *lazyLocalStack) connect(ctx context.Context, id string, sink backendSink, text bool) (backendSession, error) {
	if cfg, ok := l.cfg.(*voicegateway.PipelineConfig); !text && ok && cfg.TextOnly {
		return nil, errors.New("text-only choice does not support WebRTC audio")
	}
	// Heartbeats keep cold downloads and capacity waits visible, without
	// claiming readiness. Stop and join before delivering any session messages.
	loadCtx, cancelLoad := context.WithCancel(ctx)
	defer cancelLoad()
	progressDone := make(chan struct{})
	var progressErr error
	var wg sync.WaitGroup
	wg.Go(func() {
		progressErr = l.reportStartupProgress(ctx, sink, progressDone)
		if progressErr != nil {
			cancelLoad()
		}
	})
	b, err := l.acquire(loadCtx)
	close(progressDone)
	wg.Wait()
	if progressErr != nil && err == nil {
		l.release()
		return nil, progressErr
	}
	if err != nil {
		return nil, errors.Join(err, progressErr)
	}
	var s backendSession
	if text {
		s = b.(*localStackRuntime).newSession(ctx, id, sink, true)
	} else {
		s, err = b.connect(ctx, id, sink)
		if err != nil {
			l.release()
			return nil, err
		}
	}
	// An acquired stack unloads before its lease only when closed or when a
	// worker exited.
	lease := &leasedLocalStackSession{backendSession: s, stack: l, ctx: ctx, sink: sink}
	l.pool.mu.Lock()
	attached, closed := l.loaded == b, l.closed
	if attached {
		l.leases = append(l.leases, lease)
	}
	l.pool.mu.Unlock()
	switch {
	case attached:
		return lease, nil
	case closed:
		return nil, errors.Join(errStackClosed, lease.close())
	default:
		return nil, errors.Join(errWorkerExited, lease.close())
	}
}

// reportStartupProgress sends the current startup step as soon as it changes,
// and repeats it every 5 seconds so clients keep extending their setup deadline.
func (l *lazyLocalStack) reportStartupProgress(ctx context.Context, sink backendSink, done <-chan struct{}) error {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	sent, heartbeat := "", true
	for {
		l.pool.mu.Lock()
		cached := l.loaded != nil
		message := "Server is starting voice models…"
		if l.loading != nil && l.stage != "" {
			message = l.stage
		}
		count, idle := 0, false
		if !cached && l.loading == nil {
			for _, e := range l.pool.entries {
				if e.loading != nil || e.loaded != nil || e.unloading != nil {
					count++
				}
				idle = idle || (e.loaded != nil && e.users == 0)
			}
			if count >= l.pool.limits.MaxInstances && !idle {
				message = "Server is waiting for a free voice model slot…"
			}
		}
		changed := l.pool.changed
		l.pool.mu.Unlock()
		// A cached observation is not a lease. Keep watching until acquire
		// finishes: an idle timer or competing choice can evict it meanwhile.
		if !cached && (heartbeat || message != sent) {
			if err := sink.sendGatewayMessage(ctx, mustGatewayServerMessage(&voicev2.SessionStatus{
				Kind: voicev2.MessageKindSessionStatus, Message: message,
			})); err != nil {
				if ctx.Err() == nil {
					return err
				}
				return nil
			}
			sent = message
		}
		heartbeat = false
		select {
		case <-ctx.Done():
			return nil
		case <-done:
			return nil
		case <-changed:
		case <-ticker.C:
			heartbeat = true
		}
	}
}

// startupStageKey carries a loading stack's setStage to the code that
// downloads and starts its runtimes.
type startupStageKey struct{}

// reportStartupStage names the current startup step for every session waiting
// on the loading stack. Outside a stack load it does nothing.
func reportStartupStage(ctx context.Context, message string) {
	if set, ok := ctx.Value(startupStageKey{}).(func(string)); ok {
		set(message)
	}
}

func (l *lazyLocalStack) setStage(message string) {
	l.pool.mu.Lock()
	defer l.pool.mu.Unlock()
	if l.loading != nil {
		l.stage = message
		l.pool.notifyLocked()
	}
}

type leasedLocalStackSession struct {
	backendSession
	stack *lazyLocalStack
	ctx   context.Context //nolint:containedctx // A worker exit reports through the session's own lifetime.
	sink  backendSink
}

func (s *leasedLocalStackSession) close() error {
	err := s.backendSession.close()
	l := s.stack
	l.pool.mu.Lock()
	l.leases = slices.DeleteFunc(l.leases, func(e *leasedLocalStackSession) bool { return e == s })
	l.pool.mu.Unlock()
	l.release()
	return err
}
