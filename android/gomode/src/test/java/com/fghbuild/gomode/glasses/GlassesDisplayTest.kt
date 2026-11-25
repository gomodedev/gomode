// Tests fitImage: an image larger than the display shrinks to fit with its aspect ratio; one that fits stays as it is.
package com.fghbuild.gomode.glasses

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassesDisplayTest {
    @Test
    fun aWideImageShrinksToTheDisplayWidthAndKeepsItsAspectRatio() {
        val image = Bitmap.createBitmap(1024, 256, Bitmap.Config.ARGB_8888)
        val fitted = fitImage(image, 256, 256)
        assertEquals(256 to 64, fitted.width to fitted.height)
    }

    @Test
    fun aTallImageShrinksToTheDisplayHeight() {
        val image = Bitmap.createBitmap(300, 600, Bitmap.Config.ARGB_8888)
        val fitted = fitImage(image, 576, 288)
        assertEquals(144 to 288, fitted.width to fitted.height)
    }

    @Test
    fun anImageThatFitsIsDrawnAsItIs() {
        val image = Bitmap.createBitmap(256, 100, Bitmap.Config.ARGB_8888)
        assertSame(image, fitImage(image, 256, 256))
    }
}
