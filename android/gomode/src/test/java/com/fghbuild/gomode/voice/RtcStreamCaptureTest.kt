// Tests the stream sent in place of the WebRTC capture: resampling, its jitter buffer, and its latency bound.
package com.fghbuild.gomode.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class RtcStreamCaptureTest {
    @Test
    fun `stream replaces the capture at the processing rate once its jitter buffer fills`() {
        val capture = RtcStreamCapture()
        val processing = RtcCaptureProcessing({ capture }, MicrophoneDucking { false })
        processing.initialize(48_000, 1)
        val tone = ShortArray(1_600) { (10_000 * sin(2 * PI * 500 * it / 16_000)).toInt().toShort() }
        capture.write(pcm(tone), 480 * 2)
        assertTrue("30 ms must not start the stream", process(processing, 480).all { it == 0f })
        capture.write(pcm(tone.copyOfRange(480, tone.size)), (tone.size - 480) * 2)
        val sent = (1..5).flatMap { process(processing, 480).toList() }.drop(6)
        assertEquals("500 Hz over 50 ms", 50.0, crossings(sent).toDouble(), 2.0)
        assertTrue(sent.maxOf { abs(it) } in 9_500f..10_000f)
    }

    @Test
    fun `stream that runs dry sends silence until its jitter buffer refills`() {
        val capture = RtcStreamCapture()
        val processing = RtcCaptureProcessing({ capture }, MicrophoneDucking { false })
        processing.initialize(16_000, 1)
        capture.write(pcm(ShortArray(800) { 5_000 }), 1_600)
        val drained = (1..6).flatMap { process(processing, 160).toList() }
        assertTrue(drained.subList(10, 800).all { it == 5_000f })
        assertTrue(drained.takeLast(100).all { it == 0f })
        capture.write(pcm(ShortArray(320) { 5_000 }), 640)
        assertTrue("20 ms must not restart the stream", process(processing, 160).all { it == 0f })
        capture.write(pcm(ShortArray(320) { 5_000 }), 640)
        assertEquals(5_000f, process(processing, 160).last())
    }

    @Test
    fun `stream faster than the capture clock keeps only its newest 160 ms`() {
        val capture = RtcStreamCapture()
        val processing = RtcCaptureProcessing({ capture }, MicrophoneDucking { false })
        processing.initialize(16_000, 1)
        capture.write(pcm(ShortArray(16_000) { it.toShort() }), 32_000)
        val sent = process(processing, 160)
        assertTrue(sent.drop(2).all { it >= 16_000 - 2_560 })
    }

    @Test
    fun `phone microphone capture only gets ducked`() {
        val processing = RtcCaptureProcessing({ null }, MicrophoneDucking { false })
        processing.initialize(48_000, 1)
        assertTrue(process(processing, 480).all { it == 1_234f })
    }

    private fun pcm(samples: ShortArray): ByteArray =
        ByteBuffer
            .allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach(::putShort) }
            .array()

    // Fills the buffer with phone microphone audio before processing, as the audio device module does.
    private fun process(
        processing: RtcCaptureProcessing,
        frames: Int,
    ): FloatArray {
        val buffer = ByteBuffer.allocateDirect(frames * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        for (offset in 0 until buffer.capacity() step Float.SIZE_BYTES) buffer.putFloat(offset, 1_234f)
        processing.process(frames / 160, frames, buffer)
        return FloatArray(frames) { buffer.getFloat(it * Float.SIZE_BYTES) }
    }

    private fun crossings(samples: List<Float>): Int = samples.zipWithNext().count { (a, b) -> (a < 0) != (b < 0) }
}
