// Fake Even G2 glasses behind fake arm links that answer AUTH, prelude, settings, case, Even Hub, and Faceclaw drawing.
//
// Decodes protobuf, private-stream records, and draw calls with its own minimal readers,
// independent of the SDK's codecs; only the CRC comes from the SDK. The microphone answers its
// start with 1 and its stop with 0, as the enterprise SDK's tests do, with the command
// FakeG2Firmware.microphoneReply, and a page's close stops it; the test supplies its packets.
// The right arm answers settings
// reads with the glasses' battery, as a pair on 2.3.0.24 with Faceclaw/45 did, and reads and pushes
// the charging case's state as faceclaw reports stock firmware doing. Stock firmware by default; a
// [FakeG2Firmware.revision] adds settings field 100, the framebuffer lease, and the private stream
// on SID 0xF0 that both lenses acknowledge through the receiving arm, as g2flash's
// patches/message_transport.c does. Also holds a fake scan.

package com.fghbuild.eveng2.testing

import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2ArmConnector
import com.fghbuild.eveng2.G2ArmLink
import com.fghbuild.eveng2.G2Charge
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
import java.util.zip.Inflater

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

    /** The command of the microphone's replies: 16, as the enterprise SDK's tests have it; null for none. */
    @Volatile var microphoneReply: Long? = 16L

    /** Whether the microphone runs: from a start to a stop or the close of the page. */
    @Volatile var microphone = false
        private set

    /** The revision the settings read reports in field 100, such as "Faceclaw/45"; null for stock firmware. */
    @Volatile var revision: String? = null

    /** Whether the right arm answers settings reads. */
    @Volatile var answersSettings = true

    /** The glasses' battery in the settings replies; null leaves the battery fields out. */
    @Volatile var charge: G2Charge? = G2Charge(80, charging = false)

    /** The charging case the glasses sit in, or null while they are out of it. */
    @Volatile var case: G2Charge? = null

    /** Whether the right arm answers CASE_INFO reads. */
    @Volatile var answersCase = true

    /** Whether the lenses acknowledge private-stream messages; when false, they draw them but stay silent. */
    @Volatile var answersStream = true

    /** Whether the lenses draw private-stream messages; when false, they refuse each one. */
    @Volatile var draws = true

    /** The arms whose framebuffer lease is held; the lenses refuse drawing unless both are. */
    val leased: Set<G2Side> @Synchronized get() = leases.toSet()
    private val leases = mutableSetOf<G2Side>()

    /** Every screen buffer PRESENT showed, oldest first. */
    val presented = CopyOnWriteArrayList<FakeG2Frame>()
    private val screen = FakeG2Screen()
    private val receivers = mutableMapOf<FakeG2Link, FakeG2Receiver>()

    /** Thrown by the connector instead of bonding with an arm. */
    @Volatile var bondFailure: (G2Side) -> G2Exception? = { null }

    /** The arm of every bond request, in order. */
    val bonds = CopyOnWriteArrayList<G2Side>()

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
        object : G2ArmConnector {
            override suspend fun bond(arm: G2Arm) {
                bonds += arm.side
                bondFailure(arm.side)?.let { throw it }
            }

            override suspend fun connect(
                arm: G2Arm,
                background: Boolean,
            ): G2ArmLink {
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
                return FakeG2Link(arm, this@FakeG2Firmware).also {
                    links += it
                    maxOpen = maxOf(maxOpen, links.count { link -> !link.closed })
                    connected(arm.side)
                }
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
        microphone = false
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
     * The right arm forwards a raw R1 ring report of [type] at ring [tick], as g2flash's
     * patches/gesture_fwd.c does under the lease: a system event of [event] (0 for report type 1, a
     * click) from the ring, with the report in field 100 and no widget or session.
     */
    fun ringReport(
        type: Int,
        event: Long,
        tick: Long,
    ) {
        val report =
            byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 1, 1, type.toByte(), 0, 0, 0) +
                ByteArray(4) { (tick ushr (8 * it)).toByte() }
        val system =
            proto {
                // Explicit, unlike stock proto3.
                scalar(1, event)
                scalar(2, 2)
                bytes(100, report)
            }
        deviceEvent(G2Side.RIGHT, proto { bytes(3, system) })
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

    /**
     * The arms in [sides] notify one microphone [packet] each on their audio characteristic; the
     * microphone must run.
     */
    fun speak(
        packet: ByteArray,
        sides: List<G2Side> = G2Side.entries,
    ) {
        check(microphone) { "the microphone is off" }
        sides.forEach { link(it).hear(packet) }
    }

    /**
     * The right arm pushes the case's state, as it does on each change while the glasses sit in the
     * case; without [level], the push leaves the level out, as docking's first push does.
     */
    fun pushCase(level: Boolean = true) =
        link(G2Side.RIGHT).notify(G2Protocol.SID_CASE, 0, G2Protocol.FLAG_NOTIFY, caseInfo(PUSH_MAGIC, level))

    /** The right arm pushes its settings, with [charge], as it does when the glasses leave the case. */
    fun pushSettings() =
        link(G2Side.RIGHT).notify(G2Protocol.SID_SETTINGS, 0, G2Protocol.FLAG_NOTIFY, settingsReply(PUSH_MAGIC))

    // A settings read's reply, or the arm's own push, with [magic].
    private fun settingsReply(magic: Long): ByteArray {
        val revision = revision
        val charge = charge
        val versions =
            proto {
                bytes(5, "2.2.9".toByteArray())
                bytes(6, "2.2.9".toByteArray())
                // Proto3 omits zeros.
                if (charge != null && charge.percent != 0) scalar(12, charge.percent.toLong())
                if (charge?.charging == true) scalar(13, 1)
            }
        return proto {
            scalar(1, 2)
            scalar(2, magic)
            bytes(4, versions)
            if (revision != null) bytes(100, revision.toByteArray())
        }
    }

    // A CASE_INFO message with [magic]: empty out of the case, as the glasses cannot see it.
    private fun caseInfo(
        magic: Long,
        level: Boolean,
    ): ByteArray {
        val case = case
        val info =
            if (case == null) {
                byteArrayOf()
            } else {
                proto {
                    if (level) scalar(1, case.percent.toLong())
                    if (level && case.charging) scalar(2, 1)
                    scalar(4, 1)
                }
            }
        return proto {
            scalar(1, 1)
            scalar(2, magic)
            bytes(3, info)
        }
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
        fun record(
            kind: String,
            text: String? = null,
        ) {
            requests += Request(link.side, link.arm.address, kind, text)
        }
        // Private-stream packets carry no protobuf.
        if (sid == G2Protocol.SID_CFW) return stream(link, sync, payload, ::record)
        val root = fields(payload)
        val magic = root.scalar(2) ?: 0L
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

            G2Protocol.SID_SETTINGS -> {
                settings(link, sync, root, magic, ::record)
            }

            G2Protocol.SID_CASE -> {
                record("case")
                if (link.side == G2Side.RIGHT && answersCase && root.scalar(1) == 1L) {
                    link.notify(sid, sync, 0, caseInfo(magic, level = true))
                }
            }
        }
    }

    private fun settings(
        link: FakeG2Link,
        sync: Int,
        root: Map<Int, Any>,
        magic: Long,
        record: (String, String?) -> Unit,
    ) {
        when (root.scalar(1)) {
            2L -> {
                record("settings", null)
                if (!answersSettings) return
                link(G2Side.RIGHT).notify(G2Protocol.SID_SETTINGS, sync, 0, settingsReply(magic))
            }

            1L -> {
                val control = root.bytes(101)
                // Stock firmware ignores the field.
                if (revision == null || control == null || control.size != 6) return
                when (control[3].toInt()) {
                    5 -> leases += link.side
                    6 -> leases -= link.side
                    else -> return
                }
                record("lease", if (control[3].toInt() == 5) "acquire" else "release")
            }
        }
    }

    // One private-stream packet to [link]; each lens answers a complete message through it.
    private fun stream(
        link: FakeG2Link,
        sequence: Int,
        payload: ByteArray,
        record: (String, String?) -> Unit,
    ) {
        check(revision != null) { "private stream on stock firmware" }
        val message = receivers.getOrPut(link) { FakeG2Receiver() }.accept(sequence, payload) ?: return
        val drawn = message.body != null && draw(message.body, record)
        if (!answersStream) return
        for (lens in 1..2) {
            val reply =
                byteArrayOf(if (drawn) 1 else 3, message.stream.toByte(), 0, 0, lens.toByte()) +
                    u16(message.body?.size ?: 0) +
                    u16(message.checksum)
            link.notify(G2Protocol.SID_CFW, 0, 0, reply)
        }
    }

    // Applies one decoded drawing message; false when the lenses refuse it.
    private fun draw(
        message: ByteArray,
        record: (String, String?) -> Unit,
    ): Boolean {
        if (!draws || message.isEmpty() || leases.size < 2) return false
        return when (message[0].toInt()) {
            26 -> {
                record("draw", null)
                screen.apply(message)
            }

            27 -> {
                record("root", null)
                message.size == 3
            }

            28 -> {
                record("present", null)
                presented += screen.snapshot()
                message.size == 1
            }

            else -> {
                false
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
                microphone = false
                if (answersShutdown) ack(10, 12, proto { scalar(1, 10) })
            }

            12L -> {
                record("heartbeat", null)
                ack(13, 15, proto { scalar(2, 12) })
            }

            15L -> {
                val on = fields(checkNotNull(root.bytes(18))).scalar(1) == 1L
                record("microphone", if (on) "on" else "off")
                microphone = on
                // Proto3 omits a zero result.
                microphoneReply?.let { ack(it, 19, if (on) proto { scalar(1, 1) } else byteArrayOf()) }
            }
        }
    }
}

/** One line the stock font drew with its top-left corner at [x], [y]. */
data class FakeG2Text(
    val x: Int,
    val y: Int,
    val text: String,
)

/** A presented 640×480 screen buffer: its pixels' grey levels and the stock-font lines drawn over them. */
class FakeG2Frame(
    private val levels: ByteArray,
    val texts: List<FakeG2Text>,
) {
    fun level(
        x: Int,
        y: Int,
    ): Int = levels[y * SCREEN_WIDTH + x].toInt()

    /** The drawn lines, top to bottom, each joined from its pieces. */
    val lines: List<String>
        get() =
            texts
                .groupBy { it.y }
                .toSortedMap()
                .values
                .map { line -> line.joinToString("") { it.text } }
}

// The magic the glasses choose for their own case pushes, unlike any request's.
private const val PUSH_MAGIC = 200L

private const val SCREEN_WIDTH = 640
private const val SCREEN_HEIGHT = 480

// The screen buffer, which draw calls change and PRESENT shows.
private class FakeG2Screen {
    private val levels = ByteArray(SCREEN_WIDTH * SCREEN_HEIGHT)
    private val texts = mutableListOf<FakeG2Text>()

    fun snapshot() = FakeG2Frame(levels.copyOf(), texts.toList())

    // `26 <count> {<length> <call>}`; false for a malformed message or a call the fake does not know.
    fun apply(message: ByteArray): Boolean {
        val reader = Reader(message, 1)
        repeat(reader.u16()) {
            if (!call(Reader(reader.bytes(reader.u16()), 0))) return false
        }
        return reader.done
    }

    private fun call(reader: Reader): Boolean {
        val op = reader.u8()
        if (reader.u8() != 0) return false
        when (op) {
            9 -> {
                levels.fill(reader.u8().toByte())
                texts.clear()
            }

            3 -> {
                val x = reader.u16().toShort().toInt()
                val y = reader.u16().toShort().toInt()
                if (reader.u8() != 15) return false
                texts += FakeG2Text(x, y, String(reader.bytes(reader.u8())))
            }

            1 -> {
                if (reader.u8() != 1) return false
                return box(reader, reader.u16(), reader.u16(), reader.u16(), reader.u16())
            }

            else -> {
                return false
            }
        }
        return reader.done
    }

    // The RLE pixels of a [width] by [height] rectangle at [x], [y].
    private fun box(
        reader: Reader,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean {
        if (x + width > SCREEN_WIDTH || y + height > SCREEN_HEIGHT) return false
        var pixel = 0
        while (pixel < width * height) {
            val head = reader.u8()
            val run = if (head ushr 4 != 0) head ushr 4 else reader.u8().takeIf { it != 0 } ?: reader.u16()
            if (pixel + run > width * height) return false
            repeat(run) {
                val at = pixel + it
                levels[(y + at / width) * SCREEN_WIDTH + x + at % width] = (head and 0x0F).toByte()
            }
            pixel += run
        }
        return reader.done
    }
}

// Joins one link's private-stream packets into records and inflates them in one zlib context.
private class FakeG2Receiver {
    class Message(
        val stream: Int,
        val checksum: Int,
        // Null when the record does not decode to a message with its checksum.
        val body: ByteArray?,
    )

    private var stream = 0
    private var record: ByteArrayOutputStream? = null
    private val inflater = Inflater()
    private var context = false

    // [payload] is <options> <bytes>; a message once the packet with option 0x40 ends its record.
    fun accept(
        sequence: Int,
        payload: ByteArray,
    ): Message? {
        val options = payload[0].toInt() and 0xFF
        if (options and 0x80 != 0) {
            stream = sequence
            record = ByteArrayOutputStream()
        }
        val bytes = checkNotNull(record) { "packet outside a stream" }
        bytes.write(payload, 1, payload.size - 1)
        if (options and 0x40 == 0) return null
        record = null
        val reader = Reader(bytes.toByteArray(), 0)
        val flags = reader.u8()
        val size = reader.u16()
        val checksum = reader.u16()
        val wire = reader.bytes(size)
        if (flags and 8 != 0) {
            inflater.reset()
            context = true
        }
        val body =
            if (flags and 4 == 0) {
                wire
            } else if (context) {
                inflate(wire)
            } else {
                null
            }
        val valid = context && reader.done && flags and 3 == 3 && body != null && G2Frame.crc16(body) == checksum
        if (!valid) context = false
        return Message(stream, checksum, body.takeIf { valid })
    }

    private fun inflate(wire: ByteArray): ByteArray {
        inflater.setInput(wire)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val count = inflater.inflate(chunk)
            out.write(chunk, 0, count)
            if (count == 0 && inflater.needsInput()) return out.toByteArray()
        }
    }
}

// Little-endian fields of [data] from [offset]; throws when they run out.
private class Reader(
    private val data: ByteArray,
    private var offset: Int,
) {
    val done: Boolean get() = offset == data.size

    fun u8(): Int = data[offset++].toInt() and 0xFF

    fun u16(): Int = u8() or (u8() shl 8)

    fun bytes(count: Int): ByteArray {
        check(offset + count <= data.size) { "truncated" }
        return data.copyOfRange(offset, offset + count).also { offset += count }
    }
}

private fun u16(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())

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
    private val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val partial = mutableMapOf<Int, ByteArray>()

    @Volatile private var lost: G2LinkLostException? = null

    @Volatile var closed = false

    /** When set, writes fail with this reason while the link stays up. */
    @Volatile var writeFailure: String? = null

    override val side: G2Side get() = arm.side
    override val notifications = channel
    override val audio = audioChannel

    // As Android negotiates with the glasses.
    override val mtu = 512

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
        audioChannel.close()
    }

    /** The arm notifies [packet] on its audio characteristic. */
    fun hear(packet: ByteArray) {
        audioChannel.trySend(packet.copyOf())
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
        lost =
            G2LinkLostException(status).also {
                channel.close(it)
                audioChannel.close(it)
            }
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
