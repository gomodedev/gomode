// G2Glasses' sensors on fake firmware: wear events and query, head up and down, accelerometer, light, compass.

package com.fghbuild.eveng2

import com.fghbuild.eveng2.testing.FakeG2Firmware
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // Virtual time: advanceTimeBy, runCurrent.
class G2SensorsTest {
    private fun pair() =
        G2Pair(
            "1",
            G2Arm(G2Side.LEFT, "1-L", "Even G2_1_L_000001"),
            G2Arm(G2Side.RIGHT, "1-R", "Even G2_1_R_000002"),
        )

    // Collects the sensors of [glasses] until the returned job is cancelled.
    private fun TestScope.read(
        glasses: G2Glasses,
        seen: MutableList<G2Sensors>,
    ) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { glasses.sensors.toList(seen) }

    private fun FakeG2Firmware.imuRequests() = requests.filter { it.kind == "imu" }.map { it.toString() }

    @Test
    fun stockFirmwareReportsEachWearEventWithoutAQuery() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.worn = true
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            val seen = mutableListOf<G2Sensors>()
            read(glasses, seen)
            glasses.connect(pair())
            glasses.state.first { it is G2State.Ready }
            runCurrent()
            assertNull(seen.last().wearing)
            assertTrue(firmware.requests.none { it.kind == "wear query" })

            firmware.wear(false)
            runCurrent()
            assertEquals(false, seen.last().wearing)
            firmware.wear(true)
            runCurrent()
            assertEquals(true, seen.last().wearing)
        }

    @Test
    fun faceclawFirmwareAnswersTheWearQueryAndReportsHeadUpAndDown() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.revision = "Faceclaw/45"
            firmware.worn = true
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            val seen = mutableListOf<G2Sensors>()
            read(glasses, seen)
            glasses.connect(pair())
            glasses.state.first { it is G2State.Ready }
            runCurrent()
            assertEquals(true, seen.last().wearing)

            firmware.systemInput(G2Side.RIGHT, G2InputEvent.HEAD_UP, G2InputEvent.SOURCE_UNSPECIFIED)
            runCurrent()
            assertEquals(G2Head.UP, seen.last().head)
            firmware.systemInput(G2Side.RIGHT, G2InputEvent.HEAD_DOWN, G2InputEvent.SOURCE_UNSPECIFIED)
            runCurrent()
            assertEquals(G2Head.DOWN, seen.last().head)
        }

    @Test
    fun theAccelerometerStreamsWhileCollectedAndItsSamplesAreNoInputEvents() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair())
            glasses.state.first { it is G2State.Ready }
            runCurrent()
            assertEquals(emptyList<String>(), firmware.imuRequests())
            val events = mutableListOf<G2InputEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { glasses.events.toList(events) }

            val seen = mutableListOf<G2Sensors>()
            val reading = read(glasses, seen)
            runCurrent()
            assertEquals(listOf("RIGHT imu on"), firmware.imuRequests())
            firmware.accelerate(0.5f, -0.25f, 1f)
            runCurrent()
            assertEquals(G2Acceleration(0.5f, -0.25f, 1f), seen.last().acceleration)
            assertEquals(emptyList<G2InputEvent>(), events)

            reading.cancel()
            // The start's reply, which no source shows, is awaited first.
            advanceTimeBy(G2Protocol.COMMAND_TIMEOUT_MS + 1)
            assertEquals(listOf("RIGHT imu on", "RIGHT imu off"), firmware.imuRequests())
            assertFalse(firmware.imu)
        }

    @Test
    fun faceclawFirmwareRunsTheLightSensorAndCompassWhileCollected() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.revision = "Faceclaw/45"
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair())
            glasses.state.first { it is G2State.Ready }
            runCurrent()
            val seen = mutableListOf<G2Sensors>()
            val reading = read(glasses, seen)
            // After the accelerometer's start, whose reply is awaited.
            advanceTimeBy(G2Protocol.COMMAND_TIMEOUT_MS + 1)
            assertTrue(firmware.light)
            assertTrue(firmware.compass)
            firmware.shine(123)
            firmware.turn(0)
            runCurrent()
            assertEquals(123L, seen.last().light)
            assertEquals(0, seen.last().heading)

            reading.cancel()
            advanceTimeBy(G2Protocol.COMMAND_TIMEOUT_MS + 1)
            assertFalse(firmware.light)
            assertFalse(firmware.compass)
        }

    @Test
    fun faceclawMotionRunsTheAccelerometerAndCompassWithoutTheLightSensor() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.revision = "Faceclaw/45"
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair())
            glasses.state.first { it is G2State.Ready }
            runCurrent()
            val seen = mutableListOf<G2Sensors>()
            val collecting = UnconfinedTestDispatcher(testScheduler)
            val reading = backgroundScope.launch(collecting) { glasses.motion.toList(seen) }
            advanceTimeBy(G2Protocol.COMMAND_TIMEOUT_MS + 1)
            assertTrue(firmware.imu)
            assertTrue(firmware.compass)
            assertFalse(firmware.light)
            firmware.turn(359)
            runCurrent()
            assertEquals(359, seen.last().heading)

            reading.cancel()
            advanceTimeBy(G2Protocol.COMMAND_TIMEOUT_MS + 1)
            assertFalse(firmware.compass)
            assertTrue(firmware.requests.none { it.kind == "light" })
        }

    @Test
    fun stockFirmwareRunsNeitherTheLightSensorNorTheCompass() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair())
            glasses.state.first { it is G2State.Ready }
            read(glasses, mutableListOf())
            advanceTimeBy(G2Protocol.COMMAND_TIMEOUT_MS + 1)
            assertTrue(firmware.requests.none { it.kind == "light" || it.kind == "compass" })
        }
}
