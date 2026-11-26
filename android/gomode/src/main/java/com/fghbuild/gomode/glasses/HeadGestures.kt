// Turns glasses motion into head gestures: a nod says yes, a shake says no.
//
// HeadGestureDetector follows two angles: pitch, from the accelerometer's gravity vector, and yaw, the
// compass heading, which only Faceclaw firmware reports, so stock firmware only nods. Each angle is
// traced as legs between its turning points: a leg ends when the angle comes back LEG_DEGREES (pitch) or
// YAW_LEG_DEGREES (yaw) from its latest extreme. Two such reversals within GESTURE_MAX_MS of the
// first's extreme, with at most LEG_MAX_MS between extremes, are an oscillation: down, up, and down again for a nod, which on a pair dips
// below the head's rest and comes back to it rather than above it, or left, right, and left again for a
// shake. A glance aside and back has one reversal and is no gesture. A shake also swings the pitch, so a
// yaw reversal within GESTURE_MAX_MS makes the movement a shake, never a nod. After a gesture, neither
// angle starts another until it has had no reversal for DEBOUNCE_MS, so a long shake is one answer.
//
// GlassesOwner.detectHeadGestures runs the detector while a voice session runs; see there.
package com.fghbuild.gomode.glasses

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

// TODO(hardware): Tuned on one session of a pair on 2.3.0.24 with Faceclaw/45, at about 10 accelerometer
// samples and 10 headings a second: a nod dipped 10° to 20° below rest and came back within 0.2 s; a
// shake swung the heading 20° to 45° and the pitch as much, about 2.5 times a second. Nod and shake a
// few times each during a voice session, look around, and read `adb logcat -s GoModeGlasses`: "Head
// reading pitch P° heading H°" for every reading and "Head nod|shake in M ms" for each gesture. Set the
// leg thresholds below the smallest deliberate swing and above a still head's jitter, about 5°; then
// delete the per-reading log in GlassesOwner and this TODO.

/** How far the pitch comes back from its latest extreme to end a leg, in degrees. */
internal const val LEG_DEGREES = 12f

/** How far the heading comes back from its latest extreme to end a leg, in degrees. */
internal const val YAW_LEG_DEGREES = 15f

/** The longest time from the extreme of a gesture's first reversal to the end of its second. */
internal const val GESTURE_MAX_MS = 1_000L

/** The longest time from one extreme of a gesture to the next. */
internal const val LEG_MAX_MS = 600L

/** How long the head stays without a reversal after a gesture before the next can start. */
private const val DEBOUNCE_MS = 1_000L

/** What the wearer's head says: a nod is [YES], a shake [NO]. */
internal enum class HeadAnswer(
    val label: String,
) {
    YES("yes"),
    NO("no"),
}

/**
 * The head's pitch in degrees, positive up, from [acceleration]: the angle of the forward axis above the
 * plane square to gravity. A level head, which reads (0, 0, 1), is at 0.
 */
internal fun pitchDegrees(acceleration: GlassesAcceleration): Float =
    Math
        .toDegrees(
            atan2(
                acceleration.x.toDouble(),
                sqrt(acceleration.y.toDouble() * acceleration.y + acceleration.z.toDouble() * acceleration.z),
            ),
        ).toFloat()

/**
 * Finds nods and shakes in a stream of readings; see the file header. Not thread safe: one collector
 * feeds it. [log] receives each gesture with its swings and duration.
 */
internal class HeadGestureDetector(
    private val log: (String) -> Unit = {},
) {
    private val pitch = LegAxis(LEG_DEGREES, wraps = false)
    private val yaw = LegAxis(YAW_LEG_DEGREES, wraps = true)
    private var quietUntilMs = Long.MIN_VALUE

    /**
     * Takes the readings at [atMs], monotonic time: the head's [pitch] in degrees, positive up, and the
     * compass [heading] in degrees, 0 to 359, each null without a reading. Returns the answer they end, if any.
     */
    fun accept(
        atMs: Long,
        pitch: Float?,
        heading: Int?,
    ): HeadAnswer? {
        val tilted = pitch?.let { this.pitch.accept(atMs, it) } == true
        val turned = heading?.let { yaw.accept(atMs, it.toFloat()) } == true
        if (atMs < quietUntilMs) {
            // The gesture goes on: wait until the head has been still for DEBOUNCE_MS.
            if (tilted || turned) quietUntilMs = atMs + DEBOUNCE_MS
            return null
        }
        val answer =
            when {
                yaw.oscillates(atMs) -> HeadAnswer.NO

                pitch != null &&
                    this.pitch.oscillates(
                        atMs,
                    ) &&
                    !yaw.reversedSince(atMs - GESTURE_MAX_MS) -> HeadAnswer.YES

                else -> return null
            }
        val axis = if (answer == HeadAnswer.NO) yaw else this.pitch
        log("Head ${if (answer == HeadAnswer.YES) "nod" else "shake"} in ${axis.since(atMs)} ms")
        quietUntilMs = atMs + DEBOUNCE_MS
        this.pitch.clear()
        yaw.clear()
        return answer
    }

    /** Forgets both angles, as when the glasses come off the head. */
    fun reset() {
        pitch.clear()
        yaw.clear()
    }
}

// One angle traced as legs between turning points; see the file header. A [wraps] angle, a heading,
// takes 359 to 0 as one degree.
private class LegAxis(
    private val legDegrees: Float,
    private val wraps: Boolean,
) {
    // The latest extreme and when it was reached; null before the first reading.
    private var extreme: Float? = null
    private var extremeMs = 0L

    // The direction of the leg under way: +1 rising, -1 falling, 0 before the first leg.
    private var trend = 0

    // When each of the latest reversals' extremes was reached, oldest first.
    private val reversals = ArrayDeque<Long>()

    // Returns whether this reading ends a leg.
    fun accept(
        atMs: Long,
        value: Float,
    ): Boolean {
        val last = extreme
        if (last == null) {
            extreme = value
            extremeMs = atMs
            return false
        }
        val delta = difference(value, last)
        when {
            trend == 0 && abs(delta) >= legDegrees -> {
                trend = if (delta > 0) 1 else -1
                moveTo(value, atMs)
            }

            trend == 0 -> {}

            delta * trend > 0 -> {
                moveTo(value, atMs)
            }

            abs(delta) >= legDegrees -> {
                // A leg slower than LEG_MAX_MS, such as a pause at rest, starts the count again.
                if (reversals.lastOrNull()?.let { extremeMs - it > LEG_MAX_MS } == true) reversals.clear()
                reversals.addLast(extremeMs)
                while (reversals.size > 2) reversals.removeFirst()
                trend = -trend
                moveTo(value, atMs)
                return true
            }
        }
        return false
    }

    // Whether two reversals came at most GESTURE_MAX_MS before [atMs], counted from the first's extreme.
    fun oscillates(atMs: Long) = reversals.size == 2 && since(atMs) <= GESTURE_MAX_MS

    fun reversedSince(ms: Long) = reversals.lastOrNull()?.let { it >= ms } == true

    fun since(atMs: Long) = atMs - (reversals.firstOrNull() ?: atMs)

    fun clear() {
        extreme = null
        trend = 0
        reversals.clear()
    }

    private fun moveTo(
        value: Float,
        atMs: Long,
    ) {
        extreme = if (wraps) (value % 360f + 360f) % 360f else value
        extremeMs = atMs
    }

    // The signed angle from [base] to [value]: for a heading, the shorter way round, positive clockwise.
    private fun difference(
        value: Float,
        base: Float,
    ): Float {
        val away = value - base
        return if (wraps) ((away % 360f + 540f) % 360f) - 180f else away
    }
}
