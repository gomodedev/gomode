// Tests that the Go trace tool can read a trace after gateway startup fails.

//go:build integration

package main

import (
	"encoding/base64"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// This test owns the process-wide Windows shutdown event during startup.
func TestIntegrationStartupFailureTrace(t *testing.T) { //nolint:paralleltest // Run in isolation from other startup tests.
	dir := t.TempDir()
	path := filepath.Join(dir, "config.toml")
	tracePath := filepath.Join(dir, "execution.trace")
	encoded, err := os.ReadFile("../../httpserver/geoip/testdata/country.mmdb.base64")
	if err != nil {
		t.Fatal(err)
	}
	rawDB, err := base64.StdEncoding.DecodeString(strings.Join(strings.Fields(string(encoded)), ""))
	if err != nil {
		t.Fatal(err)
	}
	dbPath := filepath.Join(dir, "country.mmdb")
	if err := os.WriteFile(dbPath, rawDB, 0600); err != nil { //nolint:gosec // Test-owned path inside t.TempDir.
		t.Fatal(err)
	}
	data := fmt.Sprintf(`[server]
http = "invalid-listen-address"
geo_db = %q
[debug]
trace = %q
`, filepath.ToSlash(dbPath), filepath.ToSlash(tracePath))
	if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
		t.Fatal(err)
	}
	writeTestVoiceConfig(t, dir)
	if err := mainImpl([]string{"-config", path}); err == nil || !strings.Contains(err.Error(), "missing port") {
		t.Fatalf("startup error: %v", err)
	}
	// On platforms that retain mapped files, this also proves startup failure
	// released the optional MMDB reader before returning.
	if err := os.Rename(dbPath, dbPath+".closed"); err != nil {
		t.Fatal(err)
	}
	cmd := exec.CommandContext(t.Context(), "go", "tool", "trace", "-d=parsed", tracePath) //nolint:gosec // The trace path is generated inside t.TempDir.
	cmd.Stdout = io.Discard
	if err := cmd.Run(); err != nil {
		t.Fatalf("read execution trace: %v", err)
	}
}
