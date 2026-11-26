// The Even G2 microphone: both arms' packets without copies as liblc3's PCM, and its start and stop by G2Glasses.
//
// The packets carry the frames of G2Lc3Fixture, five each: liblc3's encoding of a synthetic signal.
// The owner's recording from a pair is the check on the glasses' own packets.

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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // Virtual time: advanceTimeBy, runCurrent.
class G2MicrophoneTest {
    private val frames = G2Lc3Fixture.frames
    private val pcm = G2Lc3Fixture.pcm

    // Packet [index]: its five frames, a signal-strength ratio and angle, and [counter].
    private fun packet(
        index: Int,
        counter: Int = index,
    ): ByteArray {
        val lc3 = G2Microphone.FRAMES_PER_PACKET * G2Microphone.FRAME_BYTES
        return frames.copyOfRange(index * lc3, (index + 1) * lc3) + byteArrayOf(0x10, 0, -20, -1, counter.toByte())
    }

    // liblc3's PCM of packets [from] until [to], decoded in a row from the first.
    private fun expected(
        from: Int,
        to: Int,
    ): ShortArray = pcm.copyOfRange(from * G2Microphone.SAMPLES_PER_PACKET, to * G2Microphone.SAMPLES_PER_PACKET)

    private fun List<ShortArray>.joined(): ShortArray {
        val out = ShortArray(sumOf { it.size })
        var at = 0
        for (part in this) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    @Test
    fun theFixtureDecodesAsLiblc3DecodesItWithItsInvalidFrameConcealed() {
        val stream = G2MicrophoneStream()
        val decoded = (0 until PACKETS).map { checkNotNull(stream.accept(G2Side.RIGHT, packet(it))) }
        assertArrayEquals(pcm, decoded.joined())
        assertEquals(
            "received right 40 left 0, decoded 40, duplicate 0, missing 0, malformed 0, concealed frames 1",
            stream.toString(),
        )
    }

    @Test
    fun copiesFromEitherArmAndLateCopiesDecodeOnce() {
        val stream = G2MicrophoneStream()
        val decoded = mutableListOf<ShortArray>()
        decoded += checkNotNull(stream.accept(G2Side.LEFT, packet(0)))
        assertNull(stream.accept(G2Side.RIGHT, packet(0)))
        decoded += checkNotNull(stream.accept(G2Side.RIGHT, packet(1)))
        decoded += checkNotNull(stream.accept(G2Side.RIGHT, packet(2)))
        // The left arm's copies come after the next packet.
        assertNull(stream.accept(G2Side.LEFT, packet(1)))
        assertNull(stream.accept(G2Side.LEFT, packet(2)))
        assertArrayEquals(expected(0, 3), decoded.joined())
        assertEquals(
            "received right 3 left 3, decoded 3, duplicate 3, missing 0, malformed 0, concealed frames 0",
            stream.toString(),
        )
    }

    @Test
    fun theCounterWrapsAndAGapCountsItsLostPackets() {
        val stream = G2MicrophoneStream()
        val decoded =
            listOf(packet(0, 254), packet(1, 255), packet(2, 0), packet(3, 3)).map {
                checkNotNull(stream.accept(G2Side.RIGHT, it))
            }
        // 131 is 128 ahead of 3: a late copy, not 127 losses.
        assertNull(stream.accept(G2Side.RIGHT, packet(4, 131)))
        assertArrayEquals(expected(0, 4), decoded.joined())
        assertEquals(
            "received right 5 left 0, decoded 4, duplicate 1, missing 2, malformed 0, concealed frames 0",
            stream.toString(),
        )
    }

    @Test
    fun aPacketOfAnotherSizeIsMalformed() {
        val stream = G2MicrophoneStream()
        assertNull(stream.accept(G2Side.RIGHT, packet(0).copyOf(G2Microphone.PACKET_BYTES - 1)))
        assertArrayEquals(expected(0, 1), stream.accept(G2Side.RIGHT, packet(0)))
        assertTrue(stream.toString().endsWith("malformed 1, concealed frames 0"))
    }

    private fun pair() =
        G2Pair(
            "1",
            G2Arm(G2Side.LEFT, "1-L", "Even G2_1_L_000001"),
            G2Arm(G2Side.RIGHT, "1-R", "Even G2_1_R_000002"),
        )

    private fun FakeG2Firmware.microphoneRequests() = requests.filter { it.kind == "microphone" }.map { it.toString() }

    // Collects the microphone of [glasses] until the returned job is cancelled.
    private fun TestScope.listen(
        glasses: G2Glasses,
        heard: MutableList<ShortArray>,
    ) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { glasses.microphone.toList(heard) }

    @Test
    fun theMicrophoneRunsWhileTheHostListensAndBothArmsCopiesReachItOnce() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            glasses.connect(pair())
            glasses.state.first { it is G2State.Ready }
            runCurrent()
            assertEquals(emptyList<String>(), firmware.microphoneRequests())

            val heard = mutableListOf<ShortArray>()
            val listening = listen(glasses, heard)
            runCurrent()
            assertEquals(listOf("RIGHT microphone on"), firmware.microphoneRequests())
            repeat(3) { firmware.speak(packet(it)) }
            runCurrent()
            assertArrayEquals(expected(0, 3), heard.joined())

            listening.cancel()
            runCurrent()
            assertEquals(listOf("RIGHT microphone on", "RIGHT microphone off"), firmware.microphoneRequests())
            assertFalse(firmware.microphone)
        }

    @Test
    fun aPageThatComesBackStartsTheMicrophoneAgainWithANewStream() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            val heard = mutableListOf<ShortArray>()
            listen(glasses, heard)
            glasses.connect(pair())
            glasses.state.first { it is G2State.Ready }
            runCurrent()
            firmware.speak(packet(0))
            advanceTimeBy(G2Protocol.PAGE_SETTLED_MS)

            firmware.exitPage(type = G2InputEvent.EXIT, session = firmware.pageSession)
            runCurrent()
            assertEquals(listOf("RIGHT microphone on", "RIGHT microphone on"), firmware.microphoneRequests())
            assertTrue(firmware.microphone)
            // The glasses count from 0 again, which the previous stream would drop as a copy.
            firmware.speak(packet(0))
            runCurrent()
            assertArrayEquals(expected(0, 1) + expected(0, 1), heard.joined())
        }

    @Test
    fun aStartAnsweredUnderAnotherCommandStartsTheMicrophoneAndLogsTheReplyAndTheFirstPacket() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.microphoneReply = 99
            val logs = mutableListOf<String>()
            val glasses = G2Glasses(firmware.connector, backgroundScope, log = { logs += it })
            val heard = mutableListOf<ShortArray>()
            listen(glasses, heard)
            glasses.connect(pair())
            glasses.state.first { it is G2State.Ready }
            runCurrent()
            firmware.speak(packet(0), listOf(G2Side.RIGHT))
            firmware.speak(packet(1), listOf(G2Side.RIGHT))
            runCurrent()

            assertTrue(glasses.state.value is G2State.Ready)
            assertArrayEquals(expected(0, 2), heard.joined())
            assertEquals(
                listOf("Even G2 MICROPHONE_ON answered G2HubEcho(command=99, branch=19, result=1, header=0)"),
                logs.filter { "answered" in it },
            )
            val hex = packet(0).joinToString("") { "%02x".format(it) }
            assertEquals(
                listOf("Even G2 first microphone packet from RIGHT: $hex"),
                logs.filter { "first microphone packet" in it },
            )
        }

    @Test
    fun anUnansweredStartEndsTheSession() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.microphoneReply = null
            val glasses = G2Glasses(firmware.connector, backgroundScope)
            listen(glasses, mutableListOf())
            glasses.connect(pair())
            val closed = glasses.state.first { it is G2State.Closed } as G2State.Closed
            assertEquals(G2CloseCause.CommandTimedOut(G2HubCommand.MICROPHONE_ON), closed.cause)
        }

    private companion object {
        // The fixture's 200 frames.
        const val PACKETS = 40
    }
}
