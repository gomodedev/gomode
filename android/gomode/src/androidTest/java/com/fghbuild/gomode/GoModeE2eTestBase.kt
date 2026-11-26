// Shared prelaunch validation, settings setup, and hosted WebView e2e helpers.
package com.fghbuild.gomode

import android.Manifest
import android.app.UiAutomation
import android.util.AtomicFile
import android.util.Log
import android.webkit.WebView
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal const val GOMODE_DEFAULT_TIMEOUT_MS = 30_000L
internal const val GOMODE_LOAD_TIMEOUT_MS = 60_000L

// AndroidComposeViewAccessibilityDelegateCompat.isEnabled$ui() returns false under UiAutomation
// when no AccessibilityService is enabled, so Compose skips TYPE_WINDOW_CONTENT_CHANGED events
// and AccessibilityInteractionClient serves stale cached nodes (and WebView updates asynchronously).
// Clear the cache on every poll and snapshot read.
internal fun UiDevice.hasFreshObject(
    uiAutomation: UiAutomation,
    selector: BySelector,
): Boolean {
    uiAutomation.clearCache()
    return hasObject(selector)
}

internal fun UiDevice.findFreshObject(
    uiAutomation: UiAutomation,
    selector: BySelector,
): UiObject2? {
    uiAutomation.clearCache()
    return findObject(selector)
}

internal fun UiDevice.findFreshObjects(
    uiAutomation: UiAutomation,
    selector: BySelector,
): List<UiObject2> {
    uiAutomation.clearCache()
    return findObjects(selector)
}

internal fun UiDevice.awaitFreshObject(
    uiAutomation: UiAutomation,
    selector: BySelector,
    timeoutMs: Long,
): UiObject2? =
    wait(
        {
            uiAutomation.clearCache()
            findObject(selector)
        },
        timeoutMs,
    )

internal fun UiDevice.awaitFreshGone(
    uiAutomation: UiAutomation,
    selector: BySelector,
    timeoutMs: Long,
): Boolean =
    wait(
        {
            uiAutomation.clearCache()
            !hasObject(selector)
        },
        timeoutMs,
    ) == true

internal fun captureFailureDiagnostics(label: String) {
    runCatching {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val dir = File(context.getExternalFilesDir(null), "failure-diagnostics").apply { mkdirs() }
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_")
        instrumentation.uiAutomation.clearCache()
        device.dumpWindowHierarchy(File(dir, "$safe.xml"))
        device.takeScreenshot(File(dir, "$safe.png"))
    }.onFailure { error ->
        Log.w("GoModeTestDiagnostics", "Could not capture failure diagnostics for $label", error)
    }
}

internal class FailureDiagnosticsRule : TestWatcher() {
    override fun failed(
        e: Throwable,
        description: Description,
    ) {
        captureFailureDiagnostics("${description.testClass.simpleName}_${description.methodName}")
    }
}

abstract class GoModeE2eTestBase {
    @get:Rule(order = 0)
    val clearSettingsRule =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    validateDeviceBeforeSetup()
                    enableSoftKeyboardWithHardwareKeyboard()
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    runBlocking { context.goModeSettingsDataStore.edit { it.clear() } }
                    // Settings disclose failures that earlier voice sessions retained. The log reads them
                    // once per process, when first used, so this clears them only for a process that has not.
                    AtomicFile(File(context.noBackupFilesDir, "voice-errors.json")).delete()
                    base.evaluate()
                }
            }
        }

    @get:Rule(order = 1)
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    // Rely on the v2 rule's StandardTestDispatcher default so DataStore completions
    // do not resume unconfined recomposition on IO threads.
    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule(order = 3)
    val failureDiagnosticsRule: TestRule = FailureDiagnosticsRule()

    protected val baseUrl: String by lazy {
        InstrumentationRegistry.getArguments().getString("baseUrl", DEFAULT_BASE_URL)
    }

    // Specialized suites establish device requirements before any setup rule mutates it.
    protected open fun validateDeviceBeforeSetup() = Unit

    protected fun openWebShell() {
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            hasNodeWithTag("gomode-service-url") || hasNodeWithTag("gomode-web-shell")
        }
        if (hasNodeWithTag("gomode-service-url")) {
            fillServiceField("gomode-service-url", baseUrl)
            dismissServiceKeyboard()
            composeRule.onNodeWithTag("gomode-create-service").performScrollTo().performClick()
        }
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            hasNodeWithTag("gomode-web-shell")
        }
        waitForSettingsButton()
    }

    protected fun fillServiceField(
        tag: String,
        text: String,
    ) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        // Let the input connection and IME settle before replacing text or navigating.
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { isImeVisible() }
        composeRule.onNodeWithTag(tag).performTextReplacement(text)
    }

    protected fun dismissServiceKeyboard() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { !isImeVisible() }
    }

    // The settings button sits in the voice panel, which the shell hides while the soft
    // keyboard is closing. That IME dismissal races the "gomode-web-shell" tag appearing,
    // so callers that need to click the settings button must wait for it separately.
    protected fun waitForSettingsButton() {
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            hasNodeWithTag("gomode-web-open-settings")
        }
    }

    protected fun waitForHostedFrontend() {
        // Synchronize Compose before polling a platform view. Native clicks can
        // schedule state changes whose frames the Compose test clock must advance.
        composeRule.waitUntil(GOMODE_LOAD_TIMEOUT_MS) { hasNodeWithTag("gomode-web-shell") }
        waitForWebView()
        waitForDom("frontend origin") { "location.origin === ${baseUrl.jsString()}" }
        waitForDom("Go Mode host query") { "new URL(location.href).searchParams.get('goModeHost') === '1'" }
        waitForDom("Go Mode host bridge") { "typeof window.goModeHost?.shellVersion === 'function'" }
        waitForDom("frontend document ready") { "document.readyState === 'complete'" }
        waitForDom("frontend body has content") { "document.body?.innerText.trim().length > 0" }
    }

    protected fun waitForText(
        text: String,
        timeoutMs: Long = GOMODE_DEFAULT_TIMEOUT_MS,
    ) {
        waitForDom("text '$text'", timeoutMs) {
            "document.body?.innerText.includes(${text.jsString()}) === true"
        }
    }

    protected fun waitForDom(
        description: String,
        timeoutMs: Long = GOMODE_DEFAULT_TIMEOUT_MS,
        script: () -> String,
    ) {
        val condition = script()
        var nextPoll = 0L
        // Waiting through Compose advances the test clock, so lifecycle and visibility effects keep
        // running; a sleeping wait would freeze them. JavaScript still runs at most once per interval.
        val met =
            try {
                composeRule.waitUntil(timeoutMs) {
                    val now = System.nanoTime()
                    if (now < nextPoll) return@waitUntil false
                    nextPoll = now + TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MS)
                    js(wrapDomHelpers(condition)) == "true"
                }
                true
            } catch (_: ComposeTimeoutException) {
                false
            }
        if (met) return
        val bodyText = js("document.body?.innerText.slice(0, 1000) ?? ''")
        error("Timed out waiting for DOM condition '$description': $condition\nBody: $bodyText")
    }

    protected fun executeDom(
        description: String,
        script: () -> String,
    ) {
        val result = js(wrapDomHelpers(script()))
        check(result == "true") { "DOM action failed '$description': $result" }
    }

    protected fun waitForWebView(timeoutMs: Long = GOMODE_DEFAULT_TIMEOUT_MS): WebView {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            webViewOrNull()?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("WebView was not created")
    }

    protected fun pressActivityBack() {
        val latch = CountDownLatch(1)
        composeRule.activity.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
            latch.countDown()
        }
        check(latch.await(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Back dispatch timed out" }
    }

    protected fun tapDomElement(selector: String) {
        val view = waitForWebView()
        val widthScale = view.width / js("window.innerWidth").toFloat()
        val heightScale = view.height / js("window.innerHeight").toFloat()
        val xCss =
            js(
                """
                (() => {
                  const r = document.querySelector(${selector.jsString()}).getBoundingClientRect();
                  return r.left + r.width / 2;
                })()
                """.trimIndent(),
            ).toFloat()
        val yCss =
            js(
                """
                (() => {
                  const r = document.querySelector(${selector.jsString()}).getBoundingClientRect();
                  return r.top + r.height / 2;
                })()
                """.trimIndent(),
            ).toFloat()
        val location = webViewScreenLocation()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).click(
            location[0] + (xCss * widthScale).roundToInt(),
            location[1] + (yCss * heightScale).roundToInt(),
        )
    }

    protected fun webViewScreenLocation(): IntArray {
        val view = waitForWebView()
        val location = IntArray(2)
        val latch = CountDownLatch(1)
        composeRule.activity.runOnUiThread {
            view.getLocationOnScreen(location)
            latch.countDown()
        }
        check(latch.await(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "WebView bounds lookup timed out" }
        return location
    }

    protected fun isImeVisible(): Boolean {
        var visible = false
        val latch = CountDownLatch(1)
        composeRule.activity.runOnUiThread {
            val root = composeRule.activity.window.decorView.rootView
            visible = root.rootWindowInsets
                ?.getInsets(
                    android.view.WindowInsets.Type
                        .ime(),
                )?.bottom
                ?.let { it > 0 }
                ?: false
            latch.countDown()
        }
        check(latch.await(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "IME inset lookup timed out" }
        return visible
    }

    protected fun js(script: String): String {
        var result: String? = null
        val latch = CountDownLatch(1)
        val view = waitForWebView()
        composeRule.activity.runOnUiThread {
            view.evaluateJavascript(script) {
                result = it
                latch.countDown()
            }
        }
        check(latch.await(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "JavaScript evaluation timed out" }
        return result ?: "null"
    }

    private fun enableSoftKeyboardWithHardwareKeyboard() {
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            .close()
    }

    private fun hasNodeWithTag(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun webViewOrNull(): WebView? {
        var view: WebView? = null
        val latch = CountDownLatch(1)
        composeRule.activity.runOnUiThread {
            view = composeRule.activity.findViewById(R.id.web_shell)
            latch.countDown()
        }
        check(latch.await(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "WebView lookup timed out" }
        return view
    }

    private fun wrapDomHelpers(script: String): String =
        """
        (() => {
          const isVisible = (selector) => {
            const el = document.querySelector(selector);
            if (!el) return false;
            const rect = el.getBoundingClientRect();
            const style = getComputedStyle(el);
            return rect.width > 0 && rect.height > 0 &&
              style.visibility !== "hidden" && style.display !== "none";
          };
          const isEnabled = (selector) => {
            const el = document.querySelector(selector);
            return !!el && !el.disabled;
          };
          return Boolean($script);
        })()
        """.trimIndent()

    protected fun String.jsString(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    companion object {
        private const val DEFAULT_BASE_URL = "http://localhost:8090"
        private const val JS_TIMEOUT_MS = 5_000L
        private const val POLL_INTERVAL_MS = 250L
    }
}
