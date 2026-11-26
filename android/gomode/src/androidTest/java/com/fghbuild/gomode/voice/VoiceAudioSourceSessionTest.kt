// Drives device recognition and the WebRTC microphone track from synthetic PCM, switching and ending it mid-call.
package com.fghbuild.gomode.voice

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.MainActivity
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SpeechStageLocation
import com.fghbuild.gomode.data.VoiceLanguageModel
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.testing.MemoryPreferences
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.voicegateway.sdk.v2.TurnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

// The test recognizer reports "tone <Hz>" for a tone in the PCM it reads, and the gateway fixture counts the
// tones it decodes, so each path's output shows which source it heard. Streams enter through the app's
// VoiceAudioInput, as any production source does.
@RunWith(AndroidJUnit4::class)
class VoiceAudioSourceSessionTest {
    @get:Rule(order = 0)
    val speech: TestRule = ModelFreeSpeechRule()

    @get:Rule(order = 1)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private val input =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GoModeApplication)
            .voiceAudioInput

    @After
    fun selectPhoneMicrophone() = input.select(VoiceAudioSource.PhoneMicrophone)

    @Test
    @ModelFreeSpeech
    fun deviceRecognitionHearsEachSelectedStreamAndReturnsToThePhoneWhenOneEnds() =
        withSession(SpeechStageLocation.DEVICE) { session ->
            val low = SyntheticToneStream(600)
            val high = SyntheticToneStream(900)
            val messages = fixtureStats().getJSONArray("rtcUserMessages").length()
            utter(session, low)
            awaitFixture { userMessages(it, messages) == listOf("tone 600") }
            utter(session, high)
            awaitFixture { userMessages(it, messages) == listOf("tone 600", "tone 900") }
            assertEquals("Switching must close the replaced stream", low.opens.get(), low.closes.get())

            // Recognition closes its source while the gateway thinks, and opens it again for the next turn.
            high.end()
            withTimeout(TIMEOUT_MS) { input.source.first { it == VoiceAudioSource.PhoneMicrophone } }
            awaitStream { high.closes.get() == high.opens.get() }
            assertNull(awaitState(session) { it.listening }.error)
        }

    @Test
    fun webRtcTransmitsEachSelectedStreamAndReturnsToThePhoneWhenOneEnds() =
        withSession(SpeechStageLocation.GATEWAY) { session ->
            awaitState(session) { it.turnState == TurnState.Idle && !it.speaking && it.transcript.size >= 2 }
            val low = SyntheticToneStream(600).apply { sounding = true }
            val high = SyntheticToneStream(900).apply { sounding = true }
            val before = fixtureStats()
            input.select(low)
            awaitFixture { tones(it, 600) >= tones(before, 600) + 25 }
            input.select(high)
            awaitFixture { tones(it, 900) >= tones(before, 900) + 25 }
            assertEquals("Switching must close the replaced stream", 1, low.closes.get())

            // Muting stops reading the stream; unmuting opens it again.
            withContext(Dispatchers.Main) { session.setMicrophoneMode(MicrophoneMode.MUTED) }
            awaitStream { high.closes.get() == 1 }
            withContext(Dispatchers.Main) { session.setMicrophoneMode(MicrophoneMode.LISTENING) }
            val resumed = fixtureStats()
            awaitFixture { tones(it, 900) >= tones(resumed, 900) + 25 }
            assertEquals(2, high.opens.get())

            high.end()
            withTimeout(TIMEOUT_MS) { input.source.first { it == VoiceAudioSource.PhoneMicrophone } }
            awaitStream { high.closes.get() == 2 }
            // Packets already in flight still arrive; after them the phone microphone carries no tone.
            delay(500)
            val settled = tones(fixtureStats(), 900)
            delay(1_000)
            assertEquals("The phone microphone must replace the ended stream", settled, tones(fixtureStats(), 900))
            assertNull(session.state.value.error)
        }

    // Selects [stream] while recognition listens, so it replaces the previous source under a running recognizer.
    private suspend fun utter(
        session: VoiceSession,
        stream: SyntheticToneStream,
    ) {
        awaitState(session) { it.listening && it.turnState == TurnState.Idle && !it.speaking }
        input.select(stream)
        stream.sounding = true
        delay(600)
        stream.sounding = false
    }

    private fun userMessages(
        stats: JSONObject,
        from: Int,
    ): List<String> = stats.getJSONArray("rtcUserMessages").let { all -> (from until all.length()).map(all::getString) }

    private fun tones(
        stats: JSONObject,
        hz: Int,
    ): Int = stats.optJSONObject("micTones")?.optInt(hz.toString()) ?: 0

    private fun withSession(
        recognition: SpeechStageLocation,
        block: suspend (VoiceSession) -> Unit,
    ): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = SettingsRepository(MemoryPreferences())
            repository.addService("fixture", baseUrl)
            repository.updateSavedVoiceSetup(null) {
                it.copy(
                    preference =
                        it.preference.copy(
                            mode = VoiceSetupMode.SEPARATE_STAGES,
                            recognition = recognition,
                            speech = SpeechStageLocation.GATEWAY,
                            languageModel = VoiceLanguageModel.GATEWAY,
                        ),
                )
            }
            repository.settings.first {
                it.activeServiceURL == baseUrl && it.voiceSetup.effectiveRecognition == recognition
            }
            val activity = ActivityScenario.launch(MainActivity::class.java)
            val pages = ServiceShellOwner(context, repository, glassesDisplay = { null })
            val conversation =
                VoiceConversation(repository, ServiceSettingsClient(), pages.catalog) { pages.entries }
            val session =
                VoiceSession(
                    context,
                    repository,
                    conversation,
                    "en-US",
                    thinkingEarcon =
                        object : ThinkingEarcon {
                            override fun setThinking(active: Boolean) = Unit
                        },
                )
            try {
                withTimeout(TIMEOUT_MS) {
                    withContext(Dispatchers.Main) { pages.prepareCall(repository.settings.value) {} }
                }
                activity.onActivity { session.connect() }
                val ready = awaitState(session) { it.connected || it.error != null }
                assertNull(ready.error)
                block(session)
            } finally {
                withContext(Dispatchers.Main) {
                    session.close()
                    pages.close()
                }
                activity.close()
            }
        }

    private suspend fun awaitState(
        session: VoiceSession,
        predicate: (VoiceState) -> Boolean,
    ): VoiceState =
        withTimeout(TIMEOUT_MS) {
            session.state.first {
                check(it.error == null) { "Voice session failed: ${it.error}" }
                predicate(it)
            }
        }

    private suspend fun awaitStream(predicate: () -> Boolean) =
        withTimeout(TIMEOUT_MS) {
            while (!predicate()) delay(20)
        }

    private suspend fun awaitFixture(predicate: (JSONObject) -> Boolean) =
        withTimeout(TIMEOUT_MS) {
            while (!predicate(fixtureStats())) delay(50)
        }

    private suspend fun fixtureStats(): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = URL(baseUrl.trimEnd('/') + "/fixture/stats").openConnection() as HttpURLConnection
            try {
                // A stalled fixture connection must fail here: withTimeout cannot interrupt a blocking read.
                connection.connectTimeout = TIMEOUT_MS.toInt()
                connection.readTimeout = TIMEOUT_MS.toInt()
                check(connection.responseCode == HttpURLConnection.HTTP_OK) { "stats: HTTP ${connection.responseCode}" }
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }

    private val baseUrl: String
        get() =
            requireNotNull(InstrumentationRegistry.getArguments().getString("gatewayBaseUrl")) {
                "gatewayBaseUrl instrumentation argument is required"
            }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
