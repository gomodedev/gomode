// Checks the notice that fake Even G2 glasses show when their press cannot start voice, one per reason.
package com.fghbuild.gomode.voice

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.testing.FakeG2Firmware
import com.fghbuild.eveng2.testing.fakeG2Scanner
import com.fghbuild.gomode.DeniedMicrophonePermission
import com.fghbuild.gomode.R
import com.fghbuild.gomode.awaitFreshObject
import com.fghbuild.gomode.data.GlassesDevice
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.glasses.GlassesLink
import com.fghbuild.gomode.glasses.GlassesOwner
import com.fghbuild.gomode.testing.MemoryPreferences
import com.fghbuild.gomode.ui.GoModeApp
import com.fghbuild.gomode.ui.theme.GoModeTheme
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.halo.testing.FakeHaloTransport
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

private val PAIR =
    G2Pair(
        "7",
        G2Arm(G2Side.LEFT, "00:00:5E:00:53:08", "Even G2_7_L_005308"),
        G2Arm(G2Side.RIGHT, "00:00:5E:00:53:09", "Even G2_7_R_005309"),
    )
private const val TIMEOUT_MS = 30_000L
private const val RTC_OFFER = "/api/voicegateway/v2/voice/rtc/offer"

// The microphone stays as the suite left it: granted by the cases that need it, revoked for the
// microphone prompt by the denied-microphone suite.
class GlassesPressNoticeTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aPressWhileGoModeIsInTheBackgroundAsksToOpenIt() =
        withGoMode { firmware, _ ->
            // ActivityScenario.moveToState(CREATED) took 46 s to stop the Activity here; Home stops it at once.
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome()
            composeRule.waitUntil(TIMEOUT_MS) {
                composeRule.runOnUiThread {
                    !composeRule.activity.lifecycle.currentState.isAtLeast(
                        Lifecycle.State.STARTED,
                    )
                }
            }
            firmware.touch(G2Side.RIGHT, 0)
            awaitShown(firmware, context.getString(R.string.glasses_notice_open_app))
        }

    @Test
    fun aPressWithoutAServiceAsksToAddOne() =
        withGoMode(service = false) { firmware, _ ->
            firmware.touch(G2Side.RIGHT, 0)
            awaitShown(firmware, context.getString(R.string.glasses_notice_add_service))
        }

    @Test
    fun aPressInSettingsSaysVoiceIsNotReady() =
        withGoMode { firmware, _ ->
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.waitForIdle()
            firmware.touch(G2Side.RIGHT, 0)
            awaitShown(firmware, context.getString(R.string.glasses_notice_voice_unavailable))
        }

    @Test
    @DeniedMicrophonePermission
    fun aPressAsksToAllowTheMicrophoneWhileThePhonePromptsForIt() =
        withGoMode { firmware, fixture ->
            firmware.touch(G2Side.RIGHT, 0)
            awaitShown(firmware, context.getString(R.string.glasses_notice_allow_microphone))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val device = UiDevice.getInstance(instrumentation)
            requireNotNull(
                device.awaitFreshObject(
                    instrumentation.uiAutomation,
                    By.res(Pattern.compile(".*:id/permission_deny.*_button")),
                    TIMEOUT_MS,
                ),
            ) { "Microphone permission prompt did not appear" }.click()
            assertEquals(0, fixture.sessions.size)
        }

    @Test
    fun aPressWhoseVoiceFailsToStartShowsTheError() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        withGoMode(failOffer = true) { firmware, _ ->
            firmware.touch(G2Side.RIGHT, 0)
            val notices =
                listOf(
                    R.string.glasses_notice_open_app,
                    R.string.glasses_notice_add_service,
                    R.string.glasses_notice_voice_unavailable,
                    R.string.glasses_notice_allow_microphone,
                ).map(context::getString)
            composeRule.waitUntil(TIMEOUT_MS) {
                val shown = firmware.shownText()
                !shown.isNullOrBlank() && shown !in notices
            }
        }
    }

    private fun awaitShown(
        firmware: FakeG2Firmware,
        notice: String,
    ) {
        composeRule.waitUntil(TIMEOUT_MS) { firmware.shownText() == notice }
    }

    // Connects fake Even G2 glasses, shows Go Mode with one hosted service unless [service] is false,
    // waits until it can start voice, and runs [body]. [failOffer] makes the gateway reject the call.
    private fun withGoMode(
        service: Boolean = true,
        failOffer: Boolean = false,
        body: (FakeG2Firmware, ServerVoiceSwitchTest.HostedVoiceFixture) -> Unit,
    ) = runBlocking {
        MockWebServer().use { server ->
            val fixture = ServerVoiceSwitchTest.HostedVoiceFixture(bearer = false)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (failOffer && request.path == RTC_OFFER) {
                            MockResponse().setResponseCode(503)
                        } else {
                            fixture.dispatch(request)
                        }
                }
            server.start()
            val repository = SettingsRepository(MemoryPreferences())
            if (service) repository.addService("Local stack", server.url("/").toString())
            repository.updateSavedVoiceSetup(null) {
                it.copy(preference = it.preference.copy(mode = VoiceSetupMode.FOLLOW_GATEWAY))
            }
            repository.settings.first { it.services.isNotEmpty() == service && !it.voiceSetup.deviceSpeech }
            val firmware = FakeG2Firmware()
            val glassesScope = MainScope()
            val glasses =
                GlassesOwner(
                    glassesScope,
                    context,
                    repository,
                    FakeHaloTransport(context),
                    fakeG2Scanner(),
                    firmware.connector,
                )
            val models = LocalModelOwner(context, localModelInstallation(ModelDownloadState.Missing(0)))
            val shellOwner = ServiceShellOwner(context, repository, glasses::connectedDisplay)
            try {
                composeRule.runOnUiThread { glasses.connect(GlassesDevice.EvenG2(PAIR)) }
                withTimeout(TIMEOUT_MS) { glasses.state.first { it.link is GlassesLink.Connected } }
                composeRule.setContent { GoModeTheme { GoModeApp(shellOwner, models, glasses) } }
                if (service) {
                    composeRule.waitUntil(TIMEOUT_MS) {
                        composeRule
                            .onAllNodes(hasTestTag("gomode-voice-connect") and isEnabled())
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                } else {
                    composeRule.waitForIdle()
                }
                body(firmware, fixture)
            } finally {
                composeRule.runOnUiThread {
                    glassesScope.cancel()
                    shellOwner.close()
                }
                models.close()
                fixture.close()
            }
        }
    }
}
