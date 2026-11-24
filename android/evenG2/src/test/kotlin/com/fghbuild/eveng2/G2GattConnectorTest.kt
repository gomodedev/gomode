// G2GattConnector against Robolectric's Bluetooth: callback matching, a disconnect during an operation, close after failure.

package com.fghbuild.eveng2

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
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
import org.robolectric.shadows.ShadowBluetoothGatt

private val ARM = G2Arm(G2Side.RIGHT, "00:11:22:33:44:55", "Even G2_1_R_334455")

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent drives the connector between callbacks.
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ManualGatt::class])
class G2GattConnectorTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val connector = G2GattConnector(context)

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        ManualGatt.latest = null
    }

    @Test
    fun eachOperationCompletesOnlyOnItsOwnCallback() =
        runTest {
            val opening = async { connector.connect(ARM) }
            runCurrent()
            val gatt = gatt()
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
            val opening = async { runCatching { connector.connect(ARM) } }
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
            val opening = async { runCatching { connector.connect(ARM) } }
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
            val opening = async { connector.connect(ARM) }
            runCurrent()
            val gatt = gatt()
            assertFalse(gatt.isClosed)
            opening.cancel()
            runCurrent()
            assertTrue(gatt.isClosed)
        }

    // Opens a link through every setup operation.
    private suspend fun TestScope.open(): Pair<ManualGatt, G2ArmLink> {
        val opening = async { connector.connect(ARM) }
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
}

/**
 * A GATT client whose operations start and then wait for the callback the test delivers, as a
 * peripheral answers later; Robolectric's own shadow answers each operation at once. Services
 * expose the arm's write and notify characteristics.
 */
@Implements(BluetoothGatt::class)
class ManualGatt : ShadowBluetoothGatt() {
    @RealObject
    lateinit var real: BluetoothGatt

    val started = mutableListOf<String>()
    val written = mutableListOf<ByteArray>()
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
    private val service =
        BluetoothGattService(G2Protocol.WRITE_CHARACTERISTIC, BluetoothGattService.SERVICE_TYPE_PRIMARY).also {
            it.addCharacteristic(write)
            it.addCharacteristic(notify)
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
    ): Boolean = characteristic === notify && enable

    @Implementation
    override fun writeDescriptor(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Int {
        started += "DESCRIPTOR_WRITE"
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
    }
}
