// G2Glasses sessions against fake glasses on Faceclaw custom firmware: detection, lease, drawing, gestures, failures.

package com.fghbuild.eveng2

import com.fghbuild.eveng2.testing.FakeG2Firmware
import com.fghbuild.eveng2.testing.FakeG2Text
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // Virtual time: advanceTimeBy, runCurrent, currentTime.
class G2FaceclawSessionTest {
    private val pair =
        G2Pair(
            "1",
            G2Arm(G2Side.LEFT, "1-L", "Even G2_1_L_000001"),
            G2Arm(G2Side.RIGHT, "1-R", "Even G2_1_R_000002"),
        )

    private val faceclaw = G2Firmware.Faceclaw(45)

    private fun firmware(revision: String? = "Faceclaw/45") = FakeG2Firmware().also { it.revision = revision }

    private suspend fun G2Glasses.ready(shown: G2Content): G2State =
        state.first {
            it is G2State.Ready &&
                it.shown == shown
        }

    private suspend fun G2Glasses.closeCause(): G2CloseCause =
        (
            state.first {
                it is G2State.Closed
            } as G2State.Closed
        ).cause

    private fun FakeG2Firmware.requestList() = requests.map { it.toString() }

    @Test
    fun drawsTextOnThePanelOverAnEmptyContainer() =
        runTest {
            val firmware = firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.show("Hello\nworld")
            glasses.connect(pair)
            assertEquals(
                G2State.Ready(pair, faceclaw, G2Content.Text("Hello\nworld")),
                glasses.ready(G2Content.Text("Hello\nworld")),
            )
            assertEquals(
                listOf(
                    "RIGHT auth",
                    "LEFT auth",
                    "RIGHT prelude",
                    "RIGHT settings",
                    "RIGHT lease acquire",
                    "LEFT lease acquire",
                    "RIGHT create ",
                    "LEFT root",
                    "LEFT draw",
                    "LEFT present",
                ),
                firmware.requestList(),
            )
            assertEquals(
                listOf(FakeG2Text(0, 0, "Hello"), FakeG2Text(0, 27, "world")),
                firmware.presented.single().texts,
            )

            firmware.requests.clear()
            glasses.show("Updated")
            glasses.ready(G2Content.Text("Updated"))
            assertEquals(listOf("LEFT draw", "LEFT present"), firmware.requestList())
            assertEquals(listOf("Updated"), firmware.presented.last().lines)
        }

    @Test
    fun drawsAnImageCentredOnTheClearedPanel() =
        runTest {
            val firmware = firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.show("Before")
            glasses.connect(pair)
            glasses.ready(G2Content.Text("Before"))

            // A 200 × 100 image whose level is its column modulo 16, on the panel at 220, 190.
            val image = G2Image(200, 100, ByteArray(200 * 100) { (it % 200 % 16).toByte() })
            firmware.requests.clear()
            glasses.show(image)
            glasses.ready(G2Content.Image(image))
            // Rows 0 to 95, then 96 to 99.
            assertEquals(listOf("LEFT draw", "LEFT draw", "LEFT present"), firmware.requestList())
            val frame = firmware.presented.last()
            assertEquals(emptyList<FakeG2Text>(), frame.texts)
            for ((x, y) in listOf(0 to 0, 17 to 50, 199 to 99)) assertEquals(x % 16, frame.level(220 + x, 190 + y))
            for ((x, y) in listOf(219 to 190, 420 to 190, 220 to 189, 220 to 290)) assertEquals(0, frame.level(x, y))
        }

    @Test
    fun renewsTheLeaseOnBothArmsAndReleasesItOnDisconnect() =
        runTest {
            val firmware = firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            glasses.ready(G2Content.Text(""))
            firmware.requests.clear()

            advanceTimeBy(G2Settings.LEASE_RENEW_MS + 1)
            assertEquals(
                listOf("RIGHT lease acquire", "LEFT lease acquire"),
                firmware.requestList().filter { it.contains("lease") },
            )

            firmware.requests.clear()
            glasses.disconnect()
            assertEquals(listOf("RIGHT lease release", "LEFT lease release", "RIGHT shutdown"), firmware.requestList())
            assertEquals(emptySet<G2Side>(), firmware.leased)
        }

    @Test
    fun aLostArmReleasesTheLeaseOnTheOther() =
        runTest {
            val firmware = firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            glasses.ready(G2Content.Text(""))
            firmware.requests.clear()

            firmware.link(G2Side.LEFT).drop(status = 8)
            assertEquals(G2CloseCause.Disconnected(G2Side.LEFT, 8), glasses.closeCause())
            assertEquals(listOf("RIGHT lease release", "RIGHT shutdown"), firmware.requestList())
            assertEquals(setOf(G2Side.LEFT), firmware.leased)
        }

    @Test
    fun anUnacknowledgedFrameEndsTheSession() =
        runTest {
            val firmware = firmware()
            firmware.answersStream = false
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            val cause = glasses.closeCause()
            assertEquals(G2CloseCause.DrawFailed(rejected = false), cause)
            assertTrue(cause.retryable)
            assertTrue(firmware.links.all { it.closed })
            assertEquals(
                listOf("RIGHT lease release", "LEFT lease release", "RIGHT shutdown"),
                firmware.requestList().takeLast(3),
            )
        }

    @Test
    fun aRefusedFrameEndsTheSession() =
        runTest {
            val firmware = firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            glasses.ready(G2Content.Text(""))
            firmware.draws = false
            val sent = currentTime
            glasses.show("Refused")
            assertEquals(G2CloseCause.DrawFailed(rejected = true), glasses.closeCause())
            assertEquals(sent, currentTime)
        }

    @Test
    fun ringClicksArePressesOnceAndOtherGesturesAreNot() =
        runTest {
            val firmware = firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            val presses = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { glasses.presses.toList(presses) }
            glasses.connect(pair)
            glasses.ready(G2Content.Text(""))

            // Report type 1 is a click, sent as system event 0; one within 100 ring ticks repeats it.
            firmware.ringReport(type = 1, event = 0, tick = 1_000)
            firmware.ringReport(type = 1, event = 0, tick = 1_050)
            firmware.ringReport(type = 1, event = 0, tick = 1_200)
            // A ring touch-down and release, a long press, a tap then hold, and the head going up and down.
            firmware.ringReport(type = 10, event = 14, tick = 1_300)
            firmware.ringReport(type = 8, event = 10, tick = 1_400)
            for (type in listOf(9L, 11L, 12L, 15L)) firmware.systemInput(G2Side.RIGHT, type, source = 1)
            firmware.touch(G2Side.LEFT, type = 0)
            runCurrent()
            assertEquals(3, presses.size)
            assertTrue(glasses.state.value is G2State.Ready)
        }

    @Test
    fun otherFirmwareGetsTheStockPage() =
        runTest {
            for (revision in listOf(null, "Faceclaw/44", "EVENCFW/0.3 fb")) {
                val firmware = firmware(revision)
                val glasses = G2Glasses(firmware.connector, backgroundScope)
                val image = G2Image(1, 1, byteArrayOf(15))
                glasses.show(image)
                glasses.connect(pair)
                // Stock firmware shows an image as empty text.
                assertEquals(
                    G2State.Ready(pair, G2Firmware.EvenHub(revision), G2Content.Text("")),
                    glasses.state.first { it is G2State.Ready },
                )
                assertEquals(listOf("RIGHT settings", "RIGHT create "), firmware.requestList().drop(3))
                glasses.disconnect()
            }
        }

    @Test
    fun anUnansweredSettingsReadCountsAsStockFirmware() =
        runTest {
            val firmware = firmware()
            firmware.answersSettings = false
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.show("Hello")
            glasses.connect(pair)
            assertEquals(
                G2State.Ready(pair, G2Firmware.EvenHub(null), G2Content.Text("Hello")),
                glasses.state.first { it is G2State.Ready },
            )
            assertEquals(G2Protocol.COMMAND_TIMEOUT_MS, currentTime)
            assertEquals(listOf("RIGHT settings", "RIGHT create Hello"), firmware.requestList().drop(3))
        }
}
