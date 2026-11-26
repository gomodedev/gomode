// Exercises real WebRTC and text gateway sessions, network recovery, and Telecom with controlled server responses.
package com.fghbuild.gomode.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.MainActivity
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.SpeechOutput
import com.fghbuild.gomode.data.SpeechStageLocation
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

// Uses production session clients against a controlled server for text and WebRTC
// transports, background setup, caller identity, network recovery, and Telecom ownership.
@RunWith(AndroidJUnit4::class)
class GatewaySessionIntegrationTest {
    @get:Rule(order = 0)
    val speech: TestRule = ModelFreeSpeechRule()

    @get:Rule(order = 1)
    val recordAudioPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun webRtcSessionRetainsItsConversationAndTelecomAcrossNetworkChanges(): Unit =
        runBlocking {
            val baseUrl = requireNotNull(InstrumentationRegistry.getArguments().getString("gatewayBaseUrl"))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dataStoreJob = SupervisorJob()
            try {
                val repository =
                    SettingsRepository(
                        PreferenceDataStoreFactory.create(
                            scope = CoroutineScope(Dispatchers.IO + dataStoreJob),
                            produceFile = { File(context.filesDir, "rtc-handoff-${System.nanoTime()}.preferences_pb") },
                        ),
                    )
                repository.addService("fixture", baseUrl)
                repository.updateSavedVoiceSetup(null) {
                    it.copy(preference = it.preference.copy(mode = VoiceSetupMode.FOLLOW_GATEWAY))
                }
                awaitSettings(repository) { it.activeServiceURL == baseUrl && !it.voiceSetup.deviceSpeech }
                val activity = ActivityScenario.launch(MainActivity::class.java)
                val pages = ServiceShellOwner(context, repository, glassesDisplay = { null })
                val conversation =
                    VoiceConversation(repository, ServiceSettingsClient(), pages.catalog) { pages.entries }
                val calls = TelecomCallController(context, onTelecomEnded = {})
                val session = VoiceSession(context, repository, conversation, "en-US", callController = calls)
                try {
                    withTimeout(TIMEOUT_MS) {
                        withContext(Dispatchers.Main) { pages.prepareCall(repository.settings.value) {} }
                    }
                    activity.onActivity { session.connect() }
                    awaitConnected(session)
                    val initial = withTimeout(TIMEOUT_MS) { session.state.first { it.transcript.size >= 2 }.transcript }
                    val conversationID = conversation.sessionID
                    exerciseNetworkChanges(session, calls)
                    assertEquals(conversationID, conversation.sessionID)
                    assertEquals(
                        initial.map { it.speaker to it.text },
                        session.state.value.transcript
                            .take(initial.size)
                            .map { it.speaker to it.text },
                    )
                } finally {
                    withContext(Dispatchers.Main) {
                        session.close()
                        calls.close()
                        pages.close()
                    }
                    activity.close()
                }
            } finally {
                withContext(NonCancellable) { dataStoreJob.cancelAndJoin() }
                assertTrue("Fixture DataStore scope did not finish", dataStoreJob.isCompleted)
            }
        }

    @Test
    @ModelFreeSpeech
    fun deviceSessionConnectsAfterBackgroundingDuringSetup(): Unit =
        runBlocking {
            val baseUrl =
                requireNotNull(InstrumentationRegistry.getArguments().getString("gatewayBaseUrl")) {
                    "gatewayBaseUrl instrumentation argument is required"
                }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dataStoreJob = SupervisorJob()
            try {
                val dataStore =
                    PreferenceDataStoreFactory.create(
                        scope = CoroutineScope(Dispatchers.IO + dataStoreJob),
                        produceFile = { File(context.filesDir, "voice-fixture-${System.nanoTime()}.preferences_pb") },
                    )
                val repository = SettingsRepository(dataStore)
                repository.addService("fixture", baseUrl)
                repository.updateSavedVoiceSetup(null) {
                    it.copy(
                        preference =
                            it.preference.copy(
                                mode = VoiceSetupMode.SEPARATE_STAGES,
                                recognition = SpeechStageLocation.DEVICE,
                                speech = SpeechOutput.DEVICE,
                            ),
                    )
                }
                awaitSettings(repository) { it.activeServiceURL == baseUrl && it.voiceSetup.deviceSpeech }
                val expectedName = ServiceSettingsClient().fetch(baseUrl).service

                val speech = AndroidDeviceSpeech(context, "en-US", "", 1f)
                val calls = TelecomCallController(context, onTelecomEnded = {})
                val finishSetup = CompletableDeferred<Unit>()
                val activity = ActivityScenario.launch(MainActivity::class.java)
                val session =
                    DeviceVoiceSession(
                        appContext = context,
                        settingsRepository = repository,
                        setupSource =
                            DeviceVoiceSetupSource.SelectedService {
                                finishSetup.await()
                                prepareVoiceSetup(
                                    repository,
                                    ServiceSettingsClient(),
                                    { null },
                                    VoiceSetupMcp.Service { null },
                                )
                            },
                        languageTag = "en-US",
                        callController = calls,
                        speech = speech,
                    )
                try {
                    prepareSpeechLanguage(speech)
                    activity.onActivity { session.connect(preserveTranscript = false) }
                    val notifications = context.getSystemService(NotificationManager::class.java)
                    awaitCallerName(notifications, "fixture")
                    activity.moveToState(Lifecycle.State.CREATED)
                    finishSetup.complete(Unit)
                    awaitCallerName(notifications, expectedName)
                    withTimeout(TIMEOUT_MS) {
                        session.state.first { it.connected || it.error != null }
                    }
                    assertNull("device session reported ${session.state.value.error}", session.state.value.error)
                    assertTrue("device session did not connect", session.state.value.connected)

                    exerciseNetworkChanges(session, calls)
                } finally {
                    withContext(Dispatchers.Main) {
                        session.close()
                        calls.close()
                    }
                    // Finish a resumed Activity; a stopped scenario otherwise waits
                    // for the platform's background Activity destruction timeout.
                    activity.moveToState(Lifecycle.State.RESUMED)
                    activity.close()
                }
            } finally {
                withContext(NonCancellable) { dataStoreJob.cancelAndJoin() }
                assertTrue("Fixture DataStore scope did not finish", dataStoreJob.isCompleted)
            }
        }

    private suspend fun exerciseNetworkChanges(
        session: VoiceSessionController,
        calls: VoiceCallController,
    ) = coroutineScope {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1") {
            "Network handoff coverage requires an emulator"
        }
        val wifiEnabled = device.executeShellCommand("settings get global wifi_on").trim() == "1"
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        var stockWifi = false
        try {
            setWifiEnabled(device, true)
            stockWifi = hasStockWifiConfig(device)
            ensureWifiRoute(device, connectivity, reconnectStockWifi = stockWifi)
            awaitConnected(session)
            // The stock fixture disables its modem. Cycling Wi-Fi exercises actual
            // route replacement and the offline wait without requiring a carrier.
            repeat(2) {
                val recovering =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_MS) {
                            session.state.first {
                                !it.connected && it.connectStatus?.contains("reconnecting") == true
                            }
                        }
                    }
                setWifiEnabled(device, false)
                withTimeout(TIMEOUT_MS) { recovering.await() }
                assertNull(session.state.value.error)
                assertTrue("Telecom call ended while offline", calls.isStarted)
                setWifiEnabled(device, true)
                // Ask for the stock AP explicitly. CI has reported a completed scan
                // after enabling Wi-Fi without ever attempting to rejoin AndroidWifi.
                if (stockWifi) device.executeShellCommand("cmd wifi connect-network AndroidWifi open")
                ensureWifiRoute(device, connectivity, reconnectStockWifi = stockWifi)
                awaitConnected(session)
                assertTrue("Telecom call ended during handoff", calls.isStarted)
            }
        } finally {
            withContext(NonCancellable) {
                setWifiEnabled(device, wifiEnabled)
                if (wifiEnabled && !hasWifiRoute(connectivity)) {
                    runCatching { ensureWifiRoute(device, connectivity, reconnectStockWifi = stockWifi) }
                        .onFailure { Log.w(TAG, "Failed to restore Wi-Fi IPv4 route during teardown", it) }
                }
            }
        }
    }

    private suspend fun awaitSettings(
        repository: SettingsRepository,
        predicate: (SettingsState) -> Boolean,
    ) {
        val state =
            withTimeout(TIMEOUT_MS) {
                repository.settings.first { it.loadState is SettingsLoadState.Failed || predicate(it) }
            }
        val failed = state.loadState as? SettingsLoadState.Failed
        assertNull("Settings failed to load: ${failed?.message}", failed)
    }

    private fun hasStockWifiConfig(device: UiDevice): Boolean =
        device.executeShellCommand("cmd wifi list-networks").lines().any { line ->
            line.trim().split(Regex("\\s+")).getOrNull(1) == "AndroidWifi"
        }

    private suspend fun ensureWifiRoute(
        device: UiDevice,
        connectivity: ConnectivityManager,
        reconnectStockWifi: Boolean,
    ) {
        val initialTimeoutMs = if (reconnectStockWifi) WIFI_ROUTE_ATTEMPT_TIMEOUT_MS else TIMEOUT_MS
        if (awaitWifiRoute(connectivity, initialTimeoutMs)) return
        if (reconnectStockWifi) {
            repeat(WIFI_RECOVERY_ATTEMPTS) {
                setWifiEnabled(device, false)
                setWifiEnabled(device, true)
                device.executeShellCommand("cmd wifi connect-network AndroidWifi open")
                if (awaitWifiRoute(connectivity, WIFI_ROUTE_ATTEMPT_TIMEOUT_MS)) return
            }
        }
        val status = device.executeShellCommand("cmd wifi status").trim()
        val route = device.executeShellCommand("ip -4 route get 192.0.2.1").trim()
        throw AssertionError("Wi-Fi IPv4 default route unavailable: wifi=$status; route=$route")
    }

    private suspend fun setWifiEnabled(
        device: UiDevice,
        enabled: Boolean,
    ) {
        val state = if (enabled) "enabled" else "disabled"
        device.executeShellCommand("cmd wifi set-wifi-enabled $state")
        withTimeout(TIMEOUT_MS) {
            while (!device.executeShellCommand("cmd wifi status").startsWith("Wifi is $state")) {
                delay(50)
            }
        }
    }

    private suspend fun awaitConnected(session: VoiceSessionController) {
        val ready = withTimeout(TIMEOUT_MS) { session.state.first { it.connected || it.error != null } }
        assertNull(ready.error)
        assertTrue(ready.connected)
    }

    private suspend fun awaitWifiRoute(
        connectivity: ConnectivityManager,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!hasWifiRoute(connectivity)) {
            if (System.nanoTime() >= deadline) return false
            delay(50)
        }
        return true
    }

    private fun hasWifiRoute(connectivity: ConnectivityManager): Boolean {
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            connectivity.getLinkProperties(network)?.routes?.any {
                it.isDefaultRoute && it.destination.address is Inet4Address
            } == true
    }

    private suspend fun prepareSpeechLanguage(speech: AndroidDeviceSpeech) {
        val language = speech.checkLanguage()
        assertTrue(
            "Speech language unavailable: $language",
            language == SpeechLanguageState.Ready || language is SpeechLanguageState.Unverified,
        )
    }

    private suspend fun awaitCallerName(
        notifications: NotificationManager,
        expectedName: String,
    ) {
        withTimeout(TIMEOUT_MS) {
            while (notifications.activeNotifications.none {
                    it.notification.extras
                        .getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
                        ?.name
                        ?.toString() == expectedName
                }
            ) {
                delay(50)
            }
        }
    }

    private companion object {
        const val TAG = "GatewaySessionTest"
        const val TIMEOUT_MS = 30_000L
        const val WIFI_ROUTE_ATTEMPT_TIMEOUT_MS = 12_000L
        const val WIFI_RECOVERY_ATTEMPTS = 2
    }
}
