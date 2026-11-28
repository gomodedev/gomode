// Package ratelimit limits requests per key with token buckets and tells rejected clients when to retry.
//
// It uses only the standard library, so the oauth protocol packages can depend on it.
//
// Every host and Go Mode handler that answers 429 sets Retry-After with SetRetryAfter, or with
// WriteHeaders for a Limiter result, so clients see one rule: whole seconds, rounded up, at least one.
package ratelimit

import (
	"math"
	"net/http"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

// maxBuckets bounds per-key state; keys hold caller-controlled subjects or network addresses.
const maxBuckets = 100_000

// pruneInterval is how often Allow drops buckets that have refilled, which hold no state worth keeping.
const pruneInterval = time.Minute

// Result is the outcome of one Allow call.
type Result struct {
	Allowed bool
	// Limit is the configured requests per window; 0 means unlimited.
	Limit int
	// Remaining is how many requests the key can make now.
	Remaining int
	// ResetAt is when the key's bucket is full again.
	ResetAt time.Time
	// RetryAfter is how long a rejected key waits until its next request is allowed; 0 when allowed.
	RetryAfter time.Duration
}

// Limiter admits requests per key at a sustained rate with a burst. The zero value is not usable;
// call New.
type Limiter struct {
	count atomic.Int64

	mu sync.Mutex
	// perSecond is the refill rate in tokens per second; 0 means unlimited.
	perSecond float64
	burst     int
	window    time.Duration
	buckets   map[string]*bucket
	nextPrune time.Time
}

// bucket holds tokens as of last; refill() brings them up to date.
type bucket struct {
	tokens float64
	last   time.Time
}

// New returns a limiter allowing requests per window per key, up to burst at once. A requests or
// window of 0 or less means unlimited.
func New(requests int, window time.Duration, burst int) *Limiter {
	l := &Limiter{buckets: map[string]*bucket{}}
	l.Update(requests, window, burst)
	return l
}

// Update changes the limit for every key. Existing buckets keep their tokens, capped at the new burst.
func (l *Limiter) Update(requests int, window time.Duration, burst int) {
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	for _, b := range l.buckets {
		l.refill(b, now)
	}
	l.window = window
	if requests <= 0 || window <= 0 {
		l.perSecond, l.burst = 0, 0
		clear(l.buckets)
		return
	}
	l.perSecond = float64(requests) / window.Seconds()
	l.burst = max(burst, 1)
	for _, b := range l.buckets {
		b.tokens = min(b.tokens, float64(l.burst))
	}
}

func (l *Limiter) refill(b *bucket, now time.Time) {
	b.tokens = min(float64(l.burst), b.tokens+now.Sub(b.last).Seconds()*l.perSecond)
	b.last = now
}

// Count reports how many requests Allow has checked.
func (l *Limiter) Count() int64 {
	return l.count.Load()
}

// Allow consumes one request for key when its bucket has a token. When the bucket table is full,
// a new key waits until the next prune can free a bucket.
func (l *Limiter) Allow(key string) Result {
	l.count.Add(1)
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.perSecond <= 0 {
		return Result{Allowed: true}
	}
	if !now.Before(l.nextPrune) {
		for k, b := range l.buckets {
			if l.refill(b, now); b.tokens >= float64(l.burst) {
				delete(l.buckets, k)
			}
		}
		l.nextPrune = now.Add(pruneInterval)
	}
	limit := int(l.perSecond * l.window.Seconds())
	b := l.buckets[key]
	if b == nil {
		if len(l.buckets) >= maxBuckets {
			return Result{Limit: limit, ResetAt: l.nextPrune, RetryAfter: l.nextPrune.Sub(now)}
		}
		b = &bucket{tokens: float64(l.burst), last: now}
		l.buckets[key] = b
	}
	l.refill(b, now)
	var wait time.Duration
	if b.tokens >= 1 {
		b.tokens--
	} else {
		wait = l.duration(1 - b.tokens)
	}
	return Result{
		Allowed:    wait == 0,
		Limit:      limit,
		Remaining:  int(b.tokens),
		ResetAt:    now.Add(l.duration(float64(l.burst) - b.tokens)),
		RetryAfter: wait,
	}
}

// duration is how long the bucket takes to gain tokens, rounded up so the wait never ends early.
func (l *Limiter) duration(tokens float64) time.Duration {
	return time.Duration(math.Ceil(tokens / l.perSecond * float64(time.Second)))
}

// RetryAfterSeconds is wait in whole seconds, rounded up so a retry lands after it, and at least 1.
func RetryAfterSeconds(wait time.Duration) int {
	return int(max(time.Second, wait+time.Second-1) / time.Second)
}

// SetRetryAfter sets the Retry-After header of a 429 or 503 response to wait.
func SetRetryAfter(h http.Header, wait time.Duration) {
	h.Set("Retry-After", strconv.Itoa(RetryAfterSeconds(wait)))
}

// WriteHeaders describes result to the client: X-RateLimit-Limit (requests per window),
// X-RateLimit-Remaining, and X-RateLimit-Reset (Unix seconds when the bucket is full again), plus
// Retry-After on a rejection. Call it before the handler writes, on every limited response. An
// unlimited result writes nothing.
func WriteHeaders(h http.Header, result Result) {
	if result.Limit == 0 && result.Allowed {
		return
	}
	h.Set("X-RateLimit-Limit", strconv.Itoa(result.Limit))
	h.Set("X-RateLimit-Remaining", strconv.Itoa(result.Remaining))
	// Round up so a client that waits until the reset finds the bucket full.
	h.Set("X-RateLimit-Reset", strconv.FormatInt(result.ResetAt.Add(time.Second-1).Unix(), 10))
	if !result.Allowed {
		SetRetryAfter(h, result.RetryAfter)
	}
}
