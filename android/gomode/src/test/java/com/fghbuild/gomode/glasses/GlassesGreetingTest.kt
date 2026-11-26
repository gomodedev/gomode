// Tests the greeting's frames: a ring over the name for image displays, typed text otherwise, the still variant.
package com.fghbuild.gomode.glasses

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassesGreetingTest {
    @Test
    fun imageDisplaysGetARingThatClosesAroundACheckMarkOverTheName() {
        val steps = greetingSteps(images = true, width = 640, height = 480, animate = true)
        val images = steps.map { (it.frame as GlassesFrame.Image).image }
        // The ring is half the shorter side wide; the name sits under it from the first frame.
        assertTrue(images.all { it.width == 240 && it.height > 240 })
        val labelLit = { i: Int -> (0 until 240).any { x -> Color.green(images[i].getPixel(x, 240 + 40)) > 0 } }
        assertTrue(labelLit(0))
        // The ring starts at the top and closes clockwise: its left edge lights only at the end of the sweep.
        val leftEdge = { i: Int -> images[i].getPixel(10, 120) }
        assertEquals(Color.BLACK, leftEdge(0))
        assertTrue(Color.green(leftEdge(images.lastIndex)) > 0)
        // The check mark's corner appears only in the last frames.
        val corner = { i: Int -> images[i].getPixel(105, 158) }
        assertEquals(Color.BLACK, corner(images.size / 2))
        assertTrue(Color.green(corner(images.lastIndex)) > 0)
        assertTrue(steps.sumOf { it.durationMs } < 10_000L)
    }

    @Test
    fun textDisplaysGetTheMessageTyped() {
        val steps = greetingSteps(images = false, width = 576, height = 288, animate = true)
        val texts = steps.map { (it.frame as GlassesFrame.Text).text }
        assertTrue(texts.zipWithNext().all { (a, b) -> b.startsWith(a) && b.length > a.length })
        assertEquals(GREETING_TEXT, texts.last())
        assertTrue(steps.sumOf { it.durationMs } < 10_000L)
    }

    @Test
    fun withoutAnimationsOnlyTheFinalFrameShows() {
        val steps = greetingSteps(images = false, width = 576, height = 288, animate = false)
        assertEquals(listOf(GreetingStep(GlassesFrame.Text(GREETING_TEXT), GREETING_HOLD_MS)), steps)
        val still = greetingSteps(images = true, width = 256, height = 256, animate = false)
        assertEquals(1, still.size)
        assertTrue((still.single().frame as GlassesFrame.Image).image.height <= 256)
    }
}
