// Pairs fake Even G2 and Halo glasses from settings on one glasses screen, switching kinds, through the real owner.
package com.fghbuild.gomode.glasses

import android.Manifest
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2Charge
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
import com.fghbuild.gomode.voice.VoiceIssues
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

// A serial as long as a real pair's, which the saved row shows as its identity.
private const val SERIAL = "S200GCBD005306"
private val PAIR =
    G2Pair(
        SERIAL,
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
            firmware.charge = G2Charge(71, charging = true)
            firmware.case = G2Charge(55, charging = false)
            val halo = ScanningFakeHalo(context, HALO_ADDRESS)
            val glassesScope = MainScope()
            val glasses =
                GlassesOwner(
                    glassesScope,
                    context,
                    repository,
                    halo,
                    fakeG2Scanner(),
                    firmware.connector,
                    VoiceIssues(),
                )
            val models = LocalModelOwner(context, localModelInstallation(ModelDownloadState.Missing(0)))
            val shellOwner = ServiceShellOwner(context, repository, glasses::connectedDisplay)
            try {
                // Without a service, Go Mode opens on its settings.
                composeRule.setContent { GoModeTheme { GoModeApp(shellOwner, models, glasses) } }
                waitForTag("gomode-manage-glasses")
                composeRule.onNodeWithText("Even G2 $SERIAL").performScrollTo()
                composeRule.onNodeWithTag("gomode-manage-glasses").performClick()
                waitForTag("gomode-glasses-screen")
                composeRule.onNodeWithTag("gomode-glasses-saved", useUnmergedTree = true).assertTextEquals(SERIAL)

                composeRule.onNodeWithTag("gomode-glasses-connect").performClick()
                composeRule.waitUntil(TIMEOUT_MS) { glasses.connectedDisplay == G2_DISPLAY }
                composeRule
                    .onNodeWithTag("gomode-glasses-link-state", useUnmergedTree = true)
                    .assertContentDescriptionEquals("Connected")
                // The dot says it; no status text stands beside it.
                composeRule.onNodeWithTag("gomode-glasses-link", useUnmergedTree = true).assertDoesNotExist()
                // The glasses greet the connection, then show the blank frame.
                composeRule.waitUntil(TIMEOUT_MS) { firmware.shownText() == GREETING_TEXT }
                composeRule.waitUntil(TIMEOUT_MS) { firmware.shownText() == "" }

                // The input events screen lists a double tap, the glasses show the same list, and the
                // double tap's action is held; system back returns to the glasses screen.
                val acted = AtomicBoolean(false)
                composeRule.runOnUiThread {
                    glasses.takeActionsFirst {
                        acted.set(true)
                        true
                    }
                }
                composeRule.onNodeWithTag("gomode-glasses-open-inputs").performClick()
                waitForTag("gomode-glasses-inputs")
                composeRule.waitUntil(TIMEOUT_MS) { firmware.shownText() == "Input events\nTry a gesture." }
                firmware.touch(G2Side.RIGHT, type = G2InputEvent.DOUBLE_CLICK)
                composeRule.waitUntil(TIMEOUT_MS) {
                    composeRule.onAllNodesWithTag("gomode-glasses-input").fetchSemanticsNodes().size == 1
                }
                composeRule.onNodeWithText("Double tap").assertExists()
                composeRule.waitUntil(TIMEOUT_MS) { firmware.shownText() == "Input events\nDouble tap" }
                composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
                waitForTag("gomode-glasses-screen")
                composeRule.waitUntil(TIMEOUT_MS) { firmware.shownText() == "" }
                assertFalse(acted.get())
                composeRule.runOnUiThread { glasses.takeActionsFirst(null) }

                // The glasses' level comes with the session's first settings read and the case's once
                // the session is quiet; a screen reader hears them in words.
                composeRule.waitUntil(TIMEOUT_MS) {
                    composeRule
                        .onAllNodesWithTag("gomode-glasses-battery", useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .any {
                            it.config.getOrNull(SemanticsProperties.ContentDescription) ==
                                listOf("Glasses 71%, charging. Case 55%")
                        }
                }

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
                // Halo reports no battery.
                composeRule.onNodeWithTag("gomode-glasses-battery", useUnmergedTree = true).assertDoesNotExist()
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
