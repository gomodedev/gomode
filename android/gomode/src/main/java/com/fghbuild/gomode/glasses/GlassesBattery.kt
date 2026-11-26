// The batteries connected glasses report: the glasses' and the charging case's level and whether it charges.
package com.fghbuild.gomode.glasses

/** Which battery of the glasses a reading is for, with the [label] that names it on screen and aloud. */
internal enum class GlassesBatteryPart(
    val label: String,
) {
    GLASSES("Glasses"),
    CASE("Case"),
}

/** One battery's level in [percent], 0 to 100, and whether it is [charging]. */
internal data class GlassesBattery(
    val part: GlassesBatteryPart,
    val percent: Int,
    val charging: Boolean,
) {
    /** The reading as a screen reader says it, such as "Glasses 80%, charging". */
    val description: String
        get() = "${part.label} $percent%" + if (charging) ", charging" else ""
}
