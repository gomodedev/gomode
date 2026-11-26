// Tests nods and shakes in readings recorded on a pair and in synthetic ones: glances, drift, debounce.
package com.fghbuild.gomode.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadGestureDetectorTest {
    // Feeds [readings] of (ms, pitch, heading) and returns the answers.
    private fun answers(readings: List<Triple<Long, Float?, Int?>>): List<HeadAnswer> {
        val detector = HeadGestureDetector()
        return readings.mapNotNull { (ms, pitch, heading) -> detector.accept(ms, pitch, heading) }
    }

    // [pitch] readings 100 ms apart, without a compass.
    private fun pitches(vararg pitch: Float) =
        pitch.mapIndexed { i, p -> Triple<Long, Float?, Int?>(i * 100L, p, null) }

    // [heading] readings 100 ms apart, the pitch level.
    private fun headings(vararg heading: Int) =
        heading.mapIndexed { i, h -> Triple<Long, Float?, Int?>(i * 100L, 0f, h) }

    // A nod recorded on a pair on 2.3.0.24 with Faceclaw/45: it dips below rest and comes back to it.
    private val recordedNod =
        listOf<Triple<Long, Float?, Int?>>(
            Triple(21L, -1f, 137),
            Triple(42L, 2f, 137),
            Triple(139L, 1f, 137),
            Triple(217L, 2f, 137),
            Triple(319L, 2f, 137),
            Triple(422L, 5f, 137),
            Triple(525L, 5f, 135),
            Triple(545L, -19f, 135),
            Triple(627L, -19f, 135),
            Triple(726L, 2f, 138),
            Triple(825L, 1f, 141),
            Triple(938L, -10f, 140),
            Triple(1020L, -10f, 141),
            Triple(1040L, -1f, 141),
            Triple(1117L, -1f, 142),
            Triple(1140L, 7f, 142),
            Triple(1314L, 3f, 142),
            Triple(1356L, -6f, 142),
            Triple(1485L, 0f, 143),
            Triple(1545L, 5f, 143),
            Triple(1626L, 5f, 142),
            Triple(1628L, 1f, 142),
        )

    // A shake recorded on the same pair: the heading swings 20° to 45°, and the pitch as much.
    private val recordedShake =
        listOf<Triple<Long, Float?, Int?>>(
            Triple(31L, -2f, 145),
            Triple(141L, -2f, 145),
            Triple(241L, -3f, 145),
            Triple(319L, -3f, 147),
            Triple(342L, 22f, 147),
            Triple(424L, 22f, 128),
            Triple(448L, 1f, 128),
            Triple(523L, 1f, 104),
            Triple(558L, -29f, 104),
            Triple(624L, -29f, 120),
            Triple(645L, -3f, 120),
            Triple(726L, -3f, 146),
            Triple(745L, 14f, 146),
            Triple(825L, 14f, 150),
            Triple(825L, 8f, 150),
            Triple(927L, -5f, 133),
            Triple(1041L, -15f, 122),
            Triple(1141L, -15f, 135),
            Triple(1143L, 4f, 135),
            Triple(1220L, 4f, 152),
            Triple(1242L, 13f, 152),
            Triple(1321L, 13f, 149),
            Triple(1345L, 5f, 149),
            Triple(1422L, 5f, 130),
            Triple(1448L, -12f, 130),
            Triple(1525L, -12f, 118),
            Triple(1546L, -17f, 118),
            Triple(1625L, -17f, 129),
            Triple(1647L, -1f, 129),
            Triple(1726L, -1f, 148),
            Triple(1749L, 10f, 148),
            Triple(1831L, 5f, 153),
            Triple(1930L, -8f, 138),
            Triple(2043L, -13f, 126),
            Triple(2142L, -13f, 127),
            Triple(2147L, -9f, 127),
            Triple(2221L, -9f, 136),
            Triple(2244L, -5f, 136),
            Triple(2324L, -5f, 143),
            Triple(2346L, -7f, 143),
            Triple(2424L, -7f, 147),
            Triple(2446L, -5f, 147),
        )

    @Test
    fun theRecordedNodIsYes() {
        assertEquals(listOf(HeadAnswer.YES), answers(recordedNod))
    }

    @Test
    fun theRecordedShakeIsNoThoughThePitchSwingsToo() {
        assertEquals(listOf(HeadAnswer.NO), answers(recordedShake))
    }

    @Test
    fun aNodWithoutACompassIsYes() {
        assertEquals(listOf(HeadAnswer.YES), answers(pitches(0f, 0f, -15f, 0f, -15f, 0f, 0f)))
    }

    @Test
    fun aShakeAcrossNorthIsNo() {
        assertEquals(listOf(HeadAnswer.NO), answers(headings(0, 0, 340, 20, 340, 0, 0)))
    }

    @Test
    fun aGlanceAsideAndBackIsNoAnswer() {
        assertEquals(emptyList<HeadAnswer>(), answers(headings(90, 90, 130, 130, 130, 90, 90, 90, 90)))
    }

    @Test
    fun lookingAroundSlowlyIsNoAnswer() {
        assertEquals(emptyList<HeadAnswer>(), answers(headings(*IntArray(60) { 90 + it * 2 })))
    }

    @Test
    fun reversalsMoreThanASecondApartAreNoAnswer() {
        assertEquals(
            emptyList<HeadAnswer>(),
            answers(pitches(0f, -15f, -15f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, -15f, 0f)),
        )
    }

    @Test
    fun aStillHeadsJitterIsNoAnswer() {
        assertEquals(emptyList<HeadAnswer>(), answers(pitches(0f, 5f, -5f, 5f, -5f, 5f, -5f, 0f)))
    }

    @Test
    fun aLongNodIsOneYes() {
        assertEquals(listOf(HeadAnswer.YES), answers(pitches(0f, -15f, 0f, -15f, 0f, -15f, 0f, -15f, 0f)))
    }

    @Test
    fun theLevelReadingIsPitchZero() {
        assertEquals(0f, pitchDegrees(GlassesAcceleration(0f, 0f, 1f)), 0.01f)
        assertEquals(30f, pitchDegrees(GlassesAcceleration(0.5f, 0f, 0.866f)), 0.1f)
    }
}
