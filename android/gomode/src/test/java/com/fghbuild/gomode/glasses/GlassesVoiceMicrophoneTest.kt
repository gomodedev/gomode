// Tests that voice hears a fake Even G2's microphone while the setting is on, and the phone's after it disconnects.
package com.fghbuild.gomode.glasses

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2Microphone
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Scanner
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.testing.FakeG2Firmware
import com.fghbuild.gomode.data.GlassesDevice
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.testing.MemoryPreferences
import com.fghbuild.gomode.voice.VoiceAudioInput
import com.fghbuild.gomode.voice.VoiceAudioSource
import com.fghbuild.halo.testing.FakeHaloTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

private val G2 =
    GlassesDevice.EvenG2(
        G2Pair(
            "7",
            G2Arm(G2Side.LEFT, "00:11:22:33:55:01", "Even G2_7_L_335501"),
            G2Arm(G2Side.RIGHT, "00:11:22:33:55:02", "Even G2_7_R_335502"),
        ),
    )

// Five LC3 frames of 40 bytes and a 5-byte trailer.
private const val PACKET_BYTES = 205

// The longest a connection's greeting may last.
private const val GREETING_LIMIT_MS = 10_000L

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GlassesVoiceMicrophoneTest {
    @get:Rule val folder = TemporaryFolder()

    private val context: Application = RuntimeEnvironment.getApplication()
    private val repository = SettingsRepository(MemoryPreferences())
    private val firmware = FakeG2Firmware()
    private val input = VoiceAudioInput()
    private var packets = 0
    private lateinit var owner: GlassesOwner

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        // Robolectric's adapter starts off; background connections wait for it to be on.
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun voiceHearsTheGlassesOnlyWhileTheSettingIsOn() =
        runVoiceTest {
            connect()
            val voice = Reader(this)
            assertEquals(VoiceAudioSource.PhoneMicrophone, input.source.value)
            assertFalse(firmware.microphone)

            useGlassesMicrophone(true)
            assertTrue(input.source.value is GlassesMicrophoneStream)
            assertTrue(firmware.microphone)
            speak()
            assertEquals(listOf(G2Microphone.SAMPLES_PER_PACKET * Short.SIZE_BYTES), voice.heard.map { it.size })

            useGlassesMicrophone(false)
            assertEquals(VoiceAudioSource.PhoneMicrophone, input.source.value)
            assertEquals(2, voice.phone)
            assertFalse(firmware.microphone)
        }

    @Test
    fun aStreamDeliversEachBlockAsLittleEndianPcmInItsOwnBufferAndEndsWithItsFlow() =
        runTest {
            val blocks = Channel<ShortArray>()
            val stream = GlassesMicrophoneStream(blocks.consumeAsFlow(), backgroundScope)
            val heard = mutableListOf<ByteArray>()
            var ended = 0
            stream.open({ pcm, count -> heard += pcm.copyOf(count) }) { ended++ }
            blocks.send(shortArrayOf(0x1234, -2))
            blocks.send(shortArrayOf(1))
            runCurrent()
            assertEquals(
                listOf(listOf<Byte>(0x34, 0x12, -2, -1), listOf<Byte>(1, 0)),
                heard.map { it.toList() },
            )
            assertEquals(0, ended)
            blocks.close()
            runCurrent()
            assertEquals(1, ended)
        }

    @Test
    fun aClosedStreamStopsCollectingAndReportsNoEnd() =
        runTest {
            var collecting = false
            val blocks =
                flow<ShortArray> {
                    collecting = true
                    try {
                        awaitCancellation()
                    } finally {
                        collecting = false
                    }
                }
            var ended = 0
            val handle = GlassesMicrophoneStream(blocks, backgroundScope).open({ _, _ -> }) { ended++ }
            runCurrent()
            assertTrue(collecting)
            handle.close()
            runCurrent()
            assertFalse("Closing must cancel the collector", collecting)
            assertEquals(0, ended)
        }

    @Test
    fun aDisconnectReturnsVoiceToThePhoneAndTheReconnectionToTheGlasses() =
        runVoiceTest {
            useGlassesMicrophone(true)
            connect()
            val voice = Reader(this)
            val first = input.source.value
            assertTrue(first is GlassesMicrophoneStream)
            speak()
            assertEquals(1, voice.heard.size)

            firmware.link(G2Side.RIGHT).drop(status = 8)
            owner.state.first { it.link is GlassesLink.Reconnecting }
            runCurrent()
            assertEquals(VoiceAudioSource.PhoneMicrophone, input.source.value)
            assertEquals(1, voice.phone)

            advanceTimeBy(RECONNECT_INITIAL_DELAY_MS)
            owner.microphone.first { it != null }
            advanceTimeBy(GREETING_LIMIT_MS)
            runCurrent()
            val second = input.source.value
            assertTrue(second is GlassesMicrophoneStream)
            assertNotSame(first, second)
            // The new session turns the microphone on for voice's new reader.
            assertEquals(2, microphoneStarts())
            speak()
            assertEquals(2, voice.heard.size)
        }

    @Test
    fun theRecorderAndVoiceHearTheGlassesTogetherAndNeitherStopsTheOther() =
        runVoiceTest {
            useGlassesMicrophone(true)
            connect()
            val voice = Reader(this)
            val microphone = checkNotNull(owner.microphone.value)
            val recording =
                backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
                    recordGlassesMicrophone(microphone.microphone, folder.newFile())
                }
            runCurrent()
            val packets = GLASSES_RECORDING_SECONDS * 20
            repeat(packets) { speak() }
            assertEquals(GLASSES_RECORDING_SECONDS.toDouble(), recording.await(), 0.0)
            assertEquals(packets, voice.heard.size)

            // The finished recording leaves voice's microphone on.
            assertTrue(firmware.microphone)
            speak()
            assertEquals(packets + 1, voice.heard.size)
        }

    private fun runVoiceTest(body: suspend TestScope.() -> Unit) =
        runTest {
            owner =
                GlassesOwner(
                    backgroundScope,
                    context,
                    repository,
                    FakeHaloTransport(context),
                    G2Scanner(context),
                    firmware.connector,
                )
            val enabled = repository.settings.map { it.glassesMicrophoneForVoice }
            backgroundScope.launch { followGlassesMicrophone(input, enabled, owner.microphone, backgroundScope) }
            body()
        }

    // The repository publishes the saved setting from its own thread.
    private suspend fun TestScope.useGlassesMicrophone(enabled: Boolean) {
        repository.updateGlassesMicrophoneForVoice(enabled)
        repository.settings.first { it.glassesMicrophoneForVoice == enabled }
        runCurrent()
    }

    private suspend fun TestScope.connect() {
        owner.start()
        owner.connect(G2)
        owner.microphone.first { it != null }
        advanceTimeBy(GREETING_LIMIT_MS)
        runCurrent()
    }

    private fun microphoneStarts() = firmware.requests.count { it.kind == "microphone" && it.text == "on" }

    // Both arms notify one packet, which the SDK keeps once; each counts one up in its last byte.
    private fun TestScope.speak() {
        firmware.speak(ByteArray(PACKET_BYTES).also { it[PACKET_BYTES - 1] = (++packets).toByte() })
        runCurrent()
    }

    // Follows the input as both voice readers do: the phone microphone needs no stream.
    private inner class Reader(
        scope: TestScope,
    ) {
        var phone = 0
        val heard = mutableListOf<ByteArray>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                input.source.collectLatest { source ->
                    when (source) {
                        VoiceAudioSource.PhoneMicrophone -> phone++
                        is VoiceAudioSource.Stream -> input.read(source) { pcm, count -> heard += pcm.copyOf(count) }
                    }
                }
            }
            scope.runCurrent()
        }
    }
}
