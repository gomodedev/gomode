// Tests how a service sign-in change ends voice for active and idle sessions.
package com.fghbuild.gomode.voice

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSessionControllerTest {
    @Test
    fun signInChangeReportsWhyActiveVoiceEnded() {
        val voice = RecordingVoice(VoiceState(connected = true))
        voice.endForSignInChange()
        assertEquals(listOf("error: $VOICE_CONVERSATION_ENDED_ERROR"), voice.calls)
    }

    @Test
    fun signInChangeEndsIdleVoiceWithoutAnError() {
        val voice = RecordingVoice(VoiceState())
        voice.endForSignInChange()
        assertEquals(listOf("disconnect: service sign-in changed while idle"), voice.calls)
    }

    private class RecordingVoice(
        initial: VoiceState,
    ) : VoiceSessionController {
        override val state = MutableStateFlow(initial)
        override val issues = VoiceIssues()
        val calls = mutableListOf<String>()

        override fun connect(
            preserveTranscript: Boolean,
            conversationID: String?,
        ) = Unit

        override fun disconnect(reason: String) {
            calls += "disconnect: $reason"
        }

        override fun setMicrophoneMode(mode: MicrophoneMode) = Unit

        override fun selectAudioDevice(deviceId: Int) = Unit

        override fun clearTranscript() = Unit

        override fun announce(text: String) = Unit

        override fun contextChanged() = Unit

        override fun setError(message: String) {
            calls += "error: $message"
        }

        override fun close() = Unit
    }
}
