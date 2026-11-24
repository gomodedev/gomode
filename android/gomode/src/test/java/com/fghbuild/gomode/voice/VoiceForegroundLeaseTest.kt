// Verifies disposing idle or superseded controllers cannot release another native surface's foreground call.
package com.fghbuild.gomode.voice

import android.app.Application
import com.fghbuild.gomode.data.MemoryPreferences
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceSettingsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class VoiceForegroundLeaseTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun idleCloudControllerDisposalCannotStopDialForegroundOwnership() {
        val repository =
            SettingsRepository(
                MemoryPreferences().apply { beforeWrite = { error("Idle controller must not write settings") } },
            )
        val idle =
            VoiceSession(
                context,
                repository,
                voiceConversation =
                    VoiceConversation(
                        repository,
                        ServiceSettingsClient(),
                        ServiceCatalog(
                            CoroutineScope(SupervisorJob() + Dispatchers.Main),
                            glassesDisplay = { null },
                        ),
                    ) { emptyMap() },
                languageTag = "en-US",
            )
        val dial = VoiceForegroundLease(context)
        dial.start("Dial server")
        idle.close()
        assertNull("Idle shell disposal stopped another call", shadowOf(context).nextStoppedService)
        dial.stop()
        assertEquals(VoiceService::class.java.name, shadowOf(context).nextStoppedService.component?.className)
    }

    @Test
    fun supersededLeaseCannotStopReplacementCall() {
        val old = VoiceForegroundLease(context)
        val replacement = VoiceForegroundLease(context)
        old.start("First server")
        replacement.start("Replacement server")
        old.stop()
        assertNull(shadowOf(context).nextStoppedService)
        replacement.stop()
        assertEquals(VoiceService::class.java.name, shadowOf(context).nextStoppedService.component?.className)
    }
}
