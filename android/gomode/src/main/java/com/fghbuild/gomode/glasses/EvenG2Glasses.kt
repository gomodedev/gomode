// Even G2's GlassesDisplay: a stock firmware text container, or the whole panel with images on Faceclaw firmware.
//
// The session's first G2State.Ready names the firmware, and connect() returns the display it
// draws: on stock firmware, one text container, created on connect and updated in place; on
// Faceclaw firmware (G2Firmware.Faceclaw), text and images that the SDK draws on the whole panel.
// The page's text container captures the touchpads and an R1 ring, whose gestures g2Action maps to
// actions and g2Scroll to scrolls.
// The session polls the glasses' battery and the case's; batteries are G2Glasses.battery. The
// microphone is G2Glasses.microphone and the sensors G2Glasses.sensors, or G2Glasses.motion without the
// light sensor, which run while collected; they outlive the connection, so a connection's flows complete when that connection ends. Head up
// and down, ambient light, and the compass come only from Faceclaw firmware (see G2Sensors.kt), so
// only its sensors list them.
//
// Each GlassesOwner attempt is one G2Glasses session; GlassesOwner alone decides whether to start
// another. connect() returns at the session's first G2State.Ready and fails if it reaches
// G2State.Closed first; once connected, Closed sets closeCause. A retryable G2CloseCause is a
// retryable GlassesException; the others, such as a refused pairing, are permanent. The session
// itself creates its page again when the glasses close it, so that ends no connection.
//
// GlassesOwner keeps one G2Glasses across attempts, so request and page session IDs never repeat
// on the glasses. G2Glasses.connect() publishes Connecting before it returns, so every state
// connect() and the watcher read belongs to this attempt.
package com.fghbuild.gomode.glasses

import android.graphics.Bitmap
import com.fghbuild.eveng2.G2Battery
import com.fghbuild.eveng2.G2Charge
import com.fghbuild.eveng2.G2CloseCause
import com.fghbuild.eveng2.G2Content
import com.fghbuild.eveng2.G2Display
import com.fghbuild.eveng2.G2Firmware
import com.fghbuild.eveng2.G2Glasses
import com.fghbuild.eveng2.G2Head
import com.fghbuild.eveng2.G2Image
import com.fghbuild.eveng2.G2InputEvent
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Panel
import com.fghbuild.eveng2.G2Sensors
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.G2State
import com.fghbuild.eveng2.G2TextPage
import com.fghbuild.gomode.sdk.v1.Display
import com.fghbuild.gomode.sdk.v1.DisplayColorSpace
import com.fghbuild.gomode.sdk.v1.DisplayShape
import com.fghbuild.gomode.voice.PNG_MIME_TYPE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Even G2's display of one eye on stock firmware as MCP requests declare it: 16 grey levels, which the
 * glasses show in green, and no image types, since Go Mode draws only text there.
 *
 * Stock firmware draws an image only in an Even Hub image container of at most 288 by 144 pixels, four
 * to a page: the image container rows of the page schema in the enterprise SDK's
 * docs/enterprise/enterprise-flutter-sdk.md (commit e49130b), and "Up to 288 x 144 px per container"
 * on hub.evenrealities.com/docs/build/display. A full-display image would take a 2x2 grid of them. No
 * source shows that grid on a pair, and MentraOS (Apache-2.0) commit 9f100b466 reports the firmware
 * refusing transfers into containers beyond about 200 by 100, so declaring images would promise what
 * hosts may not get.
 *
 * TODO(hardware): On a pair on stock firmware, rebuild the page with four 288 by 144 image containers
 * over the event-capturing text container and send each, one at a time, an uncompressed 4-bit BMP
 * (compressMode 0, 4 KiB fragments) through updateImage, whose wire format G2EvenHubCodec.kt of the
 * enterprise SDK holds. If all four draw, add image/png to imageTypes here and make EvenG2Glasses.Stock a
 * GlassesImageDisplay.
 */
internal val G2_DISPLAY =
    Display(
        width = G2Display.WIDTH_PX,
        height = G2Display.HEIGHT_PX,
        shape = DisplayShape.Rectangle,
        colorSpace = DisplayColorSpace.Luminance,
        bitsPerPixel = bitsPerPixel(G2Display.GREY_LEVELS),
    )

/**
 * Even G2's display of one eye on Faceclaw custom firmware: the whole [G2Panel] of 16 grey levels,
 * on which Go Mode draws text and PNG images.
 */
internal val G2_FACECLAW_DISPLAY =
    Display(
        width = G2Panel.WIDTH_PX,
        height = G2Panel.HEIGHT_PX,
        shape = DisplayShape.Rectangle,
        colorSpace = DisplayColorSpace.Luminance,
        bitsPerPixel = bitsPerPixel(G2Panel.GREY_LEVELS),
        imageTypes = listOf(PNG_MIME_TYPE),
    )

/**
 * One G2 text container: the firmware shows a text whole when [G2TextPage.fits] it as [g2Text]
 * sends it, before the byte limit cuts it.
 */
internal data class G2ScreenFit(
    val page: G2TextPage,
) : ScreenFit {
    override fun fits(text: String): Boolean = page.fits(utf8(text))

    override fun lines(text: String): List<String> = page.lines(utf8(text))
}

/** The Faceclaw panel: it shows a text whole when [G2Panel.fits] it as [g2Text] sends it. */
internal data object G2PanelFit : ScreenFit {
    override fun fits(text: String): Boolean = G2Panel.fits(utf8(text))

    override fun lines(text: String): List<String> = G2Panel.lines(utf8(text))
}

internal sealed class EvenG2Glasses(
    protected val glasses: G2Glasses,
    firmware: G2Firmware,
) : GlassesMicrophone,
    GlassesSensing {
    private val ended = MutableStateFlow<GlassesException?>(null)
    private var watcher: Job? = null

    override val closeCause: StateFlow<Exception?> = ended.asStateFlow()
    override val actions: Flow<GlassesAction> = glasses.gestures.mapNotNull { g2Action(it, firmware) }
    override val scrolls: Flow<GlassesScroll> = glasses.gestures.mapNotNull(::g2Scroll)
    override val events: Flow<GlassesEvent> = glasses.events.map(::glassesEvent)
    override val batteries: Flow<List<GlassesBattery>> = glasses.battery.map(::glassesBatteries)
    override val microphone: Flow<ShortArray> = whileConnected(glasses.microphone).buffer(Channel.RENDEZVOUS)
    override val sensors: Flow<GlassesSensors> =
        whileConnected(glasses.sensors).map { glassesSensors(it, firmware) }.conflate()
    override val motion: Flow<GlassesSensors> = whileConnected(glasses.motion).map { glassesSensors(it, firmware) }

    // [flow] until the connection ends.
    private fun <T> whileConnected(flow: Flow<T>): Flow<T> =
        channelFlow {
            val forwarding = launch { flow.collect(::send) }
            ended.first { it != null }
            forwarding.cancel()
        }

    /** Shows [text], cut to the firmware's limit, and waits until the glasses acknowledge it. */
    override suspend fun showText(text: String) {
        val shown = g2Text(text)
        glasses.show(shown)
        awaitShown(G2Content.Text(shown))
    }

    protected suspend fun awaitShown(content: G2Content) {
        val state = glasses.state.first { (it is G2State.Ready && it.shown == content) || it is G2State.Closed }
        if (state is G2State.Closed) throw end(state.cause)
    }

    override suspend fun close() {
        watcher?.cancel()
        glasses.disconnect()
        end(G2CloseCause.Stopped)
    }

    // The first end wins, so closeCause and a failing showText agree.
    private fun end(cause: G2CloseCause): GlassesException {
        ended.compareAndSet(null, cause.toGlassesException())
        return checkNotNull(ended.value)
    }

    /** Stock firmware: one text container. */
    private class Stock(
        glasses: G2Glasses,
        firmware: G2Firmware.EvenHub,
    ) : EvenG2Glasses(glasses, firmware) {
        override val description = G2_DISPLAY
        override val screenFit = G2ScreenFit(glasses.page)
    }

    /** Faceclaw firmware: text and images drawn on the whole panel. */
    private class Faceclaw(
        glasses: G2Glasses,
        firmware: G2Firmware.Faceclaw,
    ) : EvenG2Glasses(glasses, firmware),
        GlassesImageDisplay {
        override val description = G2_FACECLAW_DISPLAY
        override val screenFit = G2PanelFit

        /** Shows [image], fitted to the panel in its grey levels, and waits until both lenses draw it. */
        override suspend fun showImage(image: Bitmap) {
            val shown = g2Image(fitImage(image, G2Panel.WIDTH_PX, G2Panel.HEIGHT_PX))
            glasses.show(shown)
            awaitShown(G2Content.Image(shown))
        }
    }

    companion object {
        /**
         * Opens a session of [glasses] with [pair], which shows what [G2Glasses.show] last set, and
         * returns the display of the firmware it finds. A [background] session waits for the arms to
         * advertise; see [G2Glasses.connect]. [scope] watches the connection until [close]. The
         * caller holds BLUETOOTH_CONNECT. A failure or cancellation disconnects.
         */
        suspend fun connect(
            glasses: G2Glasses,
            pair: G2Pair,
            background: Boolean,
            scope: CoroutineScope,
        ): EvenG2Glasses {
            var connected: EvenG2Glasses? = null
            try {
                // Before the first transcript frame measures on the main thread.
                G2TextPage.loadMetrics()
                glasses.connect(pair, background)
                val first = glasses.state.first { it is G2State.Ready || it is G2State.Closed }
                if (first is G2State.Closed) throw first.cause.toGlassesException()
                val firmware = (first as G2State.Ready).firmware
                val display =
                    when (firmware) {
                        is G2Firmware.Faceclaw -> Faceclaw(glasses, firmware)
                        is G2Firmware.EvenHub -> Stock(glasses, firmware)
                    }
                display.watcher =
                    scope.launch {
                        val closed = glasses.state.filterIsInstance<G2State.Closed>().first()
                        display.end(closed.cause)
                    }
                connected = display
                return display
            } finally {
                if (connected == null) withContext(NonCancellable) { glasses.disconnect() }
            }
        }
    }
}

/**
 * [image] in the panel's grey levels: each pixel's BT.601 luma over black, at its opacity, rounded to
 * the nearest of [G2Panel.GREY_LEVELS] levels.
 */
private fun g2Image(image: Bitmap): G2Image {
    val pixels = IntArray(image.width * image.height)
    image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
    val top = G2Panel.GREY_LEVELS - 1
    val levels =
        ByteArray(pixels.size) { i ->
            val argb = pixels[i]
            // 0.299 R + 0.587 G + 0.114 B in thousandths, scaled by alpha.
            val luma = 299 * (argb ushr 16 and 0xFF) + 587 * (argb ushr 8 and 0xFF) + 114 * (argb and 0xFF)
            val shaded = luma.toLong() * (argb ushr 24) * top
            val scale = 1000L * 255 * 255
            ((shaded + scale / 2) / scale).toInt().toByte()
        }
    return G2Image(image.width, image.height, levels)
}

/**
 * [text] as the firmware takes it: valid UTF-8 without NUL, at most [G2Display.MAX_TEXT_BYTES]. Like
 * a page too short for its text, the firmware limit cuts the end.
 */
internal fun g2Text(text: String): String {
    val bytes = utf8(text).toByteArray()
    if (bytes.size <= G2Display.MAX_TEXT_BYTES) return bytes.decodeToString()
    var end = G2Display.MAX_TEXT_BYTES
    // Cut before a code point's continuation bytes.
    while (bytes[end].toInt() and 0xC0 == 0x80) end--
    return bytes.decodeToString(0, end)
}

// [text] without NUL and with unpaired surrogates, which UTF-8 cannot carry, replaced as encoding does.
private fun utf8(text: String): String = text.replace("\u0000", "").toByteArray().decodeToString()

private fun G2CloseCause.toGlassesException(): GlassesException {
    val problem =
        when (this) {
            is G2CloseCause.PairingFailed,
            is G2CloseCause.ConnectFailed,
            is G2CloseCause.Disconnected,
            is G2CloseCause.WriteFailed,
            -> {
                GlassesProblem.UNREACHABLE
            }

            else -> {
                GlassesProblem.UNRESPONSIVE
            }
        }
    val message =
        when (this) {
            is G2CloseCause.PairingFailed -> {
                "Even G2 ${side.label()} arm did not pair: $reason. Connect again and accept the pairing request."
            }

            is G2CloseCause.ConnectFailed -> {
                "Even G2 ${side.label()} arm did not connect: $reason"
            }

            is G2CloseCause.Disconnected -> {
                "Even G2 ${side.label()} arm disconnected (status=$status)"
            }

            is G2CloseCause.WriteFailed -> {
                "Even G2 ${side.label()} arm did not take a write: $reason"
            }

            is G2CloseCause.AuthTimedOut -> {
                "Even G2 ${side.label()} arm did not accept Go Mode"
            }

            G2CloseCause.PreludeTimedOut -> {
                "Even G2 did not answer the session prelude"
            }

            is G2CloseCause.CommandTimedOut -> {
                "Even G2 did not answer ${command.name}"
            }

            is G2CloseCause.CommandRejected -> {
                "Even G2 refused ${command.name} (result=$result)"
            }

            is G2CloseCause.DrawFailed -> {
                if (rejected) "Even G2 refused to draw Go Mode's screen" else "Even G2 did not draw Go Mode's screen"
            }

            is G2CloseCause.Failed -> {
                "Could not use Even G2: ${error.message}"
            }

            G2CloseCause.Stopped -> {
                "Disconnected"
            }
        }
    return GlassesException(message, (this as? G2CloseCause.Failed)?.error, permanent = !retryable, problem = problem)
}

private fun G2Side.label(): String = name.lowercase()

/** The parts of [battery] that have a reading, in the order [GlassesDisplay.batteries] lists them. */
internal fun glassesBatteries(battery: G2Battery): List<GlassesBattery> {
    fun reading(
        part: GlassesBatteryPart,
        charge: G2Charge?,
    ) = charge?.let { GlassesBattery(part, it.percent, it.charging) }
    return listOfNotNull(
        reading(GlassesBatteryPart.GLASSES, battery.glasses),
        reading(GlassesBatteryPart.CASE, battery.case),
    )
}

/** [sensors] as the input events screen lists them: [firmware] decides which it reports. */
internal fun glassesSensors(
    sensors: G2Sensors,
    firmware: G2Firmware,
): GlassesSensors {
    val report =
        when (firmware) {
            is G2Firmware.Faceclaw -> {
                listOf(
                    GlassesSensor.WORN,
                    GlassesSensor.HEAD,
                    GlassesSensor.ACCELEROMETER,
                    GlassesSensor.LIGHT,
                    GlassesSensor.HEADING,
                )
            }

            is G2Firmware.EvenHub -> {
                listOf(GlassesSensor.WORN, GlassesSensor.ACCELEROMETER)
            }
        }
    return GlassesSensors(
        report = report,
        worn = sensors.wearing,
        headUp = sensors.head?.let { it == G2Head.UP },
        acceleration = sensors.acceleration?.let { GlassesAcceleration(it.x, it.y, it.z) },
        // g2flash's als_sensor.c gives the stock driver's unit as roughly a tenth of a lux.
        lux = sensors.light?.let { it / 10f },
        heading = sensors.heading,
    )
}

/**
 * [event] as the input events screen lists it. A click names no temple in a text event, the arm that
 * reported it aside, and either arm reports either temple, so only a system event names its place.
 */
internal fun glassesEvent(event: G2InputEvent): GlassesEvent {
    val gesture =
        when (event.type) {
            G2InputEvent.CLICK -> GlassesGesture.TAP
            G2InputEvent.SCROLL_UP -> GlassesGesture.SCROLL_UP
            G2InputEvent.SCROLL_DOWN -> GlassesGesture.SCROLL_DOWN
            G2InputEvent.DOUBLE_CLICK -> GlassesGesture.DOUBLE_TAP
            G2InputEvent.FOREGROUND_ENTER -> GlassesGesture.PAGE_SHOWN
            G2InputEvent.FOREGROUND_EXIT -> GlassesGesture.PAGE_HIDDEN
            G2InputEvent.ABNORMAL_EXIT, G2InputEvent.EXIT -> GlassesGesture.PAGE_CLOSED
            G2InputEvent.LONG_PRESS -> GlassesGesture.HOLD
            G2InputEvent.RELEASE -> GlassesGesture.RELEASE
            G2InputEvent.TAP_THEN_HOLD -> GlassesGesture.TAP_THEN_HOLD
            G2InputEvent.HEAD_UP -> GlassesGesture.HEAD_UP
            G2InputEvent.HEAD_DOWN -> GlassesGesture.HEAD_DOWN
            G2InputEvent.RING_TOUCH_DOWN -> GlassesGesture.RING_TOUCH
            else -> GlassesGesture.OTHER
        }
    val place =
        when (event.source) {
            G2InputEvent.SOURCE_LEFT_TEMPLE -> GlassesInputPlace.LEFT
            G2InputEvent.SOURCE_RIGHT_TEMPLE -> GlassesInputPlace.RIGHT
            G2InputEvent.SOURCE_RING -> GlassesInputPlace.RING
            else -> null
        }
    return GlassesEvent(gesture, place, event.type.takeIf { gesture == GlassesGesture.OTHER })
}
