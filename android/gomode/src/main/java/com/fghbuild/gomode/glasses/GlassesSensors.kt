// The sensors connected glasses report and their latest readings: whether they are worn, the head, the accelerometer.
package com.fghbuild.gomode.glasses

/** A sensor of connected glasses, with the [label] that names it on screen. */
internal enum class GlassesSensor(
    val label: String,
) {
    WORN("Worn"),
    HEAD("Head"),
    ACCELEROMETER("Accelerometer"),
}

/** One accelerometer sample in g along the glasses' axes: [x] forward, [y] to the wearer's left, [z] up. */
internal data class GlassesAcceleration(
    val x: Float,
    val y: Float,
    val z: Float,
)

/**
 * The sensors connected glasses [report], in the order a screen lists them, and their latest readings,
 * each null until its first: whether the glasses are [worn], whether the head last went up ([headUp]),
 * and the latest [acceleration].
 */
internal data class GlassesSensors(
    val report: List<GlassesSensor>,
    val worn: Boolean? = null,
    val headUp: Boolean? = null,
    val acceleration: GlassesAcceleration? = null,
)
