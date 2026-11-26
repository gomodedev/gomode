// Turns glasses motion into head flicks: a quick tilt up or down, or turn left or right, that comes back.
//
// HeadFlickDetector follows two angles: pitch, from the accelerometer's gravity vector, and yaw, the
// compass heading, which only Faceclaw firmware reports, so stock firmware flicks only up and down.
// Each angle has a baseline that follows it while it stays within FLICK_DEGREES, so a slow drift, such
// as looking around, moves the baseline and is no flick. When an angle departs FLICK_DEGREES or more
// from its baseline and comes back within RETURN_DEGREES of it at most FLICK_MAX_MS after it left, that
// is one flick, in the direction of its peak. An angle that stays away longer takes its value as the new
// baseline. After a flick, neither angle starts another for DEBOUNCE_MS, which swallows the overshoot of
// the head coming back and the other angle's echo of the same movement.
//
// GlassesOwner.detectHeadFlicks runs the detector while a voice session runs; see there.
package com.fghbuild.gomode.glasses

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

// TODO(hardware): These thresholds are first guesses, and no pair has run the detector yet. On a pair
// on 2.3.0.24 with Faceclaw/45 the accelerometer streamed about 5.5 samples a second, so a flick shorter
// than about 200 ms may fall between samples, and the compass about 10 headings a second. During a voice
// session, flick up, down, left, and right a few times each, then look around slowly, and read
// `adb logcat -s GoModeGlasses`: "Head <pitch|yaw> baseline <angle>°" at the first reading,
// "Head flick <direction>: <angle>° in <duration> ms" for each flick, and "Head <pitch|yaw> went <angle>°
// away for over <FLICK_MAX_MS> ms: no flick" for each departure that timed out. Set FLICK_DEGREES between the largest angle of looking around and the smallest deliberate
// flick, FLICK_MAX_MS above the slowest deliberate flick, and DEBOUNCE_MS above the gap between a flick
// and any echo that the screen lists as a second flick. Also unverified: that pitch grows as the head
// tilts up and the heading as it turns right; if the screen names the opposite direction, flip the sign
// in pitchDegrees or in FlickAxis.difference. Delete this TODO with the measured values.

/** How far an angle departs from its baseline to start a flick, in degrees. */
internal const val FLICK_DEGREES = 20f

/** How near its baseline an angle comes back to end a flick, in degrees. */
private const val RETURN_DEGREES = 8f

/** The longest time from the departure to the return of a flick. */
internal const val FLICK_MAX_MS = 1_000L

/** How long after a flick no other starts. */
private const val DEBOUNCE_MS = 500L

/** The time constant with which a baseline follows its angle while it stays within [FLICK_DEGREES]. */
private const val BASELINE_FOLLOW_MS = 500L

/** A direction the wearer gives the glasses, as the input events screen names it. */
internal enum class GlassesDirection(
    val label: String,
) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
}

/** A head flick toward [direction] that peaked [degrees] from its baseline and came back [durationMs] after leaving. */
internal data class HeadFlick(
    val direction: GlassesDirection,
    val degrees: Float,
    val durationMs: Long,
)

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
 * Finds head flicks in a stream of readings; see the file header. Not thread safe: one collector feeds
 * it. [log] receives the first baseline of each angle and each departure that came back too late.
 */
internal class HeadFlickDetector(
    private val log: (String) -> Unit = {},
) {
    private val pitch = FlickAxis("pitch", wraps = false)
    private val yaw = FlickAxis("yaw", wraps = true)
    private var quietUntilMs = Long.MIN_VALUE

    /**
     * Takes the readings at [atMs], monotonic time: the head's [pitch] in degrees, positive up, and the
     * compass [heading] in degrees, 0 to 359, each null without a reading. Returns the flick they end, if any.
     */
    fun accept(
        atMs: Long,
        pitch: Float?,
        heading: Int?,
    ): HeadFlick? {
        val quiet = atMs < quietUntilMs
        val tilt = pitch?.let { this.pitch.accept(atMs, it, quiet, log) }
        val turn = heading?.let { yaw.accept(atMs, it.toFloat(), quiet, log) }
        val flick =
            tilt?.flick(GlassesDirection.UP, GlassesDirection.DOWN)
                ?: turn?.flick(GlassesDirection.RIGHT, GlassesDirection.LEFT)
        if (flick != null) {
            quietUntilMs = atMs + DEBOUNCE_MS
            this.pitch.settle()
            yaw.settle()
        }
        return flick
    }

    /** Forgets both baselines, as when the glasses come off the head. */
    fun reset() {
        pitch.reset()
        yaw.reset()
    }
}

// A departure that came back: its signed [peak] from the baseline, and [ms] from leaving to returning.
private class Excursion(
    val peak: Float,
    val ms: Long,
) {
    fun flick(
        positive: GlassesDirection,
        negative: GlassesDirection,
    ) = HeadFlick(if (peak > 0) positive else negative, abs(peak), ms)
}

// One angle's baseline and departure. A [wraps] angle, a heading, takes 359 to 0 as one degree.
private class FlickAxis(
    private val name: String,
    private val wraps: Boolean,
) {
    private var baseline: Float? = null
    private var lastMs = 0L

    // The departure under way: when it left the baseline, and its signed peak; null while near it.
    private var leftMs: Long? = null
    private var peak = 0f

    fun accept(
        atMs: Long,
        value: Float,
        quiet: Boolean,
        log: (String) -> Unit,
    ): Excursion? {
        val base = baseline
        if (base == null) {
            log("Head $name baseline ${value.roundToInt()}°")
            baseline = value
            lastMs = atMs
            return null
        }
        val away = difference(value, base)
        val previousMs = lastMs
        lastMs = atMs
        val left = leftMs
        if (left == null) {
            if (abs(away) < FLICK_DEGREES) {
                follow(base, away, atMs - previousMs)
            } else if (!quiet) {
                // It left after the last reading near the baseline.
                leftMs = previousMs
                peak = away
            }
            return null
        }
        if (abs(away) > abs(peak) && away * peak > 0) peak = away
        val elapsed = atMs - left
        return when {
            abs(away) <= RETURN_DEGREES && elapsed <= FLICK_MAX_MS -> {
                leftMs = null
                Excursion(peak, elapsed)
            }

            elapsed > FLICK_MAX_MS -> {
                log("Head $name went ${abs(peak).roundToInt()}° away for over $FLICK_MAX_MS ms: no flick")
                leftMs = null
                baseline = value
                null
            }

            else -> {
                null
            }
        }
    }

    // Ends a departure under way without a flick; the baseline stays.
    fun settle() {
        leftMs = null
    }

    fun reset() {
        baseline = null
        leftMs = null
    }

    // Moves the baseline toward the angle [away] from it, by the share of BASELINE_FOLLOW_MS that [ms] is.
    private fun follow(
        base: Float,
        away: Float,
        ms: Long,
    ) {
        val share = 1f - exp(-ms.toFloat() / BASELINE_FOLLOW_MS)
        val next = base + away * share
        baseline = if (wraps) (next % 360f + 360f) % 360f else next
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
