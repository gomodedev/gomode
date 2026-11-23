// Package rsatest supplies public test fixtures for RSA signing and verification.
package rsatest

import (
	"crypto/rsa"
	"crypto/x509"
	_ "embed"
	"encoding/pem"
	"testing"
)

// This key is public test data and must never be used outside tests.
//
//go:embed testdata/key.pem
var keyPEM string

// PEM returns a disposable copy of the test key in PKCS1 PEM format.
func PEM() []byte {
	return []byte(keyPEM)
}

// Key parses a fresh copy of the 2048-bit RSA test key.
func Key(t testing.TB) *rsa.PrivateKey {
	block, _ := pem.Decode(PEM())
	if block == nil {
		t.Fatal("invalid PEM encoding for RSA fixture")
	}
	key, err := x509.ParsePKCS1PrivateKey(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	return key
}
