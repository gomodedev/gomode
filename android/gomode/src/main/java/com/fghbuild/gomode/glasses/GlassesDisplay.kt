// Host-neutral contract for showing text and images on one connected pair of glasses and hearing its press.
package com.fghbuild.gomode.glasses

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.fghbuild.gomode.sdk.v1.Display
import com.fghbuild.gomode.voice.PNG_MIME_TYPE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A connected pair of glasses that shows text and reports its one deliberate press; a
 * [GlassesImageDisplay] also draws images. Each glasses model implements it: [HaloGlasses]
 * rasterizes text on the phone; [EvenG2Glasses] hands it to stock firmware that lays it out, or
 * draws it on custom firmware. [GlassesOwner] holds at most one and replaces it after the
 * connection ends.
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

    /**
     * Each gesture and system event the glasses report while connected, for the wearer checking
     * their input; [presses] alone says which ones act. Empty for glasses that report only their
     * press. The flow may end when the connection ends.
     */
    val events: Flow<GlassesEvent>

    /** Disconnects; [closeCause] then reports the disconnection. */
    suspend fun close()
}

/**
 * Glasses that also draw images. Exactly the implementations whose [description] [drawsImages]
 * implement it, so MCP hosts are offered images only where they can be drawn.
 */
internal interface GlassesImageDisplay : GlassesDisplay {
    /**
     * Replaces everything on the display with [image] as [fitImage] fits it to [description]. Throws
     * [GlassesException] when the glasses do not take it.
     */
    suspend fun showImage(image: Bitmap)
}

/**
 * Whether one screen of a display shows all of a text, laid out as that display lays it out. A
 * display's implementation is a value: two connections to the same model have equal fits.
 */
internal interface ScreenFit {
    fun fits(text: String): Boolean
}

/** Whether glasses with this display draw images: it declares the PNG images the shell decodes. */
internal val Display.drawsImages: Boolean
    get() = imageTypes.orEmpty().contains(PNG_MIME_TYPE)

/** What a display shows: a [Text] it lays out, or an [Image] fitted to it. */
internal sealed interface GlassesFrame {
    data class Text(
        val text: String,
    ) : GlassesFrame

    /** Equal only to a frame of the same [image], since a Bitmap compares by identity. */
    data class Image(
        val image: Bitmap,
    ) : GlassesFrame
}

/**
 * [image] as a [width] by [height] display shows it: as it is when it fits, otherwise scaled down,
 * keeping its aspect ratio, to the largest size that fits. The display centres it.
 */
internal fun fitImage(
    image: Bitmap,
    width: Int,
    height: Int,
): Bitmap {
    if (image.width <= width && image.height <= height) return image
    val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
    return image.scale(
        (image.width * scale).toInt().coerceIn(1, width),
        (image.height * scale).toInt().coerceIn(1, height),
    )
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
