// Builds binaries to test tagged, dirty, development and pseudo-version identities.

//go:build integration

package autoupdate

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestIntegrationVersion(t *testing.T) {
	f := newReleaseFixture(t)
	binary := filepath.Join(t.TempDir(), "version")
	if runtime.GOOS == "windows" {
		binary += ".exe"
	}
	check := func(t *testing.T, want string, stable bool) {
		cmd := exec.CommandContext(t.Context(), binary, "version")
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("version: %v\n%s", err, out)
		}
		got := strings.TrimSpace(string(out))
		if got != want {
			t.Fatalf("version = %q, want %q", got, want)
		}
		if IsStable(got) != stable {
			t.Fatalf("eligibility of %q = %v", got, IsStable(got))
		}
	}
	t.Run("tagged", func(t *testing.T) {
		f.command(t, "git", "tag", "v1.2.3")
		f.build(t, binary)
		check(t, "v1.2.3", true)
	})
	t.Run("dirty", func(t *testing.T) {
		path := filepath.Join(f.dir, "cmd", "restart", "main.go")
		src, err := os.ReadFile(path) //nolint:gosec // Mutates only the copied source in the private fixture repository.
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, append(src, []byte("\n// Uncommitted fixture change.\n")...), 0600); err != nil { //nolint:gosec // Fixture-owned source path inside t.TempDir.
			t.Fatal(err)
		}
		f.build(t, binary)
		check(t, "v1.2.3+dirty", false)
	})
	t.Run("development", func(t *testing.T) {
		f.build(t, binary, "-buildvcs=false")
		check(t, "(devel)", false)
	})
	t.Run("pseudo version", func(t *testing.T) {
		f.command(t, "git", "add", ".")
		f.command(t, "git", "commit", "--quiet", "-m", "Next source commit")
		f.build(t, binary)
		cmd := exec.CommandContext(t.Context(), binary, "version")
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("version: %v\n%s", err, out)
		}
		got := strings.TrimSpace(string(out))
		if !strings.HasPrefix(got, "v1.2.4-0.") || IsStable(got) {
			t.Fatalf("unreleased build identity = %q", got)
		}
	})
}

// releaseFixture owns a copied source module and tags only its private Git repo.
type releaseFixture struct{ dir string }

func newReleaseFixture(t *testing.T) *releaseFixture {
	f := &releaseFixture{dir: filepath.Join(t.TempDir(), "module")}
	if err := os.MkdirAll(filepath.Join(f.dir, "autoupdate"), 0700); err != nil {
		t.Fatal(err)
	}
	copyFile := func(src, dst string) {
		data, err := os.ReadFile(src) //nolint:gosec // Copies this checkout's named module and package sources.
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(dst, data, 0600); err != nil { //nolint:gosec // Writes only the private copied module in t.TempDir.
			t.Fatal(err)
		}
	}
	for _, name := range []string{"go.mod", "go.sum"} {
		copyFile(filepath.Join("..", name), filepath.Join(f.dir, name))
	}
	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".go") && !strings.HasSuffix(e.Name(), "_test.go") {
			copyFile(e.Name(), filepath.Join(f.dir, "autoupdate", e.Name()))
		}
	}
	if err := os.MkdirAll(filepath.Join(f.dir, "cmd", "restart"), 0700); err != nil {
		t.Fatal(err)
	}
	copyFile(filepath.Join("testdata", "restart", "main.go"), filepath.Join(f.dir, "cmd", "restart", "main.go"))
	f.command(t, "git", "init", "--quiet")
	f.command(t, "git", "add", ".")
	f.command(t, "git", "commit", "--quiet", "-m", "Fixture source")
	return f
}
func (f *releaseFixture) command(t *testing.T, args ...string) {
	cmd := exec.CommandContext(t.Context(), args[0], args[1:]...) //nolint:gosec // Runs fixture-owned Git and Go commands with separate arguments.
	cmd.Dir = f.dir
	// Inherited Git context, signing or hooks must not redirect these private
	// fixture operations into the user's repository or configuration.
	for _, env := range os.Environ() {
		if !strings.HasPrefix(strings.ToUpper(env), "GIT_") {
			cmd.Env = append(cmd.Env, env)
		}
	}
	cmd.Env = append(cmd.Env, "GOWORK=off", "GOPROXY=off", "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL="+filepath.Join(f.dir, ".git", "fixture-config"), "GIT_AUTHOR_NAME=Go Mode tests", "GIT_AUTHOR_EMAIL=tests@example.invalid", "GIT_COMMITTER_NAME=Go Mode tests", "GIT_COMMITTER_EMAIL=tests@example.invalid")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("%s: %v\n%s", strings.Join(args, " "), err, out)
	}
}
func (f *releaseFixture) build(t *testing.T, path string, flags ...string) {
	args := append([]string{"go", "build"}, flags...)
	args = append(args, "-o", path, "./cmd/restart")
	f.command(t, args...)
}
