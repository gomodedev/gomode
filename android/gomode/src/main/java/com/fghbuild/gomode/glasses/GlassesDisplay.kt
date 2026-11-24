// Host-neutral contract for showing text on one connected pair of glasses, whatever their model.
package com.fghbuild.gomode.glasses

import com.fghbuild.gomode.sdk.v1.Display
import kotlinx.coroutines.flow.StateFlow

/**
 * A connected pair of glasses that shows text. Each glasses model implements it: [HaloGlasses]
 * rasterizes text on the phone; glasses whose firmware lays out text take it as is. [GlassesOwner]
 * holds at most one and replaces it after the connection ends.
 */
internal interface GlassesDisplay {
    /** The display as MCP requests declare it while these glasses are connected. */
    val description: Display

    /** Why the connection ended, or null while it is up. Once set, it stays set. */
    val closeCause: StateFlow<Exception?>

    /**
     * Replaces everything on the display with [text]. A line feed starts a new line, long lines wrap
     * at spaces, and lines past the last one that fits are not shown. Blank text clears the display.
     * Throws [GlassesException] when the glasses do not take it.
     */
    suspend fun showText(text: String)

    /** Disconnects; [closeCause] then reports the disconnection. */
    suspend fun close()
}

/** The bits a pixel needs to take one of [values] levels or colours. */
internal fun bitsPerPixel(values: Int): Int = Int.SIZE_BITS - (values - 1).countLeadingZeroBits()

/**
 * Connecting to glasses or showing text on them failed. A [permanent] failure, such as a device of
 * an unsupported model, fails again on retry, so the owner stops instead of reconnecting.
 */
internal class GlassesException(
    message: String,
    cause: Throwable? = null,
    val permanent: Boolean = false,
) : Exception(message, cause)
