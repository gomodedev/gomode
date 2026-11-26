// G2Lc3Decoder: an LC3 decoder of 10 ms frames at 16 kHz to mono PCM, the format of the Even G2 microphone.
//
// Ported and modified from Google's liblc3 v1.1.3, commit 96a3af0beb5487aca3b98a4b992a539a1f6d80d1
// (Apache-2.0, Copyright 2022 Google LLC): the decoder half of src/lc3.c, bits.c, bwdet.c, spec.c,
// sns.c, tns.c, ltpf.c, mdct.c, plc.c, and fastmath.h, narrowed to 16 kHz frames of 10 ms without
// LC3plus high resolution; see NOTICE in this module. Each step keeps liblc3's single-precision
// operations in its order, so on a host that, like the JVM, never fuses a multiply and an add, both
// decoders give the same samples; G2Lc3DecoderTest holds liblc3's output.
//
// A frame of 20 to 400 bytes decodes to 160 samples. A frame whose bitstream is invalid is
// concealed, as liblc3 conceals it: the last valid spectrum again with random signs, fading after
// three frames. The decoder keeps the history of one stream, so a new stream needs a new decoder.

package com.fghbuild.eveng2

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class G2Lc3Decoder {
    // liblc3's decoder->x: the LTPF history ring of NH + NS samples, whose NS at [xs] are the current
    // frame's, then the MDCT delay line at XD and the last spectrum at XG.
    private val x = FloatArray(NH + NS + ND + NS)
    private var xs = NH

    // lc3_plc_state.
    private var plcSeed = 24607
    private var plcCount = 1
    private var plcAlpha = 1.0f

    // lc3_ltpf_synthesis.
    private var ltpfActive = false
    private var ltpfPitch = 0
    private val ltpfC = FloatArray(2 * LTPF_WIDTH)
    private val ltpfX = FloatArray(LTPF_WIDTH - 1)

    private val bits = Bits()
    private val side = Side()

    // Scratch buffers of one frame.
    private val sns = Sns()
    private val tnsRc = FloatArray(8)
    private val tnsState = FloatArray(8)
    private val fftA = FloatArray(NS)
    private val fftB = FloatArray(NS)
    private val imdct = FloatArray(NS)
    private val ltpfU = FloatArray(LTPF_WIDTH)
    private val ltpfCoefficients = FloatArray(2 * LTPF_WIDTH)
    private val ltpfFadeIn = FloatArray(LTPF_WIDTH - 1)
    private val ltpfPrevious = FloatArray(LTPF_WIDTH - 1)

    /**
     * Decodes the [size] bytes at [offset] of [data], one frame, into [SAMPLES] samples of [pcm] from
     * [pcmOffset]. Returns false when the frame was invalid and these samples conceal it.
     */
    fun decode(
        data: ByteArray,
        offset: Int,
        size: Int,
        pcm: ShortArray,
        pcmOffset: Int,
    ): Boolean {
        require(size in MIN_FRAME_BYTES..MAX_FRAME_BYTES) { "LC3 frame of $size bytes" }
        require(offset >= 0 && offset + size <= data.size && pcmOffset >= 0 && pcmOffset + SAMPLES <= pcm.size)
        val valid = read(data, offset, size)
        synthesize(valid, size)
        for (i in 0 until NS) {
            val v = x[xs + i]
            val s = if (v >= 0) (v + 0.5f).toInt() else (v - 0.5f).toInt()
            pcm[pcmOffset + i] = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        // complete()
        xs = if (xs < NH) xs + NS else 0
        return valid
    }

    // decode(): the side data into [side] and the spectrum into x[xs]; false on an invalid bitstream.
    private fun read(
        data: ByteArray,
        offset: Int,
        size: Int,
    ): Boolean {
        bits.setup(data, offset, size)
        // lc3_bwdet_get_bw: one bit at 16 kHz, and both values are valid.
        side.bw = bits.bits(1)
        // lc3_spec_get_side
        side.nq = (bits.bits(NBITS_NQ) + 1) shl 1
        side.lsbMode = bits.bit() != 0
        side.gIdx = bits.bits(8)
        if (side.nq > NE) return false
        readTns(size)
        side.pitchPresent = bits.bit() != 0
        if (!readSns()) return false
        if (side.pitchPresent) {
            side.ltpfActive = bits.bit() != 0
            side.pitchIndex = bits.bits(9)
        }
        if (!decodeSpectrum(size)) return false
        return bits.check()
    }

    // lc3_tns_get_data: one filter below 32 kHz.
    private fun readTns(size: Int) {
        side.lpcWeighting = size * 8 < 120 * (1 + DT_10M)
        side.rcOrder = bits.bit()
        if (side.rcOrder == 0) return
        side.rcOrder += bits.symbol(G2Lc3Tables.tnsOrderModels, if (side.lpcWeighting) 1 else 0)
        for (i in 0 until side.rcOrder) side.rc[i] = bits.symbol(G2Lc3Tables.tnsCoeffsModels, i) - 8
    }

    // lc3_sns_get_data
    private fun readSns(): Boolean {
        side.lfcb = bits.bits(5)
        side.hfcb = bits.bits(5)
        side.idxA = 0
        side.idxB = 0
        side.lsB = false
        val shapeMsb = bits.bit()
        side.gain = bits.bits(1 + shapeMsb)
        side.lsA = bits.bit() != 0
        var mux = bits.bits(25 - shapeMsb)
        if (shapeMsb == 0) {
            if (mux >= SNS_SIZE_A_LOW * 14) return false
            side.idxA = mux % SNS_SIZE_A_LOW
            mux /= SNS_SIZE_A_LOW
            side.shape = if (mux < 2) 1 else 0
            if (side.shape == 0) {
                side.idxB = (mux - 2) / 2
                side.lsB = (mux - 2) % 2 != 0
            } else {
                side.gain = (side.gain shl 1) + mux % 2
            }
        } else {
            if (mux >= SNS_SIZE_A_HIGH + 1549824) return false
            side.shape = if (mux >= SNS_SIZE_A_HIGH) 3 else 2
            if (side.shape == 2) {
                side.idxA = mux
            } else {
                mux -= SNS_SIZE_A_HIGH
                side.idxA = mux / 2
                side.gain = (side.gain shl 1) + mux % 2
            }
        }
        return true
    }

    // lc3_spec_decode into x[xs].
    private fun decodeSpectrum(size: Int): Boolean {
        val nf = bits.bits(3)
        val nq = side.nq
        var seed = getQuantized(size, nq) ?: return false
        if (side.lsbMode) seed = getLsb(bits.left(), nq, seed) else getResidual(bits.left(), nq)
        // unquantize()
        val g = unquantizeGain(side.gIdx - gainOffset(size))
        for (i in 0 until nq) x[xs + i] = x[xs + i] * g
        for (i in nq until NE) x[xs + i] = 0f
        if (nq > 2 || x[xs] != 0f || x[xs + 1] != 0f || side.gIdx > 0 || nf < 7) fillNoise(nf, seed, g, nq)
        return true
    }

    // get_quantized: the coefficients and the noise seed, or null on an invalid bitstream.
    private fun getQuantized(
        size: Int,
        nq: Int,
    ): Int? {
        // resolve_modes
        val highRate = size > 20 * (1 + SR_16K)
        var seed = 0
        var state = 0
        var i = 0
        for (h in 0 until 2) {
            val lut = ((if (highRate) 2 else 0) + h) * 256
            while (i < min(nq, (NE + 2) shr (1 - h))) {
                val row = (lut + state) * 4
                var u = 0
                var v = 0
                var k = 0
                var shl = 0
                var s = bits.symbol(G2Lc3SpectrumTables.spectrumModels, lookup(row + k))
                if (side.lsbMode && s >= 16) {
                    s = bits.symbol(G2Lc3SpectrumTables.spectrumModels, lookup(row + ++k))
                    shl++
                }
                while (s >= 16 && shl < MAX_SHL) {
                    u = u or (bits.bit() shl shl)
                    v = v or (bits.bit() shl shl)
                    if (k < 3) k++
                    s = bits.symbol(G2Lc3SpectrumTables.spectrumModels, lookup(row + k))
                    shl++
                }
                if (s >= 16) return null
                val a = s % 4
                val b = s / 4
                u = u or (a shl shl)
                v = v or (b shl shl)
                x[xs + i] = (if (u != 0 && bits.bit() != 0) -u else u).toFloat()
                x[xs + i + 1] = (if (v != 0 && bits.bit() != 0) -v else v).toFloat()
                seed = (seed + (u and 0x7fff) * i + (v and 0x7fff) * (i + 1)) and 0xffff
                state = ((state shl 4) + if (k > 1) 12 + k else 1 + (a + b) * (k + 1)) and 0xff
                i += 2
            }
        }
        return seed
    }

    private fun lookup(index: Int): Int = G2Lc3SpectrumTables.spectrumLookup[index].toInt()

    // get_residual, without high resolution.
    private fun getResidual(
        available: Int,
        n: Int,
    ) {
        var left = available
        var i = 0
        while (i < n && left > 0) {
            val j = xs + i++
            if (x[j] == 0f) continue
            if (bits.bit() == 0) {
                x[j] -= if (x[j] < 0) 5f / 16 else 3f / 16
            } else {
                x[j] += if (x[j] > 0) 5f / 16 else 3f / 16
            }
            left--
        }
    }

    // get_lsb: returns the updated noise seed.
    private fun getLsb(
        available: Int,
        nq: Int,
        initial: Int,
    ): Int {
        var left = available
        var seed = initial
        var i = 0
        while (i < nq && left > 0) {
            val a = abs(x[xs + i])
            val b = abs(x[xs + i + 1])
            if (max(a, b) >= 4) {
                for (j in i..i + 1) {
                    val magnitude = if (j == i) a else b
                    if (left-- > 0 && bits.bit() != 0) {
                        if (magnitude != 0f) {
                            x[xs + j] += if (x[xs + j] < 0) -1f else 1f
                            seed = (seed + j) and 0xffff
                        } else if (left-- > 0) {
                            x[xs + j] = if (bits.bit() != 0) -1f else 1f
                            seed = (seed + j) and 0xffff
                        }
                    }
                }
            }
            i += 2
        }
        return seed
    }

    // fill_noise
    private fun fillNoise(
        nf: Int,
        initial: Int,
        g: Float,
        nq: Int,
    ) {
        val bwStop = bandwidthStop()
        val s = g * (8 - nf).toFloat() / 16
        var seed = initial
        var z = 0
        var i = NOISE_START - NOISE_WIDTH
        while (i < min(nq, bwStop)) {
            z = if (x[xs + i] != 0f) 0 else z + 1
            if (z > 2 * NOISE_WIDTH) {
                seed = (13849 + seed * 31821) and 0xffff
                x[xs + i - NOISE_WIDTH] = if (seed and 0x8000 != 0) -s else s
            }
            i++
        }
        while (i < bwStop + NOISE_WIDTH) {
            if (++z > 2 * NOISE_WIDTH) {
                seed = (13849 + seed * 31821) and 0xffff
                x[xs + i - NOISE_WIDTH] = if (seed and 0x8000 != 0) -s else s
            }
            i++
        }
    }

    // lc3_ne of the bandwidth: 80 coefficients for narrow band, 160 for wide band.
    private fun bandwidthStop(): Int = if (side.bw == 0) NE / 2 else NE

    // synthesize(): the spectrum, or a concealment of it, into the current samples, then the LTPF.
    private fun synthesize(
        valid: Boolean,
        size: Int,
    ) {
        if (valid) {
            plcCount = 1
            plcAlpha = 1.0f
            tnsSynthesize()
            snsSynthesize()
            mdctInverse(XG)
        } else {
            plcSynthesize()
            mdctInverse(xs)
        }
        ltpfSynthesize(size, valid && side.pitchPresent)
    }

    // lc3_plc_synthesize from x[XG] into x[xs].
    private fun plcSynthesize() {
        var seed = plcSeed
        plcAlpha *=
            if (plcCount < 4) {
                1.0f
            } else if (plcCount < 8) {
                0.9f
            } else {
                0.85f
            }
        for (i in 0 until NE) {
            seed = (16831 + seed * 12821) and 0xffff
            x[xs + i] = plcAlpha * if (seed and 0x8000 != 0) -x[XG + i] else x[XG + i]
        }
        plcSeed = seed
        plcCount++
    }

    // lc3_tns_synthesize: inverse_filtering of x[xs] with one filter.
    private fun tnsSynthesize() {
        val order = side.rcOrder
        if (order == 0) return
        tnsRc.fill(0f)
        for (i in 0 until order) {
            val m = TNS_Q_INV[abs(side.rc[i])]
            tnsRc[i] = if (side.rc[i] < 0) -m else m
        }
        val s = tnsState
        s.fill(0f)
        for (i in 3 * (1 + DT_10M) until bandwidthStop()) {
            var xi = x[xs + i]
            xi -= s[7] * tnsRc[7]
            for (k in 6 downTo 0) {
                xi -= s[k] * tnsRc[k]
                s[k + 1] = s[k] + tnsRc[k] * xi
            }
            s[0] = xi
            x[xs + i] = xi
        }
    }

    // lc3_sns_synthesize from x[xs] into x[XG].
    private fun snsSynthesize() {
        sns.deenumerate(side)
        // normalize()
        var c2 = 0
        for (c in sns.c) c2 += c * c
        val norm = 1f / sqrt(c2.toFloat())
        for (i in 0 until 16) sns.cn[i] = sns.c[i] * norm
        // unquantize()
        val g = SNS_VQ_GAINS[side.shape][side.gain]
        for (i in 0 until 16) {
            var y = 0f
            for (j in 0 until 16) y += sns.cn[j] * G2Lc3Tables.dct16[i * 16 + j]
            sns.scf[i] = y
        }
        for (i in 0 until 8) sns.scf[i] = G2Lc3Tables.snsLfcb[side.lfcb * 8 + i] + g * sns.scf[i]
        for (i in 8 until 16) sns.scf[i] = G2Lc3Tables.snsHfcb[side.hfcb * 8 + i - 8] + g * sns.scf[i]
        // spectral_shaping(inv = true), whose 64 bands need no merging.
        val scf = sns.bands
        var s0: Float
        var s1 = -sns.scf[0]
        scf[0] = s1
        scf[1] = s1
        for (i in 0 until 15) {
            s0 = s1
            s1 = -sns.scf[i + 1]
            scf[4 * i + 2] = s0 + 0.125f * (s1 - s0)
            scf[4 * i + 3] = s0 + 0.375f * (s1 - s0)
            scf[4 * i + 4] = s0 + 0.625f * (s1 - s0)
            scf[4 * i + 5] = s0 + 0.875f * (s1 - s0)
            if (i == 14) {
                scf[62] = s1 + 0.125f * (s1 - s0)
                scf[63] = s1 + 0.375f * (s1 - s0)
            }
        }
        val limits = G2Lc3Tables.bandLimits
        var i = 0
        for (band in 0 until BANDS) {
            val gain = exp2(-scf[band])
            while (i < limits[band + 1]) {
                x[XG + i] = x[xs + i] * gain
                i++
            }
        }
    }

    // lc3_mdct_inverse of x[from] into x[xs], through the delay line at x[XD].
    private fun mdctInverse(from: Int) {
        val w = G2Lc3Tables.mdctRotation
        val n4 = NS / 2
        // imdct_pre_fft, with real and imaginary parts swapped.
        var x0 = from
        var x1 = from + 2 * n4
        var w0 = 0
        var w1 = n4
        var y0 = 0
        var y1 = n4
        while (x0 < x1) {
            val u0 = x[x0++]
            val u1 = x[--x1]
            val v0 = x[x0++]
            val v1 = x[--x1]
            val uwRe = w[2 * w0]
            val uwIm = w[2 * w0++ + 1]
            val vwRe = w[2 * --w1]
            val vwIm = w[2 * w1 + 1]
            fftA[2 * y0] = -u0 * uwRe - u1 * uwIm
            fftA[2 * y0++ + 1] = -u1 * uwRe + u0 * uwIm
            fftA[2 * --y1] = -v1 * vwRe - v0 * vwIm
            fftA[2 * y1 + 1] = -v0 * vwRe + v1 * vwIm
        }
        val z = fft()
        // imdct_post_fft
        x0 = 0
        x1 = n4
        w0 = 0
        w1 = n4
        y0 = 0
        y1 = 2 * n4
        while (x0 < x1) {
            val uzRe = z[2 * x0]
            val uzIm = z[2 * x0++ + 1]
            val vzRe = z[2 * --x1]
            val vzIm = z[2 * x1 + 1]
            val uwRe = w[2 * w0]
            val uwIm = w[2 * w0++ + 1]
            val vwRe = w[2 * --w1]
            val vwIm = w[2 * w1 + 1]
            imdct[y0++] = uzRe * uwIm - uzIm * uwRe
            imdct[--y1] = uzRe * uwRe + uzIm * uwIm
            imdct[--y1] = vzRe * vwIm - vzIm * vwRe
            imdct[y0++] = vzRe * vwRe + vzIm * vwIm
        }
        imdctWindow()
    }

    // fft() of the NS / 2 points in fftA: one radix-5 stage, then four radix-2 stages; returns the result.
    private fun fft(): FloatArray {
        var from = fftA
        var to = fftB
        fft5(from, to, NS / 2 / 5)
        var n = NS / 2 / 5
        for (twiddles in FFT_TWIDDLES) {
            val t = from
            from = to
            to = t
            n = n shr 1
            fftBf2(twiddles, from, to, n)
        }
        return to
    }

    // fft_5 of n interleaved transforms.
    private fun fft5(
        x: FloatArray,
        y: FloatArray,
        n: Int,
    ) {
        for (i in 0 until n) {
            val x0Re = x[2 * i]
            val x0Im = x[2 * i + 1]
            val x1Re = x[2 * (i + n)]
            val x1Im = x[2 * (i + n) + 1]
            val x2Re = x[2 * (i + 2 * n)]
            val x2Im = x[2 * (i + 2 * n) + 1]
            val x3Re = x[2 * (i + 3 * n)]
            val x3Im = x[2 * (i + 3 * n) + 1]
            val x4Re = x[2 * (i + 4 * n)]
            val x4Im = x[2 * (i + 4 * n) + 1]
            val s14Re = x1Re + x4Re
            val s14Im = x1Im + x4Im
            val d14Re = x1Re - x4Re
            val d14Im = x1Im - x4Im
            val s23Re = x2Re + x3Re
            val s23Im = x2Im + x3Im
            val d23Re = x2Re - x3Re
            val d23Im = x2Im - x3Im
            val o = 10 * i
            y[o] = x0Re + s14Re + s23Re
            y[o + 1] = x0Im + s14Im + s23Im
            y[o + 2] = x0Re + s14Re * COS1 - d14Im * SIN1 + s23Re * COS2 - d23Im * SIN2
            y[o + 3] = x0Im + s14Im * COS1 + d14Re * SIN1 + s23Im * COS2 + d23Re * SIN2
            y[o + 4] = x0Re + s14Re * COS2 - d14Im * SIN2 + s23Re * COS1 + d23Im * SIN1
            y[o + 5] = x0Im + s14Im * COS2 + d14Re * SIN2 + s23Im * COS1 - d23Re * SIN1
            y[o + 6] = x0Re + s14Re * COS2 + d14Im * SIN2 + s23Re * COS1 - d23Im * SIN1
            y[o + 7] = x0Im + s14Im * COS2 - d14Re * SIN2 + s23Im * COS1 + d23Re * SIN1
            y[o + 8] = x0Re + s14Re * COS1 + d14Im * SIN1 + s23Re * COS2 + d23Im * SIN2
            y[o + 9] = x0Im + s14Im * COS1 - d14Re * SIN1 + s23Im * COS2 - d23Re * SIN2
        }
    }

    // fft_bf2 of n interleaved transforms with the twiddles of one stage.
    private fun fftBf2(
        w: FloatArray,
        x: FloatArray,
        y: FloatArray,
        n: Int,
    ) {
        val n2 = w.size / 2
        var x0 = 0
        var x1 = n * n2
        var y0 = 0
        for (i in 0 until n) {
            val y1 = y0 + n2
            for (j in 0 until n2) {
                val wRe = w[2 * j]
                val wIm = w[2 * j + 1]
                val x0Re = x[2 * x0]
                val x0Im = x[2 * x0 + 1]
                val x1Re = x[2 * x1]
                val x1Im = x[2 * x1 + 1]
                y[2 * (y0 + j)] = x0Re + x1Re * wRe - x1Im * wIm
                y[2 * (y0 + j) + 1] = x0Im + x1Im * wRe + x1Re * wIm
                y[2 * (y1 + j)] = x0Re - x1Re * wRe + x1Im * wIm
                y[2 * (y1 + j) + 1] = x0Im - x1Im * wRe - x1Re * wIm
                x0++
                x1++
            }
            y0 += 2 * n2
        }
    }

    // imdct_window: the middle half of the IMDCT in [imdct] and the delay line into x[xs].
    private fun imdctWindow() {
        val win = G2Lc3Tables.mdctWindow
        val n4 = NS shr 1
        var w2 = 0
        var w0 = 3 * n4
        var w1 = w0
        var x0 = XD + ND - n4
        var x1 = x0
        var input = 0
        val y = xs
        var y0 = y + ND - n4
        var y1 = y0
        var y2 = XD + ND
        var y3 = XD
        while (y0 > y) {
            x[--y0] = x[--x0] - imdct[input] * win[w1++]
            x[y1++] = x[x1++] + imdct[input++] * win[--w0]
        }
        while (y1 < y + ND) x[y1++] = x[x1++] + imdct[input++] * win[--w0]
        while (y1 < y + 2 * n4) {
            x[y1++] = imdct[input] * win[--w0]
            x[--y2] = imdct[input++] * win[w2++]
        }
        while (y2 > y3) {
            x[y3++] = imdct[input] * win[--w0]
            x[--y2] = imdct[input++] * win[w2++]
        }
    }

    // lc3_ltpf_synthesize of x[xs] over the history ring at x[0]; [pitch] when the frame has LTPF data.
    private fun ltpfSynthesize(
        size: Int,
        pitch: Boolean,
    ) {
        val p = if (pitch) side.pitchIndex else 0
        var lag =
            if (p >= 440) {
                (p - 283) shl 2
            } else if (p >= 380) {
                (((p shr 1) - 63) shl 2) + ((p and 1) shl 1)
            } else {
                (((p shr 2) + 32) shl 2) + (p and 3)
            }
        lag = (lag * NS + 64) / 128
        val nbits = size * 8 * (1 + DT_10M) / (1 + DT_10M)
        val gIdx = max(nbits / 80, 3 + SR_16K) - (3 + SR_16K)
        val active = pitch && side.ltpfActive && gIdx < 4
        val c = ltpfCoefficients
        for (i in 0 until LTPF_WIDTH) {
            val g = if (active) 0.4f - 0.05f * gIdx else 0f
            c[i] = g * G2Lc3Tables.ltpfDenominators[(lag and 3) * LTPF_WIDTH + (LTPF_WIDTH - 1) - i]
            c[LTPF_WIDTH + i] =
                0.85f * g * G2Lc3Tables.ltpfNumerators[min(gIdx, 3) * LTPF_WIDTH + (LTPF_WIDTH - 1) - i]
        }
        // Transition handling
        val nt = NS / (1 + DT_10M)
        ltpfX.copyInto(ltpfPrevious)
        x.copyInto(ltpfX, 0, xs + NS - (LTPF_WIDTH - 1), xs + NS)
        if (active) x.copyInto(ltpfFadeIn, 0, xs + nt - (LTPF_WIDTH - 1), xs + nt)
        if (!ltpfActive && active) {
            ltpfFilter(lag / 4, ltpfPrevious, 0, xs, nt, c, 1)
        } else if (ltpfActive && !active) {
            ltpfFilter(ltpfPitch / 4, ltpfPrevious, 0, xs, nt, ltpfC, -1)
        } else if (ltpfActive && active && ltpfPitch == lag) {
            ltpfFilter(lag / 4, ltpfPrevious, 0, xs, nt, c, 0)
        } else if (ltpfActive && active) {
            ltpfFilter(ltpfPitch / 4, ltpfPrevious, 0, xs, nt, ltpfC, -1)
            ltpfFilter(lag / 4, x, (if (xs <= 0) xs + RING else xs) - (LTPF_WIDTH - 1), xs, nt, c, 1)
        }
        // Remainder
        if (active && NS > nt) ltpfFilter(lag / 4, ltpfFadeIn, 0, xs + nt, NS - nt, c, 0)
        ltpfActive = active
        ltpfPitch = lag
        c.copyInto(ltpfC)
    }

    // synthesize_template of width LTPF_WIDTH: filters the [n] samples of x[at] over the ring, after the
    // previous inputs at [previous][from]; [fade] is -1 out, 1 in, 0 none.
    private fun ltpfFilter(
        pitchLag: Int,
        previous: FloatArray,
        from: Int,
        at: Int,
        n: Int,
        c: FloatArray,
        fade: Int,
    ) {
        val w = LTPF_WIDTH
        var g = if (fade <= 0) 1f else 0f
        val gIncr = ((if (fade > 0) 1 else 0) - (if (fade < 0) 1 else 0)).toFloat() / n
        val u = ltpfU
        val lag = pitchLag + (w shr 1)
        var y = if (at < lag) at + (RING - lag) else at - lag
        val yEnd = RING - 1
        var x0 = from
        for (j in 0 until w - 1) {
            u[j] = 0f
            val yi = x[y]
            val xi = previous[x0++]
            y = if (y < yEnd) y + 1 else 0
            for (k in 0..j) u[j - k] -= yi * c[k]
            for (k in 0..j) u[j - k] += xi * c[w + k]
        }
        u[w - 1] = 0f
        var xp = at
        var i = 0
        while (i < n) {
            for (j in 0 until w) {
                val yi = x[y]
                val xi = x[xp]
                y = if (y < yEnd) y + 1 else 0
                for (k in 0 until w) u[(j + (w - 1) - k) % w] -= yi * c[k]
                for (k in 0 until w) u[(j + (w - 1) - k) % w] += xi * c[w + k]
                x[xp++] = xi - g * u[j]
                u[j] = 0f
                g += gIncr
            }
            i += w
        }
    }

    // resolve_gain_offset at 16 kHz.
    private fun gainOffset(size: Int): Int = min(255, 105 + 5 * (1 + SR_16K) + min(size * 8 / (10 * (1 + SR_16K)), 115))

    // unquantize_gain
    private fun unquantizeGain(index: Int): Float {
        var gInt = index
        var g = 1f
        while (gInt < 0) {
            gInt += 28
            g *= 0.1f
        }
        while (gInt >= 28) {
            gInt -= 28
            g *= 10f
        }
        return g * G2Lc3Tables.gains[gInt]
    }

    // The bitstream data of one frame.
    private class Side {
        var bw = 0
        var nq = 0
        var lsbMode = false
        var gIdx = 0
        var lpcWeighting = false
        var rcOrder = 0
        val rc = IntArray(8)
        var pitchPresent = false
        var ltpfActive = false
        var pitchIndex = 0
        var lfcb = 0
        var hfcb = 0
        var shape = 0
        var gain = 0
        var idxA = 0
        var idxB = 0
        var lsA = false
        var lsB = false
    }

    // The scale factors of one frame.
    private class Sns {
        val c = IntArray(16)
        val cn = FloatArray(16)
        val scf = FloatArray(16)
        val bands = FloatArray(BANDS)

        // deenumerate()
        fun deenumerate(side: Side) {
            val pulsesA = intArrayOf(10, 10, 8, 6)[side.shape]
            deenumerate(side.idxA, side.lsA, pulsesA, 0, if (side.shape < 2) 10 else 16)
            if (side.shape == 0) {
                deenumerate(side.idxB, side.lsB, 1, 10, 6)
            } else if (side.shape == 1) {
                c.fill(0, 10, 16)
            }
        }

        // deenum_mvpq
        private fun deenumerate(
            index: Int,
            sign: Boolean,
            pulses: Int,
            from: Int,
            n: Int,
        ) {
            var idx = index
            var ls = sign
            var npulses = pulses
            var out = from
            val offsets = G2Lc3Tables.mpvqOffsets
            var i = n - 1
            while (i >= 0 && idx != 0) {
                var ci = 0
                while (idx < offsets[i * 11 + npulses - ci]) ci++
                idx -= offsets[i * 11 + npulses - ci]
                c[out++] = if (ls) -ci else ci
                npulses -= ci
                if (ci > 0) {
                    ls = idx and 1 != 0
                    idx = idx shr 1
                }
                i--
            }
            if (i-- >= 0) c[out++] = if (ls) -npulses else npulses
            while (i-- >= 0) c[out++] = 0
        }
    }

    // lc3_bits_t in read mode: the arithmetic coder reads forward and plain bits backward.
    private class Bits {
        private lateinit var data: ByteArray
        private var start = 0
        private var end = 0
        private var fw = 0
        private var bw = 0
        private var low = 0
        private var range = 0
        private var error = false
        private var accu = 0
        private var accuN = 0
        private var accuOver = 0

        // lc3_setup_bits
        fun setup(
            data: ByteArray,
            offset: Int,
            size: Int,
        ) {
            this.data = data
            start = offset
            end = offset + size
            fw = offset
            bw = end
            range = 0xffffff
            error = false
            accu = 0
            accuN = ACCU_BITS
            accuOver = 0
            low = acGet() shl 16
            low = low or (acGet() shl 8)
            low = low or acGet()
            accuLoad()
        }

        // get_bits_left in read mode, where the pending bits of the coder are 26 - log2(range) - 1.
        private fun rawLeft(): Int {
            val n = (bw + ACCU_BITS / 8) - (fw - AC_BITS / 8)
            return 8 * n - (accuN + accuOver + 26 - rangeBits())
        }

        private fun rangeBits(): Int = Int.SIZE_BITS - range.countLeadingZeroBits()

        // lc3_get_bits_left
        fun left(): Int = max(rawLeft(), 0)

        // lc3_check_bits
        fun check(): Boolean = rawLeft() >= 0 && !error

        fun bit(): Int = bits(1)

        // lc3_get_bits of 1 to 25 bits.
        fun bits(n: Int): Int {
            if (accuN + n <= ACCU_BITS) {
                val v = (accu ushr accuN) and ((1 shl n) - 1)
                accuN += n
                return v
            }
            accuLoad()
            val n1 = min(ACCU_BITS - accuN, n)
            var v = (accu ushr accuN) and ((1 shl n1) - 1)
            accuN += n1
            val n2 = n - n1
            if (n2 != 0) {
                accuLoad()
                v = v or (((accu ushr accuN) and ((1 shl n2) - 1)) shl n1)
                accuN += n2
            }
            return v
        }

        // lc3_get_symbol of the model at [model] in [models], 17 low, range pairs each.
        fun symbol(
            models: IntArray,
            model: Int,
        ): Int {
            val m = model * 34
            val r = (range ushr 10) and 0xffff
            error = error or (low >= (r shl 10))
            if (error) low = 0
            var s = 16
            if (low < r * models[m + 2 * s]) {
                s = s shr 1
                s -= if (low < r * models[m + 2 * s]) 4 else -4
                s -= if (low < r * models[m + 2 * s]) 2 else -2
                s -= if (low < r * models[m + 2 * s]) 1 else -1
                if (low < r * models[m + 2 * s]) s--
            }
            low -= r * models[m + 2 * s]
            range = r * models[m + 2 * s + 1]
            // lc3_ac_read_renorm
            while (range < 0x10000) {
                low = ((low shl 8) or acGet()) and 0xffffff
                range = range shl 8
            }
            return s
        }

        private fun acGet(): Int = if (fw < end) data[fw++].toInt() and 0xff else 0

        private fun accuLoad() {
            val nbytes = min(accuN shr 3, bw - start)
            accuN -= 8 * nbytes
            repeat(nbytes) {
                accu = accu ushr 8
                accu = accu or ((data[--bw].toInt() and 0xff) shl (ACCU_BITS - 8))
            }
            if (accuN >= 8) {
                accuOver = min(accuOver + accuN, ACCU_BITS)
                accu = accu ushr accuN
                accuN = 0
            }
        }
    }

    companion object {
        /** Samples of one frame: 10 ms at 16 kHz. */
        const val SAMPLES = 160

        const val MIN_FRAME_BYTES = 20
        const val MAX_FRAME_BYTES = 400

        // lc3_ns, lc3_ne, lc3_nd, and lc3_nh at 10 ms and 16 kHz, and the size of the LTPF history ring.
        private const val NS = SAMPLES
        private const val NE = SAMPLES
        private const val ND = 100
        private const val NH = 320
        private const val RING = NH + NS
        private const val XD = NH + NS
        private const val XG = XD + ND

        // enum lc3_dt and enum lc3_srate.
        private const val DT_10M = 3
        private const val SR_16K = 1

        private const val BANDS = 64
        private const val NBITS_NQ = 7
        private const val MAX_SHL = 14

        // fill_noise: the first coefficient, 6 * (1 + dt), and the width of its zero runs.
        private const val NOISE_START = 24
        private const val NOISE_WIDTH = 3

        private const val SNS_SIZE_A_LOW = 2390004
        private const val SNS_SIZE_A_HIGH = 15158272

        // The width of the LTPF filter at 16 kHz.
        private const val LTPF_WIDTH = 4

        private const val ACCU_BITS = 32
        private const val AC_BITS = 24

        // lc3_sns_vq_gains
        private val SNS_VQ_GAINS =
            arrayOf(
                floatArrayOf(8915f / 4096, 12054f / 4096),
                floatArrayOf(6245f / 4096, 15043f / 4096, 17861f / 4096, 21014f / 4096),
                floatArrayOf(7099f / 4096, 9132f / 4096, 11253f / 4096, 14808f / 4096),
                floatArrayOf(
                    4336f / 4096,
                    5067f / 4096,
                    5895f / 4096,
                    8149f / 4096,
                    10235f / 4096,
                    12825f / 4096,
                    16868f / 4096,
                    19882f / 4096,
                ),
            )

        // unquantize_rc: sin(i Pi / 17) in Q15.
        private val TNS_Q_INV =
            intArrayOf(0x0000, 0x1785, 0x2e3d, 0x4362, 0x563c, 0x6625, 0x7295, 0x7b1d, 0x7f74)
                .map { it / 32768f }
                .toFloatArray()

        private val FFT_TWIDDLES =
            listOf(
                G2Lc3Tables.fftTwiddles10,
                G2Lc3Tables.fftTwiddles20,
                G2Lc3Tables.fftTwiddles40,
                G2Lc3Tables.fftTwiddles80,
            )

        // fft_5: cos and sin of -2 Pi / 5 and -4 Pi / 5, double literals that C rounds to float.
        private val COS1 = 0.3090169944.toFloat()
        private val COS2 = (-0.8090169944).toFloat()
        private val SIN1 = (-0.9510565163).toFloat()
        private val SIN2 = (-0.5877852523).toFloat()

        // lc3_exp2f: 2^(i / 8) and the polynomial of 2^x for |x| < 1/16.
        private val EXP2_E =
            doubleArrayOf(
                1.00000000e+00,
                1.09050773e+00,
                1.18920712e+00,
                1.29683955e+00,
                1.41421356e+00,
                1.54221083e+00,
                1.68179283e+00,
                1.83400809e+00,
            ).map { it.toFloat() }.toFloatArray()
        private val EXP2_P =
            doubleArrayOf(1.00448128e-02, 5.54563260e-02, 2.40228756e-01, 6.93147140e-01)
                .map { it.toFloat() }
                .toFloatArray()

        // lc3_exp2f: 2^x for x in -100..100, within a relative 4e-7.
        private fun exp2(value: Float): Float {
            val split = value + SPLIT
            val k = split.toRawBits()
            val x = value - (split - SPLIT)
            var y = EXP2_P[0] * x
            y = (y + EXP2_P[1]) * x
            y = (y + EXP2_P[2]) * x
            y = (y + EXP2_P[3]) * x
            y = (y + 1f) * EXP2_E[k and 7]
            return Float.fromBits(y.toRawBits() + ((k shr 3) shl 23))
        }

        // 0x1.8p20f
        private const val SPLIT = 1572864f
    }
}
