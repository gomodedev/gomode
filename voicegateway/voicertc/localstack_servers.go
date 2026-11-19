// Managed model servers shared by the local stacks that need the same model.

package voicertc

import (
	"context"
	"log/slog"
	"sync"

	"github.com/maruel/genai"
)

// sharedServers lets local stacks that need the same model, context size and
// slots share one managed server, so switching between choices that differ
// elsewhere keeps its model loaded. A server runs while any stack holds a
// lease. An unleased server stops at the next sweep, which a stack unload or
// load completion triggers; a capacity eviction defers it so the stack taking
// the slot can adopt the evicted stack's servers.
type sharedServers struct {
	launch serverLauncher
	wg     sync.WaitGroup

	mu      sync.Mutex
	entries map[serverSpec]*sharedServer
}

// sharedServer is one server and its startup. Fields after ready closes are
// immutable except refs and cancel, which belong to sharedServers.mu.
type sharedServer struct {
	spec   serverSpec
	ready  chan struct{}
	srv    managedServer // set before ready closes unless err is
	err    error
	cancel context.CancelFunc
	// refs counts leases and acquisitions waiting on startup.
	refs int
}

func newSharedServers(launch serverLauncher) *sharedServers {
	return &sharedServers{launch: launch, entries: map[serverSpec]*sharedServer{}}
}

// acquire is a serverLauncher. The server outlives ctx, which only
// bounds the wait; the last waiter leaving cancels a startup nobody awaits.
// Closing the returned server releases the lease and never stops the process.
func (s *sharedServers) acquire(ctx context.Context, spec serverSpec) (managedServer, error) {
	s.mu.Lock()
	e := s.entries[spec]
	if e != nil && e.exitedLocked() {
		e = nil
	}
	if e == nil {
		runCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
		e = &sharedServer{spec: spec, ready: make(chan struct{}), cancel: cancel}
		s.entries[spec] = e
		s.wg.Go(func() {
			srv, err := s.launch(runCtx, spec)
			s.mu.Lock()
			e.srv, e.err = srv, err
			if err != nil && s.entries[spec] == e {
				delete(s.entries, spec)
			}
			s.mu.Unlock()
			close(e.ready)
		})
	}
	e.refs++
	s.mu.Unlock()
	select {
	case <-e.ready:
	case <-ctx.Done():
		s.release(e)
		return nil, context.Cause(ctx)
	}
	if e.err != nil {
		s.release(e)
		return nil, e.err
	}
	l := &serverLease{s: s, e: e, released: make(chan struct{}), done: make(chan struct{})}
	var exited <-chan struct{}
	if g, ok := e.srv.(genai.Server); ok {
		exited = g.Done()
	}
	s.wg.Go(func() {
		select {
		case <-exited:
		case <-l.released:
		}
		close(l.done)
	})
	return l, nil
}

// exitedLocked reports whether the process exited; such a server is replaced.
func (e *sharedServer) exitedLocked() bool {
	select {
	case <-e.ready:
	default:
		return false
	}
	if e.err != nil {
		return true
	}
	g, ok := e.srv.(genai.Server)
	if !ok {
		return false
	}
	select {
	case <-g.Done():
		return true
	default:
		return false
	}
}

// release drops one reference. An exited server stops with its last lease and
// a startup nobody awaits is canceled; a healthy idle server waits for sweep.
func (s *sharedServers) release(e *sharedServer) {
	s.mu.Lock()
	e.refs--
	var stop *sharedServer
	if e.refs == 0 {
		select {
		case <-e.ready:
			if e.err == nil && e.exitedLocked() {
				if s.entries[e.spec] == e {
					delete(s.entries, e.spec)
				}
				stop = e
			}
		default:
			e.cancel()
		}
	}
	s.mu.Unlock()
	if stop != nil {
		s.stop(stop)
	}
}

// takeIdle removes and returns the started servers no stack holds.
func (s *sharedServers) takeIdle() []*sharedServer {
	s.mu.Lock()
	defer s.mu.Unlock()
	var idle []*sharedServer
	for k, e := range s.entries {
		select {
		case <-e.ready:
			if e.err == nil && e.refs == 0 {
				idle = append(idle, e)
				delete(s.entries, k)
			}
		default:
		}
	}
	return idle
}

func (s *sharedServers) stop(servers ...*sharedServer) {
	for _, e := range servers {
		err := e.srv.Close()
		e.cancel()
		if err != nil {
			slog.Error("voicertc: stop shared server", "engine", e.spec.engine, "model", e.spec.model, "err", err)
		}
	}
}

// serverLease is one stack's hold on a shared server. It implements
// genai.Server so the stack's watcher sees the process exit; Done also closes
// on release, which lets the watcher return when the stack unloads.
type serverLease struct {
	s        *sharedServers
	e        *sharedServer
	released chan struct{}
	done     chan struct{}
}

func (l *serverLease) URL() string { return l.e.srv.URL() }

func (l *serverLease) Done() <-chan struct{} { return l.done }

// Err reports the process exit result, or nil while it runs.
func (l *serverLease) Err() error {
	if g, ok := l.e.srv.(genai.Server); ok {
		select {
		case <-g.Done():
			return g.Err()
		default:
		}
	}
	return nil
}

// Close releases the lease and reports the server's exit result, if it exited.
func (l *serverLease) Close() error {
	close(l.released)
	l.s.release(l.e)
	return l.Err()
}
