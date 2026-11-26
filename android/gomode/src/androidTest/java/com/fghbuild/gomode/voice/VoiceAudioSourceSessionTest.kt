// Drives device recognition and the WebRTC microphone track from synthetic PCM and fake glasses, switching mid-call.
package com.fghbuild.gomode.voice

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.testing.FakeG2Firmware
import com.fghbuild.eveng2.testing.fakeG2Scanner
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.MainActivity
import com.fghbuild.gomode.data.GlassesDevice
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SpeechOutput
import com.fghbuild.gomode.data.SpeechStageLocation
import com.fghbuild.gomode.data.VoiceLanguageModel
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.glasses.GlassesMicrophoneStream
import com.fghbuild.gomode.glasses.GlassesOwner
import com.fghbuild.gomode.glasses.followGlassesMicrophone
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.testing.MemoryPreferences
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.halo.testing.FakeHaloTransport
import com.fghbuild.voicegateway.sdk.v2.TurnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        GrantPermissionRule.grant(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.RECORD_AUDIO,
        )

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
            val lowUtteranceOpen = utter(session, low, "tone 600")
            awaitFixture { userMessages(it, messages) == listOf("tone 600") }
            awaitRecognitionRestart(session, low, lowUtteranceOpen)
            val highUtteranceOpen = utter(session, high, "tone 900")
            awaitFixture { userMessages(it, messages) == listOf("tone 600", "tone 900") }
            assertEquals("Switching must close the replaced stream", low.opens.get(), low.closes.get())

            awaitRecognitionRestart(session, high, highUtteranceOpen)
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

    @Test
    fun webRtcHearsTheGlassesMicrophoneAndKeepsTheCallOnThePhoneWhileTheyReconnect() =
        withSession(SpeechStageLocation.GATEWAY) { session ->
            awaitState(session) { it.turnState == TurnState.Idle && !it.speaking && it.transcript.size >= 2 }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val firmware = FakeG2Firmware()
            val glassesScope = MainScope()
            val owner =
                GlassesOwner(
                    glassesScope,
                    context,
                    SettingsRepository(MemoryPreferences()),
                    FakeHaloTransport(context),
                    fakeG2Scanner(),
                    firmware.connector,
                )
            val streams = CoroutineScope(glassesScope.coroutineContext + Dispatchers.Default)
            glassesScope.launch { followGlassesMicrophone(input, flowOf(true), owner.microphone, streams) }
            // The glasses speak every 50 ms, as a pair does, so the capture post-processor transmits them.
            val speaking =
                glassesScope.launch(Dispatchers.Default) {
                    while (true) {
                        // The microphone can stop between the check and the packet, which speak refuses.
                        if (firmware.microphone) runCatching { firmware.speak(ByteArray(G2_MICROPHONE_PACKET_BYTES)) }
                        delay(50)
                    }
                }
            try {
                withContext(Dispatchers.Main) { owner.connect(GlassesDevice.EvenG2(G2_PAIR)) }
                val first = withTimeout(TIMEOUT_MS) { input.source.first { it is GlassesMicrophoneStream } }
                // Only a voice reader collects the microphone: the peer connection opened the glasses' stream.
                awaitStream { firmware.microphone }

                firmware.link(G2Side.RIGHT).drop(status = 8)
                withTimeout(TIMEOUT_MS) { input.source.first { it == VoiceAudioSource.PhoneMicrophone } }
                assertNull(session.state.value.error)
                assertTrue(session.state.value.connected)

                // A reconnection offers a new microphone, which the call hears again. A direct connection
                // stands in for the background one, which waits for the emulator's Bluetooth adapter.
                withContext(Dispatchers.Main) { owner.connect(GlassesDevice.EvenG2(G2_PAIR)) }
                val second = withTimeout(TIMEOUT_MS) { input.source.first { it is GlassesMicrophoneStream } }
                assertNotSame(first, second)
                awaitStream { firmware.requests.count { it.kind == "microphone" && it.text == "on" } == 2 }
                assertNull(session.state.value.error)
            } finally {
                speaking.cancel()
                withContext(Dispatchers.Main) { glassesScope.cancel() }
            }
        }

    // Selects [stream] while recognition listens, so it replaces the previous source under a running recognizer.
    private suspend fun utter(
        session: VoiceSession,
        stream: SyntheticToneStream,
        expected: String,
    ): Int {
        awaitIdleRecognition(session)
        input.select(stream)
        stream.sounding = true
        return try {
            // The partial confirms the recognizer heard the tone. Silence lets it finalize the utterance.
            awaitState(session) {
                it.transcript.lastOrNull()?.let { entry ->
                    entry.speaker == TranscriptSpeaker.USER && !entry.final && entry.text == expected
                } == true
            }
            stream.opens.get()
        } finally {
            stream.sounding = false
        }
    }

    private suspend fun awaitIdleRecognition(session: VoiceSession) =
        awaitState(session) { it.listening && it.turnState == TurnState.Idle && !it.speaking }

    // The gateway can record the utterance before capture closes and reopens its stream for the reply.
    private suspend fun awaitRecognitionRestart(
        session: VoiceSession,
        stream: SyntheticToneStream,
        utteranceOpen: Int,
    ) {
        awaitIdleRecognition(session)
        awaitStream { stream.opens.get() > utteranceOpen }
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
                            speech = SpeechOutput.GATEWAY,
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

        // Five LC3 frames of 40 bytes and a 5-byte trailer; any such packet decodes to 50 ms.
        const val G2_MICROPHONE_PACKET_BYTES = 205

        val G2_PAIR =
            G2Pair(
                "7",
                G2Arm(G2Side.LEFT, "00:00:5E:00:53:0A", "Even G2_7_L_00530A"),
                G2Arm(G2Side.RIGHT, "00:00:5E:00:53:0B", "Even G2_7_R_00530B"),
            )
    }
}
