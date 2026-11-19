// Tracks the default route this app can use, without requiring public Internet validation for local services.
//
// A blocked route counts as absent. A new process can start before Android grants its UID network access:
// getActiveNetwork() then returns null, and DNS fails with "No address associated with hostname" until
// onBlockedStatusChanged reports the unblock. Publishing that transition lets owners retry at once instead
// of waiting out their backoff.
package com.fghbuild.gomode.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread owner; callbacks from a stopped registration cannot revive a call. */
internal class DefaultNetworkMonitor(
    context: Context,
) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val current = MutableStateFlow<Network?>(null)
    val network = current.asStateFlow()

    fun start() {
        if (callback != null) return
        current.value = connectivity.activeNetwork
        val registration =
            object : ConnectivityManager.NetworkCallback() {
                // Android calls this last after onAvailable, after link properties, so sockets can use
                // an unblocked route now. It is called again whenever the app's access changes.
                override fun onBlockedStatusChanged(
                    network: Network,
                    blocked: Boolean,
                ) {
                    if (callback === this) current.value = network.takeUnless { blocked }
                }

                override fun onLost(network: Network) {
                    if (callback === this && current.value == network) current.value = null
                }
            }
        connectivity.registerDefaultNetworkCallback(registration, Handler(Looper.getMainLooper()))
        callback = registration
    }

    override fun close() {
        val registration = callback ?: return
        callback = null
        connectivity.unregisterNetworkCallback(registration)
        current.value = null
    }
}
