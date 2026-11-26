// G2Glasses sessions against fake arm links: both arms, heartbeat, page return, close causes, retryability, races.

package com.fghbuild.eveng2

import com.fghbuild.eveng2.testing.FakeG2Firmware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // Virtual time: advanceTimeBy, runCurrent, currentTime.
class G2GlassesTest {
    private fun pair(id: String) =
        G2Pair(
            id,
            G2Arm(G2Side.LEFT, "$id-L", "Even G2_${id}_L_000001"),
            G2Arm(G2Side.RIGHT, "$id-R", "Even G2_${id}_R_000002"),
        )

    private val pair = pair("1")

    private fun ready(
        pair: G2Pair,
        text: String,
    ) = G2State.Ready(pair, G2Firmware.EvenHub(null), G2Content.Text(text))

    private suspend fun G2Glasses.await(predicate: (G2State) -> Boolean): G2State = state.first(predicate)

    private suspend fun G2Glasses.closeCause(): G2CloseCause = (await { it is G2State.Closed } as G2State.Closed).cause

    private fun TestScope.record(glasses: G2Glasses): List<G2State> {
        val states = mutableListOf<G2State>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { glasses.state.toList(states) }
        return states
    }

    @Test
    fun opensThePageThroughBothArmsAndUpdatesItInPlace() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.show("Hello")
            glasses.connect(pair)
            assertEquals(ready(pair, "Hello"), glasses.await { it is G2State.Ready })
            assertEquals(
                listOf("RIGHT auth", "LEFT auth", "RIGHT prelude", "RIGHT settings", "RIGHT create Hello"),
                firmware.requests.map { it.toString() },
            )

            firmware.requests.clear()
            advanceTimeBy(3 * G2Protocol.HEARTBEAT_INTERVAL_MS + 1)
            assertEquals(List(3) { "RIGHT heartbeat" }, firmware.requests.map { it.toString() })

            firmware.requests.clear()
            glasses.show("Updated")
            assertEquals(ready(pair, "Updated"), glasses.await { it == ready(pair, "Updated") })
            assertEquals(listOf("RIGHT update Updated"), firmware.requests.map { it.toString() })
            assertTrue(firmware.links.none { it.closed })
        }

    @Test
    fun aBackgroundSessionWaitsForBothArmsWithoutADeadline() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.reachable = { false }
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair, background = true)
            advanceTimeBy(60 * 60_000L)
            assertEquals(G2State.Connecting(pair), glasses.state.value)
            assertEquals(2, firmware.waiting.value)

            firmware.reachable = { true }
            glasses.await { it is G2State.Ready }
            assertEquals(listOf(G2Side.LEFT, G2Side.RIGHT), firmware.backgroundAttempts.sorted())
            assertEquals(0, firmware.waiting.value)
        }

    @Test
    fun aBackgroundSessionGivesTheOtherArmTheConnectDeadlineOnceOneArmIsUp() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.reachable = { false }
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair, background = true)
            advanceTimeBy(60_000)
            val start = currentTime

            firmware.reachable = { it == G2Side.RIGHT }
            val cause = glasses.closeCause()
            assertEquals(G2CloseCause.ConnectFailed(G2Side.LEFT, "timed out"), cause)
            assertTrue(cause.retryable)
            assertEquals(start + G2Protocol.CONNECT_TIMEOUT_MS, currentTime)
            // The right arm's link and the left arm's pending connection are both released.
            assertTrue(firmware.links.single().closed)
            assertEquals(0, firmware.waiting.value)
        }

    @Test
    fun textBeyondTheCreationLimitArrivesByUpdate() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            val long = "é".repeat(600) // 1,200 UTF-8 bytes.
            glasses.show(long)
            glasses.connect(pair)
            glasses.await { it == ready(pair, long) }
            assertEquals(listOf("RIGHT create ", "RIGHT update $long"), firmware.requests.drop(4).map { it.toString() })
        }

    @Test
    fun authTimeoutEndsTheSessionAndClosesBothLinks() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.authenticates = { it == G2Side.LEFT }
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            val cause = glasses.closeCause()
            assertEquals(G2CloseCause.AuthTimedOut(G2Side.RIGHT), cause)
            assertTrue(cause.retryable)
            assertEquals(G2Protocol.AUTH_TIMEOUT_MS, currentTime)
            // AUTH is re-sent until the deadline; nothing follows it.
            assertEquals(List(6) { "RIGHT auth" }, firmware.requests.map { it.toString() })
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun rightArmDropEndsTheSessionAndTheNextConnectShowsTheLatestText() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.show("First")
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            val firstPage = firmware.pageSession
            firmware.requests.clear()

            firmware.link(G2Side.RIGHT).drop(status = 8)
            val cause = glasses.closeCause()
            assertEquals(G2CloseCause.Disconnected(G2Side.RIGHT, 8), cause)
            assertTrue(cause.retryable)
            // The right link is gone, so no shutdown is attempted; the left link is released.
            assertEquals(emptyList<String>(), firmware.requests.map { it.toString() })
            assertTrue(firmware.links.all { it.closed })
            // The host owns retries: the SDK opens nothing on its own.
            advanceTimeBy(60_000)
            assertEquals(2, firmware.links.size)

            glasses.show("Second")
            glasses.connect(pair)
            assertEquals(G2State.Connecting(pair), glasses.state.value)
            assertEquals(ready(pair, "Second"), glasses.await { it is G2State.Ready })
            assertEquals("RIGHT create Second", firmware.requests.last().toString())
            assertTrue(firmware.pageSession != firstPage)
        }

    @Test
    fun leftArmDropShutsThePageDownThroughTheRightArm() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            firmware.requests.clear()

            firmware.link(G2Side.LEFT).drop(status = 19)
            assertEquals(G2CloseCause.Disconnected(G2Side.LEFT, 19), glasses.closeCause())
            assertEquals(listOf("RIGHT shutdown"), firmware.requests.map { it.toString() })
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun disconnectShutsThePageDownAndStops() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            firmware.requests.clear()

            glasses.disconnect()
            assertEquals(G2State.Closed(G2CloseCause.Stopped), glasses.state.value)
            assertFalse(G2CloseCause.Stopped.retryable)
            assertEquals(listOf("RIGHT shutdown"), firmware.requests.map { it.toString() })
            assertTrue(firmware.links.all { it.closed })
            advanceTimeBy(60_000)
            assertEquals(2, firmware.links.size)
        }

    @Test
    fun eachExitOfThePageOpensANewPageWithTheLatestText() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            val states = record(glasses)
            val sent = { firmware.requests.filter { it.kind != "heartbeat" }.map { it.toString() } }
            glasses.show("First")
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            advanceTimeBy(G2Protocol.PAGE_SETTLED_MS)
            val first = firmware.pageSession
            firmware.requests.clear()

            // An exit of another page session is not this page's.
            firmware.exitPage(type = G2InputEvent.EXIT, session = first + 1)
            runCurrent()
            assertEquals(emptyList<String>(), sent())

            // The glasses closed the page, so none is shut down; a page that settled returns at once.
            firmware.exitPage(type = G2InputEvent.EXIT, session = first)
            runCurrent()
            assertEquals(listOf("RIGHT create First"), sent())
            val second = firmware.pageSession
            assertTrue(second != first)

            // An abnormal exit too; text shown while no page is open goes on the next one.
            firmware.exitPage(type = G2InputEvent.ABNORMAL_EXIT, session = second)
            runCurrent()
            glasses.show("Second")
            advanceTimeBy(G2Protocol.PAGE_RETURN_DELAY_MS)
            assertEquals(listOf("RIGHT create First"), sent())
            assertEquals(ready(pair, "Second"), glasses.await { it == ready(pair, "Second") })
            assertEquals("RIGHT create Second", sent().last())
            assertTrue(firmware.pageSession != second)
            assertTrue(states.none { it is G2State.Closed })
            assertEquals(2, firmware.links.size)
            assertTrue(firmware.links.none { it.closed })
        }

    @Test
    fun aPageThatKeepsClosingReturnsAfterDoublingPauses() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            val presses = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { glasses.presses.toList(presses) }
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            val creates = { firmware.requests.count { it.kind == "create" } }

            for (pause in listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L)) {
                firmware.exitPage(type = G2InputEvent.EXIT, session = firmware.pageSession)
                runCurrent()
                firmware.requests.clear()
                // No page is open during the pause, so a click there is no press.
                firmware.touch(G2Side.RIGHT, type = 0)
                advanceTimeBy(pause - 1)
                runCurrent()
                assertEquals(0, creates())
                // Nor does a heartbeat go out, though a pause of 8 s spans two intervals.
                assertEquals(emptyList<String>(), firmware.requests.map { it.toString() })
                advanceTimeBy(1)
                runCurrent()
                assertEquals(listOf("RIGHT create "), firmware.requests.map { it.toString() })
            }
            assertEquals(emptyList<Unit>(), presses)

            // A page that stays open long enough to settle has heartbeats and resets the pause.
            firmware.requests.clear()
            advanceTimeBy(G2Protocol.PAGE_SETTLED_MS)
            assertEquals(2, firmware.requests.count { it.kind == "heartbeat" })
            firmware.exitPage(type = G2InputEvent.EXIT, session = firmware.pageSession)
            runCurrent()
            assertEquals(1, creates())
            assertTrue(glasses.state.value is G2State.Ready)
        }

    @Test
    fun aLinkDropDuringThePauseEndsTheSession() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            firmware.exitPage(type = G2InputEvent.EXIT, session = firmware.pageSession)
            runCurrent()
            val paused = currentTime
            firmware.requests.clear()

            firmware.link(G2Side.LEFT).drop(status = 19)
            assertEquals(G2CloseCause.Disconnected(G2Side.LEFT, 19), glasses.closeCause())
            assertEquals(paused, currentTime)
            // No page is open, so none is shut down.
            assertEquals(emptyList<String>(), firmware.requests.map { it.toString() })
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun clicksFromEitherTempleAndTheRingArePressesThatKeepThePageOpen() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            val presses = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { glasses.presses.toList(presses) }
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            firmware.requests.clear()

            firmware.touch(G2Side.RIGHT, type = 0)
            firmware.touch(G2Side.LEFT, type = 0)
            firmware.systemInput(G2Side.RIGHT, type = 0, source = 2)
            runCurrent()
            assertEquals(3, presses.size)

            // Double clicks, scrolls, and long presses are not presses.
            for (type in listOf(1L, 2L, 3L, 9L, 10L)) {
                firmware.touch(G2Side.RIGHT, type)
                firmware.systemInput(G2Side.RIGHT, type, source = 2)
            }
            // A system click names its touchpad or the ring; one of an unspecified source is not a press.
            firmware.systemInput(G2Side.RIGHT, type = 0, source = 0)
            firmware.systemInput(G2Side.RIGHT, type = 0, source = 4)
            runCurrent()
            assertEquals(3, presses.size)
            assertTrue(glasses.state.value is G2State.Ready)
            assertEquals(emptyList<String>(), firmware.requests.map { it.toString() })
            assertTrue(firmware.links.none { it.closed })
        }

    @Test
    fun eachInputAndSystemEventButImuSamplesIsReportedWithItsArmAndSource() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            val events = mutableListOf<G2InputEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { glasses.events.toList(events) }
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            // Proto3 omits a zero type and source: a click, of an unspecified source.
            firmware.touch(G2Side.LEFT, type = 0)
            firmware.systemInput(G2Side.RIGHT, type = G2InputEvent.RELEASE, source = G2InputEvent.SOURCE_LEFT_TEMPLE)
            firmware.systemInput(G2Side.RIGHT, type = G2InputEvent.IMU_DATA, source = 0)
            firmware.systemInput(G2Side.LEFT, type = 0, source = 0)
            firmware.exitPage(type = G2InputEvent.EXIT, session = firmware.pageSession)
            runCurrent()
            // Each arm's events keep their order; the two arms' links deliver independently.
            assertEquals(
                mapOf(
                    G2Side.LEFT to
                        listOf(
                            G2InputEvent(G2Side.LEFT, G2InputEvent.CLICK, source = null),
                            G2InputEvent(G2Side.LEFT, G2InputEvent.CLICK, G2InputEvent.SOURCE_UNSPECIFIED),
                        ),
                    G2Side.RIGHT to
                        listOf(
                            G2InputEvent(G2Side.RIGHT, G2InputEvent.RELEASE, G2InputEvent.SOURCE_LEFT_TEMPLE),
                            G2InputEvent(G2Side.RIGHT, G2InputEvent.EXIT, G2InputEvent.SOURCE_UNSPECIFIED),
                        ),
                ),
                events.groupBy { it.arm },
            )
        }

    @Test
    fun unansweredUpdateTimesOut() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.answersUpdates = false
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            val sent = currentTime
            glasses.show("Lost")
            val cause = glasses.closeCause()
            assertEquals(G2CloseCause.CommandTimedOut(G2HubCommand.UPDATE_TEXT), cause)
            assertTrue(cause.retryable)
            assertEquals(G2Protocol.COMMAND_TIMEOUT_MS, currentTime - sent)
        }

    @Test
    fun failedWriteEndsTheSession() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            firmware.link(G2Side.RIGHT).writeFailure = "busy"
            glasses.show("Lost")
            val cause = glasses.closeCause()
            assertEquals(G2CloseCause.WriteFailed(G2Side.RIGHT, "busy"), cause)
            assertTrue(cause.retryable)
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun bothArmsBondOneAtATimeBeforeEitherConnects() =
        runTest {
            val firmware = FakeG2Firmware()
            val bondsAtFirstConnect = mutableListOf<G2Side>()
            firmware.connected = { if (bondsAtFirstConnect.isEmpty()) bondsAtFirstConnect += firmware.bonds }
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            assertEquals(listOf(G2Side.RIGHT, G2Side.LEFT), bondsAtFirstConnect)
        }

    @Test
    fun failedPairingEndsTheSessionForGoodBeforeTheOtherArmBonds() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.bondFailure = { if (it == G2Side.RIGHT) G2Exception("refused") else null }
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            val cause = glasses.closeCause()
            assertEquals(G2CloseCause.PairingFailed(G2Side.RIGHT, "refused"), cause)
            assertFalse(cause.retryable)
            // The left arm is not asked to pair, and neither arm connects.
            assertEquals(listOf(G2Side.RIGHT), firmware.bonds)
            assertTrue(firmware.attempts.isEmpty())
        }

    @Test
    fun unreachableArmEndsTheSessionAsAConnectFailure() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.reachable = { it == G2Side.LEFT }
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            val cause = glasses.closeCause()
            assertEquals(G2CloseCause.ConnectFailed(G2Side.RIGHT, "unreachable"), cause)
            assertTrue(cause.retryable)
            assertEquals(listOf(G2Side.LEFT), firmware.links.map { it.side })
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun unansweredPreludeTimesOut() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.answersPrelude = false
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            val cause = glasses.closeCause()
            assertEquals(G2CloseCause.PreludeTimedOut, cause)
            assertTrue(cause.retryable)
            assertEquals(G2Protocol.COMMAND_TIMEOUT_MS, currentTime)
            // No page was requested, so none is shut down.
            assertEquals(listOf("RIGHT auth", "LEFT auth", "RIGHT prelude"), firmware.requests.map { it.toString() })
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun cancellationAsAnArmConnectsClosesItsLink() =
        runTest {
            val firmware = FakeG2Firmware()
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            // The owner's scope is cancelled while connect() returns an open link.
            firmware.connected = { scope.cancel() }
            val glasses = G2Glasses(firmware.connector, scope)
            glasses.connect(pair)
            runCurrent()
            assertTrue(firmware.links.isNotEmpty())
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun unansweredCreateShutsThePageDown() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.answersCreate = false
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            assertEquals(G2CloseCause.CommandTimedOut(G2HubCommand.CREATE_PAGE), glasses.closeCause())
            // The create request may have opened the page although its ACK never came.
            assertEquals(listOf("RIGHT create ", "RIGHT shutdown"), firmware.requests.drop(4).map { it.toString() })
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun refusedCreateShutsThePageDown() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.createResult = 1
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            val cause = glasses.closeCause()
            assertEquals(G2CloseCause.CommandRejected(G2HubCommand.CREATE_PAGE, 1), cause)
            assertTrue(cause.retryable)
            assertEquals(listOf("RIGHT create ", "RIGHT shutdown"), firmware.requests.drop(4).map { it.toString() })
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun unexpectedConnectorExceptionEndsTheSessionForGood() =
        runTest {
            val firmware = FakeG2Firmware()
            val failure = SecurityException("BLUETOOTH_CONNECT")
            firmware.failure = { side -> failure.takeIf { side == G2Side.RIGHT } }
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            val cause = glasses.closeCause()
            assertFalse(cause.retryable)
            // Coroutine stack trace recovery may hand over a copy of the exception.
            val error = (cause as G2CloseCause.Failed).error
            assertEquals(SecurityException::class, error::class)
            assertEquals(failure.message, error.message)
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun connectDuringDisconnectWaitsForTheStoppingSession() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.answersShutdown = false
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }

            // The shutdown goes unanswered, so the stopping session lingers for its timeout.
            val stopping = launch { glasses.disconnect() }
            runCurrent()
            glasses.connect(pair)
            glasses.await { it is G2State.Ready && firmware.links.size == 4 }
            stopping.join()
            assertEquals(2, firmware.maxOpen)
            assertTrue(glasses.state.value is G2State.Ready)
        }

    @Test
    fun replacedConnectionPublishesNothing() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            val states = record(glasses)
            val other = pair("2")
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            val replacedAt = states.size

            glasses.connect(other)
            assertEquals(ready(other, ""), glasses.await { it is G2State.Ready && it.pair == other })
            val after = states.drop(replacedAt)
            val pairs = after.map { (it as? G2State.Connecting)?.pair ?: (it as? G2State.Ready)?.pair }
            assertEquals(after.toString(), List(after.size) { other }, pairs)
            assertTrue(firmware.links.filter { it.arm.address.startsWith("1-") }.all { it.closed })
            assertEquals(
                "RIGHT shutdown",
                firmware.requests.first { it.address == "1-R" && it.kind == "shutdown" }.toString(),
            )
        }
}
