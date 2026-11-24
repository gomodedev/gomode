// Fake Halo glasses over a fake BLE transport that record the files and typed messages they receive.
package com.fghbuild.halo.testing

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.fghbuild.halo.ble.HaloConnection
import com.fghbuild.halo.ble.HaloDevice
import com.fghbuild.halo.ble.HaloDeviceType
import com.fghbuild.halo.ble.HaloException
import com.fghbuild.halo.ble.HaloProtocol
import com.fghbuild.halo.msg.TxMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Replaces BLE with fake Halo glasses that run nothing but answer the REPL and data.lua's protocol.
 * Scanning stays the platform's; connect by address. Tests may read the recorded connections and
 * messages from any thread.
 */
open class FakeHaloTransport(
    context: Context,
) : HaloConnection(context) {
    // One per successful connection, in order.
    val halos = CopyOnWriteArrayList<FakeHalo>()

    // How many upcoming connection attempts fail as if the glasses were off. A background attempt
    // fails only once its link came up, as when glasses connect and drop at once.
    var unreachableAttempts = 0

    // While false, the glasses are off: direct attempts time out, and background attempts wait until
    // the glasses are back, as Android's background connection does.
    var present: Boolean
        get() = presence.value
        set(value) {
            presence.value = value
        }
    private val presence = MutableStateFlow(true)

    // Background attempts that wait for the glasses now, each standing for one pending GATT client.
    val waiting: StateFlow<Int> get() = waitingNow
    private val waitingNow = MutableStateFlow(0)

    // While set, connection attempts wait until cancelled, then fail with a transport error the way a
    // transport that wraps cancellation would.
    var hangConnects = false

    // The device type the next connections report after service discovery.
    var connectedType = HaloDeviceType.HALO

    // Addresses of every connection attempt, in order, and how many were background attempts.
    val attempts = mutableListOf<String>()
    val connects get() = attempts.size
    var backgroundConnects = 0
        private set
    var disconnects = 0

    // Runs at the start of every connection attempt.
    var connecting: () -> Unit = {}

    override suspend fun connect(
        device: BluetoothDevice,
        timeoutMs: Long,
        background: Boolean,
    ): HaloDevice {
        connecting()
        attempts += device.address
        if (background) {
            backgroundConnects++
            waitingNow.update { it + 1 }
            try {
                presence.first { it }
            } finally {
                waitingNow.update { it - 1 }
            }
        } else if (!present) {
            throw HaloException("Connection timed out after ${timeoutMs}ms")
        }
        if (hangConnects) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                throw HaloException("Connection failed: ${e.message}", e)
            }
        }
        if (unreachableAttempts > 0) {
            unreachableAttempts--
            throw HaloException(
                when {
                    background -> "Disconnected during connection (status=8)"
                    else -> "Connection timed out after ${timeoutMs}ms"
                },
            )
        }
        return FakeHalo(device, connectedType).also { halos += it }.device
    }

    override suspend fun disconnect() {
        disconnects++
        halos.lastOrNull()?.closeCause?.compareAndSet(null, HaloException("Disconnected by the host"))
    }
}

class FakeHalo(
    platformDevice: BluetoothDevice,
    type: HaloDeviceType,
) {
    private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    val closeCause = MutableStateFlow<HaloException?>(null)

    // Files the host wrote, and the typed messages it sent as (message code, payload).
    val files = mutableListOf<String>()
    val messages: List<Pair<Int, ByteArray>> get() = sent
    private val sent = CopyOnWriteArrayList<Pair<Int, ByteArray>>()

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

    /** Whether the newest typed messages are [expected], each as its code and packed payload. */
    fun lastSent(expected: List<Pair<Int, TxMessage>>): Boolean {
        val packed = expected.map { (code, message) -> code to message.pack() }
        val last = sent.toList().takeLast(packed.size)
        return last.size == packed.size &&
            last.zip(packed).all { (actual, wanted) ->
                actual.first == wanted.first &&
                    actual.second.contentEquals(wanted.second)
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
            sent += receivingCode to received
            receivingSize = -1
        }
    }

    private companion object {
        val FILE_OPEN = Regex("""frame\.file\.open\("([^"]+)"""")
        val PRINT = Regex("""print\((\d)\)$""")
    }
}
