// Confirms a new glasses connection on the glasses: a ring closing around a check mark over "Go Mode", or typed text.
package com.fghbuild.gomode.glasses

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import androidx.core.graphics.createBitmap

/** One step of the greeting: [frame] stays for [durationMs] before the next step or the end. */
internal data class GreetingStep(
    val frame: GlassesFrame,
    val durationMs: Long,
)

// Each animated step; a display slower than this skips steps, since the owner shows only the latest.
internal const val GREETING_STEP_MS = 80L

// How long the final frame stays, long enough to see at a glance.
internal const val GREETING_HOLD_MS = 1_500L

internal const val GREETING_TEXT = "Go Mode connected"

// The name under the ring, which says what connected.
internal const val GREETING_LABEL = "Go Mode"

private const val RING_STEPS = 8
private const val CHECK_STEPS = 4
private const val FULL_SWEEP_DEGREES = 360f
private const val TOP_DEGREES = -90f

// Stroke width as a fraction of the ring's side.
private const val STROKE_FRACTION = 1 / 14f

// The label's text size, and the gap above it, as fractions of the ring's side.
private const val LABEL_FRACTION = 1 / 5f
private const val LABEL_GAP_FRACTION = 1 / 12f

// The check mark's corners as fractions of the ring's side: short leg down, long leg up.
private val CHECK_POINTS = listOf(0.30f to 0.52f, 0.44f to 0.66f, 0.71f to 0.37f)

/**
 * The greeting for glasses that draw images when [images] holds, on a display of [width] by [height]
 * pixels, otherwise typed text. Without [animate], as when the user turned animations off, it is the
 * final frame alone. It lasts at most about 2.5 s.
 */
internal fun greetingSteps(
    images: Boolean,
    width: Int,
    height: Int,
    animate: Boolean,
): List<GreetingStep> {
    val frames =
        if (images) {
            val side = minOf(width, height) / 2
            val ring = (1..RING_STEPS).map { ringFrame(side, it.toFloat() / RING_STEPS, check = 0f) }
            val check = (1..CHECK_STEPS).map { ringFrame(side, sweep = 1f, it.toFloat() / CHECK_STEPS) }
            ring + check
        } else {
            // Typed a word piece at a time: a step per character would take as long as the hold.
            GREETING_TEXT.indices
                .filter { it % 2 == 1 || it == GREETING_TEXT.lastIndex }
                .map { GlassesFrame.Text(GREETING_TEXT.substring(0, it + 1)) }
        }
    val shown = if (animate) frames else frames.takeLast(1)
    return shown.mapIndexed { index, frame ->
        GreetingStep(frame, if (index == shown.lastIndex) GREETING_HOLD_MS else GREETING_STEP_MS)
    }
}

// Light on black, [side] pixels wide: a ring swept clockwise from the top over [sweep] of a turn, the
// first [check] of the check mark inside it, and [GREETING_LABEL] under it from the first frame.
private fun ringFrame(
    side: Int,
    sweep: Float,
    check: Float,
): GlassesFrame.Image {
    val label =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = side * LABEL_FRACTION
            // A narrow ring, as on Halo, shrinks the label to its width.
            textSize *= minOf(1f, side / measureText(GREETING_LABEL))
        }
    val labelTop = side + side * LABEL_GAP_FRACTION
    val bitmap = createBitmap(side, (labelTop + label.fontSpacing).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.BLACK)
    canvas.drawText(GREETING_LABEL, side / 2f, labelTop - label.ascent(), label)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = side * STROKE_FRACTION
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    val inset = paint.strokeWidth
    canvas.drawArc(inset, inset, side - inset, side - inset, TOP_DEGREES, sweep * FULL_SWEEP_DEGREES, false, paint)
    if (check > 0f) {
        val mark =
            Path().apply {
                val (x, y) = CHECK_POINTS.first()
                moveTo(x * side, y * side)
                CHECK_POINTS.drop(1).forEach { (px, py) -> lineTo(px * side, py * side) }
            }
        val measure = PathMeasure(mark, false)
        val drawn = Path()
        measure.getSegment(0f, measure.length * check, drawn, true)
        canvas.drawPath(drawn, paint)
    }
    return GlassesFrame.Image(bitmap)
}
