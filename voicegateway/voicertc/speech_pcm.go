// Native provider PCM conversion for gateway speech.

package voicertc

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"iter"
	"math"
	"mime"
	"strconv"
)

// normalizePCM resamples llama-server's /v1/audio/speech PCM to the gateway
// output rate, a bounded window at a time, retaining only the prior sample for
// interpolation across response chunks. The response must identify raw
// little-endian mono signed 16-bit PCM in llama-server's spelling, such as
// audio/pcm;rate=24000;encoding=signed-int;bits=16 (tools/server/README.md at
// ggml-org/llama.cpp b1b770d90d94); containers and other encodings fail.
func normalizePCM(r io.Reader, contentType string) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		typ, params, err := mime.ParseMediaType(contentType)
		if err != nil || typ != "audio/pcm" {
			yield(nil, fmt.Errorf("unsupported speech audio type %q", contentType))
			return
		}
		rate, err := strconv.Atoi(params["rate"])
		if err != nil || rate < 8000 || rate > 192000 || params["channels"] != "" && params["channels"] != "1" || params["endian"] != "" && params["endian"] != "little" {
			yield(nil, fmt.Errorf("invalid speech PCM format %q", contentType))
			return
		}
		if params["encoding"] != "signed-int" || params["bits"] != "16" {
			yield(nil, fmt.Errorf("unsupported speech PCM encoding %q", contentType))
			return
		}
		var n, next int64
		var previous float64
		buf := make([]byte, 8192)
		pending := 0
		encode := func(out []byte, sample float64) []byte {
			value := math.Max(-32768, math.Min(32767, math.Round(sample*32768)))
			return binary.LittleEndian.AppendUint16(out, uint16(int16(value))) //nolint:gosec // Saturated signed PCM bit representation.
		}
		for {
			count, readErr := r.Read(buf[pending:8192])
			count += pending
			end := count - count%2
			out := make([]byte, 0, end*3)
			for i := 0; i < end; i += 2 {
				x := float64(int16(binary.LittleEndian.Uint16(buf[i:]))) / 32768 //nolint:gosec // Decode signed PCM bit representation.
				for next <= n*backendOutputSampleRate {
					v := x
					if n != 0 {
						fraction := float64(next-(n-1)*backendOutputSampleRate) / backendOutputSampleRate
						v = previous + (x-previous)*fraction
					}
					out = encode(out, v)
					next += int64(rate)
				}
				previous = x
				n++
			}
			pending = copy(buf, buf[end:count])
			if len(out) != 0 && !yield(out, nil) {
				return
			}
			if readErr != nil {
				switch {
				case !errors.Is(readErr, io.EOF):
					yield(nil, fmt.Errorf("read speech PCM: %w", readErr))
				case pending != 0:
					yield(nil, errors.New("speech PCM ended within a sample"))
				default:
					var tail []byte
					for next < n*backendOutputSampleRate {
						tail = encode(tail, previous)
						next += int64(rate)
					}
					if len(tail) != 0 {
						yield(tail, nil)
					}
				}
				return
			}
		}
	}
}
