// Tests EvenG2Glasses against fake G2 firmware: the display each firmware gets, and the GlassesException each close cause becomes.
package com.fghbuild.gomode.glasses

import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2Glasses
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.testing.FakeG2Firmware
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val PAIR =
    G2Pair(
        "7",
        G2Arm(G2Side.LEFT, "00:11:22:33:55:01", "Even G2_7_L_335501"),
        G2Arm(G2Side.RIGHT, "00:11:22:33:55:02", "Even G2_7_R_335502"),
    )

class EvenG2GlassesTest {
    @Test
    fun closingReportsTheDisconnectionAfterClosingThePageAndBothArms() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses =
                EvenG2Glasses.connect(
                    G2Glasses(firmware.connector, backgroundScope),
                    PAIR,
                    background = false,
                    backgroundScope,
                )
            assertEquals(null, glasses.closeCause.value)

            glasses.close()
            val cause = glasses.closeCause.value as GlassesException
            assertEquals("Disconnected", cause.message)
            // Nothing retries a connection Go Mode closed.
            assertTrue(cause.permanent)
            assertEquals("shutdown", firmware.requests.last().kind)
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun faceclawFirmwareGetsTheWholePanelWithImages() =
        runTest {
            val firmware = FakeG2Firmware().apply { revision = "Faceclaw/45" }
            val glasses = connect(firmware)
            assertTrue(glasses is GlassesImageDisplay)
            assertEquals(G2_FACECLAW_DISPLAY, glasses.description)
            assertEquals(G2PanelFit, glasses.screenFit)
            glasses.showText("Hello")
            assertEquals(listOf("Hello"), firmware.presented.last().lines)

            glasses.close()
            assertEquals(emptySet<G2Side>(), firmware.leased)
            assertEquals("shutdown", firmware.requests.last().kind)
        }

    @Test
    fun stockFirmwareGetsOneTextContainerWithoutImages() =
        runTest {
            val glasses = connect(FakeG2Firmware())
            assertFalse(glasses is GlassesImageDisplay)
            assertEquals(G2_DISPLAY, glasses.description)
        }

    @Test
    fun aSessionThatEndsBeforeItIsReadyFailsTheConnection() {
        val cases =
            listOf(
                Ending("unreachable arm", Outcome.RETRY_UNREACHABLE) { reachable = { it == G2Side.LEFT } },
                Ending("connector exception", Outcome.PERMANENT) { failure = { SecurityException("denied") } },
                Ending("unauthenticated arm", Outcome.RETRY_UNRESPONSIVE) { authenticates = { it == G2Side.LEFT } },
                Ending("unanswered prelude", Outcome.RETRY_UNRESPONSIVE) { answersPrelude = false },
                Ending("unanswered create", Outcome.RETRY_UNRESPONSIVE) { answersCreate = false },
                Ending("refused create", Outcome.RETRY_UNRESPONSIVE) { createResult = 1 },
                Ending("unacknowledged frame", Outcome.RETRY_UNRESPONSIVE) {
                    revision = "Faceclaw/45"
                    answersStream = false
                },
            )
        for (case in cases) {
            runTest {
                val firmware = FakeG2Firmware().apply(case.setUp)
                val failure =
                    try {
                        connect(firmware)
                        null
                    } catch (e: GlassesException) {
                        e
                    }
                assertNotNull(case.name, failure)
                assertEquals(case.name, case.outcome, failure?.outcome())
                assertTrue(case.name, firmware.links.all { it.closed })
            }
        }
    }

    @Test
    fun aSessionThatEndsAfterItIsReadySetsTheCloseCause() {
        val cases =
            listOf(
                Ending("dropped right arm", Outcome.RETRY_UNREACHABLE) { link(G2Side.RIGHT).drop(status = 8) },
                Ending("dropped left arm", Outcome.RETRY_UNREACHABLE) { link(G2Side.LEFT).drop(status = 19) },
                Ending("refused write", Outcome.RETRY_UNREACHABLE) { link(G2Side.RIGHT).writeFailure = "busy" },
                Ending("unanswered update", Outcome.RETRY_UNRESPONSIVE) { answersUpdates = false },
                Ending("abnormal page exit", Outcome.RETRY_UNRESPONSIVE) { exitPage(type = 6, session = pageSession) },
                Ending("wearer page exit", Outcome.PERMANENT) { exitPage(type = 7, session = pageSession) },
            )
        for (case in cases) {
            runTest {
                val firmware = FakeG2Firmware()
                val glasses = connect(firmware)
                firmware.apply(case.setUp)
                // An update makes a write or an unanswered update end the session; it is harmless otherwise.
                backgroundScope.launch { runCatching { glasses.showText("next") } }
                val cause = glasses.closeCause.filterNotNull().first() as GlassesException
                assertEquals(case.name, case.outcome, cause.outcome())
                glasses.close()
                assertEquals(case.name, cause, glasses.closeCause.value)
                assertTrue(case.name, firmware.links.all { it.closed })
            }
        }
    }

    private suspend fun TestScope.connect(firmware: FakeG2Firmware): EvenG2Glasses =
        EvenG2Glasses.connect(G2Glasses(firmware.connector, backgroundScope), PAIR, background = false, backgroundScope)

    private class Ending(
        val name: String,
        val outcome: Outcome,
        val setUp: FakeG2Firmware.() -> Unit,
    )

    private enum class Outcome { RETRY_UNREACHABLE, RETRY_UNRESPONSIVE, PERMANENT }

    private fun GlassesException.outcome(): Outcome =
        when {
            permanent -> Outcome.PERMANENT
            problem == GlassesProblem.UNREACHABLE -> Outcome.RETRY_UNREACHABLE
            else -> Outcome.RETRY_UNRESPONSIVE
        }
}
