// Tests head flicks in synthetic and recorded samples: each direction, the 359 to 0 wrap, drift, holds, debounce.
package com.fghbuild.gomode.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadFlickDetectorTest {
    // Feeds [samples] of (ms, pitch, heading) and returns the flicks they produce.
    private fun flicks(vararg samples: Triple<Long, Float?, Int?>): List<HeadFlick> {
        val detector = HeadFlickDetector()
        return samples.mapNotNull { (ms, pitch, heading) -> detector.accept(ms, pitch, heading) }
    }

    // A pitch-only sample every 180 ms, about the accelerometer's rate on a pair, from [startMs].
    private fun pitches(
        startMs: Long,
        vararg degrees: Float,
    ) = degrees.mapIndexed { i, d -> Triple(startMs + i * 180L, d, null as Int?) }.toTypedArray()

    // A heading-only sample every 100 ms, the compass's rate on Faceclaw firmware, from [startMs].
    private fun headings(
        startMs: Long,
        vararg degrees: Int,
    ) = degrees.mapIndexed { i, d -> Triple(startMs + i * 100L, null as Float?, d) }.toTypedArray()

    @Test
    fun aQuickTiltUpAndBackIsOneFlickUp() {
        // A worn level head on a pair on 2.3.0.24 with Faceclaw/45 read this, about -11° of pitch.
        val level = pitchDegrees(GlassesAcceleration(-0.19f, -0.05f, 0.98f))
        val seen = flicks(*pitches(0, level, level, level + 16, level + 29, level + 25, level + 2, level))
        assertEquals(1, seen.size)
        assertEquals(GlassesDirection.UP, seen.single().direction)
        assertTrue(seen.single().degrees >= FLICK_DEGREES)
        assertTrue(seen.single().durationMs <= FLICK_MAX_MS)
    }

    @Test
    fun aQuickTiltDownAndBackIsOneFlickDown() {
        val seen = flicks(*pitches(0, 0f, 0f, -25f, -30f, -2f, 0f))
        assertEquals(listOf(GlassesDirection.DOWN), seen.map { it.direction })
    }

    @Test
    fun turnsAcrossNorthAreFlicksLeftAndRight() {
        val right = flicks(*headings(0, 350, 350, 359, 5, 15, 10, 352, 350))
        assertEquals(listOf(GlassesDirection.RIGHT), right.map { it.direction })
        val left = flicks(*headings(0, 5, 5, 355, 340, 345, 3, 5))
        assertEquals(listOf(GlassesDirection.LEFT), left.map { it.direction })
    }

    @Test
    fun theOvershootOfTheReturnIsNoSecondFlick() {
        // Up, then past the baseline down, then back, all within the debounce.
        val seen = flicks(*pitches(0, 0f, 0f, 25f, 2f, -24f, 0f, 0f))
        assertEquals(listOf(GlassesDirection.UP), seen.map { it.direction })
    }

    @Test
    fun theHeadingsEchoOfATiltIsNoSecondFlick() {
        val detector = HeadFlickDetector()
        val samples =
            listOf(
                Triple(0L, 0f, 90),
                Triple(100L, 0f, 90),
                Triple(200L, 25f, 115),
                Triple(300L, 1f, 112),
                Triple(400L, 0f, 91),
                Triple(500L, 0f, 90),
            )
        val seen = samples.mapNotNull { (ms, pitch, heading) -> detector.accept(ms, pitch, heading) }
        assertEquals(listOf(GlassesDirection.UP), seen.map { it.direction })
    }

    @Test
    fun lookingAroundSlowlyIsNoFlick() {
        // 15° a second for 6 s, across no threshold, then back the same way: the baseline follows.
        val out = (0..60).map { it * 1.5f }
        val back = out.reversed()
        val heading = (out + back).mapIndexed { i, d -> Triple(i * 100L, null as Float?, d.toInt() % 360) }
        assertEquals(emptyList<HeadFlick>(), flicks(*heading.toTypedArray()))
    }

    @Test
    fun aHeldTiltIsNoFlickAndBecomesTheBaseline() {
        // Up for 1.6 s, then back: the return departs from the new baseline and stays there.
        val seen = flicks(*pitches(0, 0f, 0f, 25f, 25f, 25f, 25f, 25f, 25f, 25f, 25f, 25f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        assertEquals(emptyList<HeadFlick>(), seen)
    }

    @Test
    fun aLaterFlickAfterTheDebounceCounts() {
        val first = pitches(0, 0f, 0f, 25f, 0f)
        val second = pitches(first.last().first + 600, 0f, 0f, 25f, 0f)
        val seen = flicks(*first, *second)
        assertEquals(listOf(GlassesDirection.UP, GlassesDirection.UP), seen.map { it.direction })
    }

    @Test
    fun aDepartureBelowTheThresholdIsNoFlick() {
        assertEquals(emptyList<HeadFlick>(), flicks(*pitches(0, 0f, 0f, 15f, 0f, -15f, 0f)))
    }

    @Test
    fun pitchGrowsAsTheForwardAxisRises() {
        assertEquals(0f, pitchDegrees(GlassesAcceleration(0f, 0f, 1f)), 0.01f)
        assertEquals(30f, pitchDegrees(GlassesAcceleration(0.5f, 0f, 0.866f)), 0.1f)
        assertEquals(-30f, pitchDegrees(GlassesAcceleration(-0.5f, 0f, 0.866f)), 0.1f)
    }
}
