// Host-neutral contract for showing text on one connected pair of glasses and hearing its press, whatever the model.
package com.fghbuild.gomode.glasses

import com.fghbuild.gomode.sdk.v1.Display
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A connected pair of glasses that shows text and reports its one deliberate press. Each glasses
 * model implements it: [HaloGlasses]
 * rasterizes text on the phone; [EvenG2Glasses] hands it to firmware that lays it out. [GlassesOwner]
 * holds at most one and replaces it after the connection ends.
 */
internal interface GlassesDisplay {
    /** The display as MCP requests declare it while these glasses are connected. */
    val description: Display

    /** Whether one screen shows a text whole, measured in the layout [showText] uses. */
    val screenFit: ScreenFit

    /**
     * Why the connection ended, or null while it is up. Once set, it stays set. A permanent
     * [GlassesException] ends the connection for good, such as the wearer closing Go Mode's page.
     */
    val closeCause: StateFlow<Exception?>

    /**
     * Replaces everything on the display with [text]. A line feed starts a new line, long lines wrap
     * at spaces, and lines past the last one that fits are not shown. Blank text clears the display.
     * Throws [GlassesException] when the glasses do not take it.
     */
    suspend fun showText(text: String)

    /**
     * Each deliberate press while connected: one press of Halo's button, or a click of an Even G2
     * touchpad or R1 ring. Other gestures, such as double or long presses and taps, are not reported.
     * The flow may end when the connection ends.
     */
    val presses: Flow<Unit>

    /** Disconnects; [closeCause] then reports the disconnection. */
    suspend fun close()
}

/**
 * Whether one screen of a display shows all of a text, laid out as that display lays it out. A
 * display's implementation is a value: two connections to the same model have equal fits.
 */
internal interface ScreenFit {
    fun fits(text: String): Boolean
}

/** The bits a pixel needs to take one of [values] levels or colours. */
internal fun bitsPerPixel(values: Int): Int = Int.SIZE_BITS - (values - 1).countLeadingZeroBits()

/**
 * Connecting to glasses or showing text on them failed. A [permanent] failure, such as a device of
 * an unsupported model, fails again on retry, so the owner stops instead of reconnecting. [problem]
 * tells the user what failed.
 */
internal class GlassesException(
    message: String,
    cause: Throwable? = null,
    val permanent: Boolean = false,
    val problem: GlassesProblem = GlassesProblem.UNREACHABLE,
) : Exception(message, cause)
