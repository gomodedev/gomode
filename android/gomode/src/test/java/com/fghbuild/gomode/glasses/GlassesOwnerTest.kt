// Tests the app-scoped glasses owner against a fake Halo transport: connect, Activity replacement, reconnect, forget.
package com.fghbuild.gomode.glasses

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.fghbuild.gomode.data.MemoryPreferences
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.ui.halo.HaloScreen
import com.fghbuild.halo.ble.HaloConnection
import com.fghbuild.halo.ble.HaloDevice
import com.fghbuild.halo.ble.HaloDeviceType
import com.fghbuild.halo.ble.HaloException
import com.fghbuild.halo.ble.HaloProtocol
import com.fghbuild.halo.ble.HaloScannedDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
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
    private lateinit var transport: FakeHaloTransport
    private lateinit var owner: GlassesOwner

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        repository = SettingsRepository(MemoryPreferences())
        transport = FakeHaloTransport(context)
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
            owner.state.first { it.link == GlassesLink.Connected }
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

            assertEquals(GlassesLink.Connected, owner.state.value.link)
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
            owner.show("hello")
            runCurrent()

            transport.unreachableAttempts = 1
            transport.halos.single().drop()
            runCurrent()
            assertEquals(GlassesLink.Reconnecting(GlassesProblem.UNREACHABLE), owner.state.value.link)
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

            owner.state.first { it.link == GlassesLink.Connected }
            runCurrent()
            assertEquals(listOf(Shown.Page(lines = 1)), transport.halos.last().shown())
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
            owner.state.first { it.link == GlassesLink.Connected }
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
            owner.state.first { it.link == GlassesLink.Connected }
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
        owner.state.first { it.link == GlassesLink.Connected }
        runCurrent()
    }
}

// What a fake Halo was told to show.
private sealed interface Shown {
    data object Clear : Shown

    data class Page(
        val lines: Int,
    ) : Shown
}

// Replaces BLE with fake Halo glasses that run nothing but answer the REPL and data.lua's protocol.
private class FakeHaloTransport(
    context: Context,
) : HaloConnection(context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    // One per successful connection, in order.
    val halos = mutableListOf<FakeHalo>()

    // How many upcoming connection attempts fail as if the glasses were off.
    var unreachableAttempts = 0

    // While set, connection attempts wait until cancelled, then fail with a transport error the way a
    // transport that wraps cancellation would.
    var hangConnects = false

    // The device type the next connections report after service discovery.
    var connectedType = HaloDeviceType.HALO

    // Addresses of every connection attempt, in order.
    val attempts = mutableListOf<String>()
    val connects get() = attempts.size
    var disconnects = 0

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

    override suspend fun connect(
        device: BluetoothDevice,
        timeoutMs: Long,
    ): HaloDevice {
        attempts += device.address
        if (hangConnects) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                throw HaloException("Connection failed: ${e.message}", e)
            }
        }
        if (unreachableAttempts > 0) {
            unreachableAttempts--
            throw HaloException("Connection timed out after ${timeoutMs}ms")
        }
        return FakeHalo(device, connectedType).also { halos += it }.device
    }

    override suspend fun disconnect() {
        disconnects++
        halos.lastOrNull()?.closeCause?.compareAndSet(null, HaloException("Disconnected by the host"))
    }
}

private class FakeHalo(
    platformDevice: BluetoothDevice,
    type: HaloDeviceType,
) {
    private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    val closeCause = MutableStateFlow<HaloException?>(null)

    // Files the host wrote, and the typed messages it sent as (message code, payload).
    val files = mutableListOf<String>()
    private val messages = mutableListOf<Pair<Int, ByteArray>>()

    // When set, data.lua acknowledges every typed-message chunk with a failure.
    var rejectMessages = false

    private var receivingCode = 0
    private var receivingSize = -1
    private var received = ByteArray(0)

    val device =
        HaloDevice(
            platformDevice = platformDevice,
            type = type,
            txChar = BluetoothGattCharacteristic(HaloProtocol.LUA_TX_CHAR, 0, 0),
            maxStringLen = 514,
            maxDataLen = 511,
            rawNotifications = notifications.receiveAsFlow(),
            closeCause = closeCause,
        ).also { it.writeSink = ::write }

    fun drop() {
        closeCause.value = HaloException("Device disconnected (status=8)")
    }

    fun shown(): List<Shown> =
        messages.mapNotNull { (code, payload) ->
            when {
                code == HALO_CLEAR -> Shown.Clear
                code == HALO_TEXT_PAGE && payload[0] == 0xFF.toByte() -> Shown.Page(lines = payload[5].toInt())
                else -> null
            }
        }

    private fun write(write: HaloDevice.RawWrite) {
        if (closeCause.value != null) throw HaloException("writeCharacteristic returned false")
        val data = write.data
        when {
            // A control signal: break, reset, and the like.
            data.size == 1 -> Unit

            data[0] == HaloProtocol.DATA_PREFIX -> receiveChunk(data)

            else -> repl(data.toString(Charsets.UTF_8))
        }
    }

    // Answers each REPL line that ends in print(n) with n, and records the files it opens.
    private fun repl(lua: String) {
        FILE_OPEN.find(lua)?.let { files += it.groupValues[1] }
        PRINT.find(lua)?.let { notifications.trySend(it.groupValues[1].toByteArray()) }
    }

    // Reassembles a typed message: the first chunk carries the code and 16-bit length.
    private fun receiveChunk(packet: ByteArray) {
        val ack = if (rejectMessages) HaloProtocol.DATA_ACK_FAILURE else HaloProtocol.DATA_ACK_SUCCESS
        notifications.trySend(byteArrayOf(HaloProtocol.DATA_PREFIX) + ack)
        if (rejectMessages) return
        if (receivingSize < 0) {
            receivingCode = packet[1].toInt() and 0xFF
            receivingSize = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
            received = packet.copyOfRange(HaloProtocol.FIRST_MESSAGE_HEADER_SIZE, packet.size)
        } else {
            received += packet.copyOfRange(HaloProtocol.SUBSEQUENT_MESSAGE_HEADER_SIZE, packet.size)
        }
        if (received.size == receivingSize) {
            messages += receivingCode to received
            receivingSize = -1
        }
    }

    private companion object {
        val FILE_OPEN = Regex("""frame\.file\.open\("([^"]+)"""")
        val PRINT = Regex("""print\((\d)\)$""")
    }
}
