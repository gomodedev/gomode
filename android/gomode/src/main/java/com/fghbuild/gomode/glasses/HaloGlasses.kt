// Halo's GlassesDisplay: Go Mode's Lua app on the glasses draws text pages rasterized on the phone.
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
import com.fghbuild.halo.msg.HaloDisplay
import com.fghbuild.halo.msg.HalosideApp
import com.fghbuild.halo.msg.TxCode
import com.fghbuild.halo.msg.TxMessage
import com.fghbuild.halo.msg.TxTextPage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

// Message codes that Go Mode's Halo app, assets/halo/main.lua, handles.
internal const val HALO_TEXT_PAGE = 0x20
internal const val HALO_CLEAR = 0x21

/** Go Mode's Halo app: its main.lua asset and the halo-sdk Lua libraries it requires. */
internal const val HALO_APP_MAIN = "halo/main.lua"
internal val HALO_APP_LIBS = listOf("data.min.lua", "sprite.min.lua", "text_page.lua")

// About eight lines fit the circle; large enough to read at a glance.
internal const val HALO_FONT_SIZE_PX = 24

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
    val layout = CircularTextLayout(HaloDisplay.SIZE_PX, HaloDisplay.SIZE_PX, HALO_FONT_SIZE_PX)
    val page = TxTextPage(layout, text).rasterizeNextPage() ?: return listOf(HALO_CLEAR to TxCode(0))
    return listOf(HALO_TEXT_PAGE to page) + page.rasterizedSprites.map { HALO_TEXT_PAGE to it }
}

internal class HaloGlasses private constructor(
    private val connection: HaloConnection,
    private val app: HalosideApp,
    override val closeCause: StateFlow<Exception?>,
) : GlassesDisplay {
    override val description = HALO_DISPLAY

    override suspend fun showText(text: String) {
        try {
            haloTextMessages(text).forEach { (code, message) -> app.send(code, message) }
        } catch (e: HaloException) {
            throw GlassesException("Halo did not take the text: ${e.message}", e)
        }
    }

    override suspend fun close() = connection.disconnect()

    companion object {
        /**
         * Connects [connection] to [device] and starts Go Mode's app on it, replacing whatever app
         * ran there. The caller holds BLUETOOTH_CONNECT. A failure or cancellation disconnects.
         */
        suspend fun connect(
            context: Context,
            connection: HaloConnection,
            device: BluetoothDevice,
        ): HaloGlasses {
            var glasses: HaloGlasses? = null
            try {
                val halo = connection.connect(device)
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
                return HaloGlasses(connection, app, halo.closeCause).also { glasses = it }
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
