// G2GattConnector on Robolectric's Bluetooth: bonding, callback matching, audio, disconnects, close after failure.

package com.fghbuild.eveng2

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowBluetoothGatt

private val ARM = G2Arm(G2Side.RIGHT, "00:11:22:33:44:55", "Even G2_1_R_334455")
private const val OTHER_ADDRESS = "00:11:22:33:44:66"

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent drives the connector between callbacks.
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ManualGatt::class, AutoConnectRecorder::class])
class G2GattConnectorTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val connector = G2GattConnector(context)

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        ManualGatt.latest = null
        ManualGatt.withAudio = false
        AutoConnectRecorder.autoConnects.clear()
    }

    @Test
    fun aBondedArmBondsWithoutAPairingRequest() =
        runTest {
            device().setBondState(BluetoothDevice.BOND_BONDED)
            connector.bond(ARM)
        }

    @Test
    fun bondingWaitsForTheArmToBeBonded() =
        runTest {
            device().setCreatedBond(true)
            val bonding = async { runCatching { connector.bond(ARM) } }
            runCurrent()
            bondStateChanged(OTHER_ADDRESS, BluetoothDevice.BOND_BONDED)
            bondStateChanged(ARM.address, BluetoothDevice.BOND_BONDING)
            assertFalse(bonding.isCompleted)
            bondStateChanged(ARM.address, BluetoothDevice.BOND_BONDED)
            assertTrue(bonding.await().isSuccess)
        }

    @Test
    fun aRefusedPairingFailsTheBond() =
        runTest {
            device().setCreatedBond(true)
            val bonding = async { runCatching { connector.bond(ARM) } }
            runCurrent()
            bondStateChanged(ARM.address, BluetoothDevice.BOND_BONDING)
            bondStateChanged(ARM.address, BluetoothDevice.BOND_NONE)
            assertEquals("pairing was refused or failed", bonding.await().exceptionOrNull()?.message)
        }

    @Test
    fun aPairingThatCannotStartFailsTheBond() =
        runTest {
            // Robolectric's createBond() returns false, as Android does while another pairing runs.
            val failure = runCatching { connector.bond(ARM) }.exceptionOrNull()
            assertEquals(G2Exception::class, failure?.let { it::class })
            assertEquals("pairing could not start", failure?.message)
        }

    @Test
    fun eachOperationCompletesOnlyOnItsOwnCallback() =
        runTest {
            val opening = async { connector.connect(ARM, background = false) }
            runCurrent()
            val gatt = gatt()
            assertEquals(listOf(false), AutoConnectRecorder.autoConnects)
            gatt.callback.onConnectionStateChange(
                gatt.real,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED,
            )
            runCurrent()
            assertEquals(listOf("MTU"), gatt.started)

            // Callbacks of other operations, which a peripheral can trigger on its own, leave the MTU request waiting.
            gatt.callback.onCharacteristicWrite(gatt.real, gatt.write, BluetoothGatt.GATT_SUCCESS)
            gatt.callback.onDescriptorWrite(gatt.real, gatt.config, BluetoothGatt.GATT_SUCCESS)
            gatt.callback.onServicesDiscovered(gatt.real, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            assertEquals(listOf("MTU"), gatt.started)

            gatt.callback.onMtuChanged(gatt.real, G2Protocol.REQUESTED_MTU, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            gatt.callback.onServicesDiscovered(gatt.real, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            assertEquals(listOf("MTU", "DISCOVERY", "DESCRIPTOR_WRITE"), gatt.started)
            gatt.callback.onDescriptorWrite(gatt.real, gatt.config, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            val link = opening.await()

            val writing = async(start = CoroutineStart.UNDISPATCHED) { link.write(byteArrayOf(1, 2)) }
            assertArrayEquals(byteArrayOf(1, 2), gatt.written.single())
            // A notification is no write acknowledgement; one from another characteristic is not the glasses'.
            gatt.callback.onCharacteristicChanged(gatt.real, gatt.notify, byteArrayOf(9))
            gatt.callback.onCharacteristicChanged(gatt.real, gatt.write, byteArrayOf(8))
            runCurrent()
            assertTrue(writing.isActive)
            gatt.callback.onCharacteristicWrite(gatt.real, gatt.write, BluetoothGatt.GATT_SUCCESS)
            writing.await()
            assertArrayEquals(byteArrayOf(9), link.notifications.receive())
            assertTrue(link.notifications.isEmpty)
        }

    @Test
    fun anArmWithTheAudioCharacteristicNotifiesItsPacketsApartUntilTheLinkDrops() =
        runTest {
            ManualGatt.withAudio = true
            val opening = async { connector.connect(ARM, background = false) }
            runCurrent()
            val gatt = gatt()
            gatt.callback.onConnectionStateChange(
                gatt.real,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED,
            )
            runCurrent()
            gatt.callback.onMtuChanged(gatt.real, G2Protocol.REQUESTED_MTU, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            gatt.callback.onServicesDiscovered(gatt.real, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            gatt.callback.onDescriptorWrite(gatt.real, gatt.config, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            assertFalse(opening.isCompleted)
            gatt.callback.onDescriptorWrite(gatt.real, gatt.audioConfig, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            val link = opening.await()
            assertEquals(listOf(gatt.config, gatt.audioConfig), gatt.described)

            gatt.callback.onCharacteristicChanged(gatt.real, gatt.audio, byteArrayOf(7))
            gatt.callback.onCharacteristicChanged(gatt.real, gatt.notify, byteArrayOf(9))
            assertArrayEquals(byteArrayOf(7), link.audio.receive())
            assertTrue(link.audio.isEmpty)
            assertArrayEquals(byteArrayOf(9), link.notifications.receive())

            gatt.callback.onConnectionStateChange(gatt.real, 8, BluetoothProfile.STATE_DISCONNECTED)
            val lost = runCatching { link.audio.receive() }.exceptionOrNull() as G2LinkLostException
            assertEquals(8, lost.status)
        }

    @Test
    fun aBackgroundConnectionWaitsForTheArmWithoutADeadlineThenSetsItUp() =
        runTest {
            val opening = async { connector.connect(ARM, background = true) }
            runCurrent()
            assertEquals(listOf(true), AutoConnectRecorder.autoConnects)
            advanceTimeBy(60 * 60_000L)
            val gatt = gatt()
            assertTrue(opening.isActive)
            assertFalse(gatt.isClosed)

            gatt.callback.onConnectionStateChange(
                gatt.real,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED,
            )
            runCurrent()
            gatt.callback.onMtuChanged(gatt.real, G2Protocol.REQUESTED_MTU, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            gatt.callback.onServicesDiscovered(gatt.real, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            gatt.callback.onDescriptorWrite(gatt.real, gatt.config, BluetoothGatt.GATT_SUCCESS)
            runCurrent()
            assertEquals(G2Side.RIGHT, opening.await().side)
        }

    @Test
    fun aBackgroundLinkThatComesUpMustFinishItsSetupInTime() =
        runTest {
            val opening = async { runCatching { connector.connect(ARM, background = true) } }
            runCurrent()
            val gatt = gatt()
            gatt.callback.onConnectionStateChange(
                gatt.real,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED,
            )
            runCurrent()
            assertEquals(listOf("MTU"), gatt.started)

            // The MTU answer never comes.
            advanceTimeBy(G2Protocol.CONNECT_TIMEOUT_MS)
            runCurrent()
            assertEquals("setup timed out", opening.await().exceptionOrNull()?.message)
            assertTrue(gatt.isClosed)
        }

    @Test
    fun cancellingABackgroundConnectionClosesItsGattClient() =
        runTest {
            val opening = async { connector.connect(ARM, background = true) }
            runCurrent()
            val gatt = gatt()
            opening.cancel()
            runCurrent()
            assertTrue(gatt.isClosed)
        }

    @Test
    fun aDisconnectDuringAWriteFailsItAndEndsTheNotifications() =
        runTest {
            val (gatt, link) = open()
            val writing = async(start = CoroutineStart.UNDISPATCHED) { runCatching { link.write(byteArrayOf(1)) } }

            gatt.callback.onConnectionStateChange(gatt.real, 8, BluetoothProfile.STATE_DISCONNECTED)
            assertEquals(8, (writing.await().exceptionOrNull() as G2LinkLostException).status)
            assertEquals(
                8,
                (runCatching { link.notifications.receive() }.exceptionOrNull() as G2LinkLostException).status,
            )
            // Later operations fail at once instead of waiting for a callback that never comes.
            assertEquals(
                8,
                (runCatching { link.write(byteArrayOf(2)) }.exceptionOrNull() as G2LinkLostException).status,
            )
            assertEquals(1, gatt.written.size)
        }

    @Test
    fun aFailedOpenClosesTheGattClient() =
        runTest {
            val opening = async { runCatching { connector.connect(ARM, background = false) } }
            runCurrent()
            val gatt = gatt()
            gatt.callback.onConnectionStateChange(
                gatt.real,
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED,
            )
            runCurrent()
            gatt.callback.onMtuChanged(gatt.real, 23, BluetoothGatt.GATT_SUCCESS)
            runCurrent()

            val failure = opening.await().exceptionOrNull()
            assertEquals(G2Exception::class, failure?.let { it::class })
            assertEquals("MTU 23 is below ${G2Protocol.MIN_MTU}", failure?.message)
            assertTrue(gatt.isClosed)
        }

    @Test
    fun aLinkThatDropsBeforeConnectingClosesTheGattClient() =
        runTest {
            val opening = async { runCatching { connector.connect(ARM, background = false) } }
            runCurrent()
            val gatt = gatt()
            gatt.callback.onConnectionStateChange(gatt.real, 133, BluetoothProfile.STATE_DISCONNECTED)
            runCurrent()

            assertEquals(133, (opening.await().exceptionOrNull() as G2LinkLostException).status)
            assertTrue(gatt.isClosed)
            assertEquals(emptyList<String>(), gatt.started)
        }

    @Test
    fun cancellingTheOpenClosesTheGattClient() =
        runTest {
            val opening = async { connector.connect(ARM, background = false) }
            runCurrent()
            val gatt = gatt()
            assertFalse(gatt.isClosed)
            opening.cancel()
            runCurrent()
            assertTrue(gatt.isClosed)
        }

    // Opens a link through every setup operation.
    private suspend fun TestScope.open(): Pair<ManualGatt, G2ArmLink> {
        val opening = async { connector.connect(ARM, background = false) }
        runCurrent()
        val gatt = gatt()
        gatt.callback.onConnectionStateChange(gatt.real, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        runCurrent()
        gatt.callback.onMtuChanged(gatt.real, G2Protocol.REQUESTED_MTU, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        gatt.callback.onServicesDiscovered(gatt.real, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        gatt.callback.onDescriptorWrite(gatt.real, gatt.config, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        return gatt to opening.await()
    }

    private fun gatt(): ManualGatt = checkNotNull(ManualGatt.latest) { "connectGatt was not called" }

    private fun remote(address: String): BluetoothDevice =
        context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)

    private fun device(): ShadowBluetoothDevice = shadowOf(remote(ARM.address))

    // Android's broadcast of a bond state change, delivered before the test continues.
    private fun TestScope.bondStateChanged(
        address: String,
        state: Int,
    ) {
        context.sendBroadcast(
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, remote(address))
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, state),
        )
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }
}

/**
 * A GATT client whose operations start and then wait for the callback the test delivers, as a
 * peripheral answers later; Robolectric's own shadow answers each operation at once. Services
 * expose the arm's write and notify characteristics, and its audio characteristic [withAudio].
 */
@Implements(BluetoothGatt::class)
class ManualGatt : ShadowBluetoothGatt() {
    @RealObject
    lateinit var real: BluetoothGatt

    val started = mutableListOf<String>()
    val written = mutableListOf<ByteArray>()
    val described = mutableListOf<BluetoothGattDescriptor>()
    val write =
        BluetoothGattCharacteristic(
            G2Protocol.WRITE_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
    val config = BluetoothGattDescriptor(G2Protocol.CLIENT_CONFIG_DESCRIPTOR, BluetoothGattDescriptor.PERMISSION_WRITE)
    val notify =
        BluetoothGattCharacteristic(G2Protocol.NOTIFY_CHARACTERISTIC, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
            .also { it.addDescriptor(config) }
    val audioConfig =
        BluetoothGattDescriptor(G2Protocol.CLIENT_CONFIG_DESCRIPTOR, BluetoothGattDescriptor.PERMISSION_WRITE)
    val audio =
        BluetoothGattCharacteristic(G2Protocol.AUDIO_CHARACTERISTIC, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
            .also { it.addDescriptor(audioConfig) }
    private val service =
        BluetoothGattService(G2Protocol.WRITE_CHARACTERISTIC, BluetoothGattService.SERVICE_TYPE_PRIMARY).also {
            it.addCharacteristic(write)
            it.addCharacteristic(notify)
            if (withAudio) it.addCharacteristic(audio)
        }
    val callback: BluetoothGattCallback get() = gattCallback

    override fun setGattCallback(bluetoothGattCallback: BluetoothGattCallback) {
        super.setGattCallback(bluetoothGattCallback)
        latest = this
    }

    @Implementation
    override fun requestMtu(mtu: Int): Boolean = started.add("MTU")

    @Implementation
    override fun discoverServices(): Boolean = started.add("DISCOVERY")

    @Implementation
    override fun getServices(): List<BluetoothGattService> = listOf(service)

    @Implementation
    override fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        enable: Boolean,
    ): Boolean = (characteristic === notify || characteristic === audio) && enable

    @Implementation
    override fun writeDescriptor(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Int {
        started += "DESCRIPTOR_WRITE"
        described += descriptor
        return 0
    }

    @Implementation
    override fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Int {
        written += value.copyOf()
        return 0
    }

    companion object {
        var latest: ManualGatt? = null
        var withAudio = false
    }
}

/** Records the autoConnect argument of each connectGatt, which Robolectric's own shadow drops. */
@Implements(BluetoothDevice::class)
class AutoConnectRecorder : ShadowBluetoothDevice() {
    @Implementation
    override fun connectGatt(
        context: Context,
        autoConnect: Boolean,
        callback: BluetoothGattCallback,
        transport: Int,
    ): BluetoothGatt {
        autoConnects += autoConnect
        return super.connectGatt(context, autoConnect, callback, transport)
    }

    companion object {
        val autoConnects = mutableListOf<Boolean>()
    }
}
