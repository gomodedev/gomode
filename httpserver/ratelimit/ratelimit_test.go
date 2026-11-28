// Tests token-bucket admission, retry waits, bucket bounds, and rate limit response headers.

package ratelimit

import (
	"net/http"
	"strconv"
	"testing"
	"testing/synctest"
	"time"
)

func TestLimiter(t *testing.T) {
	t.Run("a rejection waits for the next token", func(t *testing.T) {
		synctest.Test(t, func(t *testing.T) {
			l := New(60, time.Minute, 2)
			for range 2 {
				if r := l.Allow("k"); !r.Allowed {
					t.Fatalf("burst request rejected: %+v", r)
				}
			}
			r := l.Allow("k")
			if r.Allowed || r.RetryAfter <= 0 || r.RetryAfter > time.Second {
				t.Fatalf("got %+v, want a rejection waiting at most one second", r)
			}
			if other := l.Allow("other"); !other.Allowed {
				t.Fatal("keys share a bucket")
			}
			time.Sleep(r.RetryAfter)
			if r := l.Allow("k"); !r.Allowed {
				t.Fatalf("rejected after waiting RetryAfter: %+v", r)
			}
			if got := l.Count(); got != 5 {
				t.Errorf("Count = %d, want 5", got)
			}
		})
	})

	t.Run("update applies to existing buckets and zero is unlimited", func(t *testing.T) {
		synctest.Test(t, func(t *testing.T) {
			l := New(1, time.Hour, 1)
			l.Allow("k")
			l.Update(3600, time.Hour, 1)
			time.Sleep(time.Second)
			if r := l.Allow("k"); !r.Allowed {
				t.Fatalf("existing bucket kept the old rate: %+v", r)
			}
			l.Update(0, time.Hour, 1)
			for range 10 {
				if r := l.Allow("k"); !r.Allowed {
					t.Fatal("unlimited limiter rejected")
				}
			}
		})
	})

	t.Run("a full bucket table rejects new keys until a prune frees one", func(t *testing.T) {
		synctest.Test(t, func(t *testing.T) {
			l := New(1, time.Hour, 1)
			for i := range maxBuckets {
				l.Allow(strconv.Itoa(i))
			}
			r := l.Allow("new")
			if r.Allowed || r.RetryAfter != pruneInterval {
				t.Fatalf("got %+v, want a rejection until the next prune", r)
			}
			if r := l.Allow("0"); r.Allowed || r.RetryAfter <= pruneInterval {
				t.Fatalf("existing key not limited by its own bucket: %+v", r)
			}
		})
	})
}

func TestSetRetryAfter(t *testing.T) {
	for _, tc := range []struct {
		wait time.Duration
		want string
	}{
		{0, "1"},
		{time.Millisecond, "1"},
		{time.Second, "1"},
		{42500 * time.Millisecond, "43"},
	} {
		h := http.Header{}
		SetRetryAfter(h, tc.wait)
		if got := h.Get("Retry-After"); got != tc.want {
			t.Errorf("SetRetryAfter(%v) = %q, want %q", tc.wait, got, tc.want)
		}
	}
}

func TestWriteHeaders(t *testing.T) {
	t.Run("an allowed request reports its budget without Retry-After", func(t *testing.T) {
		synctest.Test(t, func(t *testing.T) {
			h := http.Header{}
			WriteHeaders(h, New(60, time.Minute, 10).Allow("k"))
			// One of ten tokens is spent; it refills in a second, so the reset is one second away.
			want := map[string]string{
				"X-Ratelimit-Limit":     "60",
				"X-Ratelimit-Remaining": "9",
				"X-Ratelimit-Reset":     strconv.FormatInt(time.Now().Add(time.Second).Unix(), 10),
				"Retry-After":           "",
			}
			for k, v := range want {
				if got := h.Get(k); got != v {
					t.Errorf("%s = %q, want %q", k, got, v)
				}
			}
		})
	})
	t.Run("a rejection adds Retry-After", func(t *testing.T) {
		h := http.Header{}
		WriteHeaders(h, Result{Limit: 1, ResetAt: time.Now(), RetryAfter: 1500 * time.Millisecond})
		if got := h.Get("Retry-After"); got != "2" {
			t.Errorf("Retry-After = %q, want 2", got)
		}
	})
	t.Run("an unlimited result writes nothing", func(t *testing.T) {
		h := http.Header{}
		WriteHeaders(h, New(0, time.Minute, 0).Allow("k"))
		if len(h) != 0 {
			t.Errorf("headers = %v, want none", h)
		}
	})
}
