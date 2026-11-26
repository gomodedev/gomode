// A generated voice audio stream: real-time 10 ms frames of 16 kHz PCM, silent until a test sounds its tone.
package com.fghbuild.gomode.voice

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

// Paces frames as glasses' microphones deliver them, so the readers' buffering runs as in production.
internal class SyntheticToneStream(
    private val hz: Int,
) : VoiceAudioSource.Stream {
    @Volatile
    var sounding = false
    val opens = AtomicInteger()
    val closes = AtomicInteger()

    @Volatile
    private var onEnd: () -> Unit = { error("The stream was never opened") }

    override fun open(
        sink: PcmSink,
        onEnd: () -> Unit,
    ): AutoCloseable {
        this.onEnd = onEnd
        opens.incrementAndGet()
        val running = AtomicBoolean(true)
        val frames =
            thread(name = "synthetic-tone-$hz") {
                val frame = ByteArray(VOICE_AUDIO_RATE_HZ / 100 * 2)
                var sample = 0L
                var deadline = SystemClock.elapsedRealtime()
                while (running.get()) {
                    for (offset in frame.indices step 2) {
                        val value =
                            if (sounding) {
                                (
                                    12_000 *
                                        sin(
                                            2 * PI * hz * sample / VOICE_AUDIO_RATE_HZ,
                                        )
                                ).toInt()
                            } else {
                                0
                            }
                        frame[offset] = value.toByte()
                        frame[offset + 1] = (value shr 8).toByte()
                        sample++
                    }
                    sink.write(frame, frame.size)
                    deadline += 10
                    val wait = deadline - SystemClock.elapsedRealtime()
                    if (wait > 0) Thread.sleep(wait)
                }
            }
        return AutoCloseable {
            running.set(false)
            frames.join()
            closes.incrementAndGet()
        }
    }

    /** end ends the stream on its own, as a stream whose device disconnects does. */
    fun end() = onEnd()
}
