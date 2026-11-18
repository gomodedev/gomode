// Tests how Telecom's requests to end a voice call reach the session.
package com.fghbuild.gomode.voice

import android.content.pm.PackageManager
import android.os.Looper
import android.telecom.CallControlCallback
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
    private fun idleUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            assertTrue("Telecom did not respond", System.nanoTime() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
