// Tests that voice readers follow the selected source, and that a stream that ends returns them to the phone.
package com.fghbuild.gomode.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VoiceAudioSourceTest {
    @Test
    fun `reader switches streams and returns to the phone microphone when its stream ends`() =
        runTest {
            val input = VoiceAudioInput()
            val first = FakeStream()
            val second = FakeStream()
            val heard = mutableListOf<Byte>()
            val phone = mutableListOf<VoiceAudioSource>()
            input.select(first)
            follow(input, phone) { heard.add(it) }
            first.write(1)
            input.select(second)
            runCurrent()
            assertEquals(listOf(true, false), listOf(first.closed, second.closed))
            second.write(2)
            second.end()
            runCurrent()
            assertEquals(VoiceAudioSource.PhoneMicrophone, input.source.value)
            assertTrue(second.closed)
            assertEquals(listOf<Byte>(1, 2), heard)
            assertEquals(listOf<VoiceAudioSource>(VoiceAudioSource.PhoneMicrophone), phone)
        }

    @Test
    fun `stream that ends after another selection keeps that selection`() =
        runTest {
            val input = VoiceAudioInput()
            val ended = FakeStream()
            val selected = FakeStream()
            input.select(ended)
            follow(input, mutableListOf()) {}
            // The reader of the ended stream has not been cancelled yet when it observes the end.
            input.select(selected)
            ended.end()
            runCurrent()
            assertEquals(selected, input.source.value)
            assertTrue(ended.closed)
            assertEquals(1, selected.opens)
        }

    // Collects the input as both voice readers do until the test ends; the phone microphone needs no stream.
    private fun TestScope.follow(
        input: VoiceAudioInput,
        phone: MutableList<VoiceAudioSource>,
        heard: (Byte) -> Unit,
    ) {
        val reader =
            backgroundScope.launch {
                input.source.collectLatest { source ->
                    when (source) {
                        VoiceAudioSource.PhoneMicrophone -> phone.add(source)
                        is VoiceAudioSource.Stream -> input.read(source) { pcm, _ -> heard(pcm[0]) }
                    }
                }
            }
        runCurrent()
        check(reader.isActive)
    }

    private class FakeStream : VoiceAudioSource.Stream {
        private var sink: PcmSink? = null
        private var onEnd: () -> Unit = {}
        var opens = 0
        var closed = false

        override fun open(
            sink: PcmSink,
            onEnd: () -> Unit,
        ): AutoCloseable {
            opens++
            this.sink = sink
            this.onEnd = onEnd
            return AutoCloseable { closed = true }
        }

        fun write(marker: Byte) {
            checkNotNull(sink).write(byteArrayOf(marker, 0), 2)
        }

        fun end() = onEnd()
    }
}
