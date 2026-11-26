// Tests the fixture's estimate of the tone a microphone transmits.

// Copyright 2026 Marc-Antoine Ruel. All Rights Reserved. Use of this
// source code is governed by the GNU Affero General Public License v3 that
// can be found in the LICENSE file.

package main

import (
	"math"
	"testing"
)

func TestToneHz(t *testing.T) {
	tone := func(hz, amplitude float64) []int16 {
		pcm := make([]int16, 960)
		for i := range pcm {
			pcm[i] = int16(amplitude * math.Sin(2*math.Pi*hz*float64(i)/48000+0.3))
		}
		return pcm
	}
	t.Run("valid", func(t *testing.T) {
		for _, hz := range []int{600, 900} {
			if got := toneHz(tone(float64(hz), 12000), 48000); got != hz {
				t.Errorf("toneHz(%d Hz) = %d", hz, got)
			}
		}
	})
	t.Run("quiet", func(t *testing.T) {
		if got := toneHz(tone(600, 2000), 48000); got != 0 {
			t.Errorf("toneHz(-24 dBFS) = %d", got)
		}
		if got := toneHz(make([]int16, 960), 48000); got != 0 {
			t.Errorf("toneHz(silence) = %d", got)
		}
	})
}
