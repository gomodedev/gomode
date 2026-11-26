// Unit tests for the Go Mode voice overlay status line and audio device icons.
package com.fghbuild.gomode.voice

import android.media.AudioDeviceInfo
import com.fghbuild.gomode.R
import com.fghbuild.voicegateway.sdk.v2.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceOverlayTest {
    @Test
    fun toolHistoryKeepsRepeatedInvocationsAndBoundsNames() {
        var state = VoiceState().withToolCall("same_tool").withToolCall("same_tool")
        assertEquals(listOf("same_tool", "same_tool"), state.toolHistory.map { it.name })
        assertEquals(
            2,
            state.toolHistory
                .map { it.sequence }
                .toSet()
                .size,
        )
        for (index in 1..32) state = state.withToolCall("tool_$index")
        assertEquals(32, state.toolHistory.size)
        assertEquals("tool_1", state.toolHistory.first().name)
        assertEquals("tool_32", state.toolHistory.last().name)
        assertEquals(state.toolHistory, state.copy(activeTool = null).toolHistory)
    }

    @Test
    fun audioDeviceIconsUsePlatformTypesAndHandleUnknownRoutes() {
        assertEquals(R.drawable.ic_bluetooth, audioDeviceIcon(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(R.drawable.ic_bluetooth, audioDeviceIcon(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(R.drawable.ic_bluetooth, audioDeviceIcon(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertEquals(R.drawable.ic_headphones, audioDeviceIcon(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(R.drawable.ic_headphones, audioDeviceIcon(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(R.drawable.ic_phone_in_talk, audioDeviceIcon(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals(R.drawable.ic_speaker, audioDeviceIcon(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(R.drawable.ic_usb, audioDeviceIcon(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(R.drawable.ic_volume_up, audioDeviceIcon(AudioDeviceInfo.TYPE_UNKNOWN))
    }

    @Test
    fun voiceStatusTextDistinguishesToolExecutionFromThinking() {
        val thinking = VoiceState(connected = true, turnState = TurnState.Thinking)
        assertEquals("Thinking…", voiceStatusText(thinking))
        val calling = thinking.copy(activeTool = "example_tool")
        assertEquals("Calling tool: example_tool…", voiceStatusText(calling))
        assertEquals(
            "Calling tool: example_tool…",
            voiceStatusText(calling.copy(microphoneMode = MicrophoneMode.MUTED, speaking = true)),
        )
        assertEquals("Thinking…", voiceStatusText(calling.copy(activeTool = null)))
    }

    @Test
    fun voiceStatusTextShowsGatewayWorkBehindSpeechAndAheadOfMuting() {
        val muted = VoiceState(connected = true, microphoneMode = MicrophoneMode.MUTED)
        assertEquals("Transcribing…", voiceStatusText(muted.copy(turnState = TurnState.Transcribing)))
        assertEquals("Thinking…", voiceStatusText(muted.copy(turnState = TurnState.Thinking)))
        assertEquals("Speaking…", voiceStatusText(muted.copy(turnState = TurnState.Thinking, speaking = true)))
        assertEquals("Muted", voiceStatusText(muted))
        assertEquals(
            "Listening…",
            voiceStatusText(
                muted.copy(microphoneMode = MicrophoneMode.LISTENING, turnState = TurnState.Other("future")),
            ),
        )
    }

    @Test
    fun transcriptLabelsSeparateReasoningFromSpokenText() {
        assertEquals(
            "Reasoning",
            transcriptLabel(
                TranscriptEntry(TranscriptSpeaker.ASSISTANT, "weigh both dates", kind = TranscriptKind.REASONING),
            ),
        )
        assertEquals("You", transcriptLabel(TranscriptEntry(TranscriptSpeaker.USER, "which date is earlier?")))
        assertEquals("Assistant", transcriptLabel(TranscriptEntry(TranscriptSpeaker.ASSISTANT, "the first one")))
    }

    @Test
    fun serviceReconnectReasonCollapsesLinesAndBoundsTransportMessages() {
        assertEquals("HTTP 401", serviceReconnectReasonText("HTTP 401"))
        assertEquals(
            "Unexpected JSON token at offset 12 in {\"items\"}",
            serviceReconnectReasonText("Unexpected JSON token\n  at offset 12\n in {\"items\"}"),
        )
        val bounded = serviceReconnectReasonText("failed\n".repeat(80))
        assertEquals(201, bounded.length)
        assertTrue(bounded.endsWith("…"))
        assertFalse("Reasons must stay one line", bounded.contains('\n'))
    }
}
