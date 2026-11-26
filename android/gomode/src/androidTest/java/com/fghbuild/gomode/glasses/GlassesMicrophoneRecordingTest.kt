// Records a fake Even G2's microphone from the input events screen into the WAV file that adb pulls, then plays it.
package com.fghbuild.gomode.glasses

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.testing.FakeG2Firmware
import com.fghbuild.eveng2.testing.fakeG2Scanner
import com.fghbuild.gomode.data.GlassesDevice
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.testing.MemoryPreferences
import com.fghbuild.gomode.ui.glasses.GlassesInputsScreen
import com.fghbuild.gomode.ui.theme.GoModeTheme
import com.fghbuild.halo.testing.FakeHaloTransport
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

private val G2_PAIR =
    G2Pair(
        "7",
        G2Arm(G2Side.LEFT, "00:00:5E:00:53:0A", "Even G2_7_L_00530A"),
        G2Arm(G2Side.RIGHT, "00:00:5E:00:53:0B", "Even G2_7_R_00530B"),
    )

private const val TIMEOUT_MS = 30_000L

class GlassesMicrophoneRecordingTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_CONNECT)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theInputEventsScreenRecordsFiveSecondsOfTheGlassesMicrophone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firmware = FakeG2Firmware()
        val scope = MainScope()
        val owner =
            GlassesOwner(
                scope,
                context,
                SettingsRepository(MemoryPreferences()),
                FakeHaloTransport(context),
                fakeG2Scanner(),
                firmware.connector,
            )
        val file = File(context.getExternalFilesDir(null), "glasses-microphone.wav")
        file.delete()
        try {
            composeRule.setContent { GoModeTheme { GlassesInputsScreen(owner, onNavigateBack = {}) } }
            composeRule.onNodeWithTag("gomode-glasses-record").assertIsNotEnabled()
            composeRule.onNodeWithText("Connect glasses with a microphone to record it.").assertExists()

            composeRule.runOnUiThread { owner.connect(GlassesDevice.EvenG2(G2_PAIR)) }
            composeRule.waitUntil(TIMEOUT_MS) { owner.microphone.value != null }
            composeRule.onNodeWithTag("gomode-glasses-record").assertIsEnabled().performClick()
            composeRule.waitUntil(TIMEOUT_MS) { firmware.microphone }
            composeRule.onNodeWithTag("gomode-glasses-record").assertIsNotEnabled()
            // 100 packets of 50 ms, both arms each. The recorder collects on the rule's test dispatcher,
            // which runs only while the test waits for idle; the SDK keeps 20 blocks for a slow collector.
            repeat(100) {
                firmware.speak(ByteArray(PACKET_BYTES).also { packet -> packet[PACKET_BYTES - 1] = it.toByte() })
                composeRule.waitForIdle()
            }
            composeRule.waitUntil(TIMEOUT_MS) {
                composeRule.onAllNodesWithText("Saved 5.0 s").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(44L + 5 * 16_000 * 2, file.length())
            composeRule.waitUntil(TIMEOUT_MS) { !firmware.microphone }
            composeRule.onNodeWithTag("gomode-glasses-record").assertIsEnabled()

            composeRule.onNodeWithContentDescription("Play recording").performClick()
            composeRule.onNodeWithContentDescription("Stop playback").assertExists()
            // The player releases itself after the recording's 5 s.
            composeRule.waitUntil(TIMEOUT_MS) {
                composeRule.onAllNodesWithContentDescription("Play recording").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("gomode-glasses-playback-status").assertDoesNotExist()
        } finally {
            composeRule.runOnUiThread { scope.cancel() }
        }
    }

    private companion object {
        // Five LC3 frames of 40 bytes, then the trailer, whose last byte is the counter.
        const val PACKET_BYTES = 205
    }
}
