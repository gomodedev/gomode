// Selects the connected glasses' microphone for voice while the setting asks for it, and the phone's otherwise.
//
// followGlassesMicrophone is the only production caller of VoiceAudioInput.select. It selects a new
// GlassesMicrophoneStream for each connection that offers a microphone while the setting is on, and
// the phone microphone when the setting turns off or that connection ends; a reconnection offers a
// new microphone, which it selects again. Voice readers already running switch under a running
// recognizer or peer connection (VoiceAudioSource.kt), so a disconnect never ends the conversation.
//
// Each open of the stream collects GlassesMicrophone.microphone on its own. The SDK keeps the glasses'
// microphone on while any collector runs, so the input events screen's recorder and voice hear the
// same audio, and neither stops the other's.
package com.fghbuild.gomode.glasses

import android.util.Log
import com.fghbuild.gomode.voice.PcmSink
import com.fghbuild.gomode.voice.VoiceAudioInput
import com.fghbuild.gomode.voice.VoiceAudioSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val TAG = "GoModeGlassesVoice"

/**
 * Selects for [input] the microphone of the glasses that [microphone] offers while [enabled] holds, and
 * the phone microphone otherwise, until cancelled. Each selected stream collects in [streamScope], whose dispatcher runs the sinks.
 */
internal suspend fun followGlassesMicrophone(
    input: VoiceAudioInput,
    enabled: Flow<Boolean>,
    microphone: Flow<GlassesMicrophone?>,
    streamScope: CoroutineScope,
) {
    combine(enabled, microphone) { on, glasses -> glasses.takeIf { on } }
        .distinctUntilChanged()
        .collect { glasses ->
            if (glasses == null) {
                Log.i(TAG, "Voice hears the phone microphone")
                input.select(VoiceAudioSource.PhoneMicrophone)
            } else {
                Log.i(TAG, "Voice hears the glasses' microphone")
                input.select(GlassesMicrophoneStream(glasses.microphone, streamScope))
            }
        }
}

/**
 * [microphone], a [GlassesMicrophone.microphone], as a voice stream. Each open collects it in [scope],
 * converts the samples into its own buffer, and reports the end when the flow completes.
 */
internal class GlassesMicrophoneStream(
    private val microphone: Flow<ShortArray>,
    private val scope: CoroutineScope,
) : VoiceAudioSource.Stream {
    override fun open(
        sink: PcmSink,
        onEnd: () -> Unit,
    ): AutoCloseable {
        val job =
            scope.launch {
                var bytes = ByteArray(0)
                try {
                    microphone.collect { pcm ->
                        val count = pcm.size * Short.SIZE_BYTES
                        if (bytes.size < count) bytes = ByteArray(count)
                        for (index in pcm.indices) {
                            val sample = pcm[index].toInt()
                            bytes[2 * index] = sample.toByte()
                            bytes[2 * index + 1] = (sample shr 8).toByte()
                        }
                        sink.write(bytes, count)
                    }
                    Log.i(TAG, "The glasses' microphone ended")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failure ends the stream rather than the process; the input then selects the phone microphone.
                    Log.w(TAG, "The glasses' microphone failed", e)
                }
                onEnd()
            }
        return AutoCloseable { job.cancel() }
    }
}
