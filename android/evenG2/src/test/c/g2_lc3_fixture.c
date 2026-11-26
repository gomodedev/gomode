// Writes the LC3 fixtures of G2Lc3DecoderTest.kt with Google's liblc3 (Apache-2.0).
//
// Build against liblc3 v1.1.3 (commit 96a3af0beb5487aca3b98a4b992a539a1f6d80d1) without fused
// multiply-add, which Kotlin on the JVM never does, so that both decoders round alike:
//
//   gcc -std=c11 -O2 -ffp-contract=off -I<liblc3>/include <liblc3>/src/*.c g2_lc3_fixture.c -lm
//   ./a.out ../resources/lc3/g2-microphone.hex ../resources/lc3/g2-microphone.wav
//
// The signal is two seconds of 16 kHz speech-like sound: a voiced vowel whose pitch glides from 110
// to 180 Hz, silence, a fricative of filtered noise, a burst, a second vowel gliding from 200 to 140
// Hz, and a quiet tone. The encoder codes it as the glasses do, in 10 ms frames of 40 bytes. Frame
// BAD_FRAME is then overwritten with 0xFF bytes, which liblc3 rejects and conceals. The first file
// holds the 200 frames as the decoder receives them, one frame in hex per line; the second, a 16 kHz
// mono 16-bit WAV of the PCM that liblc3 decodes from them.
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "lc3.h"

#define RATE 16000
#define FRAME_SAMPLES 160
#define FRAME_BYTES 40
#define FRAMES 200
#define BAD_FRAME 123
#define PI 3.14159265358979323846

// Harmonic amplitude of a vowel with formants f[i] of bandwidth b[i] at frequency hz.
static double envelope(double hz, const double *f, const double *b) {
    double a = 0;
    for (int i = 0; i < 3; i++) {
        double d = (hz - f[i]) / b[i];
        a += 1 / (1 + d * d);
    }
    return a;
}

static double vowel(double t, double start, double length, double f0_start, double f0_end,
                    const double *f, const double *b, double *phase) {
    double u = (t - start) / length;
    double f0 = f0_start + (f0_end - f0_start) * u;
    *phase += 2 * PI * f0 / RATE;
    double s = 0;
    for (int h = 1; h * f0 < RATE / 2; h++) s += envelope(h * f0, f, b) * sin(h * *phase) / h;
    return s * sin(PI * u);
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s <frames.hex> <pcm.wav>\n", argv[0]);
        return 2;
    }
    static int16_t pcm[FRAMES * FRAME_SAMPLES];
    static const double a_f[3] = {700, 1200, 2600}, a_b[3] = {80, 90, 120};
    static const double i_f[3] = {300, 2300, 3000}, i_b[3] = {60, 100, 120};
    double phase = 0;
    uint32_t seed = 1, last = 0;
    for (int n = 0; n < FRAMES * FRAME_SAMPLES; n++) {
        double t = (double)n / RATE, s = 0;
        seed = seed * 1664525u + 1013904223u;
        double noise = (double)(int32_t)seed / 2147483648.0;
        double high = noise - (double)(int32_t)last / 2147483648.0;
        last = seed;
        if (t < 0.6) s = 0.08 * vowel(t, 0, 0.6, 110, 180, a_f, a_b, &phase);
        else if (t < 0.8) s = 0;
        else if (t < 1.1) s = 0.15 * high * sin(PI * (t - 0.8) / 0.3);
        else if (t < 1.2) s = 0.6 * noise * exp(-(t - 1.1) * 60);
        else if (t < 1.8) s = 0.08 * vowel(t, 1.2, 0.6, 200, 140, i_f, i_b, &phase);
        else if (t < 1.9) s = 0.02 * sin(2 * PI * 440 * t);
        pcm[n] = (int16_t)lrint(32767 * fmax(-1, fmin(1, s)));
    }

    static uint8_t frames[FRAMES][FRAME_BYTES];
    lc3_encoder_mem_16k_t encoder_mem;
    lc3_encoder_t encoder = lc3_setup_encoder(10000, RATE, 0, &encoder_mem);
    for (int i = 0; i < FRAMES; i++) {
        if (lc3_encode(encoder, LC3_PCM_FORMAT_S16, pcm + i * FRAME_SAMPLES, 1, FRAME_BYTES, frames[i]) != 0) {
            fprintf(stderr, "frame %d did not encode\n", i);
            return 1;
        }
    }
    memset(frames[BAD_FRAME], 0xFF, FRAME_BYTES);

    static int16_t decoded[FRAMES * FRAME_SAMPLES];
    lc3_decoder_mem_16k_t decoder_mem;
    lc3_decoder_t decoder = lc3_setup_decoder(10000, RATE, 0, &decoder_mem);
    for (int i = 0; i < FRAMES; i++) {
        int ret = lc3_decode(decoder, frames[i], FRAME_BYTES, LC3_PCM_FORMAT_S16, decoded + i * FRAME_SAMPLES, 1);
        if (ret != (i == BAD_FRAME)) {
            fprintf(stderr, "frame %d decoded with %d\n", i, ret);
            return 1;
        }
    }

    FILE *out = fopen(argv[1], "w");
    if (!out) return 1;
    for (int i = 0; i < FRAMES; i++) {
        for (int j = 0; j < FRAME_BYTES; j++) fprintf(out, "%02x", frames[i][j]);
        fputc('\n', out);
    }
    if (fclose(out) != 0) return 1;

    // RIFF, the sizes, and the fmt chunk of PCM, mono, RATE Hz, 2 bytes per sample, 16 bits.
    // Little-endian hosts only, as every host this runs on.
    uint32_t data = sizeof(decoded);
    uint32_t riff = 36 + data, fmt_size = 16, rate = RATE, byte_rate = RATE * 2;
    uint16_t format = 1, channels = 1, align = 2, bits = 16;
    out = fopen(argv[2], "wb");
    if (!out) return 1;
    fwrite("RIFF", 4, 1, out);
    fwrite(&riff, 4, 1, out);
    fwrite("WAVEfmt ", 8, 1, out);
    fwrite(&fmt_size, 4, 1, out);
    fwrite(&format, 2, 1, out);
    fwrite(&channels, 2, 1, out);
    fwrite(&rate, 4, 1, out);
    fwrite(&byte_rate, 4, 1, out);
    fwrite(&align, 2, 1, out);
    fwrite(&bits, 2, 1, out);
    fwrite("data", 4, 1, out);
    fwrite(&data, 4, 1, out);
    if (fwrite(decoded, sizeof(decoded), 1, out) != 1 || fclose(out) != 0) return 1;
    return 0;
}
