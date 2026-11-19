// Verifies bounded page and manifest recovery across startup failures and route changes.
package com.fghbuild.gomode.ui.web

import android.net.ConnectivityManager
import com.fghbuild.gomode.data.MemoryPreferences
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.ui.ServiceBootstrapState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
class ServiceShellOwnerTest {
    @Test
    fun networkRetriesLateFailuresOncePerRouteAndPreservesHealthyPages() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val context = RuntimeEnvironment.getApplication()
            val owner = ServiceShellOwner(context, SettingsRepository(MemoryPreferences()))
            val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))
            try {
                owner.start()
                runCurrent()
                val entry = owner.entries.values.single()
                entry.updateLoad(WebShellLoadState.Ready)
                val callback = connectivity.networkCallbacks.single()
                callback.onBlockedStatusChanged(ShadowNetwork.newInstance(41), false)
                runCurrent()
                assertEquals(0, entry.state.value.webReloadToken)
                entry.updateLoad(WebShellLoadState.Failed("Connection failed", networkFailure = true))
                runCurrent()
                assertEquals(1, entry.state.value.webReloadToken)
                entry.updateLoad(WebShellLoadState.Failed("Still unavailable", networkFailure = true))
                runCurrent()
                assertEquals(1, entry.state.value.webReloadToken)
                callback.onBlockedStatusChanged(ShadowNetwork.newInstance(42), false)
                runCurrent()
                assertEquals(2, entry.state.value.webReloadToken)
                entry.updateLoad(WebShellLoadState.Failed("Sign in again"))
                callback.onBlockedStatusChanged(ShadowNetwork.newInstance(43), false)
                runCurrent()
                assertEquals(2, entry.state.value.webReloadToken)
            } finally {
                owner.close()
                Dispatchers.resetMain()
            }
            assertTrue(connectivity.networkCallbacks.isEmpty())
        }

    // A cold start can fail its first page load on an unchanged route, for example right after an install.
    @Test
    @Config(
        shadows = [HostedWebViewFeatures::class, HostedWebViewCompat::class],
        instrumentedPackages = ["androidx.webkit"],
    )
    fun validatedBootstrapRetriesAColdStartPageFailureOnce() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val server = MockWebServer()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setBody(SETTINGS_JSON))
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val settings = SettingsRepository(MemoryPreferences())
            val owner = ServiceShellOwner(context, settings)
            try {
                settings.addService("Service", server.url("/").toString())
                owner.start()
                val entry =
                    owner.state
                        .map { snapshot -> snapshot.entries.values.singleOrNull { it.key.url.isNotBlank() } }
                        .filterNotNull()
                        .first()
                entry.updateLoad(WebShellLoadState.Failed("Could not connect", networkFailure = true))
                runCurrent()
                assertEquals("no evidence yet that the service is reachable", 0, entry.state.value.webReloadToken)
                entry.state.first { it.bootstrap is ServiceBootstrapState.Ready }
                runCurrent()
                assertEquals(1, entry.state.value.webReloadToken)
                entry.updateLoad(WebShellLoadState.Failed("Still unavailable", networkFailure = true))
                runCurrent()
                assertEquals("one retry per validation", 1, entry.state.value.webReloadToken)
                entry.updateLoad(WebShellLoadState.Ready)
                entry.reloadWeb()
                runCurrent()
                assertEquals(2, entry.state.value.webReloadToken)
                entry.updateLoad(WebShellLoadState.Failed("Host went down", networkFailure = true))
                runCurrent()
                assertEquals("a manual reload is not reachability evidence", 2, entry.state.value.webReloadToken)
                assertEquals(2, server.requestCount)
            } finally {
                owner.close()
                server.shutdown()
                Dispatchers.resetMain()
            }
        }

    @Test
    @Config(
        shadows = [HostedWebViewFeatures::class, HostedWebViewCompat::class],
        instrumentedPackages = ["androidx.webkit"],
    )
    fun pageLoadAfterValidatedBootstrapSpendsItsRetry() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val server = MockWebServer()
            server.enqueue(MockResponse().setBody(SETTINGS_JSON))
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val settings = SettingsRepository(MemoryPreferences())
            val owner = ServiceShellOwner(context, settings)
            try {
                settings.addService("Service", server.url("/").toString())
                owner.start()
                val entry =
                    owner.state
                        .map { snapshot -> snapshot.entries.values.singleOrNull { it.key.url.isNotBlank() } }
                        .filterNotNull()
                        .first()
                entry.state.first { it.bootstrap is ServiceBootstrapState.Ready }
                runCurrent()
                entry.updateLoad(WebShellLoadState.Ready)
                runCurrent()
                entry.reloadWeb()
                runCurrent()
                assertEquals(1, entry.state.value.webReloadToken)
                entry.updateLoad(WebShellLoadState.Failed("Host went down", networkFailure = true))
                runCurrent()
                assertEquals("the earlier validation was spent by the load", 1, entry.state.value.webReloadToken)
            } finally {
                owner.close()
                server.shutdown()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun bootstrapRetriesTransientFailuresWithBackoffUntilAFinalAnswer() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val server = MockWebServer()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(401))
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val settings = SettingsRepository(MemoryPreferences())
            val owner = ServiceShellOwner(context, settings)
            try {
                settings.addService("Service", server.url("/").toString())
                owner.start()
                val entry =
                    owner.state
                        .map { snapshot -> snapshot.entries.values.singleOrNull { it.key.url.isNotBlank() } }
                        .filterNotNull()
                        .first()
                val final =
                    entry.state
                        .map { it.bootstrap }
                        .filterIsInstance<ServiceBootstrapState.Unvalidated>()
                        .first { it.reason?.contains("HTTP 401") == true }
                assertFalse(final.transient)
                assertEquals(3, server.requestCount)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals("a final answer stops automatic retries", 3, server.requestCount)
            } finally {
                owner.close()
                server.shutdown()
                Dispatchers.resetMain()
            }
        }

    // A cold start can fetch settings before Android grants the process network access.
    @Test
    fun unblockedRouteRefetchesSettingsWithoutWaitingForBackoff() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val server = MockWebServer()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setBody(SETTINGS_JSON))
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val connectivity = shadowOf(manager)
            val settings = SettingsRepository(MemoryPreferences())
            val owner = ServiceShellOwner(context, settings)
            try {
                settings.addService("Service", server.url("/").toString())
                owner.start()
                val entry =
                    owner.state
                        .map { snapshot -> snapshot.entries.values.singleOrNull { it.key.url.isNotBlank() } }
                        .filterNotNull()
                        .first()
                entry.state.first { (it.bootstrap as? ServiceBootstrapState.Unvalidated)?.transient == true }
                val callback = connectivity.networkCallbacks.single()
                val network = requireNotNull(manager.activeNetwork)
                callback.onBlockedStatusChanged(network, true)
                runCurrent()
                callback.onBlockedStatusChanged(network, false)
                entry.state.first { it.bootstrap is ServiceBootstrapState.Ready }
                assertTrue("refetched after ${currentTime}ms", currentTime < 1_000)
                assertEquals(2, server.requestCount)
            } finally {
                owner.close()
                server.shutdown()
                Dispatchers.resetMain()
            }
        }

    private companion object {
        const val SETTINGS_JSON = """
            {
              "service": "generic",
              "serviceVersion": "1",
              "apiVersion": 1,
              "webShell": {"bridgeVersion": 3, "toolGroups": [], "voiceGateway": {"required": false}}
            }
        """
    }
}
