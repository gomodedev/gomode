// Tests that a failed executable replacement preserves the running image.

package autoupdate

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestReplaceExecutable(t *testing.T) {
	live := filepath.Join(t.TempDir(), "app")
	if err := os.WriteFile(live, []byte("preserved"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := replaceExecutable(live+".missing", live); err == nil {
		t.Fatal("accepted missing stage")
	}
	data, err := os.ReadFile(live) //nolint:gosec // Reads the fixture-owned file in t.TempDir.
	if err != nil || string(data) != "preserved" {
		t.Fatalf("rollback: %q %v", data, err)
	}
	if _, err := os.Stat(live + ".autoupdate-old"); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("rollback left backup: %v", err)
	}
}
