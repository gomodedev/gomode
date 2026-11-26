// Battery and case readings of G2Glasses: the codec against golden bytes, and polling on fake firmware.
//
// The CASE_INFO read's golden bytes come from GlassesSessionCoreTest.kt of jimrandomh/faceclaw commit
// 2e416b53679baf3c9e312c77f53a2b41f9b62d93, which matches them to the Even app. No capture of real
// glasses exists: the replies are built by hand from the fields BleProtocol.kt of that commit reads.

package com.fghbuild.eveng2

import com.fghbuild.eveng2.testing.FakeG2Firmware
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // Virtual time: advanceTimeBy, runCurrent.
class G2BatteryTest {
    private val pair =
        G2Pair(
            "1",
            G2Arm(G2Side.LEFT, "1-L", "Even G2_1_L_000001"),
            G2Arm(G2Side.RIGHT, "1-R", "Even G2_1_R_000002"),
        )

    private fun settings(payload: String) = G2Message(7, G2Protocol.SID_SETTINGS, 0, hex(payload))

    private fun case(payload: String) = G2Message(0, G2Protocol.SID_CASE, G2Protocol.FLAG_NOTIFY, hex(payload))

    @Test
    fun theCaseReadIsTheEvenApps() {
        val packet = G2BatteryCodec.caseRead(magic = 42, sync = 7).single()
        assertEquals(G2Protocol.SID_CASE, packet[6].toInt() and 0xFF)
        assertEquals(G2Protocol.FLAG_SYNC_BOTH, packet[7].toInt())
        assertEquals("0801102a1a00", packet.copyOfRange(8, packet.size - 2).hex())
    }

    @Test
    fun aSettingsReplyHoldsItsArmsBattery() {
        // {1: 2, 2: 42, 4: {5: "2.2.9", 6: "2.2.9", 12: 80}}.
        assertEquals(
            G2Charge(80, false),
            G2BatteryCodec.arm(settings("0802102a22102a05322e322e393205322e322e396050"), 42),
        )
        // The same with 13: 1, then 14: 1 (silent mode), which does not matter.
        val charging = "0802102a22142a05322e322e393205322e322e3960506801" + "7001"
        assertEquals(G2Charge(80, true), G2BatteryCodec.arm(settings(charging), 42))
    }

    @Test
    fun aSettingsReplyWithoutItsReadOrLevelIsNoReading() {
        val reply = "0802102a22102a05322e322e393205322e322e396050"
        assertNull(G2BatteryCodec.arm(settings(reply), magic = 43))
        assertNull(G2BatteryCodec.arm(G2Message(7, G2Protocol.SID_EVEN_HUB, 0, hex(reply)), 42))
        assertNull(G2BatteryCodec.arm(G2Message(7, G2Protocol.SID_SETTINGS, 2, hex(reply)), 42))
        // No level (proto3 drops 0 %), no field 4, and a level above 100.
        assertNull(G2BatteryCodec.arm(settings("0802102a220c2a05322e322e393205322e322e39"), 42))
        assertNull(G2BatteryCodec.arm(settings("0802102a"), 42))
        assertNull(G2BatteryCodec.arm(settings("0802102a2202606f"), 42))
    }

    @Test
    fun aCaseInfoSaysWhereTheGlassesAre() {
        // {1: 1, 2: 7, 3: {1: 64, 2: 1, 3: 1, 4: 1}}: in the closed case, which charges on USB.
        assertEquals(
            G2CaseInfo.InCase(G2Charge(64, true)),
            G2BatteryCodec.case(case("080110071a0808401001180120" + "01")),
        )
        // {3: {1: 64, 4: 1}}: in the case, which does not charge.
        assertEquals(G2CaseInfo.InCase(G2Charge(64, false)), G2BatteryCodec.case(case("080110071a0408402001")))
        // An empty caseInfo: out of the case.
        assertEquals(G2CaseInfo.OutOfCase, G2BatteryCodec.case(case("080110071a00")))
    }

    @Test
    fun aCaseInfoWithoutALevelOrWithAnErrorIsNoReading() {
        // Docking's first push: {3: {4: 1}}.
        assertNull(G2BatteryCodec.case(case("080110071a022001")))
        // {3: {1: 80, 4: 1, 5: 7}}: an error code.
        assertNull(G2BatteryCodec.case(case("080110071a06085020012807")))
        // No caseInfo, another command, and another service.
        assertNull(G2BatteryCodec.case(case("08011007")))
        assertNull(G2BatteryCodec.case(case("080210071a00")))
        assertNull(G2BatteryCodec.case(G2Message(0, G2Protocol.SID_SETTINGS, 0, hex("080110071a00"))))
    }

    // The battery rounds so far: each ends with the case read.
    private val FakeG2Firmware.rounds: Int get() = requests.count { it.kind == "case" }

    // Lets a round that is due go out: the session reads only after a quiet window that starts
    // anywhere within the previous one.
    private fun TestScope.settle() {
        advanceTimeBy(2 * G2Protocol.BATTERY_QUIET_MS)
        runCurrent()
    }

    private suspend fun FakeG2Firmware.connected(scope: TestScope): G2Glasses {
        val glasses = G2Glasses(connector, scope.backgroundScope)
        glasses.connect(pair)
        glasses.state.first { it is G2State.Ready }
        return glasses
    }

    @Test
    fun readsEachArmOnItsOwnLinkOnceTheSessionIsQuiet() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.charge = { if (it == G2Side.LEFT) G2Charge(71, charging = false) else G2Charge(64, false) }
            val glasses = firmware.connected(this)
            assertEquals(G2Battery(), glasses.battery.value)
            firmware.requests.clear()

            settle()
            assertEquals(
                listOf("RIGHT settings", "LEFT settings", "RIGHT case"),
                firmware.requests.filter { it.kind != "heartbeat" }.map { it.toString() },
            )
            // Out of the case, the right arm answers with an empty caseInfo.
            assertEquals(G2Battery(left = G2Charge(71, false), right = G2Charge(64, false)), glasses.battery.value)
        }

    @Test
    fun aReplyToTheLeftReadFromTheRightArmIsNotTheLeftArmsLevel() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.settingsAnsweredBy = { G2Side.RIGHT }
            firmware.charge = { if (it == G2Side.LEFT) G2Charge(71, charging = false) else G2Charge(32, false) }
            val glasses = firmware.connected(this)

            settle()
            assertTrue(firmware.rounds >= 1)
            assertEquals(G2Battery(right = G2Charge(32, false)), glasses.battery.value)
        }

    @Test
    fun waitsUntilNothingIsDrawnBeforeReading() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = firmware.connected(this)
            firmware.requests.clear()

            // A transcript that changes every 2 s.
            repeat(15) {
                glasses.show("Line $it")
                advanceTimeBy(2_000)
            }
            assertEquals(0, firmware.rounds)

            settle()
            assertEquals(1, firmware.rounds)
        }

    @Test
    fun pollsEveryFiveMinutesAndEveryHalfMinuteWhileCharging() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = firmware.connected(this)
            settle()
            assertEquals(1, firmware.rounds)

            advanceTimeBy(G2Protocol.BATTERY_POLL_MS - 2 * G2Protocol.BATTERY_QUIET_MS)
            assertEquals(1, firmware.rounds)
            settle()
            settle()
            assertEquals(2, firmware.rounds)

            firmware.charge = { G2Charge(81, charging = it == G2Side.LEFT) }
            advanceTimeBy(G2Protocol.BATTERY_POLL_MS)
            settle()
            assertEquals(3, firmware.rounds)
            assertEquals(G2Charge(81, true), glasses.battery.value.left)

            advanceTimeBy(G2Protocol.BATTERY_CHARGING_POLL_MS - 2 * G2Protocol.BATTERY_QUIET_MS)
            assertEquals(3, firmware.rounds)
            settle()
            settle()
            assertEquals(4, firmware.rounds)
        }

    @Test
    fun missingRepliesAndFieldsAndASilentCaseLeaveTheirPartsUnknown() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.answersSettings = { it == G2Side.RIGHT }
            firmware.charge = { null }
            firmware.answersCase = false
            firmware.case = G2Charge(50, charging = false)
            val glasses = firmware.connected(this)

            advanceTimeBy(3 * G2Protocol.BATTERY_POLL_MS)
            assertTrue(firmware.rounds >= 3)
            assertEquals(G2Battery(), glasses.battery.value)
            assertTrue(glasses.state.value is G2State.Ready)

            // The right arm's next reply with a level counts; the left arm's silence keeps it unknown.
            firmware.charge = { G2Charge(90, charging = false) }
            advanceTimeBy(G2Protocol.BATTERY_POLL_MS)
            settle()
            assertEquals(G2Battery(right = G2Charge(90, false)), glasses.battery.value)
        }

    @Test
    fun theCaseShowsWhileTheGlassesSitInItAndItsPushesUpdateIt() =
        runTest {
            val firmware = FakeG2Firmware()
            firmware.case = G2Charge(55, charging = true)
            val glasses = firmware.connected(this)
            settle()
            assertEquals(G2Charge(55, true), glasses.battery.value.case)

            firmware.case = G2Charge(56, charging = true)
            firmware.pushCase()
            runCurrent()
            assertEquals(G2Charge(56, true), glasses.battery.value.case)

            firmware.case = null
            firmware.pushCase()
            runCurrent()
            assertNull(glasses.battery.value.case)
        }

    @Test
    fun dockingReadsTheArmsSoon() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = firmware.connected(this)
            settle()
            assertEquals(1, firmware.rounds)

            firmware.case = G2Charge(40, charging = false)
            firmware.charge = { G2Charge(70, charging = true) }
            // Docking pushes the glasses' place first, which is no reading, then the level.
            firmware.pushCase(level = false)
            runCurrent()
            assertNull(glasses.battery.value.case)
            advanceTimeBy(700)
            firmware.pushCase()
            runCurrent()
            assertEquals(G2Charge(40, false), glasses.battery.value.case)

            settle()
            assertEquals(2, firmware.rounds)
            assertEquals(G2Charge(70, true), glasses.battery.value.left)
            assertEquals(G2Charge(70, true), glasses.battery.value.right)
        }

    @Test
    fun readingsBelongToTheirSession() =
        runTest {
            val firmware = FakeG2Firmware()
            val glasses = firmware.connected(this)
            settle()
            assertEquals(G2Charge(80, false), glasses.battery.value.left)

            glasses.disconnect()
            assertEquals(G2Battery(), glasses.battery.value)

            glasses.connect(pair)
            glasses.state.first { it is G2State.Ready }
            assertEquals(G2Battery(), glasses.battery.value)
            settle()
            assertEquals(G2Charge(80, false), glasses.battery.value.left)
        }
}
