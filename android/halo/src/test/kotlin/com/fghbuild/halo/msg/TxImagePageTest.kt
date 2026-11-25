// Unit tests for TxImagePage: centred strips, one shared median-cut palette, and what each pixel's index shows.
package com.fghbuild.halo.msg

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TxImagePageTest {
    @Test
    fun anImageOfFewColoursKeepsThemExactlyInCentredStrips() {
        val colors = listOf(Color.BLACK, Color.WHITE, Color.rgb(215, 50, 35))
        val image = Bitmap.createBitmap(100, 70, Bitmap.Config.ARGB_8888)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image.setPixel(x, y, colors[(x / 10 + y) % colors.size])
        }
        // Transparent pixels show black.
        image.setPixel(0, 0, Color.TRANSPARENT)
        val page = TxImagePage(image, HaloDisplay.SIZE_PX, HaloDisplay.SIZE_PX)

        val header = page.pack()
        assertEquals(page.sprites.size, header[5].toInt())
        assertEquals(listOf(32, 32, 6), page.sprites.map { it.height })
        val positions = page.sprites.indices.map { header.u16(6 + it * 4) to header.u16(8 + it * 4) }
        assertEquals(listOf(78 to 93, 78 to 125, 78 to 157), positions)

        for (sprite in page.sprites) {
            assertEquals(2, sprite.bpp)
            assertEquals(colors.toSet(), sprite.paletteColors().toSet())
        }
        for ((x, y) in listOf(0 to 0, 10 to 0, 20 to 0, 70 to 40, 99 to 69)) {
            assertEquals("($x, $y)", colors[(x / 10 + y) % colors.size], page.colorAt(x, y))
        }
    }

    @Test
    fun aPhotographGetsSixteenColoursSpreadOverItsRange() {
        val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) {
            for (x in 0 until 256) image.setPixel(x, y, Color.rgb(x, y, 128))
        }
        val page = TxImagePage(image, HaloDisplay.SIZE_PX, HaloDisplay.SIZE_PX)
        val palette = page.sprites.first().paletteColors()
        assertEquals(HaloDisplay.COLORS, palette.size)
        assertTrue(page.sprites.all { it.bpp == 4 && it.paletteColors() == palette })
        // Each pixel shows a colour near its own: 16 boxes over a 256 by 256 gradient leave at most 64 a channel.
        for ((x, y) in listOf(0 to 0, 255 to 255, 128 to 30, 10 to 240)) {
            val shown = page.colorAt(x, y)
            assertTrue("($x, $y) shows ${Integer.toHexString(shown)}", Color.red(shown) in x - 64..x + 64)
            assertTrue("($x, $y) shows ${Integer.toHexString(shown)}", Color.green(shown) in y - 64..y + 64)
        }
    }

    // The palette colour that the pixel at (x, y) of the image indexes.
    private fun TxImagePage.colorAt(
        x: Int,
        y: Int,
    ): Int {
        val sprite = sprites[y / 32]
        val index = (y % 32) * sprite.width + x
        val perByte = 8 / sprite.bpp
        val byte = sprite.pixels[index / perByte].toInt() and 0xFF
        val shift = (perByte - 1 - index % perByte) * sprite.bpp
        return sprite.paletteColors()[(byte shr shift) and ((1 shl sprite.bpp) - 1)]
    }
}

private fun ByteArray.u16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun TxSprite.paletteColors(): List<Int> =
    List(numColors) { i ->
        Color.rgb(
            palette[i * 3].toInt() and 0xFF,
            palette[i * 3 + 1].toInt() and 0xFF,
            palette[i * 3 + 2].toInt() and 0xFF,
        )
    }
