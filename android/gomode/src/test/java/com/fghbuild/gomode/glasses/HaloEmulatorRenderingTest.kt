// Draws text through Go Mode's Halo app in the Brilliant Halo Lua emulator; skipped without a running bridge.
//
// Start the emulator bridge (caic's scripts/halo-emulator.py --bridge 127.0.0.1:8765), then run
//   HALO_EMULATOR_BRIDGE_URL=ws://127.0.0.1:8765 ./gradlew :gomode:testDebugUnitTest \
//     --tests '*HaloEmulatorRenderingTest' --rerun
// The rendered frame is saved to build/halo-emulator/text.png for inspection.
package com.fghbuild.gomode.glasses

import android.graphics.BitmapFactory
import android.graphics.Color
import com.fghbuild.halo.ble.HaloEmulatorBridgeClient
import com.fghbuild.halo.ble.HaloEmulatorEvent
import com.fghbuild.halo.msg.HaloDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

private const val ACK_TIMEOUT_MS = 2_000L
private const val FRAME_TIMEOUT_MS = 5_000L
private const val STARTUP_SETTLE_MS = 500L

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HaloEmulatorRenderingTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun textPagesDrawCenteredInsideTheCircleAndBlankTextClears() {
        val url = System.getenv("HALO_EMULATOR_BRIDGE_URL")
        assumeTrue("HALO_EMULATOR_BRIDGE_URL names no Halo emulator bridge", !url.isNullOrBlank())
        runBlocking {
            HaloEmulatorBridgeClient().use { bridge ->
                bridge.connect(requireNotNull(url))
                HALO_APP_LIBS.forEach { bridge.uploadFile(it, asset("halo/$it")) }
                bridge.uploadFile("main.lua", asset(HALO_APP_MAIN))
                startApp(bridge)

                show(bridge, "Hello\nGo Mode")
                val frame = awaitFrame(bridge) { it.isNotEmpty() }
                File("build/halo-emulator").apply { mkdirs() }.resolve("text.png").writeBytes(frame)
                val text = litPixels(frame)
                val center = (HaloDisplay.SIZE_PX - 1) / 2.0
                val outside = text.filter { (x, y) -> hypot(x - center, y - center) > HaloDisplay.SIZE_PX / 2.0 }
                assertTrue("lit pixels outside the circle: $outside", outside.isEmpty())
                println("circle: ${text.size} lit pixels, none outside the display circle")
                val meanX = text.sumOf { it.first } / text.size.toDouble()
                assertTrue("text centred at x=$meanX", abs(meanX - center) < HaloDisplay.SIZE_PX / 16.0)
                println("centring: mean lit x=$meanX for centre $center")
                assertEquals("lines drawn", 2, rowBands(text.map { it.second }))
                println("lines: 2 bands of lit rows")

                show(bridge, " ")
                awaitFrame(bridge) { it.isEmpty() }
                println("blank: the frame cleared")
                bridge.stop()
            }
        }
    }

    // The emulator's stop() queues a stop event that a script must consume, and start() does not drain
    // the queue: after a stop with no script running, the next script ends at its first frame.sleep.
    // That stale event is spent once, so a second start runs.
    private suspend fun startApp(bridge: HaloEmulatorBridgeClient) {
        repeat(2) {
            bridge.start("main.lua")
            delay(STARTUP_SETTLE_MS)
            if (bridge.ping().running) return
        }
        error("Go Mode's Halo app did not start in the emulator")
    }

    private fun asset(path: String): String =
        context.assets
            .open(path)
            .bufferedReader()
            .use { it.readText() }

    // Sends each message the way HaloGlasses does, waiting for data.lua's acknowledgement. The app
    // registers its receiver after it starts, so the first message repeats until acknowledged.
    private suspend fun show(
        bridge: HaloEmulatorBridgeClient,
        text: String,
    ) {
        haloTextMessages(text).forEachIndexed { index, (code, message) ->
            repeat(if (index == 0) 10 else 1) {
                bridge.sendMessage(code, message.pack())
                val acknowledged =
                    withTimeoutOrNull(
                        ACK_TIMEOUT_MS,
                    ) { bridge.events.filterIsInstance<HaloEmulatorEvent.BluetoothSent>().first() }
                if (acknowledged != null) return@forEachIndexed
            }
            error("Halo emulator did not acknowledge message ${index + 1} for \"$text\"")
        }
    }

    // Polls the framebuffer until its lit pixels satisfy [done], and returns that frame as PNG.
    private suspend fun awaitFrame(
        bridge: HaloEmulatorBridgeClient,
        done: (List<Pair<Int, Int>>) -> Boolean,
    ): ByteArray =
        withTimeout(FRAME_TIMEOUT_MS) {
            var png = bridge.getFramebufferPng()
            while (!done(litPixels(png))) {
                delay(100)
                png = bridge.getFramebufferPng()
            }
            png
        }

    private fun litPixels(png: ByteArray): List<Pair<Int, Int>> =
        buildList {
            val frame = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
            for (y in 0 until frame.height) {
                for (x in 0 until frame.width) {
                    val pixel = frame.getPixel(x, y)
                    if (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > 3 * 128) add(x to y)
                }
            }
        }

    // Counts runs of consecutive lit rows: one per drawn line.
    private fun rowBands(rows: List<Int>): Int {
        val sorted = rows.distinct().sorted()
        return sorted.zipWithNext().count { (a, b) -> b - a > 1 } + if (sorted.isEmpty()) 0 else 1
    }
}
