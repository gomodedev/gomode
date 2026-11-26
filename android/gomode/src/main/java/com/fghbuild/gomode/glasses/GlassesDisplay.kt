// Host-neutral contract for showing text and images on one pair of glasses, its gestures, microphone, and sensors.
package com.fghbuild.gomode.glasses

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.fghbuild.gomode.sdk.v1.Display
import com.fghbuild.gomode.voice.PNG_MIME_TYPE
import com.fghbuild.gomode.voice.VOICE_AUDIO_RATE_HZ
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A connected pair of glasses that shows text and reports the actions its gestures trigger; a
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
     * The action of each gesture bound to one while connected, as GlassesAction.kt maps them. Other
     * gestures are not reported. The flow may end when the connection ends.
     */
    val actions: Flow<GlassesAction>

    /**
     * Each swipe that scrolls held text, as GlassesAction.kt maps them, while connected. Empty for
     * glasses whose gestures scroll nothing. The flow may end when the connection ends.
     */
    val scrolls: Flow<GlassesScroll>

    /**
     * Each gesture and system event the glasses report while connected, for the wearer checking
     * their input; [actions] alone says which ones act. Empty for glasses that report only the
     * gestures that act. The flow may end when the connection ends.
     */
    val events: Flow<GlassesEvent>

    /**
     * The latest readings of the batteries the glasses report, the glasses' then the case's, while
     * connected. A battery with no reading is left out, so glasses that report none, such as Halo,
     * give an empty list. The flow may end when the connection ends.
     */
    val batteries: Flow<List<GlassesBattery>>

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

/** Glasses whose microphone the phone hears. */
internal interface GlassesMicrophone : GlassesDisplay {
    /**
     * The microphone as [VOICE_AUDIO_RATE_HZ] Hz mono PCM while collected and connected; collecting it
     * turns the microphone on. Collectors share each array and must not change it. The flow completes
     * when the connection ends.
     */
    val microphone: Flow<ShortArray>
}

/** Glasses that report sensors. */
internal interface GlassesSensing : GlassesDisplay {
    /**
     * The sensors' latest readings while collected and connected; collecting it runs the sensors that
     * cost power, such as the accelerometer's stream, so collect it only while a screen shows it. The
     * flow completes when the connection ends.
     */
    val sensors: Flow<GlassesSensors>

    /**
     * The same readings as [sensors], but collecting this flow runs only the sensors that follow the
     * head's motion, such as the accelerometer and a compass, and leaves those that change how the
     * glasses behave off, such as a light sensor that stops auto-brightness. Readings of sensors it
     * leaves off are null unless [sensors] is collected too. The flow completes when the connection ends.
     */
    val motion: Flow<GlassesSensors>
}

/**
 * Whether one screen of a display shows all of a text, laid out as that display lays it out. A
 * display's implementation is a value: two connections to the same model have equal fits.
 */
internal interface ScreenFit {
    fun fits(text: String): Boolean

    /**
     * [text] wrapped into lines, each of which shows on one row of any screen of them joined with
     * line feeds, so that a screen can scroll a line at a time.
     */
    fun lines(text: String): List<String>
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
