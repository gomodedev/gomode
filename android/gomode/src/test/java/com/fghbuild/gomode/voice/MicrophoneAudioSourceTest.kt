// Tests bounded PCM delivery during recognition stalls, utterance restarts, and voice source switches.
package com.fghbuild.gomode.voice

import android.media.AudioRecord
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAudioRecord
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class MicrophoneAudioSourceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `capture failure between recognizers must surface when recognition restarts`() =
        runTest {
            Dispatchers.setMain(Dispatchers.Unconfined)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val source = MicrophoneAudioSource(scope, VoiceAudioInput())
            val started = CompletableDeferred<Unit>()
            val failed = CompletableDeferred<String>()
            val failRead = AtomicBoolean()
            val recordSource =
                object : ShadowAudioRecord.AudioRecordSource {
                    override fun readInByteArray(
                        buffer: ByteArray,
                        offset: Int,
                        size: Int,
                        blocking: Boolean,
                    ): Int {
                        started.complete(Unit)
                        return if (failRead.get()) AudioRecord.ERROR_DEAD_OBJECT else 0
                    }
                }
            ShadowAudioRecord.setSourceProvider { recordSource }
            try {
                source.open { failed.complete(it) }
                withContext(Dispatchers.Default) { withTimeout(5_000) { started.await() } }
                source.finishUtterance()
                failRead.set(true)
                withContext(Dispatchers.Default) { withTimeout(5_000) { failed.await() } }
                assertThrows(IllegalStateException::class.java) {
                    source.open { error("Unexpected restarted capture: $it") }
                }
            } finally {
                source.stop()
                scope.cancel()
                ShadowAudioRecord.clearSource()
                Dispatchers.resetMain()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `recognizer audio follows the selected stream and returns to the microphone when it ends`() =
        runTest {
            Dispatchers.setMain(Dispatchers.Unconfined)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val input = VoiceAudioInput()
            val source = MicrophoneAudioSource(scope, input)
            val opened = CompletableDeferred<Pair<PcmSink, () -> Unit>>()
            val closed = CompletableDeferred<Unit>()
            val stream =
                object : VoiceAudioSource.Stream {
                    override fun open(
                        sink: PcmSink,
                        onEnd: () -> Unit,
                    ): AutoCloseable {
                        opened.complete(sink to onEnd)
                        return AutoCloseable { closed.complete(Unit) }
                    }
                }
            val microphone =
                object : ShadowAudioRecord.AudioRecordSource {
                    override fun readInByteArray(
                        buffer: ByteArray,
                        offset: Int,
                        size: Int,
                        blocking: Boolean,
                    ): Int {
                        buffer.fill(7, offset, offset + size)
                        return size
                    }
                }
            ShadowAudioRecord.setSourceProvider { microphone }
            input.select(stream)
            // Robolectric's pipes carry no bytes, so the meter, which sees the delivered PCM, observes each source.
            try {
                source.open { error("Unexpected capture failure: $it") }
                val (sink, end) = withContext(Dispatchers.Default) { withTimeout(5_000) { opened.await() } }
                val half = ByteArray(1_600) { if (it % 2 == 0) 0 else 0x40 }
                sink.write(half, half.size)
                assertEquals(0.5f, source.rms.value, 1e-4f)
                end()
                withContext(Dispatchers.Default) { withTimeout(5_000) { closed.await() } }
                assertEquals(VoiceAudioSource.PhoneMicrophone, input.source.value)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { source.rms.first { abs(it - 0x0707 / 32_768f) < 1e-4f } }
                }
            } finally {
                source.stop()
                scope.cancel()
                ShadowAudioRecord.clearSource()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `slow recognition retains recent whole samples and resumes after backpressure`() {
        val buffer = PcmBuffer(8)
        buffer.append(byteArrayOf(1, 2, 3, 4, 5, 6), 6)
        buffer.drain { _, _, _ -> throw ErrnoException("write", OsConstants.EAGAIN) }
        buffer.append(byteArrayOf(7, 8, 9, 10, 11, 12), 6)
        val delivered = mutableListOf<Byte>()
        buffer.drain { bytes, offset, _ ->
            delivered.addAll(bytes.copyOfRange(offset, offset + 4).toList())
            4
        }
        buffer.drain { bytes, offset, length ->
            delivered.addAll(bytes.copyOfRange(offset, offset + length).toList())
            length
        }
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12), delivered.toByteArray())
        buffer.drain { _, _, _ -> error("already drained") }
    }

    @Test
    fun `utterance boundary clears old PCM and oversized frames retain only recent audio`() {
        val buffer = PcmBuffer(4)
        buffer.append(byteArrayOf(1, 2), 2)
        buffer.clear()
        buffer.drain { _, _, _ -> error("old utterance leaked") }
        buffer.append(byteArrayOf(3, 4, 5, 6, 7, 8), 6)
        buffer.drain { bytes, offset, length ->
            assertEquals(4, length)
            assertArrayEquals(byteArrayOf(5, 6, 7, 8), bytes.copyOfRange(offset, offset + length))
            length
        }
    }
}
