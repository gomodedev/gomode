// Tests stable release eligibility and development build identities.

package autoupdate

import "testing"

func TestIsStable(t *testing.T) {
	t.Run("valid", func(t *testing.T) {
		for _, v := range []string{"1.2.3", "v0.0.0", "123.40.1"} {
			if !IsStable(v) {
				t.Errorf("rejected %q", v)
			}
		}
	})
	t.Run("error", func(t *testing.T) {
		for _, v := range []string{"", "devel", "unknown", "v1", "1.2", "1.2.3+dirty", "1.2.3-rc.1", "v0.0.0-20261109120000-abcdef123456", "01.2.3", "1.2.3.4"} {
			if IsStable(v) {
				t.Errorf("accepted %q", v)
			}
		}
	})
}
func TestIsNewer(t *testing.T) {
	if !IsNewer("v1.10.0", "1.9.9") {
		t.Fatal("numeric ordering failed")
	}
	for _, pair := range [][2]string{{"1.2.3", "1.2.3"}, {"1.2.2", "1.2.3"}, {"1.3.0", "devel"}, {"1.3.0-rc.1", "1.2.3"}, {"1.3.0", "1.2.3+dirty"}} {
		if IsNewer(pair[0], pair[1]) {
			t.Errorf("unexpected update %v", pair)
		}
	}
}
