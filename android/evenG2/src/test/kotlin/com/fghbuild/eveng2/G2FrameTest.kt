// Packet split limits and reassembly of glasses-to-phone messages, including malformed and interleaved packets.

package com.fghbuild.eveng2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class G2FrameTest {
    /** Packets as the glasses send them: the client's framing with the reply address. */
    private fun reply(
        payload: ByteArray,
        sync: Int = 9,
        flag: Int = 0,
    ): List<ByteArray> = G2Frame.packets(payload, G2Protocol.SID_EVEN_HUB, flag, sync).onEach { it[1] = 0x12 }

    @Test
    fun splitKeepsCrcWholeAndFitsMinimumMtu() {
        assertEquals(1, G2Frame.packets(ByteArray(137), 1, 0, 0).size)
        for (size in listOf(138, 139, 140, 276, 277, G2Frame.MAX_PAYLOAD)) {
            val packets = G2Frame.packets(ByteArray(size), 1, 0, 0)
            assertTrue(packets.all { it.size <= G2Protocol.MIN_MTU - 3 })
            val last = packets.last()
            // The final packet carries at least one body byte before its two CRC bytes.
            assertTrue("size $size", (last[3].toInt() and 0xFF) >= 3)
            assertEquals(size + 2, packets.sumOf { (it[3].toInt() and 0xFF) })
            assertEquals(packets.indices.map { it + 1 }, packets.map { it[5].toInt() and 0xFF })
        }
    }

    @Test
    fun reassemblesInterleavedMessages() {
        val a = ByteArray(400) { it.toByte() }
        val b = ByteArray(300) { (it * 7).toByte() }
        val assembler = G2FrameAssembler()
        val pa = reply(a, sync = 1)
        val pb = reply(b, sync = 2)
        val messages = listOf(pa[0], pb[0], pa[1], pb[1], pb[2], pa[2]).mapNotNull(assembler::accept)
        assertEquals(listOf(2, 1), messages.map { it.sync })
        assertArrayEquals(b, messages[0].payload)
        assertArrayEquals(a, messages[1].payload)
    }

    @Test
    fun ignoresPaddingAfterTheDeclaredLength() {
        val packet = reply(byteArrayOf(8, 1)).single()
        val message = G2FrameAssembler().accept(packet + byteArrayOf(0, 0, 0))
        assertArrayEquals(byteArrayOf(8, 1), message?.payload)
    }

    @Test
    fun dropsGapsRestartsAndOwnRequests() {
        val packets = reply(ByteArray(300))
        val assembler = G2FrameAssembler()
        assertNull(assembler.accept(packets[0]))
        assertNull(assembler.accept(packets[2])) // Packet 2 is missing.
        assertNull(assembler.accept(packets[1]))
        // A restarted message with the same identity drops both copies.
        assertNull(assembler.accept(packets[0]))
        assertNull(assembler.accept(packets[0]))
        assertNull(assembler.accept(packets[1]))
        assertNull(assembler.accept(packets[2]))
        // The client's own address is not a reply.
        assertNull(assembler.accept(G2Frame.packets(byteArrayOf(1), 1, 0, 0).single()))
    }

    @Test
    fun evictsTheOldestPartialMessage() {
        val assembler = G2FrameAssembler()
        val messages = (1..5).map { reply(ByteArray(200), sync = it) }
        messages.forEach { assertNull(assembler.accept(it[0])) }
        assertNull(assembler.accept(messages[0][1])) // Evicted by the fifth message.
        assertEquals(5, assembler.accept(messages[4][1])?.sync)
    }

    @Test
    fun failedReplyCarriesItsResultWithoutPayload() {
        val packet = byteArrayOf(0xAA.toByte(), 0x12, 7, 0, 1, 1, 0xE0.toByte(), (3 shl 1).toByte())
        val message = G2FrameAssembler().accept(packet)
        assertEquals(3, message?.result)
        assertEquals(0, message?.payload?.size)
    }
}
