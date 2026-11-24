// Exercises the glasses screen from native settings: scanning, auto-connect, and Halo and Even G2 links that outlive their Activity.
package com.fghbuild.gomode.glasses

import android.Manifest
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Side
import com.fghbuild.gomode.GOMODE_DEFAULT_TIMEOUT_MS
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.GoModeE2eTestBase
import com.fghbuild.gomode.data.GlassesDevice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Documentation-range addresses that no glasses answer to.
private const val UNREACHABLE_ADDRESS = "00:00:5E:00:53:01"
private val UNREACHABLE_G2 =
    G2Pair(
        "7",
        G2Arm(G2Side.LEFT, "00:00:5E:00:53:03", "Even G2_7_L_005303"),
        G2Arm(G2Side.RIGHT, "00:00:5E:00:53:04", "Even G2_7_R_005304"),
    )

@RunWith(AndroidJUnit4::class)
class GlassesScreenTest : GoModeE2eTestBase() {
    @get:Rule(order = 1)
    val bluetoothPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GoModeApplication

    // The application owns the glasses across tests; leave them idle for the next one.
    @After
    fun releaseGlasses() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.glassesOwner.stopScan()
            app.glassesOwner.disconnect()
        }
    }

    @Test
    fun settingsOpenGlassesWhichScansAndPersistsAutoConnect() {
        openGlassesScreen()
        composeRule.onNodeWithTag("gomode-glasses-link", useUnmergedTree = true).assertTextEquals("Disconnected")
        composeRule.onNodeWithTag("gomode-glasses-connect").assertDoesNotExist()

        composeRule.onNodeWithTag("gomode-glasses-scan").performClick()
        // The emulator's Bluetooth stack scans for both models; no glasses advertise there.
        waitForTag("gomode-glasses-stop-scan")
        composeRule.onNodeWithTag("gomode-glasses-stop-scan").performClick()
        waitForTag("gomode-glasses-scan")
        composeRule.onNodeWithTag("gomode-glasses-error").assertDoesNotExist()

        composeRule.onNodeWithTag("gomode-glasses-auto-connect").assertIsOff().performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { app.settingsRepository.settings.value.glassesAutoConnect }
        composeRule.onNodeWithTag("gomode-glasses-auto-connect").assertIsOn()

        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForTag("gomode-settings")
    }

    @Test
    fun savedHaloStaysLinkedAcrossActivityReplacementUntilDisconnected() {
        savedGlassesStayLinkedAcrossActivityReplacement(
            GlassesDevice.Halo(UNREACHABLE_ADDRESS),
            "Halo · $UNREACHABLE_ADDRESS",
        )
    }

    @Test
    fun savedEvenG2StaysLinkedAcrossActivityReplacementUntilDisconnected() {
        savedGlassesStayLinkedAcrossActivityReplacement(GlassesDevice.EvenG2(UNREACHABLE_G2), "Even G2 7")
    }

    // Both models connect through the platform's Bluetooth stack, which retries the unreachable glasses.
    private fun savedGlassesStayLinkedAcrossActivityReplacement(
        glasses: GlassesDevice,
        label: String,
    ) {
        runBlocking { app.settingsRepository.updateGlasses(glasses) }
        openGlassesScreen()
        composeRule.onNodeWithTag("gomode-glasses-saved", useUnmergedTree = true).assertTextEquals(label)
        waitForTag("gomode-glasses-connect")
        composeRule.onNodeWithTag("gomode-glasses-connect").performClick()
        waitForTag("gomode-glasses-disconnect")

        composeRule.activityRule.scenario.recreate()
        openGlassesScreen()
        // The application's owner kept connecting while the Activity was replaced.
        waitForTag("gomode-glasses-disconnect")
        assertNotEquals(GlassesLink.Disconnected, app.glassesOwner.state.value.link)

        composeRule.onNodeWithTag("gomode-glasses-disconnect").performClick()
        composeRule.onNodeWithTag("gomode-glasses-link", useUnmergedTree = true).assertTextEquals("Disconnected")
        waitForTag("gomode-glasses-connect")
    }

    private fun openGlassesScreen() {
        waitForTag("gomode-settings")
        composeRule.onNodeWithTag("gomode-manage-glasses").performScrollTo().performClick()
        waitForTag("gomode-glasses-screen")
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
