// Exercises shell and car-dial voice sessions over WebRTC drawing their transcripts on fake Halo and Even G2 glasses.
package com.fghbuild.gomode.voice

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.G2TextPage
import com.fghbuild.eveng2.testing.FakeG2Firmware
import com.fghbuild.eveng2.testing.fakeG2Scanner
import com.fghbuild.gomode.data.GlassesDevice
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.glasses.G2ScreenFit
import com.fghbuild.gomode.glasses.G2_DISPLAY
import com.fghbuild.gomode.glasses.GlassesLink
import com.fghbuild.gomode.glasses.GlassesOwner
import com.fghbuild.gomode.glasses.HALO_DISPLAY
import com.fghbuild.gomode.glasses.HaloScreenFit
import com.fghbuild.gomode.glasses.haloTextMessages
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
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// A documentation-range address; the fake transport answers for it.
private const val HALO_ADDRESS = "00:00:5E:00:53:02"
private val G2_PAIR =
    G2Pair(
        "7",
        G2Arm(G2Side.LEFT, "00:00:5E:00:53:08", "Even G2_7_L_005308"),
        G2Arm(G2Side.RIGHT, "00:00:5E:00:53:09", "Even G2_7_R_005309"),
    )
private const val TIMEOUT_MS = 30_000L

class GlassesVoiceTranscriptTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun glassesShowTheDraftThenTheExchangeWithoutReasoningAndClearWhenVoiceEnds() =
        runBlocking {
            MockWebServer().use { server ->
                val fixture = ServerVoiceSwitchTest.HostedVoiceFixture(bearer = false)
                server.dispatcher = fixture
                server.start()
                val repository = SettingsRepository(MemoryPreferences())
                repository.addService("Local stack", server.url("/").toString())
                repository.updateSavedVoiceSetup(null) {
                    it.copy(preference = it.preference.copy(mode = VoiceSetupMode.FOLLOW_GATEWAY))
                }
                // Reasoning reaches the phone transcript, so its absence on the glasses is the selection's.
                repository.updateShowReasoning(true)
                repository.settings.first { it.showReasoning && !it.voiceSetup.deviceSpeech }
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val transport = FakeHaloTransport(context)
                val glassesScope = MainScope()
                val glasses =
                    GlassesOwner(
                        glassesScope,
                        context,
                        repository,
                        transport,
                        fakeG2Scanner(),
                        FakeG2Firmware().connector,
                    )
                val models = LocalModelOwner(context, localModelInstallation(ModelDownloadState.Missing(0)))
                val shellOwner = ServiceShellOwner(context, repository, glasses::connectedDisplay)
                try {
                    composeRule.runOnUiThread { glasses.connect(GlassesDevice.Halo(HALO_ADDRESS)) }
                    withTimeout(TIMEOUT_MS) { glasses.state.first { it.link is GlassesLink.Connected } }
                    val halo = transport.halos.single()
                    composeRule.setContent { GoModeTheme { GoModeApp(shellOwner, models, glasses) } }
                    composeRule.waitUntil(TIMEOUT_MS) {
                        composeRule
                            .onAllNodes(hasTestTag("gomode-voice-connect") and isEnabled())
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    composeRule.onNodeWithTag("gomode-voice-connect").performClick()
                    composeRule.waitUntil(TIMEOUT_MS) { fixture.sessions.size == 1 }
                    composeRule.waitUntil(TIMEOUT_MS) {
                        composeRule.onAllNodesWithContentDescription("End voice").fetchSemanticsNodes().isNotEmpty()
                    }
                    val gateway = fixture.sessions.single()

                    gateway.send("""{"kind":"transcript.draft","id":"d1","text":"what is the"}""")
                    composeRule.waitUntil(TIMEOUT_MS) { halo.lastSent(haloTextMessages("You: what is the")) }

                    gateway.send(
                        """{"kind":"transcript.delta","speaker":"user","text":"What is the code word?","draft_id":"d1"}""",
                    )
                    gateway.send("""{"kind":"reasoning.delta","text":"Recall the note."}""")
                    gateway.send(
                        """{"kind":"transcript.delta","speaker":"assistant","text":"The code word is cedar."}""",
                    )
                    composeRule.waitUntil(TIMEOUT_MS) {
                        halo.lastSent(haloTextMessages("You: What is the code word?\nThe code word is cedar."))
                    }

                    composeRule.onNodeWithContentDescription("End voice").performClick()
                    composeRule.waitUntil(TIMEOUT_MS) { halo.lastSent(haloTextMessages("")) }
                    assertEquals(GlassesLink.Connected(HALO_DISPLAY, HaloScreenFit), glasses.state.value.link)
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

    // The car-dial owner feeds the glasses from the call it owns, on its own; Go Mode only hosts the page.
    @Test
    fun aCarDialCallShowsItsTranscriptOnEvenG2AndDeclaresTheG2Display() =
        runBlocking {
            MockWebServer().use { server ->
                val fixture = ServerVoiceSwitchTest.HostedVoiceFixture(bearer = false)
                server.dispatcher = fixture
                server.start()
                val repository = SettingsRepository(MemoryPreferences())
                repository.addService("Local stack", server.url("/").toString())
                repository.updateSavedVoiceSetup(null) {
                    it.copy(preference = it.preference.copy(mode = VoiceSetupMode.FOLLOW_GATEWAY))
                }
                val service =
                    repository.settings
                        .first { it.services.isNotEmpty() && !it.voiceSetup.deviceSpeech }
                        .services
                        .single()
                val context = InstrumentationRegistry.getInstrumentation().targetContext
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
                val carDial = CarDialVoiceOwner(context, repository, models, shellOwner, glasses)
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val role = RoleManager.ROLE_CALL_REDIRECTION
                val previousHolders =
                    device.executeShellCommand("cmd role get-role-holders $role").lines().filter(String::isNotBlank)
                val component = ComponentName(context, CarDialRedirectionService::class.java)
                val originalEnabled = context.packageManager.getComponentEnabledSetting(component)
                try {
                    context.packageManager.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                    device.executeShellCommand("cmd role add-role-holder $role ${context.packageName}")
                    composeRule.runOnUiThread { glasses.connect(GlassesDevice.EvenG2(G2_PAIR)) }
                    withTimeout(TIMEOUT_MS) { glasses.state.first { it.link is GlassesLink.Connected } }
                    // The shell hosts the service page whose frontend the call waits for.
                    composeRule.setContent { GoModeTheme { GoModeApp(shellOwner, models, glasses) } }
                    composeRule.waitUntil(TIMEOUT_MS) {
                        composeRule
                            .onAllNodes(hasTestTag("gomode-voice-connect") and isEnabled())
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    val activity = Any()
                    composeRule.runOnUiThread {
                        val identity = requireNotNull(carDial.request(service))
                        check(carDial.attach(identity, activity))
                        carDial.start(identity, activity)
                    }
                    composeRule.waitUntil(TIMEOUT_MS) {
                        fixture.sessions.size == 1 && carDial.state.value.status == CarDialStatus.CONNECTED
                    }
                    val gateway = fixture.sessions.single()
                    gateway.send(
                        """{"kind":"transcript.delta","speaker":"user","text":"What is the code word?"}""",
                    )
                    gateway.send(
                        """{"kind":"transcript.delta","speaker":"assistant","text":"The code word is cedar."}""",
                    )
                    composeRule.waitUntil(TIMEOUT_MS) {
                        firmware.shownText() == "You: What is the code word?\nThe code word is cedar."
                    }
                    // Every MCP request since the G2 connected declared its display.
                    val displays =
                        fixture.requestMetadata.map {
                            it
                                .optJSONObject("io.modelcontextprotocol/clientCapabilities")
                                ?.optJSONObject("extensions")
                                ?.optJSONObject("com.fghbuild.gomode/display")
                                ?.toString()
                        }
                    assertTrue(
                        displays.toString(),
                        displays.isNotEmpty() &&
                            displays.all {
                                it ==
                                    """{"width":576,"height":288,"shape":"rectangle","colorSpace":"luminance","bitsPerPixel":4}"""
                            },
                    )

                    composeRule.runOnUiThread { carDial.cancel() }
                    composeRule.waitUntil(TIMEOUT_MS) { firmware.shownText() == "" }
                    assertEquals(GlassesLink.Connected(G2_DISPLAY, G2ScreenFit(G2TextPage())), glasses.state.value.link)
                } finally {
                    composeRule.runOnUiThread {
                        carDial.close()
                        glassesScope.cancel()
                        shellOwner.close()
                    }
                    models.close()
                    fixture.close()
                    device.executeShellCommand("cmd role remove-role-holder $role ${context.packageName}")
                    previousHolders.forEach { device.executeShellCommand("cmd role add-role-holder $role $it") }
                    context.packageManager.setComponentEnabledSetting(
                        component,
                        originalEnabled,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
        }
}
