// Golden bytes of the even-g2-protocol Python library's live-confirmed builders against the G2 codecs.
//
// Generated from even-g2-protocol commit f352806135c1a892eb10ca4d616f9fa0c186fc83 (MIT) by
// loading transport/transport.py and widgets/evenhub.py with `python3 -I` and calling
// auth_frame() with its counter at 41, PRELUDE_F5872, build_create_input_page(101, "Hello"),
// build_text_upgrade(…), build_heartbeat(103), and frame_pb(…, 0xE0, 0x20, seq).

package com.fghbuild.eveng2

import org.junit.Assert.assertEquals
import org.junit.Test

class G2PythonGoldenTest {
    private val dashboard = G2TextPage(containerName = "dashboard")

    @Test
    fun authFrame() {
        assertEquals("aa212a0c010180000804102a1a04080110041573", G2Auth.request(magic = 42, sync = 42).hex())
    }

    @Test
    fun preludeFrame() {
        assertEquals("aa219213010101200802109c01220a1a081206120408001000a142", G2Prelude.frame().hex())
    }

    @Test
    fun textUpdateFrame() {
        val golden = "aa210c230101e020080510664a1b0801120964617368626f617264180020082a084869207468657265260e"
        assertEquals(golden, G2EvenHub.updateText(dashboard, "Hi there", magic = 102, sync = 12).single().hex())
    }

    // The Python create page omits the page session ID (field 7) and its heartbeat body writes
    // an explicit zero count, so only the framing of those payloads is compared.
    @Test
    fun framingOfPythonPayloads() {
        val create =
            "080010651a2708011a200800100018c00420a0024801520964617368626f6172645801620548656c6c6f28904e"
        assertEquals(
            "aa210b2f0101e020${create}1805",
            G2Frame.packets(hex(create), G2Protocol.SID_EVEN_HUB, G2Protocol.FLAG_SYNC_BOTH, 11).single().hex(),
        )
        assertEquals(
            "aa210d0a0101e020080c106772020800d751",
            G2Frame
                .packets(
                    hex("080c106772020800"),
                    G2Protocol.SID_EVEN_HUB,
                    G2Protocol.FLAG_SYNC_BOTH,
                    13,
                ).single()
                .hex(),
        )
    }

    // Python cuts payload+CRC into 232-byte bodies; this client cuts at most 139 and keeps the
    // CRC whole in the final packet. Both carry the same byte stream with the same CRC.
    @Test
    fun multiPacketStreamMatches() {
        val packets = G2EvenHub.updateText(dashboard, "x".repeat(300), magic = 104, sync = 14)
        assertEquals(listOf(139, 139, 52), packets.map { it[3].toInt() and 0xFF })
        assertEquals(listOf(147, 147, 60), packets.map { it.size })
        val stream = packets.map { it.copyOfRange(8, it.size) }.reduce(ByteArray::plus)
        // Python: packet 1 holds 0xe8 = 232 bytes, packet 2 0x62 = 98 bytes ending in CRC 19 74.
        assertEquals(232 + 98, stream.size)
        assertEquals("080510684ac1020801120964617368626f617264180020ac022aac02", stream.copyOfRange(0, 28).hex())
        assertEquals("x".repeat(300), String(stream, 28, 300))
        assertEquals("1974", stream.copyOfRange(stream.size - 2, stream.size).hex())
    }

    @Test
    fun crcCheckValue() {
        // CRC-16/CCITT-FALSE check value, the same as transport.crc16_ccitt(b"123456789").
        assertEquals(0x29B1, G2Frame.crc16("123456789".toByteArray()))
    }
}
