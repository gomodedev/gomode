// Exercises a shell voice session over WebRTC drawing its transcript on fake Halo glasses, then clearing them.
package com.fghbuild.gomode.voice

import android.Manifest
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
import com.fghbuild.gomode.data.MemoryPreferences
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.glasses.FakeHaloTransport
import com.fghbuild.gomode.glasses.GlassesLink
import com.fghbuild.gomode.glasses.GlassesOwner
import com.fghbuild.gomode.ui.GoModeApp
import com.fghbuild.gomode.ui.theme.GoModeTheme
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// A documentation-range address; the fake transport answers for it.
private const val HALO_ADDRESS = "00:00:5E:00:53:02"
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
                val glasses = GlassesOwner(context, repository, transport)
                val models = LocalModelOwner(context, localModelInstallation(ModelDownloadState.Missing(0)))
                val shellOwner = ServiceShellOwner(context, repository)
                try {
                    composeRule.runOnUiThread { glasses.connect(HALO_ADDRESS) }
                    withTimeout(TIMEOUT_MS) { glasses.state.first { it.link == GlassesLink.Connected } }
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
                    composeRule.waitUntil(TIMEOUT_MS) { halo.lastShows("You: what is the") }

                    gateway.send(
                        """{"kind":"transcript.delta","speaker":"user","text":"What is the code word?","draft_id":"d1"}""",
                    )
                    gateway.send("""{"kind":"reasoning.delta","text":"Recall the note."}""")
                    gateway.send(
                        """{"kind":"transcript.delta","speaker":"assistant","text":"The code word is cedar."}""",
                    )
                    composeRule.waitUntil(TIMEOUT_MS) {
                        halo.lastShows("You: What is the code word?\nThe code word is cedar.")
                    }

                    composeRule.onNodeWithContentDescription("End voice").performClick()
                    composeRule.waitUntil(TIMEOUT_MS) { halo.lastShows("") }
                    assertEquals(GlassesLink.Connected, glasses.state.value.link)
                } finally {
                    composeRule.runOnUiThread {
                        glasses.close()
                        shellOwner.close()
                    }
                    models.close()
                    fixture.close()
                }
            }
        }
}
