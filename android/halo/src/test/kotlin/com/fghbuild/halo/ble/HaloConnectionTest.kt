// Unit tests for HaloConnection device type detection and service wiring logic.
// The protected open methods let us inject test data without real BLE hardware.
//
// Pure parsing logic is tested separately in HaloServiceDiscoveryTest.
@file:Suppress("MaxLineLength")

package com.fghbuild.halo.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBluetoothDevice
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [AutoConnectRecorder::class])
class HaloConnectionTest {
    private lateinit var btDevice: BluetoothDevice

    // Pre-built characteristics for HALO (has AUDIO TX) and FRAME (no AUDIO TX).
    private lateinit var haloTx: BluetoothGattCharacteristic
    private lateinit var haloRx: BluetoothGattCharacteristic
    private lateinit var haloAudioTx: BluetoothGattCharacteristic
    private lateinit var haloService: BluetoothGattService

    private lateinit var frameTx: BluetoothGattCharacteristic
    private lateinit var frameRx: BluetoothGattCharacteristic
    private lateinit var frameService: BluetoothGattService

    @Before
    fun setUp() {
        AutoConnectRecorder.autoConnects.clear()
        val adapter =
            RuntimeEnvironment
                .getApplication()
                .getSystemService(BluetoothManager::class.java)
                .adapter
        btDevice = adapter.getRemoteDevice("00:11:22:33:44:55")
        Shadows.shadowOf(btDevice).setName("Halo AB")

        // Halo service
        haloTx = BluetoothGattCharacteristic(HaloServiceDiscovery.LUA_TX_CHAR, 0, 0)
        haloRx = BluetoothGattCharacteristic(HaloServiceDiscovery.LUA_RX_CHAR, 0, 0)
        haloAudioTx = BluetoothGattCharacteristic(HaloServiceDiscovery.AUDIO_TX_CHAR, 0, 0)
        haloService = BluetoothGattService(HaloServiceDiscovery.LUA_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        haloService.addCharacteristic(haloTx)
        haloService.addCharacteristic(haloRx)
        haloService.addCharacteristic(haloAudioTx)

        // Frame service (no AUDIO TX)
        frameTx = BluetoothGattCharacteristic(HaloServiceDiscovery.LUA_TX_CHAR, 0, 0)
        frameRx = BluetoothGattCharacteristic(HaloServiceDiscovery.LUA_RX_CHAR, 0, 0)
        frameService = BluetoothGattService(HaloServiceDiscovery.LUA_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        frameService.addCharacteristic(frameTx)
        frameService.addCharacteristic(frameRx)
    }

    // ---- GATT clients of failed connections ----

    private fun pendingGatt() = Shadows.shadowOf(btDevice).bluetoothGatts.single()

    @Test
    fun `a connection that times out closes its GATT client`() =
        runTest {
            val connection = HaloConnection(RuntimeEnvironment.getApplication())
            val error = runCatching { connection.connect(btDevice, timeoutMs = 1_000) }.exceptionOrNull()
            assertTrue("$error", error is HaloException)
            assertEquals(listOf(false), AutoConnectRecorder.autoConnects)
            assertTrue(Shadows.shadowOf(pendingGatt()).isClosed)
        }

    @Test
    fun `a background connection waits past the direct deadline and cancelling it closes its GATT client`() =
        runTest {
            val connection = HaloConnection(RuntimeEnvironment.getApplication())
            val attempt = async { connection.connect(btDevice, timeoutMs = 1_000, background = true) }
            runCurrent()
            assertEquals(listOf(true), AutoConnectRecorder.autoConnects)
            // Android connects it whenever the device advertises; nothing times out meanwhile.
            advanceTimeBy(60 * 60_000L)
            assertTrue(attempt.isActive)
            assertFalse(Shadows.shadowOf(pendingGatt()).isClosed)

            attempt.cancel()
            runCurrent()
            assertTrue(Shadows.shadowOf(pendingGatt()).isClosed)
        }

    @Test
    fun `a disconnection during connection closes its GATT client`() =
        runTest {
            val connection = HaloConnection(RuntimeEnvironment.getApplication())
            val attempt = async { runCatching { connection.connect(btDevice) } }
            runCurrent()
            Shadows.shadowOf(btDevice).simulateGattConnectionChange(133, BluetoothProfile.STATE_DISCONNECTED)
            val error = attempt.await().exceptionOrNull()
            assertTrue("$error", error is HaloException)
            assertTrue(Shadows.shadowOf(pendingGatt()).isClosed)
        }

    @Test
    fun `a cancelled connection stays cancelled and closes its GATT client`() =
        runTest {
            val connection = HaloConnection(RuntimeEnvironment.getApplication())
            val attempt = async { connection.connect(btDevice) }
            runCurrent()
            attempt.cancel()
            val error = runCatching { attempt.await() }.exceptionOrNull()
            assertTrue("$error", error is CancellationException)
            assertTrue(Shadows.shadowOf(pendingGatt()).isClosed)
        }

    @Test
    fun `a cancellation during connection setup is not wrapped and closes its GATT client`() =
        runTest {
            // The setup steps after the link comes up run inside connect's failure handling.
            val connection =
                object : HaloConnection(RuntimeEnvironment.getApplication()) {
                    override fun createHaloDevice(
                        platformDevice: BluetoothDevice,
                        type: HaloDeviceType,
                        txChar: BluetoothGattCharacteristic,
                        rxChar: BluetoothGattCharacteristic,
                        audioTxChar: BluetoothGattCharacteristic?,
                        limits: HaloServiceDiscovery.PayloadLimits,
                        notificationsFlow: Flow<ByteArray>,
                        closeCause: StateFlow<HaloException?>,
                    ): HaloDevice = throw CancellationException("connection cancelled")
                }
            val attempt = async { runCatching { connection.connect(btDevice) } }
            runCurrent()
            Shadows.shadowOf(pendingGatt()).addDiscoverableService(haloService)
            Shadows.shadowOf(btDevice).simulateGattConnectionChange(0, BluetoothProfile.STATE_CONNECTED)
            val error = attempt.await().exceptionOrNull()
            assertTrue("$error", error is CancellationException)
            assertTrue(Shadows.shadowOf(pendingGatt()).isClosed)
        }

    // ---- device type detection via service ----

    @Test
    fun `HaloService detects Halo from Audio TX characteristic`() {
        assertEquals(HaloDeviceType.HALO, HaloServiceDiscovery.deviceType(haloService))
    }

    @Test
    fun `HaloService detects Frame when Audio TX is absent`() {
        assertEquals(HaloDeviceType.FRAME, HaloServiceDiscovery.deviceType(frameService))
    }

    // ---- required characteristics extraction ----

    @Test
    fun `extracts all three characteristics from Halo service`() {
        val (tx, rx, audio) = HaloServiceDiscovery.requiredCharacteristics(haloService)
        assertEquals(haloTx, tx)
        assertEquals(haloRx, rx)
        assertEquals(haloAudioTx, audio)
    }

    @Test
    fun `extracts two characteristics from Frame service`() {
        val (tx, rx, audio) = HaloServiceDiscovery.requiredCharacteristics(frameService)
        assertEquals(frameTx, tx)
        assertEquals(frameRx, rx)
        assertNull(audio)
    }

    // ---- payload limits at different MTUs ----

    @Test
    fun `payload limits for Halo at MTU 517`() {
        val limits = HaloServiceDiscovery.payloadLimits(517, HaloDeviceType.HALO)
        assertEquals(514, limits.maxStringLen)
        assertEquals(511, limits.maxDataLen)
    }

    @Test
    fun `payload limits for Frame at MTU 517`() {
        val limits = HaloServiceDiscovery.payloadLimits(517, HaloDeviceType.FRAME)
        assertEquals(514, limits.maxStringLen)
        assertEquals(513, limits.maxDataLen)
    }

    @Test
    fun `payload limits for Halo at minimal MTU 23`() {
        val limits = HaloServiceDiscovery.payloadLimits(23, HaloDeviceType.HALO)
        assertEquals(20, limits.maxStringLen)
        assertEquals(17, limits.maxDataLen) // 23 - 6
    }

    @Test
    fun `payload limits for Frame at MTU 100`() {
        val limits = HaloServiceDiscovery.payloadLimits(100, HaloDeviceType.FRAME)
        assertEquals(97, limits.maxStringLen)
        assertEquals(96, limits.maxDataLen)
    }

    // ---- Lua service matching ----

    @Test
    fun `findLuaService matches correct UUID`() {
        assertEquals(haloService, HaloServiceDiscovery.findLuaService(listOf(haloService)))
    }

    @Test
    fun `findLuaService returns null for non-matching service`() {
        val other = BluetoothGattService(UUID.randomUUID(), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        assertNull(HaloServiceDiscovery.findLuaService(listOf(other)))
    }

    // ---- UUID correctness (must match Brilliant docs) ----

    @Test
    fun `Lua service UUID matches spec`() {
        assertEquals(
            UUID.fromString("7A230001-5475-A6A4-654C-8431F6AD49C4"),
            HaloServiceDiscovery.LUA_SERVICE,
        )
    }

    @Test
    fun `Battery service UUID is standard 0x180F`() {
        assertEquals(
            UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB"),
            HaloServiceDiscovery.BATTERY_SERVICE,
        )
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
