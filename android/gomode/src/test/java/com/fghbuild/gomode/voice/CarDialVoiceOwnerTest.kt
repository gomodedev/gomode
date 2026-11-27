// Tests bounded dial authority, duplicate suppression, Activity replacement, role revocation, and startup deadlines.
package com.fghbuild.gomode.voice

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.telecom.TelecomManager
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2GattConnector
import com.fghbuild.eveng2.G2InputEvent
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Scanner
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.testing.FakeG2Firmware
import com.fghbuild.gomode.data.GlassesDevice
import com.fghbuild.gomode.data.ServiceInstance
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.glasses.GlassesAction
import com.fghbuild.gomode.glasses.GlassesLink
import com.fghbuild.gomode.glasses.GlassesOwner
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.testing.MemoryPreferences
import com.fghbuild.gomode.ui.web.HostedWebViewCompat
import com.fghbuild.gomode.ui.web.HostedWebViewFeatures
import com.fghbuild.gomode.ui.web.ServiceCallStage
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.gomode.ui.web.WebShellLoadState
import com.fghbuild.gomode.ui.web.completeFrontendPreparation
import com.fghbuild.halo.ble.HaloConnection
import com.fghbuild.halo.testing.FakeHaloTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowRoleManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit

private val G2_PAIR =
    G2Pair(
        "7",
        G2Arm(G2Side.LEFT, "00:11:22:33:55:01", "Even G2_7_L_335501"),
        G2Arm(G2Side.RIGHT, "00:11:22:33:55:02", "Even G2_7_R_335502"),
    )

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CarDialVoiceOwnerTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private lateinit var model: LocalModelOwner
    private lateinit var pages: ServiceShellOwner
    private lateinit var owner: CarDialVoiceOwner
    private val glassesScope = CoroutineScope(SupervisorJob())
    private lateinit var glasses: GlassesOwner
    private lateinit var repository: SettingsRepository
    private val shellIssues = VoiceIssues()
    private val server = ServiceInstance("saved-server", "Saved server", url = "https://example.test")

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, CarDialRedirectionService::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        ShadowRoleManager.addRoleHolder(RoleManager.ROLE_CALL_REDIRECTION, context.packageName, Process.myUserHandle())
        model = LocalModelOwner(context, localModelInstallation(ModelDownloadState.Missing(0)))
        repository = SettingsRepository(MemoryPreferences())
        pages = ServiceShellOwner(context, repository, glassesDisplay = { null })
        glasses =
            GlassesOwner(
                glassesScope,
                context,
                repository,
                HaloConnection(context),
                G2Scanner(context),
                G2GattConnector(context),
                VoiceIssues(),
            )
        owner = CarDialVoiceOwner(context, repository, model, pages, glasses, shellIssues)
    }

    @After
    fun tearDown() {
        owner.close()
        glassesScope.cancel()
        pages.close()
        model.close()
        Dispatchers.resetMain()
    }

    @Test
    fun acceptingDialDoesNotActivateServicePagesBeforeNativeStartup() =
        runTest {
            val id = repository.addService("Saved server", "https://example.test")
            val saved =
                repository.settings
                    .first { it.activeServiceId == id }
                    .services
                    .single { it.id == id }
            val identity = owner.request(saved)
            assertTrue(owner.attach(identity, Any()))
            runCurrent()
            assertTrue(pages.entries.isEmpty())
            owner.cancel(identity)
        }

    @Test
    fun servicePageSnapshotsFollowOneRepositoryAcrossSelectionAndURLReplacement() =
        runTest {
            val first = repository.addService("First", "http://127.0.0.1:2")
            val second = repository.addService("Second", "http://127.0.0.1:3")
            repository.settings.first { it.activeServiceId == second }
            pages.start()
            val initial = pages.state.first { it.settings.activeServiceId == second }
            val oldKey = ServiceShellKey(first, "http://127.0.0.1:2")
            val entry = initial.entries.getValue(oldKey)
            val generation = entry.openPage()
            repository.switchService(first)
            val selected = pages.state.first { it.settings.activeServiceId == first }
            assertTrue(selected.entries.getValue(oldKey) === entry)
            repository.updateService(first, "Renamed", "http://127.0.0.1:1")
            val replaced = pages.state.first { it.settings.activeServiceURL == "http://127.0.0.1:1" }
            assertFalse(oldKey in replaced.entries)
            assertFalse(entry.isCurrentPage(generation))
            // A later UI attachment can only start the same collector, never replay its old snapshot.
            pages.start()
            assertTrue(pages.state.value === replaced)
            assertTrue(initial.entries.getValue(oldKey) === entry)
        }

    @Test
    fun startupFailureReleasesAuthorityWithoutPublishingAnIdleSurface() =
        runTest {
            shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELECOM, false)
            val savedID = repository.addService("Saved server", "https://example.test")
            val saved =
                repository.settings
                    .first { it.services.any { service -> service.id == savedID } }
                    .services
                    .single { it.id == savedID }
            val statuses = mutableListOf<CarDialStatus>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                owner.state.collect { statuses += it.status }
            }
            val identity = owner.request(saved)
            val activity = Any()
            assertTrue(owner.attach(identity, activity))
            statuses.clear()
            owner.start(identity, activity)
            runCurrent()
            assertEquals(CarDialStatus.FAILED, owner.state.value.status)
            assertEquals(CarDialFailure.CALL, owner.state.value.failure)
            assertFalse("Idle would finish the native failure surface: $statuses", CarDialStatus.IDLE in statuses)
            assertFalse(owner.isBusy)
            assertNull(owner.session.value)
            // The shell lists the fixed message, never the session's own, until the failure is dismissed.
            assertEquals(
                listOf(VoiceIssueKey.CAR_CALL to context.getString(CarDialFailure.CALL.message)),
                shellIssues.state.value.entries
                    .map { it.key to it.message },
            )
            owner.cancel(identity)
            runCurrent()
            assertEquals(emptyList<VoiceIssue>(), shellIssues.state.value.entries)
        }

    @Test
    fun pagePreparationDistinguishesLoadingFromWaitingForSignIn() =
        runTest {
            val id = repository.addService("Saved server", "")
            val selected = repository.settings.first { it.activeServiceId == id }
            val stages = mutableListOf<ServiceCallStage>()
            backgroundScope.launch { pages.prepareCall(selected) { stages += it } }
            runCurrent()
            assertEquals(listOf(ServiceCallStage.PAGE), stages)
            val page = pages.entries.getValue(ServiceShellKey(id, ""))
            page.updateLoad(WebShellLoadState.Ready)
            runCurrent()
            // A loaded page without a settled identity must report sign-in, not a page failure.
            assertFalse(page.bearerStore.authReady.value)
            assertEquals(listOf(ServiceCallStage.PAGE, ServiceCallStage.SIGN_IN), stages)
        }

    @Test
    fun duplicateDialsAndForgedActivityTokensDoNotGainAnotherLaunch() =
        runTest {
            val identity = owner.request(server)
            assertNotNull(identity)
            assertNull(owner.request(server))
            assertFalse(owner.attach("external-intent-token", Any()))
            assertTrue(owner.attach(identity, Any()))
            owner.cancel(identity)
        }

    @Test
    fun shellCannotConsumeAuthorityBeforeTheNativeActivityStartsVoice() =
        runTest {
            val identity = owner.request(server)
            val activity = Any()
            assertTrue(owner.attach(identity, activity))
            assertFalse(owner.releaseToShell("forged", activity))
            assertFalse(owner.releaseToShell(identity, Any()))
            assertFalse(owner.releaseToShell(identity, activity))
            assertTrue(owner.isBusy)
            owner.detach(identity, activity, changingConfigurations = false)
            assertFalse(owner.isBusy)
        }

    @Test
    fun roleLossWatcherRevokesPendingAuthorityInsteadOfCancelingOnlyItsOwnJob() =
        runTest {
            val identity = owner.request(server)
            testScheduler.runCurrent()
            ShadowRoleManager.removeRoleHolder(
                RoleManager.ROLE_CALL_REDIRECTION,
                context.packageName,
                Process.myUserHandle(),
            )
            testScheduler.advanceTimeBy(251)
            testScheduler.runCurrent()
            assertFalse(owner.isBusy)
            assertFalse(owner.attach(identity, Any()))
        }

    @Test
    fun oldActivityDisposalCannotCancelItsReplacement() =
        runTest {
            val identity = owner.request(server)
            val oldActivity = Any()
            val newActivity = Any()
            assertTrue(owner.attach(identity, oldActivity))
            assertTrue(owner.attach(identity, newActivity))
            owner.detach(identity, oldActivity, changingConfigurations = false)
            assertTrue(owner.isBusy)
            owner.detach(identity, newActivity, changingConfigurations = false)
            assertFalse(owner.isBusy)
        }

    @Test
    fun expiredAndRevokedAttachedRequestsCannotStartVoiceLater() =
        runTest {
            val expired = owner.request(server)
            val activity = Any()
            assertTrue(owner.attach(expired, activity))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
            owner.start(expired, activity)
            assertFalse(owner.isBusy)
            assertFalse(owner.attach(expired, Any()))
            val revoked = owner.request(server)
            assertTrue(owner.attach(revoked, activity))
            ShadowRoleManager.removeRoleHolder(
                RoleManager.ROLE_CALL_REDIRECTION,
                context.packageName,
                Process.myUserHandle(),
            )
            owner.start(revoked, activity)
            assertFalse(owner.isBusy)
            assertFalse(owner.attach(revoked, Any()))
        }

    @Test
    fun replacingAnActivityCannotHideItsStillActiveManualController() =
        runTest {
            val idle =
                VoiceSession(
                    context,
                    repository,
                    voiceConversation =
                        VoiceConversation(
                            repository,
                            ServiceSettingsClient(),
                            ServiceCatalog(
                                CoroutineScope(SupervisorJob() + Dispatchers.Main),
                                glassesDisplay = { null },
                            ),
                        ) { emptyMap() },
                    languageTag = "en-US",
                    issues = VoiceIssues(),
                )
            val active =
                object : VoiceSessionController by idle {
                    override val state = MutableStateFlow(VoiceState(connected = true))
                }
            try {
                owner.registerManualSession(active)
                owner.registerManualSession(idle)
                owner.unregisterManualSession(idle)
                assertNull(owner.request(server))
                owner.unregisterManualSession(active)
                assertNotNull(owner.request(server))
                owner.cancel()
            } finally {
                idle.close()
            }
        }

    @Test
    fun absentMicrophonePermissionCannotAuthorizeVoiceFromARecognizedDial() =
        runTest {
            shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
            assertNull(owner.request(server))
            assertFalse(owner.isBusy)
        }

    @Test
    @Config(
        shadows = [ShadowTransactionalTelecomManager::class, HostedWebViewFeatures::class, HostedWebViewCompat::class],
    )
    fun frontendTimeoutKeepsItsSafeCauseAndReleasesCallAndMicrophoneOwnership() =
        runTest {
            shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELECOM, true)
            val manifest =
                """{"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":3,"toolGroups":[],
                "voiceGateway":{"required":false,"url":"/"}}}"""
            val server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse().setBody(manifest)
                }
            server.start()
            var identity: String? = null
            try {
                val id = repository.addService("Saved server", server.url("/").toString())
                val settings = repository.settings.first { it.activeServiceId == id }
                pages.start()
                val key = ServiceShellKey(id, settings.activeServiceURL)
                val page =
                    pages.state
                        .first { key in it.entries }
                        .entries
                        .getValue(key)
                // A signed-in, loaded page whose frontend never reports preparation.
                page.bearerStore.onNavigation(key.url)
                assertTrue(page.bearerStore.receive(Uri.parse(key.url), true, key.url, """{"bearerToken":"fixture"}"""))
                page.updateLoad(WebShellLoadState.Ready)
                identity = owner.request(settings.services.single { it.id == id })
                val activity = Any()
                assertTrue(owner.attach(identity, activity))
                owner.start(identity, activity)
                // Bootstrap discovery reads the fixture on an IO thread before the catalog is ready.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (startupRecords().none { "event=stage_started stage=FRONTEND" in it }) {
                    check(System.nanoTime() < deadline) { "Startup never waited on the frontend: ${startupRecords()}" }
                    runCurrent()
                    Thread.sleep(10)
                }
                assertEquals(CarDialStatus.PREPARING, owner.state.value.status)
                val telecom =
                    Shadow.extract<ShadowTransactionalTelecomManager>(
                        context.getSystemService(TelecomManager::class.java),
                    )
                assertFalse(telecom.controlRequests.contains("disconnect"))

                // Startup diagnostics read Android's clock; the deadline uses coroutine time.
                ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(CarDialStatus.FAILED, owner.state.value.status)
                assertEquals(CarDialFailure.FRONTEND, owner.state.value.failure)
                val terminal = startupRecords().single { "event=finished" in it }
                assertTrue(terminal, "outcome=TIMED_OUT stage=FRONTEND" in terminal && "remaining_ms=0" in terminal)
                assertTrue("The replacement call stayed active", telecom.controlRequests.contains("disconnect"))
                assertEquals(VoiceService::class.java.name, shadowOf(context).nextStoppedService?.component?.className)
                assertFalse(owner.isBusy)

                // A frontend that becomes ready after the deadline cannot start the abandoned attempt.
                completeFrontendPreparation(page)
                runCurrent()
                assertNull(owner.session.value)
                assertEquals(CarDialFailure.FRONTEND, owner.state.value.failure)
            } finally {
                // A live request keeps polling its role, which runTest's cleanup would advance forever.
                owner.cancel(identity)
                server.shutdown()
            }
        }

    // The panel's X resolves a car call's problem in the call's own list, which the shell list follows.
    @Test
    @Config(
        shadows = [ShadowTransactionalTelecomManager::class, HostedWebViewFeatures::class, HostedWebViewCompat::class],
    )
    fun dismissingACarCallsProblemResolvesItInTheCallsList() =
        runTest {
            shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELECOM, true)
            val manifest =
                """{"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":3,"toolGroups":[],
                "voiceGateway":{"required":false,"url":"/"}}}"""
            val server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse().setBody(manifest)
                }
            server.start()
            var identity: String? = null
            try {
                val id = repository.addService("Saved server", server.url("/").toString())
                val settings = repository.settings.first { it.activeServiceId == id }
                pages.start()
                val key = ServiceShellKey(id, settings.activeServiceURL)
                val page =
                    pages.state
                        .first { key in it.entries }
                        .entries
                        .getValue(key)
                page.bearerStore.onNavigation(key.url)
                assertTrue(page.bearerStore.receive(Uri.parse(key.url), true, key.url, """{"bearerToken":"fixture"}"""))
                page.updateLoad(WebShellLoadState.Ready)
                identity = owner.request(settings.services.single { it.id == id })
                val activity = Any()
                assertTrue(owner.attach(identity, activity))
                owner.start(identity, activity)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (startupRecords().none { "event=stage_started stage=FRONTEND" in it }) {
                    check(System.nanoTime() < deadline) { "Startup never waited on the frontend: ${startupRecords()}" }
                    runCurrent()
                    Thread.sleep(10)
                }
                completeFrontendPreparation(page)
                while (owner.session.value == null) {
                    check(System.nanoTime() < deadline) { "The call never built its session: ${owner.state.value}" }
                    runCurrent()
                    Thread.sleep(10)
                }
                val call = checkNotNull(owner.session.value).issues
                call.raise(VoiceIssueKey.GATEWAY, "provider detail")
                runCurrent()
                assertEquals("provider detail", shellIssues.state.value.message(VoiceIssueKey.CAR_GATEWAY))

                owner.dismissIssues(listOf(VoiceIssueKey.CAR_GATEWAY))
                runCurrent()
                assertNull(call.state.value.message(VoiceIssueKey.GATEWAY))
                assertNull(shellIssues.state.value.message(VoiceIssueKey.CAR_GATEWAY))
            } finally {
                owner.cancel(identity)
                server.shutdown()
            }
        }

    // A mute before the call's session exists acts on nothing: the shell would say voice is off.
    @Test
    fun aMuteWhileACallWaitsForItsSessionActsOnNothing() =
        runTest {
            shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
            val firmware = FakeG2Firmware().apply { revision = "Faceclaw/45" }
            val fakeGlasses =
                GlassesOwner(
                    backgroundScope,
                    context,
                    repository,
                    FakeHaloTransport(context),
                    G2Scanner(context),
                    firmware.connector,
                    VoiceIssues(),
                )
            val carDial = CarDialVoiceOwner(context, repository, model, pages, fakeGlasses, VoiceIssues())
            try {
                val shell = mutableListOf<GlassesAction>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fakeGlasses.actions.collect { shell += it }
                }
                fakeGlasses.connect(GlassesDevice.EvenG2(G2_PAIR))
                fakeGlasses.state.first { it.link is GlassesLink.Connected }
                val identity = carDial.request(server)
                assertEquals(CarDialStatus.WAITING, carDial.state.value.status)
                firmware.systemInput(G2Side.RIGHT, G2InputEvent.LONG_PRESS, G2InputEvent.SOURCE_RIGHT_TEMPLE)
                runCurrent()
                assertEquals(emptyList<GlassesAction>(), shell)
                assertNull(carDial.session.value)

                // Without a call, the shell gets it.
                carDial.cancel(identity)
                firmware.systemInput(G2Side.RIGHT, G2InputEvent.LONG_PRESS, G2InputEvent.SOURCE_RIGHT_TEMPLE)
                runCurrent()
                assertEquals(listOf(GlassesAction.TOGGLE_MUTE), shell)
            } finally {
                carDial.close()
            }
        }

    // The shell's sessions write AUDIO_ROUTE and GATEWAY; only the car call writes its CAR_ keys.
    @Test
    fun aCarCallsProblemsStayListedUntilTheCallResolvesThem() =
        runTest {
            val call = VoiceIssues()
            val sessions = MutableStateFlow<VoiceIssues?>(call)
            val shell = VoiceIssues()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { forwardCarIssues(sessions, shell) }
            call.raise(VoiceIssueKey.AUDIO_ROUTE, "Could not switch audio device")
            call.raise(VoiceIssueKey.GATEWAY, "provider detail")
            // A shell session that starts or ends resolves its own keys.
            shell.resolve(VoiceIssueKey.AUDIO_ROUTE, VoiceIssueKey.GATEWAY, VoiceIssueKey.CONNECTION)
            assertEquals(
                listOf(
                    VoiceIssueKey.CAR_AUDIO_ROUTE to "Could not switch audio device",
                    VoiceIssueKey.CAR_GATEWAY to "provider detail",
                ),
                shell.state.value.entries
                    .map { it.key to it.message },
            )
            // The call resolving its own entry resolves the shell's.
            call.resolve(VoiceIssueKey.GATEWAY)
            assertEquals(
                listOf(VoiceIssueKey.CAR_AUDIO_ROUTE),
                shell.state.value.entries
                    .map { it.key },
            )
            call.resolve(VoiceIssueKey.AUDIO_ROUTE)
            assertEquals(emptyList<VoiceIssue>(), shell.state.value.entries)
            // Each raise is a new occurrence, and an ended call takes its problems with it.
            call.raise(VoiceIssueKey.AUDIO_ROUTE, "Could not switch audio device")
            val first =
                shell.state.value.entries
                    .single()
                    .id
            call.raise(VoiceIssueKey.AUDIO_ROUTE, "Could not switch audio device")
            assertTrue(
                shell.state.value.entries
                    .single()
                    .id != first,
            )
            sessions.value = null
            assertEquals(emptyList<VoiceIssue>(), shell.state.value.entries)
        }

    private fun startupRecords(): List<String> =
        ShadowLog.getLogsForTag("GoModeVoiceStartup").map { it.msg }.filter { "source=CAR" in it }
}
