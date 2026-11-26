// G2Panel: what a session shows, and how Faceclaw custom firmware draws text and 4-bit images on its 640×480 panel.
//
// Ported and modified from DrawProtocol.kt (stockText, bbox, clear, message) and the message IDs of
// CfwMessageType.kt, DrawCallType.kt, and DrawFlags.kt in jimrandomh/faceclaw (GPL-3.0); the
// receiver is g2flash's patches/zlib_glue.c, display_list.c, rle.h, and texture_draw.c (GPL-3.0);
// see NOTICE in this module.
//
// A frame is drawn into the firmware's 640×480 screen buffer of 4-bit grey levels, which the
// glasses show in green, and shown only by the PRESENT message that follows its draw calls. Every
// message needs the framebuffer lease (G2Settings). Messages, each at most 65535 bytes:
//
//     26 <count u16> { <length u16> <call> }   draw calls, validated whole before any is drawn
//     27 FF FF                                 clear the root display list, so PRESENT shows the screen buffer
//     28                                       present the screen buffer on both lenses
//
// A call is <op> <flags 0> <payload>; integers are little-endian:
//
//     9 <level>                                          clear the buffer
//     3 <x s16> <y s16> <options> <length> <UTF-8>       one line in the stock 20 px font, top-left at x, y
//     1 01 <x u16> <y u16> <width u16> <height u16> <RLE> a rectangle of pixels
//
// The stock font is the Even Hub text container's, so G2TextLayout measures it: lines are 27 px
// high, and a text shows 17 lines. Options 15 draws at full brightness over the background; bytes
// 1 to 31 move the pen instead of drawing, so text drops them, as they have no width in the layout.
// The RLE covers the rectangle's pixels in reading order: [count 1-15 | level], [0 | level]
// [count u8], or [0 | level] 00 [count u16].
//
// A drawing message is at most 65535 bytes, and an image of incompressible noise takes one RLE byte
// per pixel, so an image goes out in bands of BAND_ROWS rows, centred on a cleared buffer.

package com.fghbuild.eveng2

/** The panel Faceclaw custom firmware draws on, as [G2Firmware.Faceclaw] reports it. */
object G2Panel {
    const val WIDTH_PX = 640
    const val HEIGHT_PX = 480

    /** Grey levels per pixel; the glasses show them in green. */
    const val GREY_LEVELS = 16

    /**
     * Whether the panel shows all of [text] as [G2Glasses.show] draws it there: its lines, wrapped
     * at the panel's width as the stock firmware wraps a text container, fit its height. Text over
     * [G2Display.MAX_TEXT_BYTES] never fits. The first call reads the firmware font table unless
     * [G2TextPage.loadMetrics] did.
     */
    fun fits(text: String): Boolean =
        text.toByteArray().size <= G2Display.MAX_TEXT_BYTES && lines(text).size <= maxLines

    /**
     * The lines [G2Glasses.show] wraps [text] into on the panel, without their line feeds. Joined with
     * line feeds, each shows on one line. The first call reads the firmware font table unless
     * [G2TextPage.loadMetrics] did.
     */
    fun lines(text: String): List<String> = G2TextLayout.lines(text, WIDTH_PX)

    // TODO(hardware): g2flash's README says the firmware uses "the full 640x480 resolution of the
    // screens"; nobody has said how much of it a wearer sees. On a pair on Faceclaw/45, draw a
    // one-pixel border at the panel's edges and a text of 17 lines. If an edge or the last line is
    // cut off, inset the drawing here and in G2Draw, and declare the visible size instead.
    internal val maxLines: Int get() = HEIGHT_PX / G2TextLayout.lineHeightPx
}

/**
 * An image of [width] by [height] pixels, each one of [G2Panel.GREY_LEVELS] grey levels in [levels],
 * row by row. It fits the panel. Equal only to itself.
 */
class G2Image(
    val width: Int,
    val height: Int,
    private val levels: ByteArray,
) {
    init {
        require(width in 1..G2Panel.WIDTH_PX && height in 1..G2Panel.HEIGHT_PX) { "image size $width×$height" }
        require(levels.size == width * height) { "${levels.size} levels for $width×$height pixels" }
        require(levels.all { it in 0 until G2Panel.GREY_LEVELS }) { "a level is outside 0..${G2Panel.GREY_LEVELS - 1}" }
    }

    /** The grey level of the pixel at [x], [y]. */
    fun level(
        x: Int,
        y: Int,
    ): Int = levels[y * width + x].toInt()
}

/** What a session shows: a [Text] laid out by the glasses or by this SDK, or an [Image]. */
sealed interface G2Content {
    data class Text(
        val text: String,
    ) : G2Content

    /** Shown only on [G2Firmware.Faceclaw]; stock firmware shows nothing instead. Equal when the image is. */
    data class Image(
        val image: G2Image,
    ) : G2Content
}

/** The private-stream messages that draw a frame on [G2Panel]; see the header of this file. */
internal object G2Draw {
    private const val DRAW_CALLS = 26
    private const val SET_ROOT = 27
    private const val PRESENT = 28
    private const val OP_BOUNDING_BOX = 1
    private const val OP_STOCK_TEXT = 3
    private const val OP_CLEAR = 9
    private const val BOX_U16 = 1
    private const val FULL_BRIGHTNESS = 15
    private const val MAX_STRING_BYTES = 255
    private const val MAX_RUN = 65_535
    private const val SHORT_RUN = 15
    private const val BYTE_RUN = 255

    /** Image rows per drawing message: at most one RLE byte per pixel keeps it below 65535 bytes. */
    const val BAND_ROWS = 96

    /** Makes PRESENT show the screen buffer whatever another app left as the root display list. */
    val clearRoot: ByteArray = byteArrayOf(SET_ROOT.toByte(), 0xFF.toByte(), 0xFF.toByte())

    /** The messages that show [content], in order, ending with PRESENT. */
    fun frame(content: G2Content): List<ByteArray> =
        when (content) {
            is G2Content.Text -> listOf(drawCalls(listOf(clear()) + text(content.text)), present())
            is G2Content.Image -> image(content.image) + present()
        }

    /** The lines of [text] that fit the panel, each as stock-font draw calls of at most 255 bytes. */
    internal fun text(text: String): List<ByteArray> =
        G2TextLayout.lines(text, G2Panel.WIDTH_PX).take(G2Panel.maxLines).flatMapIndexed { row, line ->
            val y = row * G2TextLayout.lineHeightPx
            var x = 0
            pieces(line.filterNot { it.code in 1..31 }).map { piece ->
                stockText(x, y, piece).also { x += G2TextLayout.widthPx(piece) }
            }
        }

    private fun image(image: G2Image): List<ByteArray> {
        val left = (G2Panel.WIDTH_PX - image.width) / 2
        val top = (G2Panel.HEIGHT_PX - image.height) / 2
        return (0 until image.height step BAND_ROWS).map { first ->
            val rows = minOf(BAND_ROWS, image.height - first)
            val band = boundingBox(left, top + first, image.width, rows) { x, y -> image.level(x, first + y) }
            drawCalls(if (first == 0) listOf(clear(), band) else listOf(band))
        }
    }

    fun drawCalls(calls: List<ByteArray>): ByteArray {
        val out = Writer()
        out.u8(DRAW_CALLS)
        out.u16(calls.size)
        for (call in calls) {
            out.u16(call.size)
            out.bytes(call)
        }
        val message = out.toByteArray()
        require(message.size <= G2CfwStream.MAX_MESSAGE) { "draw message of ${message.size} bytes" }
        return message
    }

    fun present(): ByteArray = byteArrayOf(PRESENT.toByte())

    fun clear(): ByteArray = byteArrayOf(OP_CLEAR.toByte(), 0, 0)

    fun stockText(
        x: Int,
        y: Int,
        text: String,
    ): ByteArray {
        val bytes = text.toByteArray()
        require(bytes.size <= MAX_STRING_BYTES) { "stock text of ${bytes.size} bytes" }
        val out = Writer()
        out.u8(OP_STOCK_TEXT)
        out.u8(0)
        out.u16(x)
        out.u16(y)
        out.u8(FULL_BRIGHTNESS)
        out.u8(bytes.size)
        out.bytes(bytes)
        return out.toByteArray()
    }

    /** A [width] by [height] rectangle at [x], [y] whose pixel at (column, row) is [level] of it. */
    fun boundingBox(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        level: (Int, Int) -> Int,
    ): ByteArray {
        val out = Writer()
        out.u8(OP_BOUNDING_BOX)
        out.u8(0)
        out.u8(BOX_U16)
        out.u16(x)
        out.u16(y)
        out.u16(width)
        out.u16(height)
        var color = -1
        var count = 0

        fun flush() {
            when {
                count == 0 -> {}

                count <= SHORT_RUN -> {
                    out.u8((count shl 4) or color)
                }

                count <= BYTE_RUN -> {
                    out.u8(color)
                    out.u8(count)
                }

                else -> {
                    out.u8(color)
                    out.u8(0)
                    out.u16(count)
                }
            }
        }
        for (row in 0 until height) {
            for (column in 0 until width) {
                val value = level(column, row)
                if (value != color || count == MAX_RUN) {
                    flush()
                    color = value
                    count = 0
                }
                count++
            }
        }
        flush()
        return out.toByteArray()
    }

    // [line] in pieces of at most MAX_STRING_BYTES UTF-8 bytes, cut between code points.
    private fun pieces(line: String): List<String> {
        val pieces = mutableListOf<String>()
        val piece = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray().size
            if (bytes + size > MAX_STRING_BYTES) {
                pieces += piece.toString()
                piece.clear()
                bytes = 0
            }
            piece.appendCodePoint(cp)
            bytes += size
            i += Character.charCount(cp)
        }
        if (piece.isNotEmpty()) pieces += piece.toString()
        return pieces
    }

    private class Writer {
        private val out = java.io.ByteArrayOutputStream()

        fun u8(value: Int) = out.write(value)

        fun u16(value: Int) {
            out.write(value)
            out.write(value ushr 8)
        }

        fun bytes(value: ByteArray) = out.write(value)

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
