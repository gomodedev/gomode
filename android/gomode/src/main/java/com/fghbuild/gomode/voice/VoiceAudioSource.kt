// The PCM source voice hears: the phone microphone by default, or a stream such as glasses' microphones.
//
// The process holds one selected source in VoiceAudioInput. Device recognition (MicrophoneAudioSource)
// and the WebRTC microphone track (RtcStreamCapture) both follow it, so a selection made during a
// conversation replaces the source under a running recognizer or peer connection.
//
// Each reader captures the phone microphone with the platform's echo cancellation: device recognition
// opens its own AudioRecord, and WebRTC keeps its audio device module with hardware AEC and NS. A Stream
// supplies 16 kHz mono signed 16-bit little-endian PCM. A reader opens it while it needs audio and closes
// it when it stops, mutes, or switches away, before it opens the stream again. A closing session's handle
// can outlive the next session's open, so a stream serves each open on its own. A stream that ends on its
// own reports it once, and the input then selects the phone microphone again. Echo cancellation does not
// apply to a stream; ducking during assistant speech does.
package com.fghbuild.gomode.voice

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "GoModeVoiceAudio"

internal const val VOICE_AUDIO_RATE_HZ = 16_000

/**
 * PcmSink receives [count] bytes of 16 kHz mono s16le PCM from [pcm], in whole samples, on the source's thread.
 * The sink may change [pcm] in place until write returns, and must not keep it afterwards.
 */
internal fun interface PcmSink {
    fun write(
        pcm: ByteArray,
        count: Int,
    )
}

internal sealed interface VoiceAudioSource {
    /** The phone's microphone, which each reader captures itself with the platform's echo cancellation. */
    data object PhoneMicrophone : VoiceAudioSource

    /** A PCM stream that runs while a reader holds it open. */
    interface Stream : VoiceAudioSource {
        /**
         * open starts delivering PCM to [sink] until the returned handle closes, in buffers no other sink
         * receives. [onEnd] runs at most once, on any thread, when the stream ends before that; a stream that
         * cannot start reports it the same way instead of throwing.
         */
        fun open(
            sink: PcmSink,
            onEnd: () -> Unit,
        ): AutoCloseable
    }
}

/** VoiceAudioInput owns the process's selected voice source. */
internal class VoiceAudioInput {
    private val selected = MutableStateFlow<VoiceAudioSource>(VoiceAudioSource.PhoneMicrophone)

    /** Readers collect this with collectLatest, so a new selection cancels the reader of the previous one. */
    val source: StateFlow<VoiceAudioSource> = selected.asStateFlow()

    fun select(source: VoiceAudioSource) {
        selected.value = source
    }

    /**
     * read delivers [stream] to [sink] until cancelled, then closes it. When the stream ends while selected,
     * the phone microphone replaces it, which cancels this reader.
     */
    suspend fun read(
        stream: VoiceAudioSource.Stream,
        sink: PcmSink,
    ): Nothing {
        val ended = CompletableDeferred<Unit>()
        val handle = stream.open(sink) { ended.complete(Unit) }
        try {
            ended.await()
            if (selected.compareAndSet(stream, VoiceAudioSource.PhoneMicrophone)) {
                Log.i(TAG, "Voice audio stream ended; the phone microphone replaces it")
            }
            awaitCancellation()
        } finally {
            handle.close()
        }
    }
}
