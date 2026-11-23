// Exercises RSA key generation through access-token signing and verification.

//go:build integration

package oauthserver

import (
	"crypto/rsa"
	"encoding/base64"
	"strings"
	"testing"
	"time"

	"github.com/gomodedev/gomode/oauth"
)

func TestIntegrationAccessTokenServiceRSARotation(t *testing.T) {
	t.Parallel()
	const issuer = "https://example.test"
	const audience = "https://example.test/resource"
	const scope = "read"
	user := oauth.User{ID: "usr_1", Username: "alice", Provider: "github"}
	svc := newTestAccessTokenService(t, "")
	// Default is ES256.
	if svc.keys[svc.currentKID].alg != "ES256" {
		t.Fatalf("default key alg = %q, want ES256", svc.keys[svc.currentKID].alg)
	}

	// Add an RSA key via RotateKeyWithAlg.
	rsaKID, err := svc.RotateKeyWithAlg("RS256")
	if err != nil {
		t.Fatalf("RotateKeyWithAlg(RS256): %v", err)
	}
	if svc.keys[rsaKID].alg != "RS256" {
		t.Fatalf("RS256 key alg = %q, want RS256", svc.keys[rsaKID].alg)
	}
	if _, ok := svc.keys[rsaKID].key.(*rsa.PrivateKey); !ok {
		t.Fatal("RotateKeyWithAlg(RS256) did not produce RSA key")
	}

	// Issue and verify with the RSA key.
	token, err := svc.IssueAccessToken(issuer, user, audience, scope, "grant-rsa", "test-client-1")
	if err != nil {
		t.Fatalf("IssueAccessToken (RSA): %v", err)
	}
	if _, err := svc.VerifyAccessToken(token, issuer, audience, time.Now(),
		func(grantID string, _ time.Time) (bool, string, error) {
			return true, "test-client-1", nil
		},
		&tokenTestSession{user: user}); err != nil {
		t.Fatalf("VerifyAccessToken (RSA key): %v", err)
	}

	// Verify RSA header alg.
	parts := strings.Split(token, ".")
	headerJSON, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		t.Fatalf("decode header: %v", err)
	}
	if !strings.Contains(string(headerJSON), "\"RS256\"") {
		t.Fatalf("RSA header alg is not RS256: %s", string(headerJSON))
	}

	// RotateKeyWithAlg with unknown alg.
	if _, err := svc.RotateKeyWithAlg("BOGUS256"); err == nil {
		t.Fatal("RotateKeyWithAlg(BOGUS256) error = nil")
	}
}
