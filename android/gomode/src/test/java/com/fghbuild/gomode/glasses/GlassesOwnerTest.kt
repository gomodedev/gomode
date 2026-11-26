// Tests the app-scoped glasses owner against fake Halo and Even G2 transports: connect, reconnect, failures, switching.
package com.fghbuild.gomode.glasses

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2Charge
import com.fghbuild.eveng2.G2InputEvent
import com.fghbuild.eveng2.G2Microphone
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Protocol
import com.fghbuild.eveng2.G2Scanner
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.G2TextPage
import com.fghbuild.eveng2.testing.FakeG2Firmware
import com.fghbuild.gomode.R
import com.fghbuild.gomode.data.GlassesDevice
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.testing.MemoryPreferences
import com.fghbuild.gomode.ui.glasses.GlassesInputsScreen
import com.fghbuild.gomode.ui.glasses.GlassesScreen
import com.fghbuild.halo.ble.HaloDeviceType
import com.fghbuild.halo.ble.HaloScannedDevice
import com.fghbuild.halo.msg.ClickType
import com.fghbuild.halo.testing.FakeHalo
import com.fghbuild.halo.testing.FakeHaloTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

private const val ADDRESS = "00:11:22:33:44:55"
private const val OTHER_ADDRESS = "00:11:22:33:44:66"
private const val FRAME_ADDRESS = "00:11:22:33:44:77"
private const val G2_LEFT_ADDRESS = "00:11:22:33:55:01"
private const val G2_RIGHT_ADDRESS = "00:11:22:33:55:02"

// Arm names as an Even G2 pair advertises them: the pair field, the side, and the address's last three bytes.
private val G2_PAIR =
    G2Pair(
        "7",
        G2Arm(G2Side.LEFT, G2_LEFT_ADDRESS, "Even G2_7_L_335501"),
        G2Arm(G2Side.RIGHT, G2_RIGHT_ADDRESS, "Even G2_7_R_335502"),
    )
private val HALO = GlassesDevice.Halo(ADDRESS)

// Five LC3 frames of 40 bytes and a 5-byte trailer.
private const val G2_MICROPHONE_PACKET_BYTES = 205
private val G2 = GlassesDevice.EvenG2(G2_PAIR)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassesOwnerTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val repository = SettingsRepository(MemoryPreferences())
    private val transport = ScanningHaloTransport(context)
    private val firmware = FakeG2Firmware()
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
    fun scanningListsHaloAndEvenG2AndConnectingAHaloSavesItStartsTheAppAndShowsTheLatestText() =
        runOwnerTest {
            advertise(G2_PAIR.left, G2_PAIR.right)
            owner.start()
            owner.startScan()
            runCurrent()
            // The scan also found a Brilliant Frame, which Go Mode does not drive.
            assertEquals(
                listOf(ScannedGlasses(G2, "Even G2 7", null), ScannedGlasses(HALO, "Halo AB", -50)),
                owner.state.value.devices,
            )

            owner.connect(
                owner.state.value.devices
                    .single { it.device is GlassesDevice.Halo }
                    .device,
            )
            owner.state.first { it.link is GlassesLink.Connected }
            finishGreeting()
            assertEquals(false, owner.state.value.scanning)
            assertEquals(HALO, repository.settings.first { it.glasses != null }.glasses)
            val halo = transport.halos.single()
            assertTrue(halo.files.toString(), halo.files.containsAll(listOf("text_page.lua", "main.lua")))
            assertEquals(Shown.Clear, halo.shown().last())

            owner.show(GlassesFrame.Text("one\ntwo"))
            runCurrent()
            assertEquals(listOf(Shown.Clear, Shown.Page(lines = 2)), halo.shown().takeLast(2))
        }

    @Test
    fun theConnectionOutlivesTheActivityThatShowedTheHaloScreen() =
        runOwnerTest {
            connectSavedGlasses()
            val first = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            first.get().setContent { GlassesScreen(owner, onNavigateBack = {}, onOpenInputs = {}) }
            owner.show(GlassesFrame.Text("before"))
            runCurrent()

            first.pause().stop().destroy()
            val second = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            second.get().setContent { GlassesScreen(owner, onNavigateBack = {}, onOpenInputs = {}) }
            owner.show(GlassesFrame.Text("after\nreplacement"))
            runCurrent()

            assertEquals(GlassesLink.Connected(HALO_DISPLAY, HaloScreenFit), owner.state.value.link)
            assertEquals(0, transport.disconnects)
            assertEquals(
                listOf(Shown.Clear, Shown.Page(lines = 1), Shown.Page(lines = 2)),
                transport.halos
                    .single()
                    .shown()
                    .takeLast(3),
            )
            second.pause().stop().destroy()
        }

    @Test
    fun aDroppedHaloWaitsInOneBackgroundConnectionUntilItReturnsAndShowsTheTextAgain() =
        runOwnerTest {
            connectSavedGlasses()
            assertEquals(HALO_DISPLAY, owner.connectedDisplay)
            owner.show(GlassesFrame.Text("hello"))
            runCurrent()
            // Connecting to saved glasses is direct.
            assertEquals(0, transport.backgroundConnects)

            transport.present = false
            transport.halos.single().drop()
            runCurrent()
            assertEquals(GlassesLink.Reconnecting(GlassesProblem.UNREACHABLE), owner.state.value.link)
            assertEquals(null, owner.connectedDisplay)
            assertEquals(1, transport.connects)

            advanceTimeBy(RECONNECT_INITIAL_DELAY_MS)
            runCurrent()
            assertEquals(1, transport.waiting.value)
            // The glasses stay off for an hour: the one background connection keeps waiting, and
            // nothing else connects.
            advanceTimeBy(60 * RECONNECT_MAX_DELAY_MS)
            runCurrent()
            assertEquals(2, transport.connects)
            assertEquals(1, transport.backgroundConnects)
            assertEquals(1, transport.waiting.value)
            assertEquals(GlassesLink.Reconnecting(GlassesProblem.UNREACHABLE), owner.state.value.link)

            transport.present = true
            owner.state.first { it.link is GlassesLink.Connected }
            runCurrent()
            assertEquals(0, transport.waiting.value)
            assertEquals(listOf(Shown.Page(lines = 1)), transport.halos.last().shown())
            assertEquals(HALO_DISPLAY, owner.connectedDisplay)
        }

    @Test
    fun aHaloThatDropsAgainAtOnceReconnectsWithBackoff() =
        runOwnerTest {
            connectSavedGlasses()
            // Each background connection comes up and fails before the glasses take the app.
            transport.unreachableAttempts = 2
            transport.halos.single().drop()
            runCurrent()

            advanceTimeBy(RECONNECT_INITIAL_DELAY_MS)
            runCurrent()
            assertEquals(2, transport.connects)
            assertEquals(GlassesLink.Reconnecting(GlassesProblem.UNREACHABLE), owner.state.value.link)
            // The failed attempt doubles the delay before the next one.
            advanceTimeBy(2 * RECONNECT_INITIAL_DELAY_MS - 1)
            assertEquals(2, transport.connects)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, transport.connects)
            advanceTimeBy(4 * RECONNECT_INITIAL_DELAY_MS)
            owner.state.first { it.link is GlassesLink.Connected }
            assertEquals(4, transport.connects)
            assertEquals(3, transport.backgroundConnects)
        }

    @Test
    fun forgettingGlassesThatAreAwayReleasesTheirBackgroundConnection() =
        runOwnerTest {
            connectSavedGlasses()
            transport.present = false
            transport.halos.single().drop()
            advanceTimeBy(RECONNECT_INITIAL_DELAY_MS)
            runCurrent()
            assertEquals(1, transport.waiting.value)

            owner.forget()
            runCurrent()
            assertEquals(0, transport.waiting.value)
            assertEquals(GlassesLink.Disconnected, owner.state.value.link)
            transport.present = true
            advanceTimeBy(RECONNECT_MAX_DELAY_MS)
            runCurrent()
            assertEquals(2, transport.connects)
            assertEquals(1, transport.halos.size)
        }

    @Test
    fun bluetoothTurningOffReleasesTheBackgroundConnectionUntilItIsBackOn() =
        runOwnerTest {
            connectSavedGlasses()
            transport.present = false
            transport.halos.single().drop()
            advanceTimeBy(RECONNECT_INITIAL_DELAY_MS)
            runCurrent()
            assertEquals(1, transport.waiting.value)

            setBluetooth(on = false)
            runCurrent()
            assertEquals(0, transport.waiting.value)
            assertEquals(GlassesLink.Reconnecting(GlassesProblem.UNREACHABLE), owner.state.value.link)
            // Nothing is armed while Bluetooth is off.
            advanceTimeBy(RECONNECT_MAX_DELAY_MS)
            runCurrent()
            assertEquals(2, transport.connects)

            setBluetooth(on = true)
            runCurrent()
            assertEquals(3, transport.connects)
            assertEquals(1, transport.waiting.value)
            transport.present = true
            owner.state.first { it.link is GlassesLink.Connected }
        }

    @Test
    fun glassesThatRejectTextReconnectAsUnresponsive() =
        runOwnerTest {
            connectSavedGlasses()
            transport.halos.single().rejectMessages = true
            owner.show(GlassesFrame.Text("hello"))
            runCurrent()
            assertEquals(GlassesLink.Reconnecting(GlassesProblem.UNRESPONSIVE), owner.state.value.link)
            assertEquals(1, transport.disconnects)
        }

    @Test
    fun forgettingClearsTheDisplayDisconnectsAndStopsReconnecting() =
        runOwnerTest {
            connectSavedGlasses()
            owner.show(GlassesFrame.Text("hello"))
            runCurrent()

            owner.forget()
            runCurrent()
            assertEquals(GlassesLink.Disconnected, owner.state.value.link)
            assertEquals(null, owner.connectedDisplay)
            assertEquals(
                listOf(Shown.Clear, Shown.Page(lines = 1), Shown.Clear),
                transport.halos
                    .single()
                    .shown()
                    .takeLast(3),
            )
            assertEquals(1, transport.disconnects)
            repository.settings.first { it.glasses == null }
            owner.state.first { it.saved == null }

            advanceTimeBy(RECONNECT_MAX_DELAY_MS)
            runCurrent()
            assertEquals(1, transport.connects)
        }

    @Test
    fun autoConnectConnectsToTheSavedGlassesWhenTheOwnerStarts() =
        runOwnerTest {
            repository.updateGlasses(HALO)
            repository.updateGlassesAutoConnect(true)
            repository.settings.first { it.glassesAutoConnect && it.glasses == HALO }

            owner.start()
            owner.state.first { it.link is GlassesLink.Connected }
            assertEquals(1, transport.connects)
        }

    @Test
    fun disconnectingWhileConnectingNeverReportsARetry() =
        runOwnerTest {
            val links = recordLinks()
            transport.hangConnects = true
            owner.connect(HALO)
            runCurrent()
            assertEquals(GlassesLink.Connecting, owner.state.value.link)

            owner.disconnect()
            runCurrent()
            advanceTimeBy(RECONNECT_MAX_DELAY_MS)
            runCurrent()
            assertEquals(GlassesLink.Disconnected, owner.state.value.link)
            assertTrue("$links", links.none { it is GlassesLink.Reconnecting })
            assertEquals(1, transport.connects)
        }

    @Test
    fun switchingGlassesWhileConnectingNeverReportsARetry() =
        runOwnerTest {
            val links = recordLinks()
            transport.hangConnects = true
            owner.connect(HALO)
            runCurrent()

            transport.hangConnects = false
            owner.connect(GlassesDevice.Halo(OTHER_ADDRESS))
            owner.state.first { it.link is GlassesLink.Connected }
            runCurrent()
            assertTrue("$links", links.none { it is GlassesLink.Reconnecting })
            assertEquals(listOf(ADDRESS, OTHER_ADDRESS), transport.attempts)
            assertEquals(
                OTHER_ADDRESS,
                transport.halos
                    .single()
                    .device.platformDevice.address,
            )
        }

    @Test
    fun aFrameStopsTheLinkWithAnAccurateErrorAndNoRetry() =
        runOwnerTest {
            transport.connectedType = HaloDeviceType.FRAME
            owner.connect(GlassesDevice.Halo(FRAME_ADDRESS))
            runCurrent()
            advanceTimeBy(RECONNECT_MAX_DELAY_MS)
            runCurrent()
            assertEquals(GlassesLink.Disconnected, owner.state.value.link)
            assertEquals("Go Mode supports Halo only; this device is a Brilliant Frame", owner.state.value.error)
            assertEquals(1, transport.connects)
            assertEquals(1, transport.disconnects)
        }

    @Test
    fun anEvenG2OpensOneTextContainerAndUpdatesItInPlace() =
        runOwnerTest {
            owner.start()
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            assertEquals(GlassesLink.Connected(G2_DISPLAY, G2ScreenFit(G2TextPage())), owner.state.value.link)
            // MCP requests declare a 576×288 rectangle of 16 grey levels.
            assertEquals(G2_DISPLAY, owner.connectedDisplay)
            assertEquals(G2, repository.settings.first { it.glasses != null }.glasses)

            owner.show(GlassesFrame.Text("one\ntwo"))
            runCurrent()
            owner.show(GlassesFrame.Text("three"))
            runCurrent()
            assertEquals(
                listOf(
                    "RIGHT auth",
                    "LEFT auth",
                    "RIGHT prelude",
                    "RIGHT settings",
                    "RIGHT create ",
                    "RIGHT update one\ntwo",
                    "RIGHT update three",
                ),
                firmware.requests.filter { it.kind != "heartbeat" }.map { it.toString() },
            )
        }

    @Test
    fun anEvenG2sMicrophoneRunsWhileCollectedUntilItsConnectionEnds() =
        runOwnerTest {
            owner.start()
            assertEquals(null, owner.microphone.value)
            owner.connect(G2)
            val microphone = owner.microphone.first { it != null }!!
            finishGreeting()
            assertFalse(firmware.microphone)

            val heard = mutableListOf<ShortArray>()
            val listening =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    microphone.microphone.collect { heard += it }
                }
            runCurrent()
            assertTrue(firmware.microphone)
            // Any 205 bytes decode to 50 ms of samples.
            firmware.speak(ByteArray(G2_MICROPHONE_PACKET_BYTES))
            runCurrent()
            assertEquals(listOf(G2Microphone.SAMPLES_PER_PACKET), heard.map { it.size })

            owner.disconnect()
            runCurrent()
            assertEquals(null, owner.microphone.value)
            assertTrue(listening.isCompleted)
            assertFalse(firmware.microphone)

            // The owner keeps its G2Glasses: a finished collector must not start the next connection's microphone.
            owner.connect(G2)
            owner.microphone.first { it != null }
            finishGreeting()
            assertFalse(firmware.microphone)
        }

    @Test
    fun anEvenG2sBatteriesStayInTheStateUntilItsConnectionEnds() =
        runOwnerTest {
            firmware.charge = G2Charge(71, charging = true)
            firmware.case = G2Charge(55, charging = false)
            owner.start()
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            assertEquals(emptyList<GlassesBattery>(), owner.state.value.batteries)

            finishGreeting()
            advanceTimeBy(2 * G2Protocol.BATTERY_QUIET_MS)
            runCurrent()
            assertEquals(
                listOf(
                    GlassesBattery(GlassesBatteryPart.GLASSES, 71, charging = true),
                    GlassesBattery(GlassesBatteryPart.CASE, 55, charging = false),
                ),
                owner.state.value.batteries,
            )

            // Out of the case, the case's reading goes.
            firmware.case = null
            firmware.pushCase()
            runCurrent()
            assertEquals(
                listOf(GlassesBatteryPart.GLASSES),
                owner.state.value.batteries
                    .map { it.part },
            )

            owner.disconnect()
            assertEquals(emptyList<GlassesBattery>(), owner.state.value.batteries)
        }

    @Test
    fun aDroppedEvenG2LeavesNoBatteriesWhileItReconnects() =
        runOwnerTest {
            owner.start()
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            finishGreeting()
            advanceTimeBy(2 * G2Protocol.BATTERY_QUIET_MS)
            runCurrent()
            assertEquals(1, owner.state.value.batteries.size)

            firmware.link(G2Side.RIGHT).drop(status = 8)
            owner.state.first { it.link is GlassesLink.Reconnecting }
            assertEquals(emptyList<GlassesBattery>(), owner.state.value.batteries)
        }

    @Test
    fun anEvenG2OnFaceclawFirmwareDrawsTextAndImagesOnItsWholePanel() =
        runOwnerTest {
            firmware.revision = "Faceclaw/45"
            owner.start()
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            assertEquals(GlassesLink.Connected(G2_FACECLAW_DISPLAY, G2PanelFit), owner.state.value.link)
            // MCP requests declare a 640×480 rectangle of 16 grey levels that takes PNG images.
            assertEquals(G2_FACECLAW_DISPLAY, owner.connectedDisplay)

            owner.show(GlassesFrame.Text("one\ntwo"))
            runCurrent()
            assertEquals(listOf("one", "two"), firmware.presented.last().lines)

            // White, black, green, and white at 40 % opacity, centred on the panel.
            val image =
                Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888).apply {
                    setPixel(0, 0, Color.WHITE)
                    setPixel(1, 0, Color.BLACK)
                    setPixel(2, 0, Color.GREEN)
                    setPixel(3, 0, Color.argb(102, 255, 255, 255))
                }
            owner.show(GlassesFrame.Image(image))
            runCurrent()
            val frame = firmware.presented.last()
            assertEquals(listOf(0, 15, 0, 9, 6, 0), (317..322).map { frame.level(it, 239) })
            assertEquals(emptyList<String>(), frame.lines)
        }

    @Test
    fun aDroppedEvenG2WaitsInOneBackgroundConnectionPerArmUntilItReturns() =
        runOwnerTest {
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            owner.show(GlassesFrame.Text("hello"))
            runCurrent()
            assertEquals(emptyList<G2Side>(), firmware.backgroundAttempts)

            firmware.reachable = { false }
            firmware.link(G2Side.RIGHT).drop(status = 8)
            runCurrent()
            assertEquals(GlassesLink.Reconnecting(GlassesProblem.UNREACHABLE), owner.state.value.link)
            assertEquals(null, owner.connectedDisplay)

            advanceTimeBy(RECONNECT_INITIAL_DELAY_MS)
            runCurrent()
            assertEquals(2, firmware.waiting.value)
            advanceTimeBy(60 * RECONNECT_MAX_DELAY_MS)
            runCurrent()
            assertEquals(listOf(G2Side.LEFT, G2Side.RIGHT), firmware.backgroundAttempts.sorted())
            assertEquals(2, firmware.waiting.value)
            assertEquals(GlassesLink.Reconnecting(GlassesProblem.UNREACHABLE), owner.state.value.link)

            firmware.reachable = { true }
            owner.state.first { it.link is GlassesLink.Connected }
            runCurrent()
            assertEquals(0, firmware.waiting.value)
            assertEquals("RIGHT create hello", firmware.requests.last { it.kind == "create" }.toString())
            assertEquals(G2_DISPLAY, owner.connectedDisplay)
        }

    @Test
    fun anEvenG2ThatRejectsGoModeBacksOffBetweenBackgroundAttempts() =
        runOwnerTest {
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            firmware.authenticates = { false }
            firmware.link(G2Side.LEFT).drop(status = 8)
            runCurrent()

            // Each attempt connects both arms and waits out AUTH; the delays between them double.
            val authAttempts = { firmware.backgroundAttempts.count { it == G2Side.RIGHT } }
            advanceTimeBy(RECONNECT_INITIAL_DELAY_MS)
            runCurrent()
            assertEquals(1, authAttempts())
            advanceTimeBy(G2Protocol.AUTH_TIMEOUT_MS)
            runCurrent()
            assertEquals(GlassesLink.Reconnecting(GlassesProblem.UNRESPONSIVE), owner.state.value.link)
            advanceTimeBy(2 * RECONNECT_INITIAL_DELAY_MS - 1)
            assertEquals(1, authAttempts())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, authAttempts())
            assertTrue(firmware.links.dropLast(2).all { it.closed })
        }

    @Test
    fun anEvenG2ThatStopsAnsweringReconnectsAsUnresponsive() =
        runOwnerTest {
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            firmware.answersUpdates = false
            owner.show(GlassesFrame.Text("hello"))
            owner.state.first { it.link is GlassesLink.Reconnecting }
            assertEquals(GlassesLink.Reconnecting(GlassesProblem.UNRESPONSIVE), owner.state.value.link)
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun anEvenG2PageThatTheGlassesCloseReturnsWithTheLatestFrame() =
        runOwnerTest {
            owner.start()
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            owner.show(GlassesFrame.Text("hello"))
            runCurrent()
            val page = firmware.pageSession

            // The page closed before it settled, so the next one follows a pause.
            firmware.exitPage(type = G2InputEvent.EXIT, session = page)
            advanceTimeBy(G2Protocol.PAGE_RETURN_DELAY_MS)
            runCurrent()
            assertTrue(firmware.pageSession != page)
            assertEquals("hello", firmware.shownText())
            assertTrue(owner.state.value.link is GlassesLink.Connected)
            assertEquals(null, owner.state.value.error)
            assertEquals(2, firmware.attempts.size)
            assertTrue(firmware.links.none { it.closed })
        }

    @Test
    fun turningAutoConnectOnConnectsToTheSavedGlassesUnlessAConnectionRuns() =
        runOwnerTest {
            repository.updateGlasses(HALO)
            repository.settings.first { it.glasses == HALO }
            owner.start()
            runCurrent()
            assertEquals(GlassesLink.Disconnected, owner.state.value.link)

            owner.updateAutoConnect(true)
            owner.state.first { it.link is GlassesLink.Connected }
            repository.settings.first { it.glassesAutoConnect }
            owner.updateAutoConnect(false)
            owner.updateAutoConnect(true)
            runCurrent()
            assertEquals(1, transport.connects)
            assertTrue(owner.state.value.link is GlassesLink.Connected)
        }

    @Test
    fun turningAutoConnectOnWithoutNearbyDevicesSavesItAndReportsThePermission() =
        runOwnerTest {
            shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            repository.updateGlasses(HALO)
            repository.settings.first { it.glasses == HALO }
            owner.start()
            runCurrent()
            assertEquals(null, owner.state.value.error)

            owner.updateAutoConnect(true)
            repository.settings.first { it.glassesAutoConnect }
            assertEquals("Allow Nearby devices to connect to glasses", owner.state.value.error)
            assertEquals(GlassesLink.Disconnected, owner.state.value.link)
            assertEquals(0, transport.connects)
        }

    @Test
    fun anUnexpectedEvenG2TransportFailureIsPermanent() =
        runOwnerTest {
            firmware.failure = { SecurityException("BLUETOOTH_CONNECT") }
            owner.connect(G2)
            owner.state.first { it.error != null }
            assertEquals(GlassesLink.Disconnected, owner.state.value.link)
            assertEquals("Could not use Even G2: BLUETOOTH_CONNECT", owner.state.value.error)
            val attempts = firmware.attempts.size

            advanceTimeBy(RECONNECT_MAX_DELAY_MS)
            runCurrent()
            assertEquals(attempts, firmware.attempts.size)
        }

    @Test
    fun switchingBetweenHaloAndEvenG2KeepsOnePairConnectedAndSavesTheNewOne() =
        runOwnerTest {
            val links = recordLinks()
            // What each connection found of the other model's link.
            val haloDisconnectsAtG2Arms = mutableListOf<Int>()
            val g2ClosedAtHaloAttempts = mutableListOf<Boolean>()
            firmware.connected = { haloDisconnectsAtG2Arms += transport.disconnects }
            transport.connecting = { g2ClosedAtHaloAttempts += firmware.links.all { it.closed } }
            connectSavedGlasses()
            owner.show(GlassesFrame.Text("hello"))
            runCurrent()

            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected && it.saved == G2 }
            runCurrent()
            // The Halo was cleared and disconnected before either G2 arm connected; the G2 shows the same text.
            assertEquals(listOf(1, 1), haloDisconnectsAtG2Arms)
            assertEquals(
                Shown.Clear,
                transport.halos
                    .single()
                    .shown()
                    .last(),
            )
            assertEquals("hello", firmware.shownText())
            assertEquals(G2_DISPLAY, owner.connectedDisplay)
            assertEquals(G2, repository.settings.first { it.glasses == G2 }.glasses)

            owner.connect(HALO)
            owner.state.first { it.link is GlassesLink.Connected && it.saved == HALO }
            runCurrent()
            assertEquals(listOf("RIGHT update ", "RIGHT shutdown"), firmware.requests.takeLast(2).map { it.toString() })
            // The G2 page was cleared and closed, and both arms disconnected, before the Halo connection began.
            assertEquals(listOf(true, true), g2ClosedAtHaloAttempts)
            assertEquals(2, transport.halos.size)
            assertTrue(transport.halos.last().lastSent(haloTextMessages("hello")))
            assertEquals(HALO_DISPLAY, owner.connectedDisplay)
            assertEquals(HALO, repository.settings.first { it.glasses == HALO }.glasses)
            assertTrue("$links", links.none { it is GlassesLink.Reconnecting })
        }

    @Test
    fun anImageDrawsOnHaloAndEvenG2WhichDrawsNoImagesIsLeftBlank() =
        runOwnerTest {
            connectSavedGlasses()
            val image = Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            owner.notify("Open Go Mode")
            owner.show(GlassesFrame.Image(image))
            runCurrent()
            val halo = transport.halos.single()
            // The image ends the notice; its 40 rows take two strips.
            assertTrue(halo.lastSent(haloImageMessages(image)))
            assertEquals(Shown.Page(lines = 2), halo.shown().last())

            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected && it.saved == G2 }
            runCurrent()
            assertEquals("", firmware.shownText())
        }

    @Test
    fun theScreenFollowsTheConnectedDisplay() =
        runOwnerTest {
            val fits = mutableListOf<ScreenFit?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                owner.screen.collect {
                    fits +=
                        it?.screenFit
                }
            }
            connectSavedGlasses()
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected && it.saved == G2 }
            owner.disconnect()
            runCurrent()
            assertEquals(listOf(null, HaloScreenFit, null, G2ScreenFit(G2TextPage()), null), fits)
        }

    @Test
    fun aHaloPressAndEvenG2DoubleTapsToggleVoiceAndSingleTapsAndStockLongPressesDoNothing() =
        runOwnerTest {
            val actions = mutableListOf<GlassesAction>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.actions.collect { actions += it } }
            connectSavedGlasses()
            val halo = transport.halos.single()
            halo.pressButton(ClickType.DOUBLE)
            halo.pressButton(ClickType.LONG)
            halo.pressButton(ClickType.SINGLE)
            runCurrent()
            assertEquals(listOf(GlassesAction.TOGGLE_VOICE), actions)

            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected && it.saved == G2 }
            finishGreeting()
            // Single taps of either temple, as text and system events, and of the ring act on nothing.
            firmware.touch(G2Side.RIGHT, G2InputEvent.CLICK)
            firmware.systemInput(G2Side.LEFT, G2InputEvent.CLICK, G2InputEvent.SOURCE_LEFT_TEMPLE)
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.CLICK, G2InputEvent.SOURCE_RIGHT_TEMPLE)
            firmware.systemInput(G2Side.LEFT, G2InputEvent.CLICK, G2InputEvent.SOURCE_RING)
            // Stock firmware opens its own menu on a long press, which therefore acts on nothing here.
            firmware.touch(G2Side.RIGHT, G2InputEvent.LONG_PRESS)
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.LONG_PRESS, G2InputEvent.SOURCE_RIGHT_TEMPLE)
            runCurrent()
            assertEquals(listOf(GlassesAction.TOGGLE_VOICE), actions)

            firmware.touch(G2Side.RIGHT, G2InputEvent.DOUBLE_CLICK)
            firmware.systemInput(G2Side.LEFT, G2InputEvent.DOUBLE_CLICK, G2InputEvent.SOURCE_LEFT_TEMPLE)
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.DOUBLE_CLICK, G2InputEvent.SOURCE_RIGHT_TEMPLE)
            firmware.systemInput(G2Side.LEFT, G2InputEvent.DOUBLE_CLICK, G2InputEvent.SOURCE_RING)
            runCurrent()
            assertEquals(List(5) { GlassesAction.TOGGLE_VOICE }, actions)
            // Nothing an action does reaches the glasses on its own.
            assertEquals(GlassesLink.Connected(G2_DISPLAY, G2ScreenFit(G2TextPage())), owner.state.value.link)
            assertEquals("", firmware.shownText())
        }

    @Test
    fun onFaceclawFirmwareALongPressOfEitherTempleOrTheRingTogglesMute() =
        runOwnerTest {
            firmware.revision = "Faceclaw/45"
            val actions = mutableListOf<GlassesAction>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.actions.collect { actions += it } }
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            finishGreeting()
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.LONG_PRESS, G2InputEvent.SOURCE_LEFT_TEMPLE)
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.LONG_PRESS, G2InputEvent.SOURCE_RIGHT_TEMPLE)
            // g2flash forwards a ring long press as R1 report type 0, system event 9.
            firmware.ringReport(type = 0, event = G2InputEvent.LONG_PRESS, tick = 1_000)
            // Single taps still act on nothing, and double taps toggle voice.
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.CLICK, G2InputEvent.SOURCE_RIGHT_TEMPLE)
            firmware.ringReport(type = 1, event = G2InputEvent.CLICK, tick = 2_000)
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.DOUBLE_CLICK, G2InputEvent.SOURCE_LEFT_TEMPLE)
            runCurrent()
            assertEquals(List(3) { GlassesAction.TOGGLE_MUTE } + GlassesAction.TOGGLE_VOICE, actions)
        }

    @Test
    fun evenG2SwipesScrollAndActOnNothing() =
        runOwnerTest {
            val actions = mutableListOf<GlassesAction>()
            val scrolls = mutableListOf<GlassesScroll>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.actions.collect { actions += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.scrolls.collect { scrolls += it } }
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            finishGreeting()
            firmware.touch(G2Side.RIGHT, type = G2InputEvent.SCROLL_DOWN)
            firmware.systemInput(G2Side.LEFT, G2InputEvent.SCROLL_UP, G2InputEvent.SOURCE_RING)
            runCurrent()
            assertEquals(listOf(GlassesScroll.FORWARD, GlassesScroll.BACK), scrolls)
            assertEquals(emptyList<GlassesAction>(), actions)
        }

    @Test
    fun evenG2EventsAreListedNewestFirst() =
        runOwnerTest {
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            finishGreeting()
            firmware.touch(G2Side.RIGHT, type = G2InputEvent.DOUBLE_CLICK)
            firmware.systemInput(G2Side.RIGHT, type = G2InputEvent.RELEASE, source = G2InputEvent.SOURCE_LEFT_TEMPLE)
            runCurrent()
            val older =
                listOf(
                    GlassesEvent(GlassesGesture.RELEASE, GlassesInputPlace.LEFT),
                    // A text event names no temple.
                    GlassesEvent(GlassesGesture.DOUBLE_TAP),
                )
            assertEquals(older, owner.inputs.value.map { it.event })

            // A tap, which acts on nothing, is listed too.
            firmware.touch(G2Side.LEFT, type = G2InputEvent.CLICK)
            runCurrent()
            assertEquals(listOf(GlassesEvent(GlassesGesture.TAP)) + older, owner.inputs.value.map { it.event })
        }

    @Test
    fun theInputsScreenMirrorsInputsOnTheGlassesAndKeepsGesturesFromActingWhileItIsStarted() =
        runOwnerTest {
            val actions = mutableListOf<GlassesAction>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.actions.collect { actions += it } }
            var offered = 0
            owner.takeActionsFirst {
                offered++
                false
            }
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            finishGreeting()
            owner.show(GlassesFrame.Text("transcript"))
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent { GlassesInputsScreen(owner, onNavigateBack = {}) }
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals("Input events\nTry a gesture.", firmware.shownText())

            firmware.touch(G2Side.RIGHT, type = G2InputEvent.DOUBLE_CLICK)
            runCurrent()
            assertEquals(0 to 0, offered to actions.size)
            val shown = checkNotNull(firmware.shownText()).lines()
            assertEquals(listOf("Input events", "Double tap"), shown)

            // A stopped screen, as when Go Mode goes to the background, gives the glasses and actions back.
            activity.pause().stop()
            runCurrent()
            assertEquals("transcript", firmware.shownText())
            firmware.touch(G2Side.RIGHT, type = G2InputEvent.DOUBLE_CLICK)
            runCurrent()
            assertEquals(1 to 1, offered to actions.size)
            activity.destroy()
        }

    @Test
    fun aVoiceToggleThatNothingCollectsSaysOnTheGlassesToOpenGoMode() =
        runOwnerTest {
            connectSavedGlasses()
            val halo = transport.halos.single()
            halo.pressButton(ClickType.SINGLE)
            runCurrent()
            assertTrue(halo.lastSent(haloTextMessages(context.getString(R.string.glasses_notice_open_app))))

            advanceTimeBy(GLASSES_NOTICE_MS + 1)
            assertTrue(halo.lastSent(haloTextMessages("")))
        }

    @Test
    fun aMuteThatNothingCollectsSaysOnTheGlassesThatVoiceIsOff() =
        runOwnerTest {
            firmware.revision = "Faceclaw/45"
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            finishGreeting()
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.LONG_PRESS, G2InputEvent.SOURCE_RIGHT_TEMPLE)
            runCurrent()
            assertEquals(listOf(context.getString(R.string.glasses_notice_voice_off)), firmware.presented.last().lines)
        }

    @Test
    fun theFirstActionHandlerTakesTheActionsItActsOnAndLeavesTheRest() =
        runOwnerTest {
            val actions = mutableListOf<GlassesAction>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.actions.collect { actions += it } }
            var take = true
            val offered = mutableListOf<GlassesAction>()
            owner.takeActionsFirst {
                offered += it
                take
            }
            connectSavedGlasses()
            val halo = transport.halos.single()
            halo.pressButton(ClickType.SINGLE)
            runCurrent()
            assertEquals(1 to 0, offered.size to actions.size)

            take = false
            halo.pressButton(ClickType.SINGLE)
            runCurrent()
            assertEquals(2 to 1, offered.size to actions.size)

            owner.takeActionsFirst(null)
            halo.pressButton(ClickType.SINGLE)
            runCurrent()
            assertEquals(2 to 2, offered.size to actions.size)
            assertEquals(List(2) { GlassesAction.TOGGLE_VOICE }, offered)
        }

    @Test
    fun aConnectionGreetsOnTheGlassesThenShowsTheFrame() =
        runOwnerTest {
            owner.start()
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            runCurrent()
            assertEquals("Go", firmware.shownText())
            // The typed text has ended and holds.
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(GREETING_TEXT, firmware.shownText())
            advanceTimeBy(GREETING_LIMIT_MS)
            runCurrent()
            assertEquals("", firmware.shownText())
        }

    @Test
    fun contentEndsTheGreetingAndAReconnectionDoesNotCoverIt() =
        runOwnerTest {
            owner.start()
            owner.connect(HALO)
            owner.state.first { it.link is GlassesLink.Connected }
            runCurrent()
            val halo = transport.halos.single()
            assertTrue(halo.shown().isNotEmpty())
            assertFalse(halo.lastSent(haloTextMessages("")))

            owner.show(GlassesFrame.Text("hello"))
            runCurrent()
            assertTrue(halo.lastSent(haloTextMessages("hello")))
            advanceTimeBy(GREETING_LIMIT_MS)
            runCurrent()
            assertTrue(halo.lastSent(haloTextMessages("hello")))

            halo.drop()
            advanceTimeBy(RECONNECT_INITIAL_DELAY_MS)
            owner.state.first { it.link is GlassesLink.Connected }
            runCurrent()
            assertEquals(listOf(Shown.Page(lines = 1)), transport.halos.last().shown())
        }

    @Test
    fun theLogoTogglesOverContentWithoutADeadlineAndContentEndsIt() =
        runOwnerTest {
            owner.toggleLogo()
            connectSavedGlasses()
            owner.show(GlassesFrame.Text("hello"))
            runCurrent()
            val halo = transport.halos.single()
            assertTrue(halo.lastSent(haloTextMessages("hello")))

            owner.toggleLogo()
            finishGreeting()
            advanceTimeBy(GREETING_LIMIT_MS)
            runCurrent()
            assertFalse(halo.lastSent(haloTextMessages("hello")))
            owner.toggleLogo()
            runCurrent()
            assertTrue(halo.lastSent(haloTextMessages("hello")))

            owner.toggleLogo()
            runCurrent()
            owner.show(GlassesFrame.Text("bye"))
            runCurrent()
            assertTrue(halo.lastSent(haloTextMessages("bye")))
            finishGreeting()
            assertTrue(halo.lastSent(haloTextMessages("bye")))
        }

    @Test
    fun aNoticeEndsTheGreeting() =
        runOwnerTest {
            owner.start()
            owner.connect(G2)
            owner.state.first { it.link is GlassesLink.Connected }
            runCurrent()
            owner.notify("Notice")
            runCurrent()
            assertEquals("Notice", firmware.shownText())
            advanceTimeBy(GLASSES_NOTICE_MS + 1)
            runCurrent()
            assertEquals("", firmware.shownText())
        }

    @Test
    fun aNoticeOutlastsBlankTextAndYieldsToText() =
        runOwnerTest {
            connectSavedGlasses()
            val halo = transport.halos.single()
            owner.notify("Notice")
            runCurrent()
            assertTrue(halo.lastSent(haloTextMessages("Notice")))
            // A transcript that clears as voice fails to start must not erase the notice that says why.
            owner.show(GlassesFrame.Text(""))
            advanceTimeBy(GLASSES_NOTICE_MS / 2)
            assertTrue(halo.lastSent(haloTextMessages("Notice")))

            owner.show(GlassesFrame.Text("You: hello"))
            runCurrent()
            assertTrue(halo.lastSent(haloTextMessages("You: hello")))
            advanceTimeBy(GLASSES_NOTICE_MS + 1)
            assertTrue(halo.lastSent(haloTextMessages("You: hello")))
        }

    // The owner lives in the test's background scope, so connected glasses end with the test.
    private fun runOwnerTest(body: suspend TestScope.() -> Unit) =
        runTest {
            owner =
                GlassesOwner(backgroundScope, context, repository, transport, G2Scanner(context), firmware.connector)
            body()
        }

    // Records every link state the owner publishes from now on.
    private fun TestScope.recordLinks(): List<GlassesLink> {
        val links = mutableListOf<GlassesLink>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.state.collect { links += it.link } }
        return links
    }

    // Each arm advertises its name; Robolectric reports these results when a scan starts.
    private fun advertise(vararg arms: G2Arm) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        for (arm in arms) {
            val device = adapter.getRemoteDevice(arm.address).also { shadowOf(it).setName(arm.name) }
            @Suppress("DEPRECATION") // The public constructor without advertising-extension fields is enough here.
            shadowOf(adapter.bluetoothLeScanner).addScanResult(ScanResult(device, null, -60, 0))
        }
    }

    // Turns the Bluetooth adapter off or on, as the system reports it to receivers.
    private fun setBluetooth(on: Boolean) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(on)
        val state = if (on) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF
        context.sendBroadcast(
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    // Connects the saved Halo and lets its greeting end.
    private suspend fun TestScope.connectSavedGlasses() {
        owner.start()
        owner.connect(HALO)
        owner.state.first { it.link is GlassesLink.Connected }
        finishGreeting()
    }

    private fun TestScope.finishGreeting() {
        advanceTimeBy(GREETING_LIMIT_MS)
        runCurrent()
    }
}

// The longest a connection's greeting may last.
private const val GREETING_LIMIT_MS = 10_000L

// What a fake Halo was told to show through Go Mode's app on the glasses.
private sealed interface Shown {
    data object Clear : Shown

    data class Page(
        val lines: Int,
    ) : Shown
}

private fun FakeHalo.shown(): List<Shown> =
    messages.mapNotNull { (code, payload) ->
        when {
            code == HALO_CLEAR -> Shown.Clear
            code == HALO_PAGE && payload[0] == 0xFF.toByte() -> Shown.Page(lines = payload[5].toInt())
            else -> null
        }
    }

// Scans find one Halo and one Brilliant Frame, named through Robolectric's Bluetooth shadow.
private class ScanningHaloTransport(
    context: Context,
) : FakeHaloTransport(context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    override fun scan(): Flow<HaloScannedDevice> =
        flow {
            emit(HaloScannedDevice(named(ADDRESS, "Halo AB"), rssi = -50, HaloDeviceType.HALO))
            emit(HaloScannedDevice(named(FRAME_ADDRESS, "Frame CD"), rssi = -40, HaloDeviceType.FRAME))
            awaitCancellation()
        }

    private fun named(
        address: String,
        name: String,
    ): BluetoothDevice = adapter.getRemoteDevice(address).also { shadowOf(it).setName(name) }
}
