// G2GattConnector: Android BluetoothGatt transport for one Even G2 arm: connect, bond, MTU, notifications, writes.
//
// Sequence per arm: connectGatt over LE, request a bond when none exists, request MTU 512 and
// require at least G2Protocol.MIN_MTU, discover services, enable notifications on 5402 through
// its CCCD, then write packets to 5401 without response. One GATT operation runs at a time;
// each waits for its callback. A disconnect fails the pending operation and closes the
// notification channel with G2LinkLostException. Every platform call that throws on a missing
// BLUETOOTH_CONNECT permission or a rejected argument goes through bluetoothCall(), so callers
// see only G2Exception.
@file:Suppress("MissingPermission") // Library: the consuming app holds BLUETOOTH_CONNECT and BLUETOOTH_SCAN.

package com.fghbuild.eveng2

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class G2GattConnector(
    private val context: Context,
) : G2ArmConnector {
    override suspend fun connect(arm: G2Arm): G2ArmLink {
        val adapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw G2Exception("Bluetooth is not available")
        val link = G2GattLink(arm.side)
        var opened = false
        try {
            link.open(context, bluetoothCall("getRemoteDevice") { adapter.getRemoteDevice(arm.address) })
            opened = true
        } finally {
            // Failure or cancellation part way must not leave a GATT client behind.
            if (!opened) link.close()
        }
        return link
    }
}

/** Run a platform Bluetooth [call]; a SecurityException or IllegalArgumentException becomes a [G2Exception]. */
internal inline fun <T> bluetoothCall(
    name: String,
    call: () -> T,
): T =
    try {
        call()
    } catch (e: SecurityException) {
        throw G2Exception("$name: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw G2Exception("$name: ${e.message}", e)
    }

private class G2GattLink(
    override val side: G2Side,
) : G2ArmLink {
    private val channel = Channel<ByteArray>(NOTIFICATION_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val operations = Mutex()
    private val connected = CompletableDeferred<Unit>()

    // An operation publishes `pending` before it reads `lost`; the disconnect callback sets
    // `lost` before it reads `pending`. Either side therefore sees the other.
    @Volatile private var pending: Pending? = null

    @Volatile private var lost: G2LinkLostException? = null

    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var mtu = 0

    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null

    override val notifications: ReceiveChannel<ByteArray> = channel

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connected.complete(Unit)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val e = G2LinkLostException(status)
                    lost = e
                    connected.completeExceptionally(e)
                    pending?.status?.completeExceptionally(e)
                    channel.close(e)
                }
            }

            override fun onMtuChanged(
                g: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                this@G2GattLink.mtu = mtu
                complete(Operation.MTU, status)
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                complete(Operation.DISCOVERY, status)
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                complete(Operation.DESCRIPTOR_WRITE, status)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                complete(Operation.WRITE, status)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == G2Protocol.NOTIFY_CHARACTERISTIC) channel.trySend(value.copyOf())
            }
        }

    suspend fun open(
        context: Context,
        device: BluetoothDevice,
    ) {
        val gatt =
            bluetoothCall("connectGatt") { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }
                ?: throw G2Exception("connectGatt returned null")
        this.gatt = gatt
        connected.await()
        // See the bonding TODO in G2Auth.kt.
        bluetoothCall("createBond") { if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond() }
        operation(Operation.MTU) { it.requestMtu(G2Protocol.REQUESTED_MTU) }
        if (mtu < G2Protocol.MIN_MTU) throw G2Exception("MTU $mtu is below ${G2Protocol.MIN_MTU}")
        operation(Operation.DISCOVERY) { it.discoverServices() }
        val characteristics = gatt.services.flatMap { it.characteristics }
        val write =
            characteristics.firstOrNull { it.uuid == G2Protocol.WRITE_CHARACTERISTIC }
                ?: throw G2Exception("write characteristic not found")
        val notify =
            characteristics.firstOrNull { it.uuid == G2Protocol.NOTIFY_CHARACTERISTIC }
                ?: throw G2Exception("notify characteristic not found")
        val config =
            notify.getDescriptor(G2Protocol.CLIENT_CONFIG_DESCRIPTOR)
                ?: throw G2Exception("notify characteristic has no configuration descriptor")
        if (!bluetoothCall("setCharacteristicNotification") { gatt.setCharacteristicNotification(notify, true) }) {
            throw G2Exception("notifications could not be enabled")
        }
        operation(Operation.DESCRIPTOR_WRITE) {
            it.writeDescriptor(config, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        }
        writeCharacteristic = write
    }

    override suspend fun write(packet: ByteArray) {
        val characteristic = writeCharacteristic ?: throw G2Exception("link is not open")
        operation(Operation.WRITE) {
            it.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                BluetoothStatusCodes.SUCCESS
        }
    }

    /** Start one GATT operation and wait for its callback's status. */
    private suspend fun operation(
        operation: Operation,
        start: (BluetoothGatt) -> Boolean,
    ) {
        operations.withLock {
            val gatt = gatt ?: throw G2Exception("link is closed")
            val done = Pending(operation)
            pending = done
            try {
                lost?.let { throw it }
                if (!bluetoothCall(operation.name) { start(gatt) }) throw G2Exception("$operation could not start")
                val status = done.status.await()
                if (status != BluetoothGatt.GATT_SUCCESS) throw G2Exception("$operation failed (status=$status)")
            } finally {
                pending = null
            }
        }
    }

    /** Complete the pending operation when the callback is its own; peripheral-initiated callbacks are ignored. */
    private fun complete(
        operation: Operation,
        status: Int,
    ) {
        pending?.takeIf { it.operation == operation }?.status?.complete(status)
    }

    override fun close() {
        val gatt = gatt ?: return
        this.gatt = null
        channel.close()
        try {
            gatt.disconnect()
            gatt.close()
        } catch (_: SecurityException) {
            // close() runs in finally blocks, where a throw would mask the failure being cleaned up.
        }
    }

    private enum class Operation { MTU, DISCOVERY, DESCRIPTOR_WRITE, WRITE }

    private class Pending(
        val operation: Operation,
        val status: CompletableDeferred<Int> = CompletableDeferred(),
    )

    private companion object {
        // Bounded: on overflow the oldest packet is dropped, and the assembler discards the
        // partial message it belonged to.
        const val NOTIFICATION_CAPACITY = 256
    }
}
