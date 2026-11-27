// Android Telecom self-managed call boundary for a voice session.
package com.fghbuild.gomode.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class CallAudioState(
    val devices: List<AudioDevice> = emptyList(),
    val selectedDeviceId: Int? = null,
    val error: String? = null,
    val switching: Boolean = false,
)

/**
 * VoiceCallController maps one active voice session to an Android Telecom
 * self-managed call. A car or headset hang-up, or Telecom yielding to another
 * call, arrives as [start]'s onTelecomDisconnect callback.
 *
 * The implementation owns the Telecom call lifecycle. A session calls [start]
 * once, [activate] when the voice transport is ready, and [endLocal] for an
 * overlay hang-up.
 */
internal interface VoiceCallController {
    // Before a call, selection is the desired starting route; during a call it is the confirmed route.
    val audioState: StateFlow<CallAudioState>
    val isStarted: Boolean get() = false

    fun selectAudioDevice(deviceId: Int)

    /** isSupported reports whether this device can host a self-managed call. */
    fun isSupported(): Boolean

    /**
     * start adds an outgoing audio call. It returns true when Telecom reports the
     * call ready and false when Telecom rejects it or the device lacks support.
     * onTelecomDisconnect receives null for a car or headset hang-up and a
     * user-facing error when Telecom ends voice for another call.
     */
    suspend fun start(
        serverName: String,
        serverID: String?,
        onTelecomDisconnect: (error: String?) -> Unit,
    ): Boolean

    /** activate marks the call active after the voice transport is ready. */
    fun activate()

    /** endLocal disconnects a call this app owns. It is a no-op after a Telecom hang-up. */
    fun endLocal()
}

/** NoopVoiceCallController serves devices and tests without Telecom. */
internal class NoopVoiceCallController : VoiceCallController {
    override val audioState = MutableStateFlow(CallAudioState()).asStateFlow()

    override fun selectAudioDevice(deviceId: Int) = Unit

    override fun isSupported(): Boolean = false

    override suspend fun start(
        serverName: String,
        serverID: String?,
        onTelecomDisconnect: (error: String?) -> Unit,
    ): Boolean = false

    override fun activate() = Unit

    override fun endLocal() = Unit
}

internal fun VoiceState.withCallAudio(audio: CallAudioState): VoiceState =
    copy(
        availableDevices = audio.devices,
        selectedDeviceId = audio.selectedDeviceId,
        audioSwitching = audio.switching,
    )

/** Raises the controller's route problem as AUDIO_ROUTE each time it changes, and resolves it once it clears. */
internal suspend fun VoiceIssues.followAudioRoute(audio: Flow<CallAudioState>) {
    audio.map { it.error }.distinctUntilChanged().collect { error ->
        if (error == null) resolve(VoiceIssueKey.AUDIO_ROUTE) else raise(VoiceIssueKey.AUDIO_ROUTE, error)
    }
}
