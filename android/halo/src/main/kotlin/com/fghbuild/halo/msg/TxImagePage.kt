// TxImagePage: an image centred on the display, sent as strips of sprites that share one median-cut palette.
package com.fghbuild.halo.msg

import android.graphics.Bitmap
import android.graphics.Color

// Rows per strip: a 256-pixel strip at 4 bpp is 4 KiB, so no message makes the Lua VM hold the whole image twice.
private const val STRIP_ROWS = 32

/**
 * [image] centred on a [displayWidth] by [displayHeight] display, in [HaloDisplay.COLORS] colours or
 * fewer that a median cut picks from its pixels. Transparent pixels show black, which an additive
 * display leaves dark. [pack] is the page header of [packPageHeader]; send it, then each of
 * [sprites] in order, under one message code, as for [TextPageData]. Each sprite carries the palette,
 * which text_page.lua passes to frame.display.bitmap, so the display's own palette stays as it is.
 */
class TxImagePage(
    image: Bitmap,
    private val displayWidth: Int,
    private val displayHeight: Int,
) : TxMessage {
    private val left = (displayWidth - image.width) / 2
    private val top = (displayHeight - image.height) / 2

    val sprites: List<TxSprite>

    init {
        require(image.width in 1..displayWidth && image.height in 1..displayHeight) {
            "A ${image.width}x${image.height} image does not fit a ${displayWidth}x$displayHeight display"
        }
        val argb = IntArray(image.width * image.height)
        image.getPixels(argb, 0, image.width, 0, 0, image.width, image.height)
        val rgb = IntArray(argb.size) { overBlack(argb[it]) }
        val palette = medianCut(rgb, HaloDisplay.COLORS)
        val indices = IntArray(rgb.size) { nearest(palette, rgb[it]) }
        val bpp =
            when {
                palette.size <= 2 -> 1
                palette.size <= 4 -> 2
                else -> 4
            }
        val paletteBytes = ByteArray(palette.size * 3)
        palette.forEachIndexed { i, color ->
            paletteBytes[i * 3] = Color.red(color).toByte()
            paletteBytes[i * 3 + 1] = Color.green(color).toByte()
            paletteBytes[i * 3 + 2] = Color.blue(color).toByte()
        }
        sprites =
            (0 until image.height step STRIP_ROWS).map { y ->
                val rows = minOf(STRIP_ROWS, image.height - y)
                TxSprite(
                    width = image.width,
                    height = rows,
                    bpp = bpp,
                    numColors = palette.size,
                    palette = paletteBytes,
                    pixels = TxSprite.packBits(indices.copyOfRange(y * image.width, (y + rows) * image.width), bpp),
                )
            }
    }

    override fun pack(): ByteArray =
        packPageHeader(displayWidth, displayHeight, sprites.indices.map { left to top + it * STRIP_ROWS })
}

// The RGB colour of [argb] composited over black.
private fun overBlack(argb: Int): Int {
    val alpha = Color.alpha(argb)
    return Color.rgb(Color.red(argb) * alpha / 255, Color.green(argb) * alpha / 255, Color.blue(argb) * alpha / 255)
}

/**
 * At most [colors] colours for [pixels]: of the distinct colours, the box with the widest channel
 * range splits at its median until there are [colors] boxes or none can split, and each box becomes
 * its mean. An image of at most [colors] distinct colours keeps them exactly.
 */
internal fun medianCut(
    pixels: IntArray,
    colors: Int,
): IntArray {
    val boxes = mutableListOf(pixels.distinct().toIntArray())
    while (boxes.size < colors) {
        val widest = boxes.maxBy { box -> CHANNELS.maxOf { range(box, it) } }
        val channel = CHANNELS.maxBy { range(widest, it) }
        if (range(widest, channel) == 0) break
        val sorted = widest.sortedBy { channel(it) }
        val half = sorted.size / 2
        boxes.remove(widest)
        boxes += sorted.subList(0, half).toIntArray()
        boxes += sorted.subList(half, sorted.size).toIntArray()
    }
    return IntArray(boxes.size) { mean(boxes[it]) }
}

private val CHANNELS: List<(Int) -> Int> = listOf(Color::red, Color::green, Color::blue)

private fun range(
    box: IntArray,
    channel: (Int) -> Int,
): Int = box.maxOf(channel) - box.minOf(channel)

private fun mean(box: IntArray): Int {
    val count = box.size.toLong()
    return Color.rgb(
        (box.sumOf { Color.red(it).toLong() } / count).toInt(),
        (box.sumOf { Color.green(it).toLong() } / count).toInt(),
        (box.sumOf { Color.blue(it).toLong() } / count).toInt(),
    )
}

private fun nearest(
    palette: IntArray,
    color: Int,
): Int =
    palette.indices.minBy { i ->
        val dr = Color.red(palette[i]) - Color.red(color)
        val dg = Color.green(palette[i]) - Color.green(color)
        val db = Color.blue(palette[i]) - Color.blue(color)
        dr * dr + dg * dg + db * db
    }
