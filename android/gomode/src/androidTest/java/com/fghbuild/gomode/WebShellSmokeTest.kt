// Instrumented smoke coverage for the Go Mode WebView shell.
package com.fghbuild.gomode

import android.Manifest
import android.app.Instrumentation.ActivityResult
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class WebShellSmokeTest : GoModeE2eTestBase() {
    // The real prompt pauses the Activity and answers before it resumes; the page stays visible under it.
    // The emulator camera cannot open in WebView, so the microphone covers the shared camera and
    // microphone path.
    @Test
    @DeniedMicrophonePermission
    fun hostedMicrophoneCaptureStartsWhenTheAndroidPromptIsAllowed() {
        openWebShell()
        waitForHostedFrontend()
        executeDom("request the microphone") {
            """
            (() => {
              window.capture = 'pending';
              navigator.mediaDevices.getUserMedia({audio: true}).then(stream => {
                window.capture = stream.getAudioTracks().every(track => track.readyState === 'live') ? 'live' : 'ended';
                stream.getTracks().forEach(track => track.stop());
              }, error => { window.capture = error.name; });
              return true;
            })()
            """.trimIndent()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val allow =
            requireNotNull(
                device.awaitFreshObject(
                    instrumentation.uiAutomation,
                    By.res(Pattern.compile(".*:id/permission_allow_foreground_only_button")),
                    GOMODE_DEFAULT_TIMEOUT_MS,
                ),
            ) { "Microphone permission prompt did not appear" }
        assertEquals("true", js("goModeHost.isPageVisible() && goModeHost.isMediaAllowed()"))
        allow.click()
        waitForDom("hosted capture settles after the prompt") { "window.capture !== 'pending'" }
        assertEquals("\"live\"", js("window.capture"))
    }

    @Test
    @DeniedMicrophonePermission
    fun microphoneDenialOffersPromptThenSettingsWithoutStartingVoiceOnReenable() {
        openWebShell()
        waitForHostedFrontend()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val uiAutomation = instrumentation.uiAutomation

        fun waitForAppFocus() {
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                composeRule.runOnUiThread { composeRule.activity.hasWindowFocus() }
            }
        }

        fun denyPermission() {
            val deny =
                device.awaitFreshObject(
                    uiAutomation,
                    By.res(Pattern.compile(".*:id/permission_deny.*_button")),
                    GOMODE_DEFAULT_TIMEOUT_MS,
                )
            requireNotNull(deny) { "Microphone permission prompt did not appear" }.click()
            waitForAppFocus()
        }
        composeRule.onNodeWithTag("gomode-voice-connect").performClick()
        requireNotNull(
            device.awaitFreshObject(uiAutomation, By.textContains("record audio"), GOMODE_DEFAULT_TIMEOUT_MS),
        )
        device.pressBack()
        waitForAppFocus()
        composeRule.onNodeWithText("Enable microphone").performClick()
        denyPermission()
        composeRule.onNodeWithText("Enable microphone").performClick()
        denyPermission()
        composeRule.onNodeWithText("Enable microphone").assertDoesNotExist()
        composeRule.onNodeWithTag("gomode-microphone-recovery").assertExists()
        if (InstrumentationRegistry.getArguments().getString("captureVoicePanel") == "true") {
            device.executeShellCommand("screencap -p /data/local/tmp/gomode-microphone-denied.png")
        }
        composeRule.onNodeWithText("Open app settings").performClick()
        requireNotNull(
            device.awaitFreshObject(
                uiAutomation,
                By.pkg("com.android.settings").text("Go Mode"),
                GOMODE_DEFAULT_TIMEOUT_MS,
            ),
        ) {
            val shown = device.findFreshObjects(uiAutomation, By.text(Pattern.compile(".+"))).mapNotNull { it.text }
            "System settings did not show Go Mode's app info in ${device.currentPackageName}; visible: $shown"
        }
        // Grant while Settings is in front, as the user would there. Settings' App info
        // rows load asynchronously and dropped taps on Permissions on CI; the app only
        // observes the grant when it resumes.
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        device.pressBack()
        waitForAppFocus()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-microphone-recovery").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("gomode-voice-connect").assertExists()
        composeRule.onNodeWithTag("gomode-voice-disconnect").assertDoesNotExist()
        tapDomElement("#save-note")
        waitForText("Saved to your field notes.")
    }

    @Test
    fun webShellLoadsHostedFrontendAndHandlesSpaBack() {
        openWebShell()
        waitForHostedFrontend()
        waitForDom("hosted frontend is visible") { "isVisible('#demo-service')" }
        tapDomElement("#save-note")
        waitForText("Saved to your field notes.")
        val webView = waitForWebView()
        val fillsViewport =
            composeRule.runOnUiThread {
                val visibleBounds = Rect()
                val windowBounds = composeRule.activity.window.decorView
                webView.getGlobalVisibleRect(visibleBounds) &&
                    visibleBounds.width() > windowBounds.width / 2 &&
                    visibleBounds.height() > windowBounds.height / 2
            }
        assertTrue("Hosted WebView does not fill the activity viewport", fillsViewport)
        waitForDom("CSS viewport height matches the hosted viewport") {
            """
            window.innerHeight > 0 &&
              Math.abs(parseFloat(getComputedStyle(document.querySelector('main')).minHeight) -
                window.innerHeight) < 1
            """.trimIndent()
        }

        executeDom("push SPA route") {
            """
            (() => {
              window.history.pushState(null, "", "$SPA_BACK_TEST_PATH");
              window.dispatchEvent(new PopStateEvent("popstate"));
              return true;
            })()
            """.trimIndent()
        }
        waitForDom("pushed SPA route") { "location.pathname === '$SPA_BACK_TEST_PATH'" }

        pressActivityBack()
        waitForDom("SPA back returned home") { "location.pathname === '/'" }
    }

    @Test
    fun systemAppearanceUpdatesTheHostedPageWithoutReloadingIt() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1") {
            "Changing the system appearance in this test requires an emulator"
        }
        val original = device.executeShellCommand("cmd uimode night").trim().removePrefix("Night mode: ")
        check(original in setOf("auto", "custom_bedtime", "custom_schedule", "no", "yes")) {
            "Unexpected night mode: $original"
        }
        try {
            device.executeShellCommand("cmd uimode night no")
            openWebShell()
            waitForHostedFrontend()
            waitForDom("the hosted light palette is applied") {
                "getComputedStyle(document.documentElement).backgroundColor === 'rgb(242, 246, 243)'"
            }
            tapDomElement("#save-note")
            waitForText("Saved to your field notes.")

            device.executeShellCommand("cmd uimode night yes")
            waitForHostedFrontend()
            waitForDom("the hosted dark palette is applied") {
                "getComputedStyle(document.documentElement).backgroundColor === 'rgb(16, 27, 27)'"
            }
            composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
            composeRule.onNodeWithTag("gomode-settings").assertExists()
            device.executeShellCommand("cmd uimode night no")
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                composeRule.runOnUiThread {
                    val mode = composeRule.activity.resources.configuration.uiMode
                    mode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO
                }
            }
            composeRule.onNodeWithTag("gomode-settings").assertExists()
            pressActivityBack()
            waitForHostedFrontend()
            waitForDom("the retained page returns to the hosted light palette") {
                "getComputedStyle(document.documentElement).backgroundColor === 'rgb(242, 246, 243)'"
            }
            assertEquals("\"Saved to your field notes.\"", js("document.querySelector('#note-status').textContent"))
        } finally {
            device.executeShellCommand("cmd uimode night $original")
        }
    }

    @Test
    fun pinchGestureZoomsTheHostedFrontend() {
        openWebShell()
        waitForHostedFrontend()
        val webView = waitForWebView()
        composeRule.runOnUiThread {
            assertTrue("Hosted WebView does not support zoom", webView.settings.supportZoom())
            assertTrue("Hosted WebView does not use built-in zoom controls", webView.settings.builtInZoomControls)
            assertFalse("Hosted WebView shows zoom buttons", webView.settings.displayZoomControls)
        }
        val initialScale = js("window.visualViewport.scale").toDouble()
        val location = webViewScreenLocation()
        val (width, height) = composeRule.runOnUiThread { webView.width to webView.height }
        pinchOpen(
            centerX = location[0] + width / 2f,
            centerY = location[1] + height / 3f,
            fromHalfSpan = 40f,
            toHalfSpan = 200f,
        )
        waitForDom("pinch zoom enlarges the hosted page") {
            "window.visualViewport.scale > ${initialScale + 0.1}"
        }
    }

    // WebView zooms on real two-finger touch, so inject trusted multi-pointer events.
    private fun pinchOpen(
        centerX: Float,
        centerY: Float,
        fromHalfSpan: Float,
        toHalfSpan: Float,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val properties =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
                MotionEvent.PointerProperties().apply {
                    id = 1
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )

        fun event(
            action: Int,
            pointerCount: Int,
            halfSpan: Float,
        ) {
            val coords =
                Array(2) { index ->
                    MotionEvent.PointerCoords().apply {
                        x = centerX + if (index == 0) -halfSpan else halfSpan
                        y = centerY
                        pressure = 1f
                        size = 1f
                    }
                }
            val motion =
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    action,
                    pointerCount,
                    properties,
                    coords,
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0,
                )
            automation.injectInputEvent(motion, true)
            motion.recycle()
        }

        val secondPointerDown = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        event(MotionEvent.ACTION_DOWN, 1, fromHalfSpan)
        SystemClock.sleep(TOUCH_EVENT_INTERVAL_MS)
        event(MotionEvent.ACTION_POINTER_DOWN or secondPointerDown, 2, fromHalfSpan)
        repeat(PINCH_STEPS) { step ->
            SystemClock.sleep(TOUCH_EVENT_INTERVAL_MS)
            event(
                MotionEvent.ACTION_MOVE,
                2,
                fromHalfSpan + ((toHalfSpan - fromHalfSpan) * (step + 1) / PINCH_STEPS),
            )
        }
        SystemClock.sleep(TOUCH_EVENT_INTERVAL_MS)
        event(MotionEvent.ACTION_POINTER_UP or secondPointerDown, 2, toHalfSpan)
        SystemClock.sleep(TOUCH_EVENT_INTERVAL_MS)
        event(MotionEvent.ACTION_UP, 1, toHalfSpan)
    }

    @Test
    fun webShellSettingsButtonOpensNativeSettingsAndBackReturnsToWebShell() {
        openWebShell()

        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-settings").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("gomode-web-open-settings").assertDoesNotExist()
        composeRule.onNodeWithTag("gomode-voice-connect").assertDoesNotExist()
        if (InstrumentationRegistry.getArguments().getString("captureVoicePanel") == "true") {
            UiDevice
                .getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand("screencap -p /data/local/tmp/gomode-settings.png")
        }
        pressActivityBack()

        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-web-shell").fetchSemanticsNodes().isNotEmpty()
        }
        waitForSettingsButton()
        // Voice turns connectable only after bootstrap, auth, and the frontend report ready.
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-voice-connect").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    @LocalEmulatorOnly
    fun settingsCanAddAndEditServicesWithoutSwitchingTheActiveService() {
        openWebShell()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        addService("Second")
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-web-shell").fetchSemanticsNodes().isNotEmpty()
        }
        waitForSettingsButton()

        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.onAllNodesWithContentDescription("Edit service").onFirst().performClick()
        waitForServiceEditor()
        fillServiceField("gomode-service-label", "First renamed")
        closeServiceEditor()

        composeRule.onNodeWithText("First renamed").assertExists()
        composeRule.onNodeWithText("Second").assertExists()
        composeRule.onNodeWithText("Active").assertExists()

        // Aliases are unique: a conflicting alias blocks Done until it changes.
        composeRule.onAllNodesWithContentDescription("Edit service").onFirst().performClick()
        waitForServiceEditor()
        fillServiceField("gomode-service-label", "second")
        composeRule.onNodeWithText("Another service uses this alias.").assertExists()
        composeRule.onNodeWithTag("gomode-service-done").performScrollTo().assertIsNotEnabled()
        fillServiceField("gomode-service-label", "First renamed")
        closeServiceEditor()
        composeRule.onNodeWithText("First renamed").assertExists()

        // URLs are unique after normalization: a duplicate blocks creation.
        composeRule.onNodeWithTag("gomode-add-service").performScrollTo().performClick()
        waitForServiceEditor()
        fillServiceField("gomode-service-label", "Third")
        fillServiceField("gomode-service-url", "$baseUrl/")
        composeRule.onNodeWithText("Another service uses this URL.").assertExists()
        composeRule.onNodeWithTag("gomode-create-service").performScrollTo().assertIsNotEnabled()
        dismissServiceKeyboard()
        composeRule.onNodeWithTag("gomode-cancel-service").performScrollTo().performClick()

        // Deleting asks for confirmation, then removes only that service.
        composeRule.onAllNodesWithContentDescription("Delete service").onFirst().performClick()
        composeRule.onNodeWithTag("gomode-confirm-delete-service").performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithText("First renamed").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("Second").assertExists()
        val observer = SettingsRepository(composeRule.activity.goModeSettingsDataStore)
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            observer.settings.value.services
                .map { it.label } == listOf("Second")
        }
    }

    @Test
    fun backFlushesAPendingServiceEditBeforeClosingSettings() {
        openWebShell()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        addService("Second")
        waitForSettingsButton()
        val observer = SettingsRepository(composeRule.activity.goModeSettingsDataStore)
        composeRule.waitUntil(
            conditionDescription = "second service is saved",
            timeoutMillis = GOMODE_DEFAULT_TIMEOUT_MS,
        ) { observer.settings.value.services.size == 2 }
        val activeServiceId = observer.settings.value.activeServiceId
        val first =
            observer.settings.value.services
                .first()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.onNodeWithTag("gomode-edit-service-${first.id}").performClick()
        composeRule.waitUntil(
            conditionDescription = "service editor shows the saved label",
            timeoutMillis = GOMODE_DEFAULT_TIMEOUT_MS,
        ) {
            composeRule
                .onNodeWithTag("gomode-service-label")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text == first.label
        }
        // Freeze the autosave debounce so the edit is still pending when the screen
        // closes. Typing through semantics keeps the soft keyboard out of the test:
        // its appearance and dismissal are device state, not app state.
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag("gomode-service-label").performTextReplacement("Pending name")
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(
                "the edit stays pending until the screen closes",
                first.label,
                observer.settings.value.services
                    .first()
                    .label,
            )
            assertEquals(
                "Pending name",
                composeRule
                    .onNodeWithTag("gomode-service-label")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.EditableText]
                    .text,
            )
            pressActivityBack()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitUntil(
            conditionDescription = "back flushes the pending service edit",
            timeoutMillis = GOMODE_DEFAULT_TIMEOUT_MS,
        ) {
            observer.settings.value.services
                .first()
                .label == "Pending name" &&
                observer.settings.value.activeServiceId == activeServiceId
        }
        waitForSettingsButton()
        composeRule.waitUntil(
            conditionDescription = "back closes the settings screen",
            timeoutMillis = GOMODE_DEFAULT_TIMEOUT_MS,
        ) {
            composeRule.onAllNodesWithTag("gomode-settings").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun switchingServersRetainsEachWebViewAndItsSpaState() {
        openWebShell()
        waitForHostedFrontend()
        val first = waitForWebView()
        executeDom("set first page state") { "(window.retainedMarker = 'first', true)" }
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.onAllNodesWithContentDescription("Edit service").onFirst().performClick()
        waitForServiceEditor()
        fillServiceField("gomode-service-label", "First retained")
        closeServiceEditor()
        addService("Second retained")
        waitForHostedFrontend()
        val second = waitForWebView()
        assertNotSame(first, second)
        executeDom("set second page state") { "(window.retainedMarker = 'second', true)" }
        waitForSettingsButton()
        composeRule.onNodeWithTag("gomode-next-app").performClick()
        // Selection is persisted asynchronously; the outgoing page also satisfies frontend readiness.
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.runOnUiThread { first.isAttachedToWindow }
        }
        waitForHostedFrontend()
        assertSame(first, waitForWebView())
        waitForDom("first SPA state survives server switches") { "window.retainedMarker === 'first'" }
        waitForSettingsButton()
        composeRule.onNodeWithTag("gomode-next-app").performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.runOnUiThread { second.isAttachedToWindow }
        }
        waitForHostedFrontend()
        assertSame(second, waitForWebView())
        waitForDom("second SPA state survives server switches") { "window.retainedMarker === 'second'" }
        val rendererCallbacks = AtomicInteger()
        val renderer =
            composeRule.runOnUiThread {
                listOf(first, second).forEach { view ->
                    val client = view.webViewClient
                    view.webViewClient =
                        object : WebViewClient() {
                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: RenderProcessGoneDetail,
                            ): Boolean {
                                rendererCallbacks.incrementAndGet()
                                return client.onRenderProcessGone(view, detail)
                            }
                        }
                }
                requireNotNull(second.webViewRenderProcess)
            }
        composeRule.activityRule.scenario.recreate()
        waitForHostedFrontend()
        assertSame(second, waitForWebView())
        waitForDom("Activity recreation retains page state") { "window.retainedMarker === 'second'" }
        composeRule.runOnUiThread {
            assertTrue(!first.isAttachedToWindow && second.isAttachedToWindow)
            renderer.terminate()
        }
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-shell-retry").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Retained page did not report renderer loss", rendererCallbacks.get() > 0)
        composeRule.onNodeWithTag("gomode-shell-retry").assertContentDescriptionEquals("Retry service")
        composeRule.onNodeWithTag("gomode-shell-open-settings").assertContentDescriptionEquals("Settings")
        UiDevice
            .getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("screencap -p /data/local/tmp/gomode-service-recovery.png")
        composeRule.onNodeWithTag("gomode-shell-retry").performClick()
        waitForHostedFrontend()
        assertNotSame(second, waitForWebView())
        waitForDom("Renderer recovery creates a new document") { "typeof window.retainedMarker === 'undefined'" }
    }

    @Test
    fun targetBlankLinksOpenInDefaultBrowser() {
        openWebShell()
        loadHostedExternalLinkTestPage()
        waitForDom("external link loaded") { "document.getElementById('external-link') !== null" }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter =
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            }
        val monitor = instrumentation.addMonitor(filter, ActivityResult(0, null), true)
        try {
            tapDomElement("#external-link")
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { monitor.hits > 0 }
            assertEquals(1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    // The real picker pauses the Activity before it returns; a stubbed result would not.
    @Test
    fun fileInputReceivesTheDocumentPickedInTheSystemPicker() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val name = "gomode-attach-${System.currentTimeMillis()}.png"
        val document =
            requireNotNull(
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "image/png")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    },
                ),
            ) { "Could not create $name in Downloads" }
        try {
            val size =
                requireNotNull(resolver.openOutputStream(document)) { "Could not write $name" }.use { output ->
                    val bytes = ByteArrayOutputStream()
                    Bitmap
                        .createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                        .compress(Bitmap.CompressFormat.PNG, 100, bytes)
                    output.write(bytes.toByteArray())
                    bytes.size()
                }
            openWebShell()
            loadHostedHtml(FILE_INPUT_TEST_PAGE)
            waitForDom("file input loaded") { "document.getElementById('picker') !== null" }

            tapDomElement("#picker")
            pickDownload(name)
            waitForDom("page is visible again after the picker") { "goModeHost.isPageVisible()" }
            waitForDom("file input received the picked document") {
                "window.picked === ${"$name|image/png|$size".jsString()}"
            }
        } finally {
            resolver.delete(document, null, null)
        }
    }

    // DocumentsUI opens on its last root and view mode and animates its roots drawer, so retry each
    // step until the document is listed by name.
    private fun pickDownload(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val uiAutomation = instrumentation.uiAutomation
        val deadline = SystemClock.uptimeMillis() + GOMODE_DEFAULT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val document = device.findFreshObject(uiAutomation, By.text(name))
                if (document != null) {
                    document.click()
                    return
                }
                // Grid view shows thumbnails without names.
                (
                    device.findFreshObject(uiAutomation, By.desc("List view"))
                        ?: device.findFreshObject(uiAutomation, By.text("Downloads"))
                        ?: device.findFreshObject(uiAutomation, By.desc("Show roots"))
                )?.click()
            } catch (_: StaleObjectException) {
                // The drawer moved between lookup and click.
            }
            device.waitForIdle()
            SystemClock.sleep(PICKER_POLL_INTERVAL_MS)
        }
        error("The document picker did not list $name in ${device.currentPackageName}")
    }

    @Test
    fun gomodePackageNameIsAppSpecific() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.fghbuild.gomode", context.packageName)
    }

    private fun addService(label: String) {
        composeRule.onNodeWithTag("gomode-add-service").performScrollTo().performClick()
        waitForServiceEditor()
        fillServiceField("gomode-service-label", label)
        // The fixture serves every path; a distinct path keeps service URLs unique.
        fillServiceField("gomode-service-url", "$baseUrl/${label.lowercase().replace(' ', '-')}")
        dismissServiceKeyboard()
        composeRule.onNodeWithTag("gomode-create-service").performScrollTo().performClick()
    }

    private fun waitForServiceEditor() {
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-service-label").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun closeServiceEditor() {
        dismissServiceKeyboard()
        composeRule.onNodeWithTag("gomode-service-done").performScrollTo().performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-service-label").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun loadHostedExternalLinkTestPage() {
        loadHostedHtml(EXTERNAL_LINK_TEST_PAGE)
    }

    private fun loadHostedHtml(html: String) {
        val latch = CountDownLatch(1)
        val view = waitForWebView()
        composeRule.activity.runOnUiThread {
            view.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
            latch.countDown()
        }
        check(latch.await(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Test page load timed out" }
    }

    companion object {
        private const val SPA_BACK_TEST_PATH = "/gomode-e2e-route"
        private const val PINCH_STEPS = 24
        private const val TOUCH_EVENT_INTERVAL_MS = 16L
        private const val JS_TIMEOUT_MS = 5_000L
        private const val PICKER_POLL_INTERVAL_MS = 250L
        private val FILE_INPUT_TEST_PAGE =
            """
            <!doctype html>
            <html>
              <body>
                <input id="picker" type="file" accept="image/png" style="width: 100%; height: 160px">
                <script>
                  document.getElementById('picker').addEventListener('change', (event) => {
                    const file = event.target.files[0];
                    window.picked = file ? [file.name, file.type, file.size].join('|') : 'none';
                  });
                </script>
              </body>
            </html>
            """.trimIndent()
        private const val EXTERNAL_LINK_URL = "https://example.com/gomode-external-link"
        private val EXTERNAL_LINK_TEST_PAGE =
            """
            <!doctype html>
            <html>
              <body>
                <a id="external-link" href="$EXTERNAL_LINK_URL" target="_blank" rel="noopener">External docs</a>
              </body>
            </html>
            """.trimIndent()
    }
}
