// Runs Go Mode's Halo app once after a stop in the Brilliant Halo Lua emulator: it draws text and reports presses.
//
// The test starts scripts/halo_emulator_bridge.py through uv, so it needs uv on PATH and, on uv's first run, network
// access to fetch the pinned emulator:
//   ./gradlew :gomode:testDebugUnitTest --tests '*HaloEmulatorRenderingTest'
// The bridge's stderr goes to build/halo-emulator/bridge.log, the frame of a two-line page to
// build/halo-emulator/short.png, and the frame of a transcript fitted to one page to build/halo-emulator/text.png.
package com.fghbuild.gomode.glasses

import android.graphics.BitmapFactory
import android.graphics.Color
import com.fghbuild.gomode.voice.TranscriptEntry
import com.fghbuild.gomode.voice.TranscriptSpeaker
import com.fghbuild.gomode.voice.VoiceState
import com.fghbuild.halo.ble.HaloEmulatorBridgeClient
import com.fghbuild.halo.ble.HaloEmulatorEvent
import com.fghbuild.halo.msg.HaloDisplay
import com.fghbuild.halo.msg.TextPageData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.hypot

private const val ACK_TIMEOUT_MS = 2_000L
private const val FRAME_TIMEOUT_MS = 5_000L
private const val BRIDGE_STOP_TIMEOUT_S = 10L

// data.lua's acknowledgement of a typed message, as the app passes it to frame.bluetooth.send.
private val DATA_ACK = byteArrayOf(0x01, 0x00, 0x00)

// A cold uv cache fetches and builds the pinned emulator before the bridge listens: about 16 s locally.
private const val BRIDGE_START_TIMEOUT_MIN = 5L

// Relative to the module directory, Gradle's working directory for unit tests.
private const val BRIDGE_SCRIPT = "../../scripts/halo_emulator_bridge.py"
private val OUTPUT_DIR = File("build/halo-emulator")

// Wide capitals end the reply, so the fitted page is full and its widest lines matter.
private val FITTED_TRANSCRIPT =
    VoiceState(
        connected = true,
        transcript =
            listOf(
                TranscriptEntry(TranscriptSpeaker.USER, "What does the sign on the mountain say?", final = true),
                TranscriptEntry(
                    TranscriptSpeaker.ASSISTANT,
                    "It says: WARNING. MAXIMUM WIND ON WOMBAT MOUNTAIN. WALK WITH A MAP AND MOW NO MEADOWS.",
                    final = true,
                ),
            ),
    )

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HaloEmulatorRenderingTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun fittedPagesDrawCentredInsideTheCircleBlankTextClearsAndASinglePressIsReported() {
        OUTPUT_DIR.mkdirs()
        val log = OUTPUT_DIR.resolve("bridge.log")
        // The bridge prints its URL once it listens and exits when its stdin closes.
        val process =
            ProcessBuilder("uv", "run", "--locked", "--script", BRIDGE_SCRIPT, "--port", "0")
                .redirectError(log)
                .start()
        try {
            val firstLine = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }
            val url =
                try {
                    firstLine.get(BRIDGE_START_TIMEOUT_MIN, TimeUnit.MINUTES)
                } catch (_: TimeoutException) {
                    error("$BRIDGE_SCRIPT did not listen within $BRIDGE_START_TIMEOUT_MIN min; see ${log.absolutePath}")
                }
            check(url != null && url.startsWith("ws://")) { "$BRIDGE_SCRIPT did not start; see ${log.absolutePath}" }
            runApp(url)
        } finally {
            process.outputStream.close()
            if (!process.waitFor(BRIDGE_STOP_TIMEOUT_S, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun runApp(url: String) {
        runBlocking {
            HaloEmulatorBridgeClient().use { bridge ->
                bridge.connect(url)
                HALO_APP_LIBS.forEach { bridge.uploadFile(it, asset("halo/$it")) }
                bridge.uploadFile("main.lua", asset(HALO_APP_MAIN))
                // Regression: the emulator queued a stop event that the next start consumed, so after a
                // stop the app had to start twice. One start after a stop must run the app.
                bridge.stop()
                bridge.start("main.lua")

                show(bridge, "Hello\nGo Mode")
                val short = awaitFrame(bridge) { rowBands(it.map { (_, y) -> y }) == 2 }
                OUTPUT_DIR.resolve("short.png").writeBytes(short)
                println("lines: 2 bands of lit rows")
                checkCentred("short", litPixels(short))

                val fitted = glassesTranscriptText(FITTED_TRANSCRIPT, HaloScreenFit)
                val lines = (haloTextMessages(fitted).first().second as TextPageData).lineTexts
                show(bridge, fitted)
                val frame = awaitFrame(bridge) { rowBands(it.map { (_, y) -> y }) == lines.size }
                OUTPUT_DIR.resolve("text.png").writeBytes(frame)
                println("fitted: ${lines.size} bands of lit rows for \"$fitted\"")
                checkCentred("fitted", litPixels(frame))

                show(bridge, " ")
                awaitFrame(bridge) { it.isEmpty() }
                println("blank: the frame cleared")

                checkPresses(bridge)
                bridge.stop()
            }
        }
    }

    // Of the button's gestures and an IMU tap, only the single press reaches HaloGlasses' presses, and
    // the app sends nothing else but late acknowledgements. The emulator reports what the app passes
    // to frame.bluetooth.send, which is what HaloDevice.dataResponse yields once it strips the 0x01
    // that the firmware prepends.
    private suspend fun checkPresses(bridge: HaloEmulatorBridgeClient) {
        bridge.buttonDouble()
        bridge.buttonLong()
        bridge.imuTap()
        bridge.buttonSingle()
        val sent = mutableListOf<List<Byte>>()
        withTimeout(FRAME_TIMEOUT_MS) {
            haloPresses(
                bridge.events
                    .filterIsInstance<HaloEmulatorEvent.BluetoothSent>()
                    .map { it.data }
                    .onEach { if (!it.contentEquals(DATA_ACK)) sent += it.toList() },
            ).first()
        }
        assertEquals(listOf(listOf<Byte>(0x0B, 0x01)), sent)
        println("press: a single press sent 0B 01; double, long, and IMU tap sent nothing")
    }

    // Every lit pixel is inside the display circle and the text sits in its middle, both ways.
    private fun checkCentred(
        name: String,
        text: List<Pair<Int, Int>>,
    ) {
        val center = (HaloDisplay.SIZE_PX - 1) / 2.0
        val outside = text.filter { (x, y) -> hypot(x - center, y - center) > HaloDisplay.SIZE_PX / 2.0 }
        assertTrue("$name: lit pixels outside the circle: $outside", outside.isEmpty())
        val meanX = text.sumOf { it.first } / text.size.toDouble()
        assertTrue("$name: text centred at x=$meanX", abs(meanX - center) < HaloDisplay.SIZE_PX / 16.0)
        val middleY = (text.minOf { it.second } + text.maxOf { it.second }) / 2.0
        assertTrue("$name: text centred at y=$middleY", abs(middleY - center) < HaloDisplay.SIZE_PX / 16.0)
        println("$name: ${text.size} lit pixels inside the circle, centred at x=$meanX, y=$middleY for $center")
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
            error(
                "Halo emulator did not acknowledge message ${index + 1} for \"$text\"; " +
                    "app running: ${bridge.ping().running}",
            )
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
