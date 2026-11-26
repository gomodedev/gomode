// Recordings of a glasses microphone: a WAV file of the requested length, or of what came before the audio stopped.
package com.fghbuild.gomode.glasses

import com.fghbuild.gomode.voice.VOICE_AUDIO_RATE_HZ
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlassesRecordingTest {
    @get:Rule val folder = TemporaryFolder()

    // Packets of 800 samples, each holding its index.
    private fun packet(index: Int) = ShortArray(PACKET) { index.toShort() }

    @Test
    fun aRecordingIsAPcmWaveFileOfItsLength() =
        runTest {
            val file = folder.newFile()
            val seconds = recordGlassesMicrophone(flow { for (i in 0 until 1_000) emit(packet(i)) }, file)
            assertEquals(GLASSES_RECORDING_SECONDS.toDouble(), seconds, 0.0)

            val samples = GLASSES_RECORDING_SECONDS * VOICE_AUDIO_RATE_HZ
            val bytes = file.readBytes()
            val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(44 + 2 * samples, bytes.size)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals(36 + 2 * samples, wav.getInt(4))
            assertEquals("WAVEfmt ", String(bytes, 8, 8))
            assertEquals(16, wav.getInt(16))
            // PCM, mono, 16 kHz, 32,000 bytes per second, 2 bytes per sample of 16 bits.
            assertEquals(listOf<Short>(1, 1), listOf(wav.getShort(20), wav.getShort(22)))
            assertEquals(listOf(16_000, 32_000), listOf(wav.getInt(24), wav.getInt(28)))
            assertEquals(listOf<Short>(2, 16), listOf(wav.getShort(32), wav.getShort(34)))
            assertEquals("data", String(bytes, 36, 4))
            assertEquals(2 * samples, wav.getInt(40))
            val pcm = ShortArray(samples)
            wav.position(44)
            wav
                .slice()
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(pcm)
            assertArrayEquals(ShortArray(samples) { (it / PACKET).toShort() }, pcm)
        }

    @Test
    fun audioThatStopsEndsTheRecordingWithWhatCame() =
        runTest {
            val file = folder.newFile()
            val stalled =
                flow {
                    repeat(20) { emit(packet(it)) }
                    awaitCancellation()
                }
            assertEquals(1.0, recordGlassesMicrophone(stalled, file), 0.0)
            assertEquals(44 + 2 * VOICE_AUDIO_RATE_HZ, file.length().toInt())
        }

    private companion object {
        const val PACKET = 800
    }
}
