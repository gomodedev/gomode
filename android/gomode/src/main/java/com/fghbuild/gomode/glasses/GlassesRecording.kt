// Records a few seconds of a glasses microphone to a WAV file, so the owner can hear whether it is intelligible.
package com.fghbuild.gomode.glasses

import com.fghbuild.gomode.voice.VOICE_AUDIO_RATE_HZ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The length of one recording. */
internal const val GLASSES_RECORDING_SECONDS = 5

// How long past the recording's length the microphone may take to start or may pause.
private const val RECORDING_SLACK_MS = 3_000L

/**
 * Writes [GLASSES_RECORDING_SECONDS] of [microphone] to [file] as a mono 16-bit WAV at
 * [VOICE_AUDIO_RATE_HZ] Hz, replacing it, and returns the seconds written: fewer when the audio
 * does not fill it within [RECORDING_SLACK_MS] past its length.
 */
internal suspend fun recordGlassesMicrophone(
    microphone: Flow<ShortArray>,
    file: File,
): Double {
    val samples = ShortArray(GLASSES_RECORDING_SECONDS * VOICE_AUDIO_RATE_HZ)
    var count = 0
    withTimeoutOrNull(GLASSES_RECORDING_SECONDS * 1_000L + RECORDING_SLACK_MS) {
        microphone.first { pcm ->
            val taken = minOf(pcm.size, samples.size - count)
            pcm.copyInto(samples, count, 0, taken)
            count += taken
            count == samples.size
        }
    }
    withContext(Dispatchers.IO) { file.writeBytes(wav(samples, count)) }
    return count.toDouble() / VOICE_AUDIO_RATE_HZ
}

// The first [count] of [samples] in a RIFF WAVE file of PCM.
private fun wav(
    samples: ShortArray,
    count: Int,
): ByteArray {
    val data = count * Short.SIZE_BYTES
    val out = ByteBuffer.allocate(WAV_HEADER_BYTES + data).order(ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray()).putInt(WAV_HEADER_BYTES - 8 + data).put("WAVE".toByteArray())
    out
        .put("fmt ".toByteArray())
        .putInt(16)
        .putShort(1)
        .putShort(1)
    out.putInt(VOICE_AUDIO_RATE_HZ).putInt(VOICE_AUDIO_RATE_HZ * Short.SIZE_BYTES)
    out.putShort(Short.SIZE_BYTES.toShort()).putShort(Short.SIZE_BITS.toShort())
    out.put("data".toByteArray()).putInt(data)
    out.asShortBuffer().put(samples, 0, count)
    return out.array()
}

private const val WAV_HEADER_BYTES = 44
