// G2GattConnector: Android BluetoothGatt transport for one Even G2 arm: bond, connect, MTU, notifications, writes.
//
// bond() calls createBond when the arm has no bond and waits for ACTION_BOND_STATE_CHANGED to
// report it bonded or unbonded. Android pairs one device at a time and refuses createBond while
// another pairing runs, so bonds are made one at a time. The arms pair with Just Works, for which
// Android shows a consent dialog; Android ends a pairing nobody answers.
//
// Sequence per bonded arm: connectGatt over LE, request MTU 512 and
// require at least G2Protocol.MIN_MTU, discover services, enable notifications on 5402 through
// its CCCD, then write packets to 5401 without response. One GATT operation runs at a time;
// each waits for its callback. A disconnect fails the pending operation and closes the
// notification channel with G2LinkLostException. Every platform call that throws on a missing
// BLUETOOTH_CONNECT permission or a rejected argument goes through bluetoothCall(), so callers
// see only G2Exception.
//
// A background connection passes autoConnect to connectGatt: Android adds the arm to the
// controller's LE accept list and connects when the arm next advertises, without a deadline and at
// a lower scan duty cycle than a direct connection, whose own deadline is the stack's (30 s in AOSP's
// connection manager). The accept-list entry takes the arm's address type and identity from the
// stack's record of the device, which the bond keeps across Bluetooth restarts; the first connection
// to a scanned pair is therefore direct. The setup after a background link comes up is bounded by
// G2Protocol.CONNECT_TIMEOUT_MS here, since G2Session bounds a background connection only from the
// first arm's link. close() releases the GATT client, which also removes a pending background
// connection.
//
// Both arms of one pair advertised static random addresses (top two bits set; Android reports
// RANDOM_DEVICE_ADDRESS).
//
// TODO(hardware): Unverified on a pair: whether a background connection matches an arm after the
// glasses restart, and how far apart the two arms' background links come up after the glasses
// wake. Time the links of several reconnections after power cycles.
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class G2GattConnector(
    private val context: Context,
) : G2ArmConnector {
    override suspend fun bond(arm: G2Arm) {
        val device = device(arm)
        val states = Channel<Int>(Channel.UNLIMITED)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    if (changed?.address == device.address) {
                        states.trySend(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
                    }
                }
            }
        // Registered before the state is read, so no change between the two is missed. Exported: the
        // Bluetooth app, not the system server, sends this protected broadcast, so a non-exported
        // receiver never gets it; no other app can send it.
        context.registerReceiver(
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            Context.RECEIVER_EXPORTED,
        )
        try {
            when (bluetoothCall("getBondState") { device.bondState }) {
                BluetoothDevice.BOND_BONDED -> {
                    return
                }

                BluetoothDevice.BOND_NONE -> {
                    if (!bluetoothCall("createBond") { device.createBond() }) {
                        throw G2Exception("pairing could not start")
                    }
                }
                // BOND_BONDING: wait for the pairing that runs.
            }
            for (state in states) {
                when (state) {
                    BluetoothDevice.BOND_BONDED -> return
                    BluetoothDevice.BOND_NONE -> throw G2Exception("pairing was refused or failed")
                }
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    override suspend fun connect(
        arm: G2Arm,
        background: Boolean,
    ): G2ArmLink {
        val link = G2GattLink(arm.side)
        var opened = false
        try {
            link.open(context, device(arm), background)
            opened = true
        } finally {
            // Failure or cancellation part way must not leave a GATT client behind.
            if (!opened) link.close()
        }
        return link
    }

    private fun device(arm: G2Arm): BluetoothDevice {
        val adapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw G2Exception("Bluetooth is not available")
        return bluetoothCall("getRemoteDevice") { adapter.getRemoteDevice(arm.address) }
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

    @Volatile override var mtu = 0
        private set

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
        background: Boolean,
    ) {
        val gatt =
            bluetoothCall("connectGatt") {
                device.connectGatt(context, background, callback, BluetoothDevice.TRANSPORT_LE)
            } ?: throw G2Exception("connectGatt returned null")
        this.gatt = gatt
        connected.await()
        if (background) {
            withTimeoutOrNull(G2Protocol.CONNECT_TIMEOUT_MS) { setUp(gatt) }
                ?: throw G2Exception("setup timed out")
        } else {
            setUp(gatt)
        }
    }

    // MTU, services, and notifications of a link that is up.
    private suspend fun setUp(gatt: BluetoothGatt) {
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
