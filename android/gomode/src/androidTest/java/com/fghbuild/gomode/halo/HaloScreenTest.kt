// Exercises the Halo screen from native settings: scanning, auto-connect, and a link that outlives its Activity.
package com.fghbuild.gomode.halo

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
import com.fghbuild.gomode.GOMODE_DEFAULT_TIMEOUT_MS
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.GoModeE2eTestBase
import com.fghbuild.gomode.glasses.GlassesLink
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// A documentation-range address that no glasses answer to.
private const val UNREACHABLE_ADDRESS = "00:00:5E:00:53:01"

@RunWith(AndroidJUnit4::class)
class HaloScreenTest : GoModeE2eTestBase() {
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
    fun settingsOpenHaloWhichScansAndPersistsAutoConnect() {
        openHaloScreen()
        composeRule.onNodeWithTag("gomode-halo-link", useUnmergedTree = true).assertTextEquals("Disconnected")
        composeRule.onNodeWithTag("gomode-halo-connect").assertDoesNotExist()

        composeRule.onNodeWithTag("gomode-halo-scan").performClick()
        // The emulator's Bluetooth stack scans; no glasses advertise there.
        waitForTag("gomode-halo-stop-scan")
        composeRule.onNodeWithTag("gomode-halo-stop-scan").performClick()
        waitForTag("gomode-halo-scan")

        composeRule.onNodeWithTag("gomode-halo-auto-connect").assertIsOff().performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { app.settingsRepository.settings.value.haloAutoConnect }
        composeRule.onNodeWithTag("gomode-halo-auto-connect").assertIsOn()

        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForTag("gomode-settings")
    }

    @Test
    fun savedHaloStaysLinkedAcrossActivityReplacementUntilDisconnected() {
        runBlocking { app.settingsRepository.updateHaloAddress(UNREACHABLE_ADDRESS) }
        openHaloScreen()
        waitForTag("gomode-halo-connect")
        composeRule.onNodeWithTag("gomode-halo-connect").performClick()
        waitForTag("gomode-halo-disconnect")

        composeRule.activityRule.scenario.recreate()
        openHaloScreen()
        // The application's owner kept connecting while the Activity was replaced.
        waitForTag("gomode-halo-disconnect")
        assertNotEquals(GlassesLink.Disconnected, app.glassesOwner.state.value.link)

        composeRule.onNodeWithTag("gomode-halo-disconnect").performClick()
        composeRule.onNodeWithTag("gomode-halo-link", useUnmergedTree = true).assertTextEquals("Disconnected")
        waitForTag("gomode-halo-connect")
    }

    private fun openHaloScreen() {
        waitForTag("gomode-settings")
        composeRule.onNodeWithTag("gomode-manage-halo").performScrollTo().performClick()
        waitForTag("gomode-halo-screen")
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
