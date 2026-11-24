// G2EvenHub: Even Hub (SID 0xE0) text page, in-place text update, heartbeat, shutdown, replies, and exit events.
//
// Ported and modified from G2EvenHubCodec.kt of the Even Realities enterprise SDK
// 0.1.0-beta.14 (Apache-2.0, Copyright 2026 Hong Kong Even Realities Limited), narrowed to one
// text container; defaults follow the even-g2-protocol Python library (MIT); see NOTICE in
// this module.
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
// The glasses report a page exit as event command 2, branch 13, system event (branch 3)
// {1: event type, 5: widget ID, 6: page session ID}; type 6 is an abnormal exit and 7 an exit.
// Only an exit that names this page's widget and session closes it.
//
// TODO(hardware): The Python library's live-confirmed create page omits the page session ID
// (field 7); the enterprise SDK sends it and matches exit events against it. Confirm on a real
// pair that consumer firmware accepts field 7 and echoes it in exit events. If exits arrive
// without it, match on the widget ID alone.

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
    private const val SYSTEM_EVENT = 3
    private const val ABNORMAL_EXIT = 6L
    private const val EXIT = 7L
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

    /** The exit of the page identified by [page] and [session], or null when [message] is not one. */
    fun pageExit(
        message: G2Message,
        page: G2TextPage,
        session: Long,
    ): G2CloseCause.PageExited? {
        if (message.sid != G2Protocol.SID_EVEN_HUB || message.result != 0) return null
        val root = G2ProtoReader.fields(message.payload) ?: return null
        if ((root.scalar(1) ?: 0L) != EVENT_COMMAND ||
            root.keys.filter { it in BRANCHES } != listOf(EVENT_BRANCH)
        ) {
            return null
        }
        val event = root.bytes(EVENT_BRANCH)?.let(G2ProtoReader::fields) ?: return null
        if (event.keys != setOf(SYSTEM_EVENT)) return null
        val system = event.bytes(SYSTEM_EVENT)?.let(G2ProtoReader::fields) ?: return null
        val type = system.scalar(1) ?: 0L
        if (type != ABNORMAL_EXIT && type != EXIT) return null
        if (system.scalar(5) != page.widgetId || system.scalar(6) != session) return null
        return G2CloseCause.PageExited(abnormal = type == ABNORMAL_EXIT)
    }
}
