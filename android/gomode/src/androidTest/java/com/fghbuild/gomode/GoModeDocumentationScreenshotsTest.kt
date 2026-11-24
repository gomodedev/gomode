// Captures native service, voice settings and pickers, glasses over fake transports, and a demo frontend.
package com.fghbuild.gomode

import android.Manifest
import android.os.Environment
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.testing.FakeG2Firmware
import com.fghbuild.eveng2.testing.fakeG2Scanner
import com.fghbuild.gomode.glasses.GlassesOwner
import com.fghbuild.gomode.glasses.ScanningFakeHalo
import com.fghbuild.gomode.ui.GoModeApp
import com.fghbuild.gomode.ui.theme.GoModeTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val SETTLE_CHECK_INTERVAL_MS = 100L

// Documentation-range addresses for the glasses the fake transports report.
private const val CAPTURE_HALO_ADDRESS = "00:00:5E:00:53:05"
private val CAPTURE_G2_PAIR =
    G2Pair(
        "7",
        G2Arm(G2Side.LEFT, "00:00:5E:00:53:06", "Even G2_7_L_005306"),
        G2Arm(G2Side.RIGHT, "00:00:5E:00:53:07", "Even G2_7_R_005307"),
    )

@RunWith(AndroidJUnit4::class)
@DocumentationCapture
class GoModeDocumentationScreenshotsTest : GoModeE2eTestBase() {
    // Both capture passes must start with the same permission state before Activity launch. Grants
    // persist across installs, so granting Bluetooth too keeps the glasses scene independent of earlier
    // tests on the emulator.
    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

    override fun validateDeviceBeforeSetup() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1") {
            "Documentation captures require an emulator"
        }
    }

    @Test
    fun captureNativeShellAndHostedService() {
        assumeTrue(
            "documentation captures require the explicit screenshot runner",
            InstrumentationRegistry.getArguments().getString("gomodeScreenshots") == "true",
        )
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val directory =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "gomode-screenshots",
            )
        check(directory.mkdirs() || directory.isDirectory) { "Could not create screenshot directory" }
        directory.listFiles()?.forEach { check(it.delete()) { "Could not remove old screenshot ${it.name}" } }

        waitForTag("gomode-settings")
        // Back closes the Activity unless the keyboard is up, so each field waits for it.
        fillServiceField("gomode-service-label", "Development")
        fillServiceField("gomode-service-url", baseUrl)
        dismissServiceKeyboard()
        capture(device, directory, "service-editor")

        composeRule.onNodeWithTag("gomode-create-service").performScrollTo().performClick()
        try {
            waitForHostedFrontend()
        } catch (error: IllegalStateException) {
            device.takeScreenshot(File(directory, "failure.png"))
            throw IllegalStateException(composeRule.onRoot().printToString(), error)
        }
        waitForDom("demonstration service loaded") { "isVisible('#demo-service')" }
        waitForSettledHostedPage()
        capture(device, directory, "hosted-service")

        waitForSettingsButton()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.onNodeWithTag("gomode-add-service").performClick()
        composeRule.onNodeWithTag("gomode-service-label").performTextReplacement("Staging")
        composeRule.onNodeWithTag("gomode-service-url").performTextReplacement("$baseUrl/staging")
        composeRule.onNodeWithTag("gomode-create-service").performScrollTo().performClick()
        waitForHostedFrontend()
        waitForSettledHostedPage()
        waitForSettingsButton()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        waitForTag("gomode-settings")
        // Capture the settled service list, not a transient reconnect state.
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Ready").fetchSemanticsNodes().size >= 2
        }
        capture(device, directory, "connections")

        // Both segments stay disabled until the gateway's choices load. The browser preview
        // captures the same setup: gateway recognition and language model, then local speech.
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule
                .onAllNodes(hasTestTag("gomode-voice-setup-separate-stages") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("gomode-voice-setup-separate-stages").performScrollTo().performClick()
        composeRule.onNodeWithTag("gomode-speech-device").performScrollTo().performClick()
        // The speaking-voice picker enables once the TextToSpeech engine lists its voices.
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule
                .onAllNodes(hasTestTag("gomode-device-speaking-voice") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // The closed menu's anchor keeps focus; capture the resting outline instead.
        composeRule.runOnUiThread { composeRule.activity.currentFocus?.clearFocus() }
        // Start at the Voice heading: scrolled past it, scrolling back aligns it with the top edge.
        composeRule.onNodeWithTag("gomode-show-usage-stats").performScrollTo()
        composeRule.onNode(hasText("Voice") and isHeading()).performScrollTo()
        capture(device, directory, "voice-settings")

        // The open speed menu matches its fitted field: every item and the current item's check fit.
        composeRule.onNodeWithTag("gomode-device-speech-rate").performScrollTo().performClick()
        waitForTag("gomode-device-speech-rates")
        capture(device, directory, "voice-settings-speed-menu")
        device.pressBack()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-device-speech-rates").fetchSemanticsNodes().isEmpty()
        }

        // An all-in-one model lists its own voices, which fit a narrow picker.
        composeRule.onNodeWithTag("gomode-voice-setup-all-in-one").performScrollTo().performClick()
        composeRule.onNodeWithTag("gomode-voice-choice-native").performScrollTo().performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule
                .onAllNodes(hasTestTag("gomode-speaking-voice") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.runOnUiThread { composeRule.activity.currentFocus?.clearFocus() }
        composeRule.onNodeWithTag("gomode-show-usage-stats").performScrollTo()
        composeRule.onNode(hasText("Voice") and isHeading()).performScrollTo()
        capture(device, directory, "voice-settings-all-in-one")

        captureGlasses(device, directory)
    }

    // The emulator has no glasses, so the shell runs over the fake Even G2 and Halo transports that
    // GlassesPairingTest drives: a first scan finds one pair of each model.
    private fun captureGlasses(
        device: UiDevice,
        directory: File,
    ) {
        val app = composeRule.activity.application as GoModeApplication
        val scope = MainScope()
        val glasses =
            GlassesOwner(
                scope,
                app,
                app.settingsRepository,
                ScanningFakeHalo(app, CAPTURE_HALO_ADDRESS),
                fakeG2Scanner(CAPTURE_G2_PAIR),
                FakeG2Firmware().connector,
            )
        try {
            composeRule.runOnUiThread {
                composeRule.activity.setContent {
                    GoModeTheme { GoModeApp(app.serviceShellOwner, app.localModelOwner, glasses) }
                }
            }
            waitForSettingsButton()
            composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
            composeRule.onNodeWithTag("gomode-manage-glasses").performScrollTo().performClick()
            waitForTag("gomode-glasses-screen")
            composeRule.onNodeWithTag("gomode-glasses-scan").performClick()
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                composeRule.onAllNodesWithTag("gomode-glasses-found").fetchSemanticsNodes().size == 2
            }
            capture(device, directory, "glasses")
        } finally {
            scope.cancel()
        }
    }

    private fun waitForSettledHostedPage() {
        // The demo host settles a cookie-backed identity after load. Captures wait for
        // painted assets and a stable service rectangle so frames never show loading UI.
        waitForDom("hosted page assets settled") {
            """
            document.readyState === 'complete' &&
              document.fonts.status === 'loaded' &&
              Array.from(document.images).every((image) => image.complete && image.naturalWidth > 0) &&
              (() => {
                const rect = document.getElementById('demo-service')?.getBoundingClientRect();
                return !!rect && rect.width > 0 && rect.height > 0;
              })()
            """
        }
        repeat(300) {
            val bounds = demoServiceBounds()
            Thread.sleep(SETTLE_CHECK_INTERVAL_MS)
            if (bounds.isNotEmpty() && demoServiceBounds() == bounds) return
        }
        error("Timed out waiting for a stable demonstration-service rectangle")
    }

    private fun demoServiceBounds(): String =
        js(
            """
            (() => {
              const rect = document.getElementById('demo-service')?.getBoundingClientRect();
              return rect && rect.width > 0 && rect.height > 0
                ? [rect.x, rect.y, rect.width, rect.height].join(',')
                : '';
            })()
            """.trimIndent(),
        )

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(
        device: UiDevice,
        directory: File,
        name: String,
    ) {
        composeRule.waitForIdle()
        // Scenes show a first run. The prelaunch rule removes retained voice failures, which settings disclose.
        val voiceErrors = (composeRule.activity.application as GoModeApplication).voiceErrors
        check(voiceErrors.history.value.isEmpty()) { "Retained voice errors would appear in $name" }
        // Cold device/test initialization can reset the managed SystemUI profile.
        // Reapply it after shell readiness; the owning runner restores each value.
        val hiddenIcons =
            checkNotNull(InstrumentationRegistry.getArguments().getString("gomodeIconBlacklist")) {
                "The managed screenshot runner must supply its saved-and-augmented icon profile"
            }
        check(hiddenIcons.matches(Regex("[A-Za-z0-9_,]+"))) { "Invalid SystemUI icon slot profile" }
        // UiAutomation tokenizes the command directly, without shell quoting.
        device.executeShellCommand("settings put secure icon_blacklist $hiddenIcons")
        check(device.executeShellCommand("settings get secure icon_blacklist").trim() == hiddenIcons) {
            "SystemUI icon profile was not applied"
        }
        device.executeShellCommand("settings put global sysui_demo_allowed 1")
        // Wait for actual SystemUI command receivers after visible shell readiness.
        val controllerCommand =
            "dumpsys activity service com.android.systemui/.SystemUIService DemoModeController"
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            val state = device.executeShellCommand(controllerCommand)
            state.contains("isDemoModeAllowed=true") &&
                Regex("clock : \\[[^\\]]+\\]").containsMatchIn(state) &&
                Regex("battery : \\[[^\\]]+\\]").containsMatchIn(state)
        }
        // Demo Wi-Fi events have no replay; active connectivity avoids fallback
        // satellite icons. The runner hides only the three network icon slots.
        for (
        command in
        listOf(
            "enter",
            "clock -e hhmm 0930",
            "battery -e level 100 -e plugged false",
            "notifications -e visible false",
            "network -e wifi show -e level 4 -e fully true -e mobile hide",
        )
        ) {
            device.executeShellCommand("am broadcast -a com.android.systemui.demo -e command $command")
        }
        // SystemUI applies icon_blacklist and demo commands asynchronously; a cold first scene once
        // drew the demo Wi-Fi icon. Every Wi-Fi view, demo or real, reports HIDDEN once the slot is blocked.
        val wifiStates = Regex("WifiView\\(slot='wifi', isCollecting=\\w+, visibleState=(\\w+)")
        val systemUiCommand = "dumpsys activity service com.android.systemui/.SystemUIService"
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            val states =
                wifiStates
                    .findAll(device.executeShellCommand(systemUiCommand))
                    .map { it.groupValues[1] }
                    .toList()
            states.isNotEmpty() && states.all { it == "HIDDEN" }
        }
        device.waitForIdle()
        check(device.executeShellCommand(controllerCommand).contains("isInDemoMode=true")) {
            "SystemUI demo mode was reset before capturing $name"
        }
        check(device.takeScreenshot(File(directory, "$name.png"))) { "Could not capture $name" }
    }
}
