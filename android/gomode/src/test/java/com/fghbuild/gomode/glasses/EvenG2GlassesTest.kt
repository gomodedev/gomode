// Tests EvenG2Glasses against fake G2 firmware: the close cause it reports when Go Mode closes it.
package com.fghbuild.gomode.glasses

import com.fghbuild.eveng2.FakeG2Firmware
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2Glasses
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Side
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            val glasses = EvenG2Glasses.connect(G2Glasses(firmware.connector, backgroundScope), PAIR, backgroundScope)
            assertEquals(null, glasses.closeCause.value)

            glasses.close()
            val cause = glasses.closeCause.value as GlassesException
            assertEquals("Disconnected", cause.message)
            // Nothing retries a connection Go Mode closed.
            assertTrue(cause.permanent)
            assertEquals("shutdown", firmware.requests.last().kind)
            assertTrue(firmware.links.all { it.closed })
        }
}
