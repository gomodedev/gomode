// G2Glasses lifecycle against fake arm links: both arms, AUTH failure, heartbeat, close causes, reconnect, races.

package com.fghbuild.eveng2

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

    private suspend fun G2Glasses.await(predicate: (G2State) -> Boolean): G2State = state.first(predicate)

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
            assertEquals(G2State.Ready(pair, "Hello"), glasses.await { it is G2State.Ready })
            assertEquals(
                listOf("RIGHT auth", "LEFT auth", "RIGHT prelude", "RIGHT create Hello"),
                firmware.requests.map { it.toString() },
            )

            firmware.requests.clear()
            advanceTimeBy(3 * G2Protocol.HEARTBEAT_INTERVAL_MS + 1)
            assertEquals(List(3) { "RIGHT heartbeat" }, firmware.requests.map { it.toString() })

            firmware.requests.clear()
            glasses.show("Updated")
            assertEquals(G2State.Ready(pair, "Updated"), glasses.await { it == G2State.Ready(pair, "Updated") })
            assertEquals(listOf("RIGHT update Updated"), firmware.requests.map { it.toString() })
            assertTrue(firmware.links.none { it.closed })
        }

    @Test
    fun textBeyondTheCreationLimitArrivesByUpdate() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            val long = "é".repeat(600) // 1,200 UTF-8 bytes.
            glasses.show(long)
            glasses.connect(pair)
            glasses.await { it == G2State.Ready(pair, long) }
            assertEquals(listOf("RIGHT create ", "RIGHT update $long"), firmware.requests.drop(3).map { it.toString() })
        }

    @Test
    fun authFailureClosesBothLinksAndRetries() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.authenticates = { it == G2Side.LEFT }
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            val reconnecting = glasses.await { it is G2State.Reconnecting }
            assertEquals(G2State.Reconnecting(pair, G2CloseCause.AuthTimedOut(G2Side.RIGHT), 2_000), reconnecting)
            assertEquals(G2Protocol.AUTH_TIMEOUT_MS, currentTime)
            // AUTH is re-sent until the deadline; nothing follows it.
            assertEquals(List(6) { "RIGHT auth" }, firmware.requests.map { it.toString() })
            assertTrue(firmware.links.all { it.closed })

            firmware.authenticates = { true }
            glasses.await { it is G2State.Ready }
            assertEquals(4, firmware.links.size)
        }

    @Test
    fun rightArmDropReconnectsWithTheLatestText() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.show("First")
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            firmware.requests.clear()

            firmware.link(G2Side.RIGHT).drop(status = 8)
            assertEquals(
                G2State.Reconnecting(pair, G2CloseCause.Disconnected(G2Side.RIGHT, 8), 2_000),
                glasses.await { it is G2State.Reconnecting },
            )
            // The right link is gone, so no shutdown is attempted; the left link is released.
            assertEquals(emptyList<String>(), firmware.requests.map { it.toString() })
            assertTrue(firmware.links.all { it.closed })

            glasses.show("Second")
            val dropped = currentTime
            assertEquals(G2State.Ready(pair, "Second"), glasses.await { it is G2State.Ready })
            assertEquals(2_000L, currentTime - dropped)
            assertEquals("RIGHT create Second", firmware.requests.last().toString())
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
            assertEquals(
                G2CloseCause.Disconnected(G2Side.LEFT, 19),
                (glasses.await { it is G2State.Reconnecting } as G2State.Reconnecting).cause,
            )
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
            assertEquals(listOf("RIGHT shutdown"), firmware.requests.map { it.toString() })
            assertTrue(firmware.links.all { it.closed })
            advanceTimeBy(60_000)
            assertEquals(2, firmware.links.size)
        }

    @Test
    fun wearerExitEndsTheConnection() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            glasses.await { it is G2State.Ready }
            firmware.requests.clear()

            // An exit of another page session is not this page's.
            firmware.exitPage(type = 7, session = firmware.pageSession + 1)
            runCurrent()
            assertTrue(glasses.state.value is G2State.Ready)

            firmware.exitPage(type = 7, session = firmware.pageSession)
            assertEquals(
                G2State.Closed(G2CloseCause.PageExited(abnormal = false)),
                glasses.await { it is G2State.Closed },
            )
            advanceTimeBy(60_000)
            assertEquals(2, firmware.links.size)
            assertEquals(emptyList<String>(), firmware.requests.map { it.toString() })
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
            val reconnecting = glasses.await { it is G2State.Reconnecting } as G2State.Reconnecting
            assertEquals(G2CloseCause.CommandTimedOut(G2HubCommand.UPDATE_TEXT), reconnecting.cause)
            assertEquals(G2Protocol.COMMAND_TIMEOUT_MS, currentTime - sent)
        }

    @Test
    fun connectFailuresBackOff() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.reachable = { it == G2Side.LEFT }
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            val states = record(glasses)
            glasses.connect(pair)
            advanceTimeBy(2_000 + 5_000 + 10_000 + 30_000 + 30_000 + 1)
            val reconnects = states.filterIsInstance<G2State.Reconnecting>()
            assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L, 30_000L, 30_000L), reconnects.map { it.delayMs })
            assertTrue(reconnects.all { it.cause == G2CloseCause.ConnectFailed(G2Side.RIGHT, "unreachable") })
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
            val reconnecting = glasses.await { it is G2State.Reconnecting } as G2State.Reconnecting
            assertEquals(G2CloseCause.CommandTimedOut(G2HubCommand.CREATE_PAGE), reconnecting.cause)
            // The create request may have opened the page although its ACK never came.
            assertEquals(listOf("RIGHT create ", "RIGHT shutdown"), firmware.requests.drop(3).map { it.toString() })
            assertTrue(firmware.links.all { it.closed })
        }

    @Test
    fun unexpectedConnectorExceptionClosesTheConnection() =
        runTest {
            val firmware = FakeG2Firmware()
            val failure = SecurityException("BLUETOOTH_CONNECT")
            firmware.failure = { side -> failure.takeIf { side == G2Side.RIGHT } }
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair)
            val closed = glasses.await { it is G2State.Closed } as G2State.Closed
            // Coroutine stack trace recovery may hand over a copy of the exception.
            val error = (closed.cause as G2CloseCause.Failed).error
            assertEquals(SecurityException::class, error::class)
            assertEquals(failure.message, error.message)
            assertTrue(firmware.links.all { it.closed })
            advanceTimeBy(60_000)
            assertEquals(1, firmware.links.size)
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
            assertEquals(G2State.Ready(other, ""), glasses.await { it is G2State.Ready && it.pair == other })
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
