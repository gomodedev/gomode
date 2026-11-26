// Tests Telecom call disconnect callbacks and starting-endpoint fallback.
package com.fghbuild.gomode.voice

import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.os.ParcelUuid
import android.telecom.CallControlCallback
import android.telecom.CallEndpoint
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowTransactionalTelecomManager::class])
class TelecomCallControllerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun yieldingToAnotherCallReportsWhyVoiceEnded() {
        assertEquals(listOf(VOICE_CALL_PREEMPTED_ERROR), endCall { it.onSetInactive {} })
    }

    @Test
    fun telecomHangUpEndsVoiceWithoutAnError() {
        val ended = endCall { it.onDisconnect(DisconnectCause(DisconnectCause.LOCAL)) {} }
        assertEquals(listOf<String?>(null), ended)
    }

    @Test
    fun missingInitialTelecomEndpointsFallBackToStartingEndpointsUntilLiveUpdatesArrive() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELECOM, true)
        val audio = context.getSystemService(AudioManager::class.java)
        val shadowAudio = shadowOf(audio)
        // Start with no communication devices so start() enters activeCall before starting endpoints arrive,
        // matching the cold-start race where Telecom drops initial endpoint callbacks.
        shadowAudio.setAvailableCommunicationDevices(emptyList())
        val telecom =
            Shadow.extract<ShadowTransactionalTelecomManager>(context.getSystemService(TelecomManager::class.java))
        telecom.emitInitialEndpoints = false

        val controller = TelecomCallController(context, onTelecomEnded = {})
        try {
            var started: Boolean? = null
            scope.launch { started = controller.start("Fixture", null) {} }
            // Core-Telecom waits 2s on the main looper for Telecom's initial endpoints before
            // entering addCall's block; advance Robolectric's looper clock so that timeout fires.
            idleUntil(advanceClockMs = 100) { started != null }
            assertEquals(true, started)
            assertTrue(
                controller.audioState.value.devices
                    .isEmpty(),
            )

            val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
            val earpiece = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
            shadowAudio.addAvailableCommunicationDevice(speaker, true)
            shadowAudio.addAvailableCommunicationDevice(earpiece, true)
            idleUntil { controller.audioState.value.devices.size == 2 }

            val fallbackDevices = controller.audioState.value.devices
            val speakerDevice = fallbackDevices.single { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val earpieceDevice = fallbackDevices.single { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            assertEquals(speakerDevice.id, controller.audioState.value.selectedDeviceId)

            controller.selectAudioDevice(earpieceDevice.id)
            idleUntil {
                !controller.audioState.value.switching &&
                    controller.audioState.value.selectedDeviceId == earpieceDevice.id
            }
            assertTrue(
                "Expected requestCallEndpointChange in ${telecom.controlRequests}",
                telecom.controlRequests.contains("requestCallEndpointChange"),
            )

            val liveSpeaker = CallEndpoint("Speaker", CallEndpoint.TYPE_SPEAKER, ParcelUuid(UUID.randomUUID()))
            val liveEarpiece = CallEndpoint("Earpiece", CallEndpoint.TYPE_EARPIECE, ParcelUuid(UUID.randomUUID()))
            telecom.eventCallbacks.single().apply {
                onAvailableCallEndpointsChanged(listOf(liveEarpiece, liveSpeaker))
                onCallEndpointChanged(liveSpeaker)
            }
            idleUntil { controller.audioState.value.selectedDeviceId == speakerDevice.id }
            assertEquals(
                setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                controller.audioState.value.devices
                    .map { it.type }
                    .toSet(),
            )
            assertEquals(
                fallbackDevices.map { it.id }.toSet(),
                controller.audioState.value.devices
                    .map { it.id }
                    .toSet(),
            )
        } finally {
            controller.close()
            idleUntil(advanceClockMs = 50) {
                controller.audioState.value.devices
                    .isEmpty()
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // endCall starts a call, lets Telecom end it through request, and returns the session's callbacks.
    private fun endCall(request: (CallControlCallback) -> Unit): List<String?> {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELECOM, true)
        val controller = TelecomCallController(context, onTelecomEnded = {})
        val ended = mutableListOf<String?>()
        var started: Boolean? = null
        scope.launch { started = controller.start("Fixture", null) { ended += it } }
        idleUntil { started != null }
        assertEquals(true, started)
        val telecom =
            Shadow.extract<ShadowTransactionalTelecomManager>(context.getSystemService(TelecomManager::class.java))
        request(telecom.controlCallbacks.single())
        idleUntil { ended.isNotEmpty() }
        return ended
    }

    // Core-Telecom hops between the main looper and its own threads.
    private fun idleUntil(
        advanceClockMs: Long = 0,
        done: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            assertTrue("Telecom did not respond", System.nanoTime() < deadline)
            if (advanceClockMs > 0) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(advanceClockMs))
            } else {
                shadowOf(Looper.getMainLooper()).idle()
            }
            Thread.sleep(10)
        }
    }
}
