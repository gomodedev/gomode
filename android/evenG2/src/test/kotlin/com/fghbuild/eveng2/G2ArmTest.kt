// Advertisement parsing and grouping of scanned Even G2 arms into pairs.

package com.fghbuild.eveng2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class G2ArmTest {
    // Manufacturer data: serial, address little-endian, flag.
    private fun manufacturer(serial: String) =
        serial.toByteArray() + byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x02)

    @Test
    fun parsesArmNames() {
        val left = G2Advertising.parse("Even G2_32_L_693CCB", "AA:00:00:69:3C:CB", null)
        assertEquals(G2Advertisement(G2Arm(G2Side.LEFT, "AA:00:00:69:3C:CB", "Even G2_32_L_693CCB"), "32"), left)
        assertEquals(G2Side.RIGHT, G2Advertising.parse("Even G2_32_R_ABCDEF", "x", null)?.arm?.side)
        for (name in listOf(null, "Even G1_32_L_693CCB", "Even G2_32_X_693CCB", "Even G2_32_L_693CC", "Halo AB")) {
            assertNull(name, G2Advertising.parse(name, "x", null))
        }
    }

    @Test
    fun serialGroupsArmsBeforeTheNamePairField() {
        val ad = G2Advertising.parse("Even G2_32_L_693CCB", "a", manufacturer("S211GABE000001"))
        assertEquals("S211GABE000001", ad?.pairKey)
        // Data too short or not a serial falls back to the name.
        assertEquals("32", G2Advertising.parse("Even G2_32_L_693CCB", "a", byteArrayOf(1, 2, 3))?.pairKey)
        assertEquals("32", G2Advertising.parse("Even G2_32_L_693CCB", "a", manufacturer("S2\u0000"))?.pairKey)
    }

    @Test
    fun collectorReportsCompletePairsOnce() {
        val collector = G2PairCollector()
        val left = checkNotNull(G2Advertising.parse("Even G2_32_L_000001", "L1", manufacturer("SERIAL1")))
        val right = checkNotNull(G2Advertising.parse("Even G2_32_R_000002", "R1", manufacturer("SERIAL1")))
        // Another pair with the same name field but its own serial stays apart.
        val other = checkNotNull(G2Advertising.parse("Even G2_32_R_000003", "R2", manufacturer("SERIAL2")))
        assertNull(collector.add(left))
        assertNull(collector.add(other))
        val pair = collector.add(right)
        assertEquals(G2Pair("SERIAL1", left.arm, right.arm), pair)
        assertNull(collector.add(right))
        val moved = right.copy(arm = right.arm.copy(address = "R9"))
        assertEquals("R9", collector.add(moved)?.right?.address)
    }
}
