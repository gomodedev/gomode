// Halo's GlassesDisplay: Go Mode's Lua app draws text pages rasterized on the phone and reports button presses.
package com.fghbuild.gomode.glasses

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.fghbuild.gomode.sdk.v1.Display
import com.fghbuild.gomode.sdk.v1.DisplayColorSpace
import com.fghbuild.gomode.sdk.v1.DisplayShape
import com.fghbuild.halo.ble.HaloConnection
import com.fghbuild.halo.ble.HaloDeviceType
import com.fghbuild.halo.ble.HaloException
import com.fghbuild.halo.msg.CircularTextLayout
import com.fghbuild.halo.msg.ClickType
import com.fghbuild.halo.msg.HaloDisplay
import com.fghbuild.halo.msg.HalosideApp
import com.fghbuild.halo.msg.RxClick
import com.fghbuild.halo.msg.TxCode
import com.fghbuild.halo.msg.TxMessage
import com.fghbuild.halo.msg.TxTextPage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// Message codes that Go Mode's Halo app, assets/halo/main.lua, handles.
internal const val HALO_TEXT_PAGE = 0x20
internal const val HALO_CLEAR = 0x21

/** Go Mode's Halo app: its main.lua asset and the halo-sdk Lua libraries it requires. */
internal const val HALO_APP_MAIN = "halo/main.lua"
internal val HALO_APP_LIBS = listOf("data.min.lua", "sprite.min.lua", "text_page.lua")

// About eight lines fit the circle; large enough to read at a glance.
private const val HALO_FONT_SIZE_PX = 24

// How Go Mode's pages lay text out in Halo's circle.
private val HALO_LAYOUT = CircularTextLayout(HaloDisplay.SIZE_PX, HaloDisplay.SIZE_PX, HALO_FONT_SIZE_PX)

/** One page of [haloTextMessages]: Halo shows a text whole when its first page holds all of it. */
internal data object HaloScreenFit : ScreenFit {
    override fun fits(text: String): Boolean = HALO_LAYOUT.fitsOnePage(text)
}

/** Halo's display as MCP requests declare it: a circle whose sprites choose 16 colours from 24-bit RGB. */
internal val HALO_DISPLAY =
    Display(
        width = HaloDisplay.SIZE_PX,
        height = HaloDisplay.SIZE_PX,
        shape = DisplayShape.Circle,
        colorSpace = DisplayColorSpace.RGB,
        bitsPerPixel = bitsPerPixel(HaloDisplay.COLORS),
    )

/**
 * The typed messages that make Go Mode's Halo app show [text]: the first page laid out in the
 * display's circle, or a clear when [text] is blank.
 */
internal fun haloTextMessages(text: String): List<Pair<Int, TxMessage>> {
    val page = TxTextPage(HALO_LAYOUT, text).rasterizeNextPage() ?: return listOf(HALO_CLEAR to TxCode(0))
    return listOf(HALO_TEXT_PAGE to page) + page.rasterizedSprites.map { HALO_TEXT_PAGE to it }
}

/**
 * The presses in the data of Go Mode's Halo app, which reports each single press of the button.
 * They end with the link, whose end the device data reports as a [HaloException].
 */
internal fun haloPresses(data: Flow<ByteArray>): Flow<Unit> =
    RxClick()
        .attach(data)
        .filter { it == ClickType.SINGLE }
        .map { }
        .catch { if (it !is HaloException) throw it }

internal class HaloGlasses private constructor(
    private val connection: HaloConnection,
    private val app: HalosideApp,
    override val closeCause: StateFlow<Exception?>,
    override val presses: Flow<Unit>,
) : GlassesDisplay {
    override val description = HALO_DISPLAY
    override val screenFit = HaloScreenFit

    override suspend fun showText(text: String) {
        try {
            haloTextMessages(text).forEach { (code, message) -> app.send(code, message) }
        } catch (e: HaloException) {
            throw GlassesException("Halo did not take the text: ${e.message}", e, problem = GlassesProblem.UNRESPONSIVE)
        }
    }

    override suspend fun close() = connection.disconnect()

    companion object {
        /**
         * Connects [connection] to [device] and starts Go Mode's app on it, replacing whatever app
         * ran there. A [background] connection waits for the Halo to advertise; see
         * [HaloConnection.connect]. The caller holds BLUETOOTH_CONNECT. A failure or cancellation
         * disconnects.
         */
        suspend fun connect(
            context: Context,
            connection: HaloConnection,
            device: BluetoothDevice,
            background: Boolean,
        ): HaloGlasses {
            var glasses: HaloGlasses? = null
            try {
                val halo = connection.connect(device, background = background)
                if (halo.type != HaloDeviceType.HALO) {
                    throw GlassesException(
                        "Go Mode supports Halo only; this device is a Brilliant Frame",
                        permanent = true,
                    )
                }
                val app = HalosideApp(context, halo)
                val main =
                    context.assets
                        .open(HALO_APP_MAIN)
                        .bufferedReader()
                        .use { it.readText() }
                app.start(main, HALO_APP_LIBS)
                return HaloGlasses(connection, app, halo.closeCause, haloPresses(halo.dataResponse)).also {
                    glasses = it
                }
            } catch (e: HaloException) {
                throw GlassesException("Could not start Go Mode on Halo: ${e.message}", e)
            } catch (e: SecurityException) {
                throw GlassesException("Bluetooth permission is missing: ${e.message}", e)
            } finally {
                if (glasses == null) withContext(NonCancellable) { connection.disconnect() }
            }
        }
    }
}
