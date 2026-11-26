// G2Lc3Decoder against liblc3's decoding of LC3 frames in the Even G2 microphone's format.
//
// lc3/g2-microphone.hex holds 200 frames of 40 bytes, 10 ms each at 16 kHz, one in hex per line, with
// one invalid frame; lc3/g2-microphone.wav holds the samples that liblc3 v1.1.3 decodes from them. The
// C program src/test/c/g2_lc3_fixture.c wrote both; its header gives the build. The frames are
// liblc3's encoding of a synthetic speech-like signal, not the glasses' own: the test proves the port
// bit-exact against liblc3, and only the owner's recording from a pair proves the glasses' frames
// decode to intelligible speech.

package com.fghbuild.eveng2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The fixture's frames, one after the other, and liblc3's samples of them. */
internal object G2Lc3Fixture {
    const val FRAMES = 200
    const val FRAME_BYTES = 40

    // BAD_FRAME of g2_lc3_fixture.c: all 0xFF bytes, which the decoder conceals.
    const val BAD_FRAME = 123

    val frames: ByteArray = hex(resource("g2-microphone.hex").decodeToString().lines().joinToString(""))

    val pcm: ShortArray =
        ShortArray(FRAMES * G2Lc3Decoder.SAMPLES).also {
            val wav = resource("g2-microphone.wav")
            // The data chunk follows the 44-byte header of a PCM WAV.
            check(wav.decodeToString(36, 40) == "data" && wav.size == 44 + 2 * it.size)
            ByteBuffer
                .wrap(wav, 44, 2 * it.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(it)
        }

    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResource("/lc3/$name")).readBytes()
}

class G2Lc3DecoderTest {
    @Test
    fun decodesLiblc3OutputExactly() {
        val frames = G2Lc3Fixture.frames
        assertEquals(G2Lc3Fixture.FRAMES * G2Lc3Fixture.FRAME_BYTES, frames.size)

        val decoder = G2Lc3Decoder()
        val pcm = ShortArray(G2Lc3Fixture.pcm.size)
        val concealed =
            (0 until G2Lc3Fixture.FRAMES).filterNot {
                val offset = it * G2Lc3Fixture.FRAME_BYTES
                decoder.decode(frames, offset, G2Lc3Fixture.FRAME_BYTES, pcm, it * G2Lc3Decoder.SAMPLES)
            }

        assertEquals(listOf(G2Lc3Fixture.BAD_FRAME), concealed)
        assertArrayEquals(G2Lc3Fixture.pcm, pcm)
    }
}
