// G2EvenHub: Even Hub (SID 0xE0) text page, in-place text update, heartbeat, shutdown, replies, presses, and exits.
//
// Ported and modified from G2EvenHubCodec.kt of the Even Realities enterprise SDK
// 0.1.0-beta.14 (Apache-2.0, Copyright 2026 Hong Kong Even Realities Limited), narrowed to one
// text container; defaults follow the even-g2-protocol Python library (MIT). G2RingFilter is
// ported from jimrandomh/faceclaw (GPL-3.0). See NOTICE in this module.
//
// Every request is one protobuf envelope, framed with header flag 0x20 and sent to the right
// arm: {1: command, 2: magic (1..255), <request branch>: body}. A reply echoes the magic as
// {1: response command, 2: magic, <response branch>: {<result field>: code}}; proto3 omits a
// zero code. Only one page is open at a time.
//
// | Command       | Request (branch) | Reply (branch) | Result field | Success |
// |---------------|------------------|----------------|--------------|---------|
// | create page   | 0 (3)            | 1 (4)          | 1            | 0       |
// | update text   | 5 (9)            | 6 (10)         | 1            | 8       |
// | shutdown page | 9 (11)           | 10 (12)        | 1            | 10      |
// | heartbeat     | 12 (14)          | 13 or 12 (15)  | 2            | 12      |
//
// Create page body: {1: container count, 3: text container, 5: widget ID, 7: page session ID}.
// Text container: {1: x, 2: y, 3: width, 4: height, 8: padding (omitted when 0), 9: container
// ID, 10: container name, 11: 1 (captures input), 12: content}. Update text body: {1: container
// ID, 2: container name, 3: byte offset 0, 4: content byte length, 5: content}; the ID routes
// the update, the name must match. Heartbeat and shutdown bodies are empty.
//
// The glasses report input and page exits as event command 2, branch 13, whose body holds one
// event: a text event (branch 2) {1: container ID, 2: container name, 3: event type}, or a system
// event (branch 3) {1: event type, 2: source (0 unspecified, 1 right temple, 2 R1 ring, 3 left
// temple), 3: IMU sample, 4: exit reason, 5: widget ID, 6: page session ID}; a list event (branch
// 1) cannot name this page's text container. Event types: 0 click, 1 scroll up, 2 scroll down, 3
// double click, 4 and 5 foreground enter and exit, 6 abnormal exit, 7 exit, 8 IMU data, 9 long
// press, 10 its release. Proto3 omits type 0, so a click carries no type. Only an exit that names
// this page's widget and session closes it. The Python library's live-confirmed create page omits
// the page session ID (field 7), which the enterprise SDK sends; on a pair, the glasses took a page
// created with it and named it in an exit while the wearer took them off and put them on. A press
// is a click: a text event that names this page's container, which captures input, or a system
// event from a touchpad or the ring whose widget and session, when present, are this page's. Either
// arm may report input, as the enterprise SDK accepts it from both. The enterprise contract vector
// g2_evenhub_click_hex is a text event click; the README of Even Realities'
// @evenrealities/even_hub_sdk 0.0.16 npm package (MIT) says source-aware input, whose sources
// include the ring, arrives as system events.
//
// Under Faceclaw's framebuffer lease (G2Settings), g2flash's patches/gesture_fwd.c (GPL-3.0)
// changes the input: touchpad and ring long presses arrive as system events 9 and 10 instead of
// opening the stock menu that offers to quit, a tap then hold as 11, head up and down as 12 and 15,
// and a ring touch-down as 14. On a pair, 10 came at the end of every touchpad touch, a tap or a
// swipe included, and a tap's click came for 4 of 13 single taps. Every R1 report then arrives raw
// as a system event from the ring whose field 100 holds it: 'R' 'I' 1 1 <report type> <aux> <speed>
// 0 <ring tick u32 LE>, without the stock firmware's suppression of a report within 100 ring ticks
// of the last; G2RingFilter, ported from faceclaw's app/ui/input-monitor.ts (GPL-3.0), suppresses
// them as faceclaw does. None of the new events is a click, so a press stays one click of a
// touchpad or the ring.
//
// TODO(hardware): Confirm on a real pair, on stock firmware, which event one touchpad click and one
// R1 ring click send, from which arm, and that a double click sends only type 3, not a click
// first. Log every event command 2 message while tapping each temple and the ring once. If one
// press arrives twice (from both arms, or as both a text and a system event), accept only one arm
// or one form in press(); otherwise every press would start and at once end voice.

package com.fghbuild.eveng2

/** Display of one eye; the firmware lays out text, so the client sends plain text. */
object G2Display {
    const val WIDTH_PX = 576
    const val HEIGHT_PX = 288

    /** Grey levels per pixel; the glasses show them in green. */
    const val GREY_LEVELS = 16

    /** UTF-8 byte limit of a text container's content when the page is created. */
    const val MAX_CREATE_TEXT_BYTES = 999

    /** UTF-8 byte limit of an in-place text update. */
    const val MAX_TEXT_BYTES = 1_999
}

/** Geometry and identity of the page's single text container; validated on construction. */
data class G2TextPage(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = G2Display.WIDTH_PX,
    val height: Int = G2Display.HEIGHT_PX,
    val padding: Int = 0,
    val containerId: Long = 1,
    val containerName: String = "text",
    val widgetId: Long = 10_000,
) {
    init {
        require(x in 0..G2Display.WIDTH_PX && width in 1..G2Display.WIDTH_PX && x + width <= G2Display.WIDTH_PX)
        require(y in 0..G2Display.HEIGHT_PX && height in 1..G2Display.HEIGHT_PX && y + height <= G2Display.HEIGHT_PX)
        require(padding in 0..32)
        require(containerId in 1..UINT32_MAX && widgetId in 1..UINT32_MAX)
        require(decodeUtf8(containerName.toByteArray()) == containerName && containerName.toByteArray().size in 1..15)
    }

    /**
     * Whether the container shows all of [text] without scrolling, as the stock firmware wraps it;
     * see [G2TextLayout]. Text over [G2Display.MAX_TEXT_BYTES] never fits: [G2Glasses.show] refuses
     * it. The first call reads the firmware font table unless [loadMetrics] did.
     */
    fun fits(text: String): Boolean {
        if (text.toByteArray().size > G2Display.MAX_TEXT_BYTES) return false
        // TODO(hardware): pretext documents the line height and glyph widths, not where a text
        // container puts its first line or whether it spaces lines further apart. On a real pair,
        // show 10 and then 11 lines of "Hg" in the default 576×288 container: 10 should show whole
        // and 11 scroll. If not, correct this.
        val inner = 2 * padding
        return G2TextLayout.lines(text, width - inner).size * G2TextLayout.lineHeightPx <= height - inner
    }

    companion object {
        /** Reads the firmware font table that [fits] measures with, 130 KB, on an IO thread. */
        suspend fun loadMetrics() = G2TextLayout.load()
    }
}

/** Even Hub commands the client awaits; values are the request, reply, and result codes. */
enum class G2HubCommand(
    internal val request: Long,
    internal val requestBranch: Int,
    internal val response: Long,
    internal val responseBranch: Int,
    internal val resultField: Int,
    internal val success: Long,
) {
    CREATE_PAGE(0, 3, 1, 4, 1, 0),
    UPDATE_TEXT(5, 9, 6, 10, 1, 8),
    SHUTDOWN_PAGE(9, 11, 10, 12, 1, 10),
    HEARTBEAT(12, 14, 13, 15, 2, 12),
}

/** Outcome of an Even Hub reply that matches the request's command and magic. */
sealed interface G2HubReply {
    data object Success : G2HubReply

    /** [result] is the firmware's code, or null when the reply is malformed or the header reports failure. */
    data class Rejected(
        val result: Long?,
    ) : G2HubReply
}

object G2EvenHub {
    private const val EVENT_COMMAND = 2L
    private const val EVENT_BRANCH = 13
    private const val TEXT_EVENT = 2
    private const val SYSTEM_EVENT = 3

    // Input sources of a system event: right temple touchpad, R1 ring, left temple touchpad.
    private val PRESS_SOURCES = G2InputEvent.SOURCE_RIGHT_TEMPLE..G2InputEvent.SOURCE_LEFT_TEMPLE
    private const val RING_REPORT_FIELD = 100
    private const val RING_REPORT_SIZE = 12

    // 'R' 'I', version 1, flags 1.
    private val RING_REPORT_HEADER = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 1, 1)
    private val BRANCHES = 3..26

    /** Magic values of Even Hub requests. */
    val MAGIC_RANGE = 1..255

    /** Throws [IllegalArgumentException] unless [text] is valid content of at most [maxBytes] UTF-8 bytes. */
    fun requireText(
        text: String,
        maxBytes: Int = G2Display.MAX_TEXT_BYTES,
    ) {
        val bytes = text.toByteArray()
        require(bytes.size <= maxBytes) { "text is ${bytes.size} UTF-8 bytes; the limit is $maxBytes" }
        require(decodeUtf8(bytes) == text) { "text must be valid Unicode without NUL" }
    }

    fun createPage(
        page: G2TextPage,
        text: String,
        session: Long,
        magic: Int,
        sync: Int,
    ): List<ByteArray> {
        requireText(text, G2Display.MAX_CREATE_TEXT_BYTES)
        require(session in 1..UINT32_MAX)
        val container =
            protobuf {
                scalar(1, page.x.toLong())
                scalar(2, page.y.toLong())
                scalar(3, page.width.toLong())
                scalar(4, page.height.toLong())
                if (page.padding != 0) scalar(8, page.padding.toLong())
                scalar(9, page.containerId)
                bytes(10, page.containerName.toByteArray())
                scalar(11, 1)
                bytes(12, text.toByteArray())
            }
        val body =
            protobuf {
                scalar(1, 1)
                bytes(3, container)
                scalar(5, page.widgetId)
                scalar(7, session)
            }
        return request(G2HubCommand.CREATE_PAGE, body, magic, sync)
    }

    fun updateText(
        page: G2TextPage,
        text: String,
        magic: Int,
        sync: Int,
    ): List<ByteArray> {
        requireText(text)
        val content = text.toByteArray()
        val body =
            protobuf {
                scalar(1, page.containerId)
                bytes(2, page.containerName.toByteArray())
                scalar(3, 0)
                scalar(4, content.size.toLong())
                bytes(5, content)
            }
        return request(G2HubCommand.UPDATE_TEXT, body, magic, sync)
    }

    fun heartbeat(
        magic: Int,
        sync: Int,
    ): List<ByteArray> = request(G2HubCommand.HEARTBEAT, byteArrayOf(), magic, sync)

    fun shutdownPage(
        magic: Int,
        sync: Int,
    ): List<ByteArray> = request(G2HubCommand.SHUTDOWN_PAGE, byteArrayOf(), magic, sync)

    private fun request(
        command: G2HubCommand,
        body: ByteArray,
        magic: Int,
        sync: Int,
    ): List<ByteArray> {
        require(magic in MAGIC_RANGE) { "magic $magic" }
        val payload =
            protobuf {
                scalar(1, command.request)
                scalar(2, magic.toLong())
                bytes(command.requestBranch, body)
            }
        return G2Frame.packets(payload, G2Protocol.SID_EVEN_HUB, G2Protocol.FLAG_SYNC_BOTH, sync)
    }

    /** Classify [message] as the reply to [command] sent with [magic] and [sync]; null when it is not. */
    fun reply(
        message: G2Message,
        command: G2HubCommand,
        magic: Int,
        sync: Int,
    ): G2HubReply? {
        if (message.sid != G2Protocol.SID_EVEN_HUB) return null
        if (message.result != 0) return if (message.sync == sync) G2HubReply.Rejected(null) else null
        val root = G2ProtoReader.fields(message.payload) ?: return null
        val responseCommand = root.scalar(1) ?: 0L
        // Enterprise firmware was observed answering a heartbeat with the request command 12.
        val matches =
            responseCommand == command.response ||
                (command == G2HubCommand.HEARTBEAT && responseCommand == command.request)
        if (!matches || root.scalar(2) != magic.toLong()) return null
        val malformed = G2HubReply.Rejected(null)
        if (root.keys.any { it !in 1..BRANCHES.last } ||
            root.keys.filter { it in BRANCHES } != listOf(command.responseBranch)
        ) {
            return malformed
        }
        val body = root.bytes(command.responseBranch)?.let(G2ProtoReader::fields) ?: return malformed
        val allowed = if (command == G2HubCommand.HEARTBEAT) setOf(1, 2) else setOf(1)
        if (body.keys.any { it !in allowed } || body.values.any { it.wire != 0 }) return malformed
        val result = body.scalar(command.resultField) ?: 0L
        return if (result == command.success) G2HubReply.Success else G2HubReply.Rejected(result)
    }

    /** Whether [message] is an exit, abnormal or not, of the page identified by [page] and [session]. */
    fun pageExit(
        message: G2Message,
        page: G2TextPage,
        session: Long,
    ): Boolean {
        val (branch, system) = deviceEvent(message) ?: return false
        if (branch != SYSTEM_EVENT) return false
        val type = system.scalar(1) ?: 0L
        return (type == G2InputEvent.ABNORMAL_EXIT || type == G2InputEvent.EXIT) &&
            system.scalar(5) == page.widgetId &&
            system.scalar(6) == session
    }

    /**
     * Whether [message] is a press on the page identified by [page] and [session]: a click on its
     * text container, or a system click from a temple touchpad or the R1 ring. Other input, such as
     * a double click, a scroll, a long press, or a click of an unspecified source, is not one.
     */
    fun press(
        message: G2Message,
        page: G2TextPage,
        session: Long,
    ): Boolean {
        val (branch, event) = deviceEvent(message) ?: return false
        return when (branch) {
            TEXT_EVENT -> {
                event.keys.all { it in 1..3 } &&
                    event.scalar(1) == page.containerId &&
                    (2 !in event || event.bytes(2)?.let(::decodeUtf8) == page.containerName) &&
                    event.enum(3) == G2InputEvent.CLICK
            }

            SYSTEM_EVENT -> {
                event.keys.all { it in 1..6 || (it == RING_REPORT_FIELD && ringReport(event) != null) } &&
                    event.enum(1) == G2InputEvent.CLICK &&
                    event.scalar(2) in PRESS_SOURCES &&
                    (5 !in event || event.scalar(5) == page.widgetId) &&
                    (6 !in event || event.scalar(6) == session)
            }

            else -> {
                false
            }
        }
    }

    /** The input or system event that [arm] reported in [message], or null when it is none or an IMU sample. */
    fun inputEvent(
        message: G2Message,
        arm: G2Side,
    ): G2InputEvent? {
        val (branch, event) = deviceEvent(message) ?: return null
        return when (branch) {
            TEXT_EVENT -> {
                G2InputEvent(arm, event.enum(3) ?: G2InputEvent.CLICK, source = null)
            }

            SYSTEM_EVENT -> {
                val type = event.enum(1) ?: G2InputEvent.CLICK
                if (type == G2InputEvent.IMU_DATA) return null
                G2InputEvent(arm, type, event.enum(2) ?: G2InputEvent.SOURCE_UNSPECIFIED)
            }

            else -> {
                null
            }
        }
    }

    /** The raw R1 report that Faceclaw firmware attaches to a ring system event in [message], or null. */
    fun ringReport(message: G2Message): G2RingReport? {
        val (branch, event) = deviceEvent(message) ?: return null
        return if (branch == SYSTEM_EVENT) ringReport(event) else null
    }

    private fun ringReport(event: Map<Int, G2ProtoField>): G2RingReport? {
        val raw = event.bytes(RING_REPORT_FIELD) ?: return null
        if (event.scalar(2) != G2InputEvent.SOURCE_RING || raw.size != RING_REPORT_SIZE) return null
        if (!raw.copyOfRange(0, RING_REPORT_HEADER.size).contentEquals(RING_REPORT_HEADER) || raw[7] != 0.toByte()) {
            return null
        }
        val tick = (0 until Int.SIZE_BYTES).sumOf { (raw[8 + it].toLong() and 0xFF) shl (8 * it) }
        return G2RingReport(raw[4].toInt() and 0xFF, tick)
    }

    // The single event of a device event message, as its branch and fields; null when [message] is not one.
    private fun deviceEvent(message: G2Message): Pair<Int, Map<Int, G2ProtoField>>? {
        if (message.sid != G2Protocol.SID_EVEN_HUB || message.result != 0) return null
        val root = G2ProtoReader.fields(message.payload) ?: return null
        if ((root.scalar(1) ?: 0L) != EVENT_COMMAND ||
            root.keys.filter { it in BRANCHES } != listOf(EVENT_BRANCH)
        ) {
            return null
        }
        val event = root.bytes(EVENT_BRANCH)?.let(G2ProtoReader::fields) ?: return null
        val branch = event.keys.singleOrNull() ?: return null
        return event.bytes(branch)?.let(G2ProtoReader::fields)?.let { branch to it }
    }

    // An enum [field]: proto3 omits its zero value; null when it has another wire type.
    private fun Map<Int, G2ProtoField>.enum(field: Int): Long? = if (field in this) scalar(field) else 0L
}

/** One R1 report as Faceclaw firmware forwards it: its [type] and the ring's own [tick] counter. */
data class G2RingReport(
    val type: Int,
    val tick: Long,
)

/**
 * Drops an R1 report within 100 ring ticks of the last one it passed, as stock firmware does before it
 * reports ring input. A release (type 8) and a touch-down (type 10) always pass, and a touch-down does
 * not start a new interval. One filter serves one session.
 */
internal class G2RingFilter {
    private var lastTick = 0L

    fun accept(report: G2RingReport): Boolean {
        if (report.type != RELEASE &&
            report.type != TOUCH_DOWN &&
            lastTick != 0L &&
            (report.tick - lastTick) and UINT32_MAX < INTERVAL_TICKS
        ) {
            return false
        }
        if (report.type != TOUCH_DOWN) lastTick = report.tick
        return true
    }

    private companion object {
        const val RELEASE = 8
        const val TOUCH_DOWN = 10
        const val INTERVAL_TICKS = 100L
    }
}
