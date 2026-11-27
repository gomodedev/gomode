// Voice over WebRTC with fake Halo and Even G2 glasses: transcripts drawn on them, and gestures that act on voice.
package com.fghbuild.gomode.voice

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2InputEvent
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.G2TextPage
import com.fghbuild.eveng2.testing.FakeG2Firmware
import com.fghbuild.eveng2.testing.fakeG2Scanner
import com.fghbuild.gomode.LocalEmulatorOnly
import com.fghbuild.gomode.R
import com.fghbuild.gomode.data.GlassesDevice
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.glasses.G2PanelFit
import com.fghbuild.gomode.glasses.G2ScreenFit
import com.fghbuild.gomode.glasses.G2_DISPLAY
import com.fghbuild.gomode.glasses.G2_FACECLAW_DISPLAY
import com.fghbuild.gomode.glasses.GlassesLink
import com.fghbuild.gomode.glasses.GlassesOwner
import com.fghbuild.gomode.glasses.HALO_DISPLAY
import com.fghbuild.gomode.glasses.HaloScreenFit
import com.fghbuild.gomode.glasses.haloImageMessages
import com.fghbuild.gomode.glasses.haloTextMessages
import com.fghbuild.gomode.testing.MemoryPreferences
import com.fghbuild.gomode.ui.GoModeApp
import com.fghbuild.gomode.ui.theme.GoModeTheme
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.halo.msg.ClickType
import com.fghbuild.halo.testing.FakeHaloTransport
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

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
                        VoiceIssues(),
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

    // A tool's blocks for the user alone reach the glasses and never the gateway's model; each holds
    // through the assistant's reply and a late transcript of the speech that called the tool, until the
    // gateway reports new user speech.
    @Test
    fun aToolsGlassesBlocksShowOnHaloUntilTheUserSpeaksAndNeverReachTheModel() =
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
                repository.settings.first { it.services.isNotEmpty() && !it.voiceSetup.deviceSpeech }
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
                        VoiceIssues(),
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
                    val gateway = fixture.sessions.single()
                    // A realtime gateway reports speech as it starts; its transcript may come after the reply.
                    gateway.send("""{"kind":"user.speech.started"}""")

                    val png = twoColourPng()
                    val data = Base64.encodeToString(png, Base64.NO_WRAP)
                    fixture.toolContent =
                        """[{"type":"image","data":"$data","mimeType":"image/png","annotations":{"audience":["user"]}}]"""
                    gateway.send(serviceToolCall("card-image"))
                    val image = haloImageMessages(BitmapFactory.decodeByteArray(png, 0, png.size))
                    composeRule.waitUntil(TIMEOUT_MS) { halo.lastSent(image) }
                    val imageShown = halo.messages.size
                    composeRule.waitUntil(TIMEOUT_MS) { fixture.toolResults.any { it.optString("id") == "card-image" } }
                    assertFalse(
                        fixture.toolResults
                            .single { it.optString("id") == "card-image" }
                            .toString()
                            .contains(data),
                    )

                    // Neither the reply nor the late transcript of the speech that called the tool ends the image.
                    gateway.send("""{"kind":"transcript.delta","speaker":"assistant","text":"Here it is."}""")
                    gateway.send("""{"kind":"transcript.delta","speaker":"user","text":"Show the build."}""")
                    composeRule.waitUntil(TIMEOUT_MS) {
                        composeRule
                            .onAllNodesWithText(
                                "Show the build.",
                                substring = true,
                            ).fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    // The user's next speech brings the transcript back.
                    gateway.send("""{"kind":"user.speech.started"}""")
                    gateway.send("""{"kind":"transcript.draft","id":"d2","text":"thanks"}""")
                    val transcript = haloTextMessages("You: thanks")
                    composeRule.waitUntil(TIMEOUT_MS) { halo.lastSent(transcript) }
                    assertEquals(transcript.size, halo.messages.size - imageShown)

                    fixture.toolContent =
                        """[{"type":"text","text":"Build green","annotations":{"audience":["user"]}}]"""
                    gateway.send(serviceToolCall("card-text"))
                    composeRule.waitUntil(TIMEOUT_MS) { fixture.toolResults.any { it.optString("id") == "card-text" } }
                    assertFalse(
                        fixture.toolResults
                            .single {
                                it.optString(
                                    "id",
                                ) == "card-text"
                            }.toString()
                            .contains("Build green"),
                    )
                    composeRule.waitUntil(TIMEOUT_MS) { halo.lastSent(haloTextMessages("Build green")) }

                    composeRule.onNodeWithContentDescription("End voice").performClick()
                    composeRule.waitUntil(TIMEOUT_MS) { halo.lastSent(haloTextMessages("")) }
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

    // On Faceclaw custom firmware an Even G2 declares its whole panel with PNG images, and a tool's
    // image block for the user is drawn there, centred, in the panel's grey levels.
    @Test
    fun aToolsImageDrawsOnAnEvenG2WithCustomFirmwareThatDeclaresImages() =
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
                repository.settings.first { it.services.isNotEmpty() && !it.voiceSetup.deviceSpeech }
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val firmware = FakeG2Firmware().apply { revision = "Faceclaw/45" }
                val glassesScope = MainScope()
                val glasses =
                    GlassesOwner(
                        glassesScope,
                        context,
                        repository,
                        FakeHaloTransport(context),
                        fakeG2Scanner(),
                        firmware.connector,
                        VoiceIssues(),
                    )
                val models = LocalModelOwner(context, localModelInstallation(ModelDownloadState.Missing(0)))
                val shellOwner = ServiceShellOwner(context, repository, glasses::connectedDisplay)
                try {
                    composeRule.runOnUiThread { glasses.connect(GlassesDevice.EvenG2(G2_PAIR)) }
                    withTimeout(TIMEOUT_MS) { glasses.state.first { it.link is GlassesLink.Connected } }
                    assertEquals(
                        GlassesLink.Connected(G2_FACECLAW_DISPLAY, G2PanelFit),
                        glasses.state.value.link,
                    )
                    composeRule.setContent { GoModeTheme { GoModeApp(shellOwner, models, glasses) } }
                    composeRule.waitUntil(TIMEOUT_MS) {
                        composeRule
                            .onAllNodes(hasTestTag("gomode-voice-connect") and isEnabled())
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    composeRule.onNodeWithTag("gomode-voice-connect").performClick()
                    composeRule.waitUntil(TIMEOUT_MS) { fixture.sessions.size == 1 }
                    val gateway = fixture.sessions.single()
                    gateway.send("""{"kind":"user.speech.started"}""")

                    val png = twoColourPng()
                    fixture.toolContent =
                        """[{"type":"image","data":"${Base64.encodeToString(png, Base64.NO_WRAP)}",""" +
                        """"mimeType":"image/png","annotations":{"audience":["user"]}}]"""
                    gateway.send(serviceToolCall("card-image"))
                    // The 120 × 60 image sits at 260, 210: red (luma 76) is level 4, green (150) level 9.
                    composeRule.waitUntil(TIMEOUT_MS) {
                        val frame = firmware.presented.lastOrNull()
                        frame != null && frame.level(260, 210) == 4 && frame.level(379, 269) == 9
                    }
                    val frame = firmware.presented.last()
                    val edges = listOf(259 to 210, 260 to 239, 379 to 239, 380 to 239)
                    assertEquals(listOf(0, 4, 4, 0), edges.map { (x, y) -> frame.level(x, y) })

                    // Every MCP request since the G2 connected declared the panel with PNG images.
                    val displays =
                        fixture.requestMetadata.mapNotNull {
                            it
                                .optJSONObject("io.modelcontextprotocol/clientCapabilities")
                                ?.optJSONObject("extensions")
                                ?.optJSONObject("com.fghbuild.gomode/display")
                        }
                    assertTrue(fixture.requestMetadata.toString(), displays.size == fixture.requestMetadata.size)
                    for (display in displays) {
                        assertEquals(640 to 480, display.getInt("width") to display.getInt("height"))
                        assertEquals("image/png", display.getJSONArray("imageTypes").getString(0))
                    }

                    composeRule.onNodeWithContentDescription("End voice").performClick()
                    composeRule.waitUntil(TIMEOUT_MS) {
                        firmware.presented.last().let { it.lines.isEmpty() && it.level(260, 210) == 0 }
                    }
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

    // A PNG of a red bar over a green one, small enough for Halo to draw at its own size.
    private fun twoColourPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) bitmap.setPixel(x, y, if (y < 30) Color.RED else Color.GREEN)
        }
        return ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
    }

    private fun serviceToolCall(id: String): String =
        """
        {"kind":"tool.call","id":"$id","name":"dispatch_service_tool","args":{
        "kind":"mcp","tool":"echo","arguments":{"value":"test"}}}
        """.trimIndent()

    @Test
    fun aHaloButtonPressStartsVoiceAndTheNextPressEndsIt() =
        withVoiceReady(GlassesDevice.Halo(HALO_ADDRESS)) { ready ->
            val halo = ready.transport.halos.single()
            halo.pressButton(ClickType.SINGLE)
            ready.awaitVoice(on = true)
            halo.pressButton(ClickType.SINGLE)
            ready.awaitVoice(on = false)
            assertEquals(1, ready.fixture.sessions.size)
        }

    // Had a single tap acted, voice would have started before the first double tap, which would then
    // end it, or ended before the ring's double tap, which would then start a second session.
    @LocalEmulatorOnly
    @Test
    fun anEvenG2TempleDoubleTapStartsVoiceAndARingDoubleTapEndsItWhileSingleTapsActOnNothing() =
        withVoiceReady(GlassesDevice.EvenG2(G2_PAIR)) { ready ->
            val firmware = ready.firmware
            firmware.touch(G2Side.LEFT, G2InputEvent.CLICK)
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.CLICK, G2InputEvent.SOURCE_RIGHT_TEMPLE)
            firmware.systemInput(G2Side.LEFT, G2InputEvent.DOUBLE_CLICK, G2InputEvent.SOURCE_LEFT_TEMPLE)
            ready.awaitVoice(on = true)
            firmware.touch(G2Side.RIGHT, G2InputEvent.CLICK)
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.CLICK, G2InputEvent.SOURCE_RING)
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.DOUBLE_CLICK, G2InputEvent.SOURCE_RING)
            ready.awaitVoice(on = false)
            composeRule.waitForIdle()
            assertEquals(1, ready.fixture.sessions.size)
        }

    @Test
    fun onFaceclawFirmwareALongPressMutesVoiceAndTheNextUnmutesIt() =
        withVoiceReady(GlassesDevice.EvenG2(G2_PAIR), revision = "Faceclaw/45") { ready ->
            val firmware = ready.firmware
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.DOUBLE_CLICK, G2InputEvent.SOURCE_RIGHT_TEMPLE)
            ready.awaitVoice(on = true)
            ready.awaitMicrophoneButton(next = "Mute once")

            firmware.systemInput(G2Side.RIGHT, G2InputEvent.LONG_PRESS, G2InputEvent.SOURCE_LEFT_TEMPLE)
            composeRule.waitUntil(TIMEOUT_MS) {
                firmware.presented.lastOrNull()?.lines == listOf(context.getString(R.string.glasses_notice_muted))
            }
            // The phone's microphone button shows it muted until unmuted: its next mode is Listening.
            ready.awaitMicrophoneButton(next = "Listening")

            firmware.systemInput(G2Side.RIGHT, G2InputEvent.LONG_PRESS, G2InputEvent.SOURCE_RIGHT_TEMPLE)
            composeRule.waitUntil(TIMEOUT_MS) {
                firmware.presented.lastOrNull()?.lines == listOf(context.getString(R.string.glasses_notice_unmuted))
            }
            ready.awaitMicrophoneButton(next = "Mute once")
            assertEquals(1, ready.fixture.sessions.size)
        }

    private inner class VoiceReady(
        val transport: FakeHaloTransport,
        val firmware: FakeG2Firmware,
        val fixture: ServerVoiceSwitchTest.HostedVoiceFixture,
    ) {
        // Waits until the voice panel shows voice running, its session opened, or not running.
        fun awaitVoice(on: Boolean) {
            if (on) composeRule.waitUntil(TIMEOUT_MS) { fixture.sessions.isNotEmpty() }
            composeRule.waitUntil(TIMEOUT_MS) {
                composeRule.onAllNodesWithContentDescription("End voice").fetchSemanticsNodes().isNotEmpty() == on
            }
        }

        // Waits until the connected voice's microphone button offers [next] as its next mode.
        fun awaitMicrophoneButton(next: String) {
            composeRule.waitUntil(TIMEOUT_MS) {
                composeRule
                    .onAllNodesWithContentDescription("Microphone mode. Next: $next")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
    }

    // Connects [device], Even G2 on firmware that reports [revision], with Go Mode open and its service
    // ready to start voice, and runs [body]; the glasses stay connected throughout.
    private fun withVoiceReady(
        device: GlassesDevice,
        revision: String? = null,
        body: (VoiceReady) -> Unit,
    ) = runBlocking {
        MockWebServer().use { server ->
            val fixture = ServerVoiceSwitchTest.HostedVoiceFixture(bearer = false)
            server.dispatcher = fixture
            server.start()
            val repository = SettingsRepository(MemoryPreferences())
            repository.addService("Local stack", server.url("/").toString())
            repository.updateSavedVoiceSetup(null) {
                it.copy(preference = it.preference.copy(mode = VoiceSetupMode.FOLLOW_GATEWAY))
            }
            repository.settings.first { it.services.isNotEmpty() && !it.voiceSetup.deviceSpeech }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val transport = FakeHaloTransport(context)
            val firmware = FakeG2Firmware().apply { this.revision = revision }
            val glassesScope = MainScope()
            val glasses =
                GlassesOwner(
                    glassesScope,
                    context,
                    repository,
                    transport,
                    fakeG2Scanner(),
                    firmware.connector,
                    VoiceIssues(),
                )
            val models = LocalModelOwner(context, localModelInstallation(ModelDownloadState.Missing(0)))
            val shellOwner = ServiceShellOwner(context, repository, glasses::connectedDisplay)
            try {
                composeRule.runOnUiThread { glasses.connect(device) }
                withTimeout(TIMEOUT_MS) { glasses.state.first { it.link is GlassesLink.Connected } }
                composeRule.setContent { GoModeTheme { GoModeApp(shellOwner, models, glasses) } }
                composeRule.waitUntil(TIMEOUT_MS) {
                    composeRule
                        .onAllNodes(hasTestTag("gomode-voice-connect") and isEnabled())
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }

                body(VoiceReady(transport, firmware, fixture))
                assertTrue(glasses.state.value.link is GlassesLink.Connected)
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
        connectedCarDialCall { call ->
            val gateway = call.fixture.sessions.single()
            gateway.send(
                """{"kind":"transcript.delta","speaker":"user","text":"What is the code word?"}""",
            )
            gateway.send(
                """{"kind":"transcript.delta","speaker":"assistant","text":"The code word is cedar."}""",
            )
            composeRule.waitUntil(TIMEOUT_MS) {
                call.firmware.shownText() == "You: What is the code word?\nThe code word is cedar."
            }
            // Every MCP request since the G2 connected declared its display.
            val displays =
                call.fixture.requestMetadata.map {
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

            composeRule.runOnUiThread { call.carDial.cancel() }
            composeRule.waitUntil(TIMEOUT_MS) { call.firmware.shownText() == "" }
            assertEquals(
                GlassesLink.Connected(G2_DISPLAY, G2ScreenFit(G2TextPage())),
                call.glasses.state.value.link,
            )
        }

    // Telecom owns a released call's hang-up, so the call outlives the shell; a double tap still ends it.
    @Test
    fun aDoubleTapEndsACarDialCallThatOutlivesTheShell() =
        connectedCarDialCall { call ->
            releaseToShellAndUnmountIt(call)
            call.firmware.touch(G2Side.RIGHT, G2InputEvent.DOUBLE_CLICK)
            composeRule.waitUntil(TIMEOUT_MS) { call.carDial.state.value.status == CarDialStatus.IDLE }
            composeRule.waitUntil(TIMEOUT_MS) { call.firmware.shownText() == "" }
        }

    // Without the shell, nothing but the car-dial owner acts on the glasses: an action it left would
    // say on the glasses that voice is off.
    @Test
    fun onFaceclawFirmwareALongPressMutesACarDialCallThatOutlivesTheShell() =
        connectedCarDialCall(revision = "Faceclaw/45") { call ->
            releaseToShellAndUnmountIt(call)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            call.firmware.systemInput(G2Side.RIGHT, G2InputEvent.LONG_PRESS, G2InputEvent.SOURCE_RIGHT_TEMPLE)
            composeRule.waitUntil(TIMEOUT_MS) {
                call.firmware.presented
                    .lastOrNull()
                    ?.lines == listOf(context.getString(R.string.glasses_notice_muted))
            }
            val voice = checkNotNull(call.carDial.session.value)
            assertEquals(MicrophoneMode.MUTED, voice.state.value.microphoneMode)
            assertEquals(CarDialStatus.CONNECTED, call.carDial.state.value.status)
        }

    // Hands the call to the shell, as Telecom's released call does, then removes the shell.
    private fun releaseToShellAndUnmountIt(call: CarDialCall) {
        composeRule.runOnUiThread { check(call.carDial.releaseToShell(call.identity, call.activity)) }
        composeRule.runOnIdle { call.mounted.value = false }
        composeRule.waitForIdle()
        assertEquals(CarDialStatus.CONNECTED, call.carDial.state.value.status)
    }

    private class CarDialCall(
        val fixture: ServerVoiceSwitchTest.HostedVoiceFixture,
        val firmware: FakeG2Firmware,
        val glasses: GlassesOwner,
        val carDial: CarDialVoiceOwner,
        val identity: String,
        val activity: Any,
        // Whether GoModeApp, which hosts the service page, stays composed.
        val mounted: MutableState<Boolean>,
    )

    // Connects a fake Even G2, starts a car-dial call to a hosted service, and runs [body] once it
    // connects; the shell hosts the service page whose frontend the call waits for.
    private fun connectedCarDialCall(
        revision: String? = null,
        body: (CarDialCall) -> Unit,
    ) = runBlocking {
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
            val firmware = FakeG2Firmware().apply { this.revision = revision }
            val glassesScope = MainScope()
            val glasses =
                GlassesOwner(
                    glassesScope,
                    context,
                    repository,
                    FakeHaloTransport(context),
                    fakeG2Scanner(),
                    firmware.connector,
                    VoiceIssues(),
                )
            val models = LocalModelOwner(context, localModelInstallation(ModelDownloadState.Missing(0)))
            val shellOwner = ServiceShellOwner(context, repository, glasses::connectedDisplay)
            val carDial = CarDialVoiceOwner(context, repository, models, shellOwner, glasses, VoiceIssues())
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val role = RoleManager.ROLE_CALL_REDIRECTION
            val previousHolders =
                device.executeShellCommand("cmd role get-role-holders $role").lines().filter(String::isNotBlank)
            val component = ComponentName(context, CarDialRedirectionService::class.java)
            val originalEnabled = context.packageManager.getComponentEnabledSetting(component)
            val mounted = mutableStateOf(true)
            try {
                context.packageManager.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
                device.executeShellCommand("cmd role add-role-holder $role ${context.packageName}")
                composeRule.runOnUiThread { glasses.connect(GlassesDevice.EvenG2(G2_PAIR)) }
                withTimeout(TIMEOUT_MS) { glasses.state.first { it.link is GlassesLink.Connected } }
                composeRule.setContent {
                    if (mounted.value) GoModeTheme { GoModeApp(shellOwner, models, glasses) }
                }
                composeRule.waitUntil(TIMEOUT_MS) {
                    composeRule
                        .onAllNodes(hasTestTag("gomode-voice-connect") and isEnabled())
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                val activity = Any()
                val identity =
                    composeRule.runOnUiThread {
                        val identity = requireNotNull(carDial.request(service))
                        check(carDial.attach(identity, activity))
                        carDial.start(identity, activity)
                        identity
                    }
                composeRule.waitUntil(TIMEOUT_MS) {
                    fixture.sessions.size == 1 && carDial.state.value.status == CarDialStatus.CONNECTED
                }
                body(CarDialCall(fixture, firmware, glasses, carDial, identity, activity, mounted))
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
