// Faceclaw custom firmware's settings read, lease, private stream, draw calls, and ring reports against golden bytes.
//
// The private-stream packets and draw calls marked faceclaw come from ProtocolTest.kt and
// DrawWireTest.kt of jimrandomh/faceclaw commit 2e416b53679baf3c9e312c77f53a2b41f9b62d93. No
// capture of real glasses exists: the settings replies, ACKs, and ring report are built by hand
// from g2flash's patches/settings_ext.c, message_transport.c, and gesture_fwd.c at commit
// c8fd8e85c77e8f378cce52029dd1225e4ab11caa, as faceclaw's own tests build them.

package com.fghbuild.eveng2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

class G2FaceclawWireTest {
    // {1: 2, 2: 42, 4: {5: "2.2.9", 6: "2.2.9", 12: 80}}, as stock firmware answers a read with magic 42.
    private val stockReply = "0802102a22102a05322e322e393205322e322e396050"

    private fun settings(payload: String) =
        G2Message(sync = 7, sid = G2Protocol.SID_SETTINGS, flag = 0, payload = hex(payload))

    // Field 100 holding [revision].
    private fun revision(revision: String) = "a206" + "%02x".format(revision.length) + revision.toByteArray().hex()

    private fun payload(packet: ByteArray): String {
        assertEquals(G2Protocol.SID_SETTINGS, packet[6].toInt())
        assertEquals(G2Protocol.FLAG_SYNC_BOTH, packet[7].toInt())
        return packet.copyOfRange(8, packet.size - 2).hex()
    }

    @Test
    fun settingsReadAndLeaseControl() {
        assertEquals("0802102a22020801", payload(G2Settings.read(magic = 42, sync = 7).single()))
        assertEquals(
            "08011000aa0606464301050000",
            payload(G2Settings.lease(G2Settings.LEASE_ACQUIRE, sync = 8).single()),
        )
        assertEquals(
            "08011000aa0606464301060000",
            payload(G2Settings.lease(G2Settings.LEASE_RELEASE, sync = 9).single()),
        )
    }

    @Test
    fun theSettingsReplyTellsTheFirmware() {
        assertEquals(G2Firmware.EvenHub(null), G2Settings.firmware(settings(stockReply), magic = 42))
        assertEquals(G2Firmware.Faceclaw(45), G2Settings.firmware(settings(stockReply + revision("Faceclaw/45")), 42))
        assertEquals(G2Firmware.Faceclaw(46), G2Settings.firmware(settings(stockReply + revision("Faceclaw/46")), 42))
        // Older revisions and older builds keep the stock page.
        assertEquals(
            G2Firmware.EvenHub("Faceclaw/44"),
            G2Settings.firmware(settings(stockReply + revision("Faceclaw/44")), 42),
        )
        assertEquals(
            G2Firmware.EvenHub("EVENCFW/0.3 fb"),
            G2Settings.firmware(settings(stockReply + revision("EVENCFW/0.3 fb")), 42),
        )
        assertEquals(
            G2Firmware.EvenHub("Faceclaw/4x"),
            G2Settings.firmware(settings(stockReply + revision("Faceclaw/4x")), 42),
        )
        // Fields the firmware appends, such as the microphone status (104) and a silent mode (14), do not matter.
        assertEquals(
            G2Firmware.Faceclaw(45),
            G2Settings.firmware(settings(stockReply + "7001" + revision("Faceclaw/45") + "c20603010203"), 42),
        )
    }

    @Test
    fun aSettingsReplyCountsOnlyForItsRead() {
        assertNull(G2Settings.firmware(settings(stockReply), magic = 43))
        assertNull(G2Settings.firmware(G2Message(7, G2Protocol.SID_EVEN_HUB, 0, hex(stockReply)), 42))
        // A failed reply.
        assertNull(G2Settings.firmware(G2Message(7, G2Protocol.SID_SETTINGS, 2, byteArrayOf()), 42))
        assertNull(G2Settings.firmware(settings("0802102a2205"), 42))
    }

    @Test
    fun privateStreamPacketsMatchFaceclaw() {
        val message = "123456789abcdef".toByteArray()
        val record = G2CfwStream.record(message, G2Frame.crc16(message), G2CfwStream.BOTH or G2CfwStream.RESET_CONTEXT)
        assertEquals(
            listOf(
                "aa21ff0c0101f000830b0f0078c331323334862b",
                "aa21000c0101f000033536373839616263648c7b",
                "aa2101050101f000436566de70",
            ),
            G2CfwStream.split(record, stream = 255, mtu = 23).map { it.hex() },
        )
        // A packet carries at most 252 bytes of the record whatever the MTU.
        val large = G2CfwStream.split(ByteArray(600), stream = 0, mtu = 517)
        assertEquals(listOf(263, 263, 107), large.map { it.size })
    }

    // Reassembles packets as the firmware does and inflates each record in one context.
    private class Receiver {
        val inflater = Inflater()
        val flags = mutableListOf<Int>()

        fun message(packets: List<ByteArray>): ByteArray {
            val record = ByteArrayOutputStream()
            packets.forEach { record.write(it, 9, it.size - 11) }
            val bytes = record.toByteArray()
            val flag = bytes[0].toInt()
            flags += flag
            val size = (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
            val checksum = (bytes[3].toInt() and 0xFF) or ((bytes[4].toInt() and 0xFF) shl 8)
            val body = bytes.copyOfRange(5, bytes.size)
            assertEquals(size, body.size)
            if (flag and G2CfwStream.RESET_CONTEXT != 0) inflater.reset()
            val decoded =
                if (flag and G2CfwStream.COMPRESSED == 0) {
                    body
                } else {
                    inflater.setInput(body)
                    val out = ByteArrayOutputStream()
                    val chunk = ByteArray(1024)
                    do {
                        val count = inflater.inflate(chunk)
                        out.write(chunk, 0, count)
                    } while (count > 0 || !inflater.needsInput())
                    out.toByteArray()
                }
            assertEquals(checksum, G2Frame.crc16(decoded))
            return decoded
        }
    }

    @Test
    fun recordsShareOneZlibContext() {
        val receiver = Receiver()
        val text = G2Draw.drawCalls(G2Draw.text("Hello"))
        G2CfwStream().use { stream ->
            val first = stream.packets(text, stream = 0, mtu = 23)
            val again = stream.packets(text, stream = 4, mtu = 23)
            val present = stream.packets(G2Draw.present(), stream = 8, mtu = 23)
            assertArrayEquals(text, receiver.message(first))
            assertArrayEquals(text, receiver.message(again))
            assertArrayEquals(G2Draw.present(), receiver.message(present))
            // The copy reuses the first one's history, so it takes fewer bytes.
            assertTrue(again.sumOf { it.size } < first.sumOf { it.size })
        }
        // Only the first record resets the receiver's context.
        val compressed = G2CfwStream.BOTH or G2CfwStream.COMPRESSED
        assertEquals(listOf(compressed or G2CfwStream.RESET_CONTEXT, compressed, compressed), receiver.flags)
    }

    private fun stream(payload: String) =
        G2Message(sync = 0, sid = G2Protocol.SID_CFW, flag = 0, payload = hex(payload))

    @Test
    fun acksNameTheStreamLensAndMessage() {
        // ACK of stream 255, message 0, from the right lens, for a 5-byte message whose CRC is 0xE3F1.
        assertEquals(G2CfwAck(false, 255, 0, 2, 5, 0xE3F1), G2CfwStream.ack(stream("01ff0000020500f1e3")))
        // With two earlier successes.
        assertEquals(
            G2CfwAck(false, 3, 0, 1, 1, 0x1234),
            G2CfwStream.ack(stream("0103000001010034120200000100aaaa0100000100bbbb")),
        )
        assertEquals(G2CfwAck(true, 3, 0, 1, 0, 0x1234), G2CfwStream.ack(stream("030300000100003412")))
        // A NACK never carries history, a lens is 1 or 2, and history comes in 7-byte entries.
        assertNull(G2CfwStream.ack(stream("0303000001000034120200000100aaaa")))
        assertNull(G2CfwStream.ack(stream("010300000300003412")))
        assertNull(G2CfwStream.ack(stream("0103000001000034120200")))
        assertNull(G2CfwStream.ack(stream("020300000100003412")))
        assertNull(G2CfwStream.ack(G2Message(0, G2Protocol.SID_SETTINGS, 0, hex("01ff0000020500f1e3"))))
    }

    @Test
    fun drawCallsMatchFaceclaw() {
        assertEquals("0300ffff02000f0341c3a9", G2Draw.stockText(-1, 2, "Aé").hex())
        assertEquals("01000101000000010001001f", G2Draw.boundingBox(1, 0, 1, 1) { _, _ -> 15 }.hex())
        assertEquals("1a0100050007027fff01", G2Draw.drawCalls(listOf(hex("07027fff01"))).hex())
        assertEquals("1bffff", G2Draw.clearRoot.hex())
        assertEquals("1c", G2Draw.present().hex())
        assertEquals("090000", G2Draw.clear().hex())
    }

    @Test
    fun runLengthsUseTheShortestForm() {
        fun runs(
            width: Int,
            level: (Int) -> Int,
        ) = G2Draw.boundingBox(0, 0, width, 1) { x, _ -> level(x) }.hex().drop(22)
        assertEquals("2112", runs(3) { if (it < 2) 1 else 2 })
        assertEquals("f3", runs(15) { 3 })
        assertEquals("0310", runs(16) { 3 })
        assertEquals("03ff", runs(255) { 3 })
        assertEquals("03000001", runs(256) { 3 })
        // 640 × 103 pixels: a run stops at 65535.
        assertEquals("0300ffff03008101", G2Draw.boundingBox(0, 0, 640, 103) { _, _ -> 3 }.hex().drop(22))
    }

    @Test
    fun textIsDrawnLineByLineInTheStockFont() {
        val lines = G2Draw.text("Hello\n\u0007world")
        assertEquals(
            listOf(G2Draw.stockText(0, 0, "Hello"), G2Draw.stockText(0, 27, "world")).map {
                it.hex()
            },
            lines.map { it.hex() },
        )
        // A line over 255 bytes goes out in pieces, each where the one before it ends: 100 code points
        // that no font has, of 4 UTF-8 bytes and 4 px each.
        val missing = String(Character.toChars(0x10FFFD))
        val pieces = G2Draw.text(missing.repeat(100))
        assertEquals(
            listOf(
                G2Draw.stockText(0, 0, missing.repeat(63)),
                G2Draw.stockText(252, 0, missing.repeat(37)),
            ).map { it.hex() },
            pieces.map { it.hex() },
        )
        // Only the lines that fit the panel are drawn.
        assertEquals(17, G2Draw.text((1..20).joinToString("\n")).size)
    }

    @Test
    fun anImageGoesOutInBandsOnAClearedBuffer() {
        val image = G2Image(640, 200, ByteArray(640 * 200) { (it % 16).toByte() })
        val frame = G2Draw.frame(G2Content.Image(image))
        assertEquals(listOf(26, 26, 26, 28), frame.map { it[0].toInt() })
        // Each band stays below a message's 65535 bytes with one RLE byte per pixel.
        assertTrue(frame.all { it.size <= G2CfwStream.MAX_MESSAGE })
        // The first band clears the buffer first; the bands sit at rows 140, 236, and 332 of the full width.
        assertEquals("1a02000300090000", frame[0].hex().take(16))
        assertEquals("01000100008c0080026000", frame[0].hex().drop(20).take(22))
        assertEquals("0100010000ec0080026000", frame[1].hex().drop(10).take(22))
        assertEquals("01000100004c0180020800", frame[2].hex().drop(10).take(22))
    }

    @Test
    fun aRingClickUnderTheLeaseIsAGesture() {
        // As gesture_fwd.c sends a ring click (report type 1) at ring tick 0x01020304.
        val click = hex("08026a151a1308001002a2060c52490101010000000403020100".dropLast(2))
        val message = G2Message(1, G2Protocol.SID_EVEN_HUB, G2Protocol.FLAG_NOTIFY, click)
        assertEquals(G2RingReport(1, 0x01020304), G2EvenHub.ringReport(message))
        assertEquals(
            G2Gesture(G2GestureType.CLICK, G2GestureSource.RING),
            G2EvenHub.gesture(message, G2TextPage(), session = 5),
        )
        // A ring touch-down (report type 10, event 14) is not a gesture.
        val down = hex("08026a151a13080e1002a2060c524901010a0000000403020100".dropLast(2))
        assertNull(
            G2EvenHub.gesture(G2Message(1, G2Protocol.SID_EVEN_HUB, G2Protocol.FLAG_NOTIFY, down), G2TextPage(), 5),
        )
    }

    @Test
    fun theRingFilterDropsReportsWithinOneHundredTicks() {
        val filter = G2RingFilter()
        assertTrue(filter.accept(G2RingReport(1, 1_000)))
        assertFalse(filter.accept(G2RingReport(1, 1_099)))
        // Releases and touch-downs always pass; a touch-down starts no interval.
        assertTrue(filter.accept(G2RingReport(10, 1_050)))
        assertTrue(filter.accept(G2RingReport(8, 1_060)))
        assertFalse(filter.accept(G2RingReport(1, 1_159)))
        assertTrue(filter.accept(G2RingReport(1, 1_160)))
        // The ring's tick wraps at 2^32.
        val wrapping = G2RingFilter()
        assertTrue(wrapping.accept(G2RingReport(1, 0xFFFF_FFF0)))
        assertFalse(wrapping.accept(G2RingReport(1, 0x10)))
        assertTrue(wrapping.accept(G2RingReport(1, 0x60)))
    }
}
