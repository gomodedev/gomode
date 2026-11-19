// Verifies default route changes, blocked access, local networks, registration cleanup, and retired callback isolation.
package com.fghbuild.gomode.service

import android.net.ConnectivityManager
import android.net.LinkProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
class DefaultNetworkMonitorTest {
    @Test
    fun routeChangesWaitForBlockedStatusAndIgnoreRetiredCallbacks() {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        val monitor = DefaultNetworkMonitor(context)
        monitor.start()
        monitor.start()
        val callback = connectivity.networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(41)
        val cellular = ShadowNetwork.newInstance(42)
        callback.onBlockedStatusChanged(wifi, false)
        callback.onAvailable(cellular)
        callback.onLinkPropertiesChanged(cellular, LinkProperties())
        assertEquals(wifi, monitor.network.value)
        // Local gateways work even when a network lacks public Internet validation.
        callback.onBlockedStatusChanged(cellular, false)
        callback.onLost(wifi)
        assertEquals(cellular, monitor.network.value)
        callback.onLost(cellular)
        assertNull(monitor.network.value)
        callback.onBlockedStatusChanged(wifi, false)
        assertEquals(wifi, monitor.network.value)
        monitor.close()
        assertTrue(connectivity.networkCallbacks.isEmpty())
        callback.onBlockedStatusChanged(cellular, false)
        assertNull(monitor.network.value)
        monitor.start()
        val replacement = connectivity.networkCallbacks.single()
        replacement.onBlockedStatusChanged(wifi, false)
        callback.onLost(wifi)
        assertEquals(wifi, monitor.network.value)
        monitor.close()
    }

    // A new process can start before Android grants its UID network access.
    @Test
    fun blockedRouteIsAbsentUntilUnblocked() {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        val monitor = DefaultNetworkMonitor(context)
        monitor.start()
        val callback = connectivity.networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(41)
        callback.onBlockedStatusChanged(wifi, true)
        assertNull(monitor.network.value)
        callback.onBlockedStatusChanged(wifi, false)
        assertEquals(wifi, monitor.network.value)
        callback.onBlockedStatusChanged(wifi, true)
        assertNull(monitor.network.value)
        monitor.close()
    }
}
