// The batteries connected glasses report: each arm's and the charging case's level and whether it charges.
package com.fghbuild.gomode.glasses

/** Which battery of the glasses a reading is for, with its short [label] and its [spoken] name. */
internal enum class GlassesBatteryPart(
    val label: String,
    val spoken: String,
) {
    LEFT("L", "Left arm"),
    RIGHT("R", "Right arm"),
    CASE("Case", "Case"),
}

/** One battery's level in [percent], 0 to 100, and whether it is [charging]. */
internal data class GlassesBattery(
    val part: GlassesBatteryPart,
    val percent: Int,
    val charging: Boolean,
) {
    /** The reading as a screen reader says it, such as "Left arm 80%, charging". */
    val description: String
        get() = "${part.spoken} $percent%" + if (charging) ", charging" else ""
}
