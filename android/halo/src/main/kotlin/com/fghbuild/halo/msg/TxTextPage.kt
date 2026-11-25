// TxTextPage: layout and rasterize text pages for rectangular Frame and circular Halo displays.
package com.fghbuild.halo.msg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

abstract class TextLayout(
    val width: Int,
    val height: Int,
    val fontSizePx: Int,
    val typeface: Typeface = Typeface.DEFAULT,
    val textAlign: Paint.Align = Paint.Align.LEFT,
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(fontSizePx > 0) { "fontSizePx must be positive" }
    }

    /**
     * Where a page of [lineCount] lines, each [lineHeight] high, starts; null when that many lines
     * cannot sit in the layout's area.
     */
    abstract fun pageTop(
        lineCount: Int,
        lineHeight: Float,
    ): Float?

    /** The width and position of a line at [lineY], or null when no line fits there. */
    abstract fun lineLayout(
        lineY: Float,
        lineHeight: Float,
    ): LineLayout?

    /** Whether one page holds all of [text], laid out as [TxTextPage] lays out its first page. */
    fun fitsOnePage(text: String): Boolean = !TxTextPage(this, text).also { it.measureNextPage() }.hasMoreText
}

data class LineLayout(
    val width: Int,
    val xOffset: Int,
)

class RectangularTextLayout(
    width: Int,
    height: Int,
    fontSizePx: Int,
    typeface: Typeface = Typeface.DEFAULT,
    textAlign: Paint.Align = Paint.Align.LEFT,
) : TextLayout(width, height, fontSizePx, typeface, textAlign) {
    // Pages start at the top.
    override fun pageTop(
        lineCount: Int,
        lineHeight: Float,
    ): Float = 0f

    override fun lineLayout(
        lineY: Float,
        lineHeight: Float,
    ): LineLayout? {
        if (lineY < 0f || lineY + lineHeight > height) return null
        return LineLayout(width = width, xOffset = 0)
    }
}

/**
 * Lines inside a circle inscribed in the display, [circleMarginPx] from its edge. A page sits in the
 * middle of the circle, where lines are widest: a short text is centred and wraps less.
 */
class CircularTextLayout(
    width: Int,
    height: Int,
    fontSizePx: Int,
    val circleMarginPx: Float = 15f,
    typeface: Typeface = Typeface.DEFAULT,
    textAlign: Paint.Align = Paint.Align.CENTER,
) : TextLayout(width, height, fontSizePx, typeface, textAlign) {
    private val radius = min(width, height) / 2f - circleMarginPx
    private val centerX = width / 2f
    private val centerY = height / 2f

    init {
        require(radius > 0f) { "circleMarginPx leaves no drawable area" }
    }

    override fun pageTop(
        lineCount: Int,
        lineHeight: Float,
    ): Float? = (centerY - lineCount * lineHeight / 2f).takeIf { it >= centerY - radius }

    override fun lineLayout(
        lineY: Float,
        lineHeight: Float,
    ): LineLayout? {
        val distanceFromCenter = lineY + lineHeight / 2f - centerY
        if (kotlin.math.abs(distanceFromCenter) > radius) return null
        val halfWidth = sqrt(radius * radius - distanceFromCenter * distanceFromCenter)
        val lineWidth = floor(halfWidth * 2f).toInt()
        if (lineWidth < fontSizePx) return null
        return LineLayout(width = lineWidth, xOffset = floor(centerX - halfWidth).toInt())
    }
}

/**
 * Splits [text] into pages that fit [layout]. Lines wrap at whitespace, a line feed starts a new
 * line, and blank lines collapse. A page holds the fewest lines that take the rest of the text, or
 * as many as fit, starting at [TextLayout.pageTop] for that many lines.
 */
class TxTextPage(
    private val layout: TextLayout,
    text: String,
) {
    private var remainingText = text.trim()

    val hasMoreText: Boolean get() = remainingText.isNotEmpty()

    fun remainingText(): String = remainingText

    fun measureNextPage(): TextPageData? {
        if (remainingText.isEmpty()) return null

        val paint = textPaint(layout)
        val lineHeight = max(layout.fontSizePx.toFloat(), paint.fontMetrics.let { it.descent - it.ascent })
        // Where lines start depends on how many there are, so try each count until one takes the text.
        var page: PageLines? = null
        var lineCount = 1
        while (page?.rest?.isEmpty() != true) {
            val top = layout.pageTop(lineCount, lineHeight) ?: break
            page = layOutLines(paint, top, lineHeight, lineCount) ?: break
            lineCount++
        }
        if (page == null || page.lines.isEmpty()) return null
        remainingText = page.rest
        return TextPageData(layout, page.lines)
    }

    fun rasterizeNextPage(): TextPageData? = measureNextPage()?.also { it.rasterize() }

    private class PageLines(
        val lines: List<TextLineData>,
        val rest: String,
    )

    // Lays the remaining text out in at most [maxLines] lines from [top]; null when one of those lines
    // has no room in the layout or for the next character.
    private fun layOutLines(
        paint: Paint,
        top: Float,
        lineHeight: Float,
        maxLines: Int,
    ): PageLines? {
        val lines = mutableListOf<TextLineData>()
        var rest = remainingText
        var y = top
        repeat(maxLines) {
            if (rest.isEmpty()) return PageLines(lines, rest)
            val lineLayout = layout.lineLayout(y, lineHeight) ?: return null
            val breakIndex = breakText(rest, paint, lineLayout.width.toFloat())
            if (breakIndex <= 0) return null
            val lineText = rest.substring(0, breakIndex).trim()
            if (lineText.isNotEmpty()) {
                lines +=
                    TextLineData(
                        text = lineText,
                        width = lineLayout.width,
                        xOffset = lineLayout.xOffset,
                        yOffset = y.toInt(),
                        lineHeight = lineHeight.toInt(),
                    )
            }
            rest = rest.substring(breakIndex).trimStart()
            y += lineHeight
        }
        return PageLines(lines, rest)
    }

    // Returns how many characters of [text] the next line consumes, including a line feed that ends it.
    private fun breakText(
        text: String,
        paint: Paint,
        maxWidth: Float,
    ): Int {
        val lineEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
        val measured = paint.breakText(text, 0, lineEnd, true, maxWidth, null)
        if (measured >= lineEnd) return min(lineEnd + 1, text.length)
        val lastWhitespace = text.substring(0, measured).indexOfLast { it.isWhitespace() }
        return if (lastWhitespace > 0) lastWhitespace + 1 else measured
    }
}

/**
 * One page of lines. [pack] is the page header of [packPageHeader], with one sprite per line. Send
 * it, then each of [rasterizedSprites] in order, under one message code; the device library
 * text_page.lua draws the page once its last sprite arrives.
 */
class TextPageData internal constructor(
    private val layout: TextLayout,
    private val lines: List<TextLineData>,
) : TxMessage {
    private val sprites = mutableListOf<TxSprite>()

    val lineTexts: List<String> get() = lines.map { it.text }
    val rasterizedSprites: List<TxSprite> get() = sprites
    val isRasterized: Boolean get() = sprites.isNotEmpty()

    fun rasterize() {
        if (isRasterized) return
        val paint = textPaint(layout)
        for (line in lines) {
            val textWidth = max(1, paint.measureText(line.text).toInt() + 1)
            val bitmap = Bitmap.createBitmap(textWidth, line.lineHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            val x =
                when (layout.textAlign) {
                    Paint.Align.CENTER -> textWidth / 2f
                    Paint.Align.RIGHT -> textWidth.toFloat()
                    else -> 0f
                }
            canvas.drawText(line.text, x, -paint.fontMetrics.ascent, paint)
            sprites += bitmap.toMonochromeSprite()
        }
    }

    // Where the line's sprite starts: aligned within the width the layout gave the line.
    private fun spriteX(
        line: TextLineData,
        sprite: TxSprite,
    ): Int {
        val slack = max(0, line.width - sprite.width)
        return line.xOffset +
            when (layout.textAlign) {
                Paint.Align.CENTER -> slack / 2
                Paint.Align.RIGHT -> slack
                else -> 0
            }
    }

    override fun pack(): ByteArray {
        require(isRasterized) { "Page must be rasterized before packing" }
        return packPageHeader(
            layout.width,
            layout.height,
            lines.mapIndexed { index, line -> spriteX(line, sprites[index]) to line.yOffset },
        )
    }
}

/**
 * The header of a page of sprites, which text_page.lua draws: 0xFF, the page's [width] and
 * [height], the sprite count, then each sprite's x and y from [positions], all 16-bit big-endian
 * except the 1-byte marker and count.
 */
internal fun packPageHeader(
    width: Int,
    height: Int,
    positions: List<Pair<Int, Int>>,
): ByteArray {
    require(positions.size <= MAX_PAGE_SPRITES) { "A page holds at most $MAX_PAGE_SPRITES sprites" }
    val header = ByteArray(6 + positions.size * 4)
    header[0] = 0xFF.toByte()
    header[1] = (width shr 8).toByte()
    header[2] = (width and 0xFF).toByte()
    header[3] = (height shr 8).toByte()
    header[4] = (height and 0xFF).toByte()
    header[5] = positions.size.toByte()
    positions.forEachIndexed { index, (x, y) ->
        val offset = 6 + index * 4
        header[offset] = (x shr 8).toByte()
        header[offset + 1] = (x and 0xFF).toByte()
        header[offset + 2] = (y shr 8).toByte()
        header[offset + 3] = (y and 0xFF).toByte()
    }
    return header
}

// The header counts sprites in one byte.
private const val MAX_PAGE_SPRITES = 255

internal data class TextLineData(
    val text: String,
    // The width the layout gives the line, which its text fits in.
    val width: Int,
    val xOffset: Int,
    val yOffset: Int,
    val lineHeight: Int,
)

private fun textPaint(layout: TextLayout): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = layout.fontSizePx.toFloat()
        typeface = layout.typeface
        textAlign = layout.textAlign
    }

private fun Bitmap.toMonochromeSprite(): TxSprite {
    val indices = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = getPixel(x, y)
            val luminance = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            indices[y * width + x] = if (luminance >= 128) 1 else 0
        }
    }
    return TxSprite(
        width = width,
        height = height,
        bpp = 1,
        numColors = 2,
        palette = byteArrayOf(0, 0, 0, -1, -1, -1),
        pixels = TxSprite.packBits(indices, 1),
    )
}
