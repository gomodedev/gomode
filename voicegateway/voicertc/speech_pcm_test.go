// Tests native provider PCM conversion across fragmented samples and sample rates.

package voicertc

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"reflect"
	"testing"
)

func TestNormalizePCM(t *testing.T) {
	t.Parallel()
	t.Run("valid", func(t *testing.T) {
		for _, tc := range []struct {
			name, mime string
			data       []byte
			want       []int16
		}{
			{"native rate", "audio/pcm;rate=24000;encoding=signed-int;bits=16", []byte{0, 128, 0, 0, 255, 127}, []int16{-32768, 0, 32767}},
			{"upsampling", "audio/pcm;rate=12000;encoding=signed-int;bits=16", pcm16Samples(0, 16384), []int16{0, 8192, 16384, 16384}},
			{"downsampling", "audio/pcm;rate=48000;encoding=signed-int;bits=16", pcm16Samples(0, 8192, 16384, 24576), []int16{0, 16384}},
		} {
			t.Run(tc.name, func(t *testing.T) {
				var got []int16
				parts := make([][]byte, len(tc.data))
				for i := range tc.data {
					parts[i] = tc.data[i : i+1]
				}
				for pcm, err := range normalizePCM(&fragmentReader{parts: parts}, tc.mime) {
					if err != nil {
						t.Fatal(err)
					}
					for i := 0; i < len(pcm); i += 2 {
						got = append(got, int16(binary.LittleEndian.Uint16(pcm[i:]))) //nolint:gosec // Decode signed PCM bit representation.
					}
				}
				if !reflect.DeepEqual(got, tc.want) {
					t.Fatalf("samples = %v, want %v", got, tc.want)
				}
			})
		}
	})
	t.Run("error", func(t *testing.T) {
		for _, tc := range []struct {
			mime string
			data []byte
		}{
			{"broken", nil},
			{"audio/wav", nil},
			{"audio/pcm;rate=0;encoding=signed-int;bits=16", nil},
			{"audio/pcm;rate=24000;encoding=signed-int;bits=16;channels=2", nil},
			{"audio/pcm;rate=24000;encoding=signed-int;bits=16;endian=big", nil},
			{"audio/pcm;rate=24000", nil},
			{"audio/pcm;rate=24000;encoding=signed-int;bits=16", []byte{1, 2, 3}},
		} {
			var got error
			for _, err := range normalizePCM(bytes.NewReader(tc.data), tc.mime) {
				got = err
			}
			if got == nil {
				t.Fatalf("accepted %q, %v", tc.mime, tc.data)
			}
		}
		sentinel := errors.New("failed audio stream")
		var got error
		for _, err := range normalizePCM(&fragmentReader{parts: [][]byte{pcm16Samples(16384)}, err: sentinel}, "audio/pcm;rate=24000;encoding=signed-int;bits=16") {
			got = err
		}
		if !errors.Is(got, sentinel) {
			t.Fatalf("read error = %v", got)
		}
	})
}

func pcm16Samples(samples ...int16) []byte {
	var out []byte
	for _, x := range samples {
		out = binary.LittleEndian.AppendUint16(out, uint16(x)) //nolint:gosec // Encode signed PCM bit representation.
	}
	return out
}

type fragmentReader struct {
	parts [][]byte
	err   error
}

func (r *fragmentReader) Read(p []byte) (int, error) {
	if len(r.parts) == 0 {
		return 0, io.EOF
	}
	n := copy(p, r.parts[0])
	r.parts = r.parts[1:]
	if len(r.parts) == 0 {
		return n, r.err
	}
	return n, nil
}
