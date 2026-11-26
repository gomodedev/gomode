// Exercises the gateway speaking-voice pickers of a realtime model and a synthesis model on a device.
package com.fghbuild.gomode

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.SpeechStageLocation.GATEWAY
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.data.VoiceSetupPreference
import com.fghbuild.gomode.testing.MemoryPreferences
import com.fghbuild.gomode.ui.settings.SettingsScreen
import com.fghbuild.gomode.ui.settings.rememberSettingsEdits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SpeakingVoiceLanguageTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun speakingVoicesFollowTheVoiceLanguage() {
        MockWebServer().use { server ->
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        MockResponse().setBody(
                            if (request.path.orEmpty().endsWith("/voice/choices")) CATALOG else MANIFEST,
                        )
                }
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            val repository = SettingsRepository(MemoryPreferences())
            runBlocking {
                repository.addService("Voice fixture", base)
                repository.settings.first { it.activeServiceURL == base }
                repository.updateSavedVoiceSetup(base) {
                    it.copy(
                        preference =
                            VoiceSetupPreference(
                                mode = VoiceSetupMode.SEPARATE_STAGES,
                                recognition = GATEWAY,
                                speech = GATEWAY,
                            ),
                    )
                }
                repository.updateVoiceLanguageTag("fr-FR")
            }
            composeRule.setContent {
                val settings by repository.settings.collectAsState()
                MaterialTheme {
                    SettingsScreen(
                        settings,
                        repository,
                        false,
                        false,
                        {},
                        {},
                        edits = rememberSettingsEdits(repository, settings),
                    )
                }
            }
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(hasTestTag("gomode-speaking-voice")).fetchSemanticsNodes().isNotEmpty()
            }

            // A French session offers the French voices only and names its default.
            composeRule.onNodeWithTag("gomode-speaking-voice").assertTextContains("Default (French)")
            openPicker()
            composeRule.onNodeWithTag("gomode-speaking-voice-FrenchWaiter").assertExists()
            composeRule.onNodeWithTag("gomode-speaking-voice-Luna").assertDoesNotExist()
            capture("synthesis-fr")
            composeRule.onNodeWithTag("gomode-speaking-voice-FrenchWaiter").performClick()
            composeRule.waitUntil(5_000) {
                repository.settings.value.synthesisVoice(base) == "FrenchWaiter"
            }

            // An English session offers the English voices only and drops the saved French one.
            runBlocking { repository.updateVoiceLanguageTag("en-US") }
            composeRule.waitUntil(5_000) { repository.settings.value.voiceLanguageTag == "en-US" }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("gomode-speaking-voice").assertTextContains("Default (Luna)")
            openPicker()
            composeRule.onNodeWithTag("gomode-speaking-voice-Luna").assertExists()
            composeRule.onNodeWithTag("gomode-speaking-voice-FrenchWaiter").assertDoesNotExist()
            capture("synthesis-en")
            composeRule.onNodeWithTag("gomode-speaking-voice-default").performClick()

            // A realtime model's voices carry no language, so a French session offers them all.
            runBlocking { repository.updateVoiceLanguageTag("fr-FR") }
            composeRule.waitUntil(5_000) { repository.settings.value.voiceLanguageTag == "fr-FR" }
            composeRule.onNodeWithTag("gomode-voice-setup-all-in-one").performScrollTo().performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasTestTag("gomode-voice-choice-live")).fetchSemanticsNodes().isNotEmpty()
            }
            // Gemini Live documents no default voice, so the provider picks it.
            composeRule.onNodeWithTag("gomode-speaking-voice").assertTextContains("Provider default")
            openPicker()
            composeRule.onNodeWithTag("gomode-speaking-voice-Kore").assertExists()
            composeRule.onNodeWithTag("gomode-speaking-voice-Orus").assertExists()
            capture("choice-fr")
        }
    }

    private fun SettingsState.synthesisVoice(base: String) =
        synthesisVoices[SettingsRepository.normalizeURL(base)]?.get("kitten")

    private fun openPicker() {
        composeRule.onNodeWithTag("gomode-speaking-voice").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "speaking-voice-$name.png")
        assertTrue(device.takeScreenshot(file))
    }

    private companion object {
        const val MANIFEST =
            """{"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":3,
            "toolGroups":[],"voiceGateway":{"required":false,"url":"/"}}}"""

        // A realtime model beside a composable language model whose KittenTTS voices each speak one language.
        const val CATALOG =
            """{"choices":[{"id":"live","label":"Gemini Live","transports":["webrtc"],
            "voices":[{"name":"Kore"},{"name":"Orus"}]},
            {"id":"llm","label":"Gateway LLM","transports":["text","webrtc"],"composable":true,
            "speechModes":[{"input":"gateway","output":"gateway"}]}],
            "defaults":[{"transport":"text","id":"llm"},{"transport":"webrtc","id":"llm"}],
            "recognitionModels":[{"id":"parakeet","label":"Parakeet"}],
            "synthesisModels":[{"id":"kitten","label":"Kitten TTS","voices":[
            {"name":"French","language":"fr","default":true},{"name":"FrenchWaiter","language":"fr"},
            {"name":"Jasper","language":"en"},{"name":"Luna","language":"en","default":true}]}]}"""
    }
}
