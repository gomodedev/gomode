// Pairs fake Even G2 and Halo glasses from settings on one glasses screen, switching kinds, through the real owner.
package com.fghbuild.gomode.glasses

import android.Manifest
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2InputEvent
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.testing.FakeG2Firmware
import com.fghbuild.eveng2.testing.fakeG2Scanner
import com.fghbuild.gomode.data.GlassesDevice
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.testing.MemoryPreferences
import com.fghbuild.gomode.ui.GoModeApp
import com.fghbuild.gomode.ui.theme.GoModeTheme
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.gomode.voice.LocalModelOwner
import com.fghbuild.gomode.voice.ModelDownloadState
import com.fghbuild.gomode.voice.localModelInstallation
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

// Documentation-range addresses; the fake transports answer for them.
private const val HALO_ADDRESS = "00:00:5E:00:53:05"
private val PAIR =
    G2Pair(
        "7",
        G2Arm(G2Side.LEFT, "00:00:5E:00:53:06", "Even G2_7_L_005306"),
        G2Arm(G2Side.RIGHT, "00:00:5E:00:53:07", "Even G2_7_R_005307"),
    )
private const val TIMEOUT_MS = 30_000L

class GlassesPairingTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aSavedEvenG2ConnectsAndAScannedHaloReplacesIt() =
        runBlocking<Unit> {
            val repository = SettingsRepository(MemoryPreferences())
            repository.updateGlasses(GlassesDevice.EvenG2(PAIR))
            repository.settings.first { it.glasses != null }
            val firmware = FakeG2Firmware()
            val halo = ScanningFakeHalo(context, HALO_ADDRESS)
            val glassesScope = MainScope()
            val glasses = GlassesOwner(glassesScope, context, repository, halo, fakeG2Scanner(), firmware.connector)
            val models = LocalModelOwner(context, localModelInstallation(ModelDownloadState.Missing(0)))
            val shellOwner = ServiceShellOwner(context, repository, glasses::connectedDisplay)
            try {
                // Without a service, Go Mode opens on its settings.
                composeRule.setContent { GoModeTheme { GoModeApp(shellOwner, models, glasses) } }
                waitForTag("gomode-manage-glasses")
                composeRule.onNodeWithText("Even G2 7").performScrollTo()
                composeRule.onNodeWithTag("gomode-manage-glasses").performClick()
                waitForTag("gomode-glasses-screen")
                composeRule.onNodeWithTag("gomode-glasses-saved", useUnmergedTree = true).assertTextEquals("7")

                composeRule.onNodeWithTag("gomode-glasses-connect").performClick()
                composeRule.waitUntil(TIMEOUT_MS) { glasses.connectedDisplay == G2_DISPLAY }
                composeRule.onNodeWithTag("gomode-glasses-link", useUnmergedTree = true).assertTextEquals("Connected")
                // The glasses greet the connection, then show the blank frame.
                composeRule.waitUntil(TIMEOUT_MS) { firmware.shownText() == GREETING_TEXT }
                composeRule.waitUntil(TIMEOUT_MS) { firmware.shownText() == "" }

                // The input events screen lists a tap, the glasses show the same list, and the tap's
                // press acts on nothing; system back returns to the glasses screen.
                val pressed = AtomicBoolean(false)
                composeRule.runOnUiThread {
                    glasses.takePressesFirst {
                        pressed.set(true)
                        true
                    }
                }
                composeRule.onNodeWithTag("gomode-glasses-open-inputs").performClick()
                waitForTag("gomode-glasses-inputs")
                composeRule.waitUntil(TIMEOUT_MS) { firmware.shownText() == "Input events\nTry a gesture." }
                firmware.touch(G2Side.RIGHT, type = G2InputEvent.CLICK)
                composeRule.waitUntil(TIMEOUT_MS) {
                    composeRule.onAllNodesWithTag("gomode-glasses-input").fetchSemanticsNodes().size == 1
                }
                composeRule.onNodeWithText("Tap").assertExists()
                composeRule.waitUntil(TIMEOUT_MS) { firmware.shownText() == "Input events\nTap" }
                composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
                waitForTag("gomode-glasses-screen")
                composeRule.waitUntil(TIMEOUT_MS) { firmware.shownText() == "" }
                assertFalse(pressed.get())
                composeRule.runOnUiThread { glasses.takePressesFirst(null) }

                composeRule.onNodeWithTag("gomode-glasses-scan").performClick()
                waitForTag("gomode-glasses-found")
                // The G2 stopped advertising while connected, so the scan found the Halo alone.
                composeRule.onNodeWithTag("gomode-glasses-g2-hint").assertExists()
                composeRule
                    .onNode(hasText("Connect") and hasAnyAncestor(hasTestTag("gomode-glasses-found")))
                    .performClick()
                composeRule.waitUntil(TIMEOUT_MS) { glasses.connectedDisplay == HALO_DISPLAY }
                composeRule.waitUntil(TIMEOUT_MS) {
                    repository.settings.value.glasses ==
                        GlassesDevice.Halo(HALO_ADDRESS)
                }
                composeRule
                    .onNodeWithTag("gomode-glasses-saved", useUnmergedTree = true)
                    .assertTextEquals(HALO_ADDRESS)
                // The G2 page closed before the Halo connected: one pair at a time.
                assertEquals("shutdown", firmware.requests.last().kind)
                assertTrue(firmware.links.all { it.closed })

                composeRule.onNodeWithContentDescription("Back").performClick()
                waitForTag("gomode-manage-glasses")
                composeRule.onNodeWithText("Halo · $HALO_ADDRESS").performScrollTo()
            } finally {
                composeRule.runOnUiThread {
                    glassesScope.cancel()
                    shellOwner.close()
                }
                models.close()
            }
        }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
