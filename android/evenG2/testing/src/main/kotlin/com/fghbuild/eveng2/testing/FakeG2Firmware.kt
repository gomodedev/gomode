// Fake Even G2 glasses behind fake arm links that answer AUTH, the prelude, and Even Hub, and a fake scan, for SDK and app tests.
//
// Decodes protobuf with its own minimal reader, independent of the SDK's codec.

package com.fghbuild.eveng2.testing

import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2ArmConnector
import com.fghbuild.eveng2.G2ArmLink
import com.fghbuild.eveng2.G2Exception
import com.fghbuild.eveng2.G2Frame
import com.fghbuild.eveng2.G2LinkLostException
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2PairScanner
import com.fghbuild.eveng2.G2Protocol
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.G2TextPage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Glasses that answer AUTH, the prelude, and Even Hub commands the way the vectors show. Thread-safe. */
class FakeG2Firmware {
    @Volatile var authenticates: (G2Side) -> Boolean = { true }

    // Bumped on each change of reachable, so waiting background connections look again.
    private val reach = MutableStateFlow(0)

    /** Whether an arm is in range: direct connections to others fail, background ones wait for it. */
    @Volatile var reachable: (G2Side) -> Boolean = { true }
        set(value) {
            field = value
            reach.update { it + 1 }
        }

    /** Background connections that wait for their arm now, each standing for one pending GATT client. */
    val waiting: StateFlow<Int> get() = waitingNow
    private val waitingNow = MutableStateFlow(0)

    @Volatile var answersPrelude = true

    @Volatile var answersUpdates = true

    @Volatile var answersCreate = true

    /** Result code of page creation replies; 0 is success. */
    @Volatile var createResult = 0L

    @Volatile var answersShutdown = true

    /** Thrown by the connector instead of connecting; any exception type, as a host connector may. */
    @Volatile var failure: (G2Side) -> Exception? = { null }

    /** Runs after an arm's link is created, before connect() returns it. */
    @Volatile var connected: (G2Side) -> Unit = {}

    /** The most links open at the same time. */
    @Volatile var maxOpen = 0

    @Volatile var pageSession = 0L

    /** The arm of every connection attempt, in order, and of every background one among them. */
    val attempts = CopyOnWriteArrayList<G2Side>()
    val backgroundAttempts: List<G2Side> get() = backgroundSides
    private val backgroundSides = CopyOnWriteArrayList<G2Side>()
    val links = CopyOnWriteArrayList<FakeG2Link>()
    val requests = CopyOnWriteArrayList<Request>()

    class Request(
        val side: G2Side,
        val address: String,
        val kind: String,
        val text: String? = null,
    ) {
        override fun toString(): String = listOfNotNull("$side $kind", text).joinToString(" ")
    }

    val connector =
        G2ArmConnector { arm, background ->
            attempts += arm.side
            if (background) {
                backgroundSides += arm.side
                waitingNow.update { it + 1 }
                try {
                    reach.first { reachable(arm.side) }
                } finally {
                    waitingNow.update { it - 1 }
                }
            } else if (!reachable(arm.side)) {
                throw G2Exception("unreachable")
            }
            failure(arm.side)?.let { throw it }
            FakeG2Link(arm, this).also {
                links += it
                maxOpen = maxOf(maxOpen, links.count { link -> !link.closed })
                connected(arm.side)
            }
        }

    fun link(side: G2Side): FakeG2Link = links.last { it.side == side }

    /** The text of the newest page creation or in-place update, or null before the first page. */
    fun shownText(): String? = requests.lastOrNull { it.kind == "create" || it.kind == "update" }?.text

    /** The glasses report a page exit of [type] (6 abnormal, 7 by the wearer) for page [session]. */
    fun exitPage(
        type: Long,
        session: Long,
    ) {
        val system =
            proto {
                scalar(1, type)
                scalar(5, G2TextPage().widgetId)
                scalar(6, session)
            }
        deviceEvent(G2Side.RIGHT, proto { bytes(3, system) })
    }

    /**
     * The [side] arm reports a touchpad event of [type] (0 click, 3 double click) on the default
     * page's text container, as the enterprise contract vector g2_evenhub_click_hex does.
     */
    fun touch(
        side: G2Side,
        type: Long,
    ) {
        val page = G2TextPage()
        val text =
            proto {
                scalar(1, page.containerId)
                bytes(2, page.containerName.toByteArray())
                // Proto3 omits a zero enum.
                if (type != 0L) scalar(3, type)
            }
        deviceEvent(side, proto { bytes(2, text) })
    }

    /**
     * The [side] arm reports a system event of [type] from [source] (0 unspecified, 1 right temple, 2
     * R1 ring, 3 left temple) for the current page; proto3 omits a zero type or source.
     */
    fun systemInput(
        side: G2Side,
        type: Long,
        source: Long,
    ) {
        val system =
            proto {
                if (type != 0L) scalar(1, type)
                if (source != 0L) scalar(2, source)
                scalar(5, G2TextPage().widgetId)
                scalar(6, pageSession)
            }
        deviceEvent(side, proto { bytes(3, system) })
    }

    private fun deviceEvent(
        side: G2Side,
        event: ByteArray,
    ) {
        val payload =
            proto {
                scalar(1, 2)
                bytes(13, event)
            }
        link(side).notify(G2Protocol.SID_EVEN_HUB, 1, G2Protocol.FLAG_NOTIFY, payload)
    }

    @Synchronized
    fun handle(
        link: FakeG2Link,
        sid: Int,
        sync: Int,
        payload: ByteArray,
    ) {
        val root = fields(payload)
        val magic = root.scalar(2) ?: 0L

        fun record(
            kind: String,
            text: String? = null,
        ) {
            requests += Request(link.side, link.arm.address, kind, text)
        }
        when (sid) {
            G2Protocol.SID_DEVICE_CONFIG -> {
                record("auth")
                val auth = if (authenticates(link.side)) byteArrayOf() else proto { scalar(3, 1) }
                link.notify(
                    sid,
                    sync,
                    0,
                    proto {
                        scalar(1, 4)
                        scalar(2, magic)
                        bytes(3, auth)
                    },
                )
            }

            G2Protocol.SID_PRELUDE -> {
                record("prelude")
                if (!answersPrelude) return
                link.notify(
                    sid,
                    sync,
                    0,
                    proto {
                        scalar(1, 2)
                        scalar(2, magic)
                    },
                )
            }

            G2Protocol.SID_EVEN_HUB -> {
                evenHub(link, sync, root, magic, ::record)
            }
        }
    }

    private fun evenHub(
        link: FakeG2Link,
        sync: Int,
        root: Map<Int, Any>,
        magic: Long,
        record: (String, String?) -> Unit,
    ) {
        fun ack(
            command: Long,
            branch: Int,
            body: ByteArray,
        ) = link.notify(
            G2Protocol.SID_EVEN_HUB,
            sync,
            0,
            proto {
                scalar(1, command)
                scalar(2, magic)
                bytes(branch, body)
            },
        )
        when (root.scalar(1) ?: 0L) {
            0L -> {
                val body = fields(checkNotNull(root.bytes(3)))
                val container = fields(checkNotNull(body.bytes(3)))
                pageSession = checkNotNull(body.scalar(7))
                record("create", String(checkNotNull(container.bytes(12))))
                // Proto3 omits a zero result.
                val result = createResult
                if (answersCreate) ack(1, 4, if (result == 0L) byteArrayOf() else proto { scalar(1, result) })
            }

            5L -> {
                val body = fields(checkNotNull(root.bytes(9)))
                record("update", String(checkNotNull(body.bytes(5))))
                if (answersUpdates) ack(6, 10, proto { scalar(1, 8) })
            }

            9L -> {
                record("shutdown", null)
                if (answersShutdown) ack(10, 12, proto { scalar(1, 10) })
            }

            12L -> {
                record("heartbeat", null)
                ack(13, 15, proto { scalar(2, 12) })
            }
        }
    }
}

/** A scan that hears [pairs] at once and then nothing more until cancelled. */
fun fakeG2Scanner(vararg pairs: G2Pair) =
    G2PairScanner {
        flow {
            pairs.forEach { emit(it) }
            awaitCancellation()
        }
    }

/** One fake arm: reassembles the phone's packets for the firmware and notifies its replies. */
class FakeG2Link(
    val arm: G2Arm,
    private val firmware: FakeG2Firmware,
) : G2ArmLink {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val partial = mutableMapOf<Int, ByteArray>()

    @Volatile private var lost: G2LinkLostException? = null

    @Volatile var closed = false

    /** When set, writes fail with this reason while the link stays up. */
    @Volatile var writeFailure: String? = null

    override val side: G2Side get() = arm.side
    override val notifications = channel

    override suspend fun write(packet: ByteArray) {
        lost?.let { throw it }
        if (closed) throw G2Exception("closed")
        writeFailure?.let { throw G2Exception(it) }
        val sync = packet[2].toInt() and 0xFF
        val length = packet[3].toInt() and 0xFF
        val last = packet[4] == packet[5]
        val body = packet.copyOfRange(8, 8 + length - if (last) 2 else 0)
        val payload =
            synchronized(partial) {
                val joined = (partial.remove(sync) ?: byteArrayOf()) + body
                if (!last) partial[sync] = joined
                joined
            }
        if (last) firmware.handle(this, packet[6].toInt() and 0xFF, sync, payload)
    }

    override fun close() {
        closed = true
        channel.close()
    }

    fun notify(
        sid: Int,
        sync: Int,
        flag: Int,
        payload: ByteArray,
    ) {
        G2Frame.packets(payload, sid, flag, sync).forEach {
            // Glasses-to-phone address.
            it[1] = 0x12
            channel.trySend(it)
        }
    }

    /** The arm's radio link drops with platform [status]. */
    fun drop(status: Int) {
        lost = G2LinkLostException(status).also { channel.close(it) }
    }
}

private class ProtoOut {
    val out = ByteArrayOutputStream()

    fun scalar(
        field: Int,
        value: Long,
    ) {
        varint((field shl 3).toLong())
        varint(value)
    }

    fun bytes(
        field: Int,
        value: ByteArray,
    ) {
        varint(((field shl 3) or 2).toLong())
        varint(value.size.toLong())
        out.write(value)
    }

    private fun varint(value: Long) {
        var rest = value
        while (rest and 0x7FL.inv() != 0L) {
            out.write(((rest and 0x7F) or 0x80).toInt())
            rest = rest ushr 7
        }
        out.write(rest.toInt())
    }
}

private fun proto(block: ProtoOut.() -> Unit): ByteArray = ProtoOut().apply(block).out.toByteArray()

// Varint fields as Long and length-delimited fields as ByteArray; the fake reads nothing else.
private fun fields(data: ByteArray): Map<Int, Any> {
    val result = mutableMapOf<Int, Any>()
    var offset = 0

    fun varint(): Long {
        var value = 0L
        var shift = 0
        while (true) {
            val byte = data[offset++].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
    }
    while (offset < data.size) {
        val key = varint()
        val field = (key ushr 3).toInt()
        when ((key and 7).toInt()) {
            0 -> {
                result[field] = varint()
            }

            2 -> {
                val size = varint().toInt()
                result[field] = data.copyOfRange(offset, offset + size)
                offset += size
            }

            else -> {
                error("wire type ${key and 7} in field $field")
            }
        }
    }
    return result
}

private fun Map<Int, Any>.scalar(field: Int): Long? = get(field) as? Long

private fun Map<Int, Any>.bytes(field: Int): ByteArray? = get(field) as? ByteArray
