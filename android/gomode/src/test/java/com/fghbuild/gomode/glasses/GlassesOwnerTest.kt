// Tests the app-scoped glasses owner against a fake Halo transport: connect, Activity replacement, reconnect, forget.
package com.fghbuild.gomode.glasses

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.fghbuild.gomode.data.MemoryPreferences
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.ui.halo.HaloScreen
import com.fghbuild.halo.ble.HaloDeviceType
import com.fghbuild.halo.ble.HaloScannedDevice
import kotlinx.coroutines.Dispatchers
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassesOwnerTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private lateinit var repository: SettingsRepository
    private lateinit var transport: ScanningHaloTransport
    private lateinit var owner: GlassesOwner

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        repository = SettingsRepository(MemoryPreferences())
        transport = ScanningHaloTransport(context)
        owner = GlassesOwner(context, repository, transport)
    }

    @After
    fun tearDown() {
        owner.close()
        Dispatchers.resetMain()
    }

    @Test
    fun scanningListsHaloOnlyAndConnectingSavesItStartsTheAppAndShowsTheLatestText() =
        runTest {
            owner.start()
            owner.startScan()
            runCurrent()
            // The scan also found a Brilliant Frame, which Go Mode does not drive.
            val scanned =
                owner.state.value.devices
                    .single()
            assertEquals(ScannedGlasses(ADDRESS, "Halo AB", -50), scanned)

            owner.connect(scanned.address)
            owner.state.first { it.link is GlassesLink.Connected }
            runCurrent()
            assertEquals(false, owner.state.value.scanning)
            assertEquals(ADDRESS, repository.settings.first { it.haloAddress != null }.haloAddress)
            val halo = transport.halos.single()
            assertTrue(halo.files.toString(), halo.files.containsAll(listOf("text_page.lua", "main.lua")))
            assertEquals(listOf(Shown.Clear), halo.shown())

            owner.show("one\ntwo")
            runCurrent()
            assertEquals(listOf(Shown.Clear, Shown.Page(lines = 2)), halo.shown())
        }

    @Test
    fun theConnectionOutlivesTheActivityThatShowedTheHaloScreen() =
        runTest {
            connectSavedGlasses()
            val first = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            first.get().setContent { HaloScreen(owner, onNavigateBack = {}) }
            owner.show("before")
            runCurrent()

            first.pause().stop().destroy()
            val second = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            second.get().setContent { HaloScreen(owner, onNavigateBack = {}) }
            owner.show("after\nreplacement")
            runCurrent()

            assertEquals(GlassesLink.Connected(HALO_DISPLAY), owner.state.value.link)
            assertEquals(0, transport.disconnects)
            assertEquals(
                listOf(Shown.Clear, Shown.Page(lines = 1), Shown.Page(lines = 2)),
                transport.halos.single().shown(),
            )
            second.pause().stop().destroy()
        }

    @Test
    fun aDroppedLinkReconnectsWithBackoffAndShowsTheTextAgain() =
        runTest {
            connectSavedGlasses()
            assertEquals(HALO_DISPLAY, owner.connectedDisplay)
            owner.show("hello")
            runCurrent()

            transport.unreachableAttempts = 1
            transport.halos.single().drop()
            runCurrent()
            assertEquals(GlassesLink.Reconnecting(GlassesProblem.UNREACHABLE), owner.state.value.link)
            assertEquals(null, owner.connectedDisplay)
            assertEquals(1, transport.connects)

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

            owner.state.first { it.link is GlassesLink.Connected }
            runCurrent()
            assertEquals(listOf(Shown.Page(lines = 1)), transport.halos.last().shown())
            assertEquals(HALO_DISPLAY, owner.connectedDisplay)
        }

    @Test
    fun glassesThatRejectTextReconnectAsUnresponsive() =
        runTest {
            connectSavedGlasses()
            transport.halos.single().rejectMessages = true
            owner.show("hello")
            runCurrent()
            assertEquals(GlassesLink.Reconnecting(GlassesProblem.UNRESPONSIVE), owner.state.value.link)
            assertEquals(1, transport.disconnects)
        }

    @Test
    fun forgettingClearsTheDisplayDisconnectsAndStopsReconnecting() =
        runTest {
            connectSavedGlasses()
            owner.show("hello")
            runCurrent()

            owner.forget()
            runCurrent()
            assertEquals(GlassesLink.Disconnected, owner.state.value.link)
            assertEquals(null, owner.connectedDisplay)
            assertEquals(listOf(Shown.Clear, Shown.Page(lines = 1), Shown.Clear), transport.halos.single().shown())
            assertEquals(1, transport.disconnects)
            repository.settings.first { it.haloAddress == null }
            owner.state.first { it.savedAddress == null }

            advanceTimeBy(RECONNECT_MAX_DELAY_MS)
            runCurrent()
            assertEquals(1, transport.connects)
        }

    @Test
    fun autoConnectConnectsToTheSavedGlassesWhenTheOwnerStarts() =
        runTest {
            repository.updateHaloAddress(ADDRESS)
            repository.updateHaloAutoConnect(true)
            repository.settings.first { it.haloAutoConnect && it.haloAddress == ADDRESS }

            owner.start()
            owner.state.first { it.link is GlassesLink.Connected }
            assertEquals(1, transport.connects)
        }

    @Test
    fun disconnectingWhileConnectingNeverReportsARetry() =
        runTest {
            val links = recordLinks()
            transport.hangConnects = true
            owner.connect(ADDRESS)
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
        runTest {
            val links = recordLinks()
            transport.hangConnects = true
            owner.connect(ADDRESS)
            runCurrent()

            transport.hangConnects = false
            owner.connect(OTHER_ADDRESS)
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
        runTest {
            transport.connectedType = HaloDeviceType.FRAME
            owner.connect(FRAME_ADDRESS)
            runCurrent()
            advanceTimeBy(RECONNECT_MAX_DELAY_MS)
            runCurrent()
            assertEquals(GlassesLink.Disconnected, owner.state.value.link)
            assertEquals("Go Mode supports Halo only; this device is a Brilliant Frame", owner.state.value.error)
            assertEquals(1, transport.connects)
            assertEquals(1, transport.disconnects)
        }

    // Records every link state the owner publishes from now on.
    private fun TestScope.recordLinks(): List<GlassesLink> {
        val links = mutableListOf<GlassesLink>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.state.collect { links += it.link } }
        return links
    }

    private suspend fun TestScope.connectSavedGlasses() {
        owner.start()
        owner.connect(ADDRESS)
        owner.state.first { it.link is GlassesLink.Connected }
        runCurrent()
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
