// Owns the one pair of glasses across Activities: scanning, the saved device, auto-connect, reconnection, and text.
package com.fghbuild.gomode.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.halo.ble.HaloConnection
import com.fghbuild.halo.ble.HaloDeviceType
import com.fghbuild.halo.ble.HaloScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val RECONNECT_INITIAL_DELAY_MS = 2_000L
internal const val RECONNECT_MAX_DELAY_MS = 60_000L

// Bounds the best-effort clear when the user disconnects; the glasses may already be out of range.
private const val CLEAR_ON_DISCONNECT_TIMEOUT_MS = 3_000L

/** The glasses connection as the glasses screen shows it. */
internal sealed interface GlassesLink {
    data object Disconnected : GlassesLink

    data object Connecting : GlassesLink

    data object Connected : GlassesLink

    /** The connection ended or failed with [problem]; the owner tries again on its own. */
    data class Reconnecting(
        val problem: GlassesProblem,
    ) : GlassesLink
}

/** Why the glasses are not connected, in terms the user can act on. */
internal enum class GlassesProblem {
    // No connection came up, or the radio link dropped: off, out of range, or asleep.
    UNREACHABLE,

    // Connected glasses did not take the app or the text.
    UNRESPONSIVE,
}

internal data class ScannedGlasses(
    val address: String,
    val name: String,
    val rssi: Int,
)

internal data class GlassesState(
    val link: GlassesLink = GlassesLink.Disconnected,
    val scanning: Boolean = false,
    val devices: List<ScannedGlasses> = emptyList(),
    val savedAddress: String? = null,
    val autoConnect: Boolean = false,
    // A failure that stopped scanning or prevented a connection attempt.
    val error: String? = null,
)

/**
 * The application owns one so the glasses stay connected across Activity replacement. A connection
 * to the saved glasses lasts until [disconnect], [forget], or [close], reconnecting in between with
 * a delay that doubles from [RECONNECT_INITIAL_DELAY_MS] to [RECONNECT_MAX_DELAY_MS] and resets after
 * each successful connection. Auto-connect starts that connection when [start] first sees the saved
 * settings. Every connection shows the latest [show] text. Callers hold BLUETOOTH_SCAN before
 * [startScan] and BLUETOOTH_CONNECT before [connect]; the owner checks BLUETOOTH_CONNECT again before
 * connecting on its own.
 *
 * Only Halo connects today, through [connection]; the link logic depends on [GlassesDisplay] alone.
 */
internal class GlassesOwner(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val connection: HaloConnection,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scanned = mutableMapOf<String, BluetoothDevice>()
    private val text = MutableStateFlow("")
    private var scanJob: Job? = null
    private var linkJob: Job? = null
    private var started = false

    private val mutableState = MutableStateFlow(GlassesState())
    val state: StateFlow<GlassesState> = mutableState.asStateFlow()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            settingsRepository.settings.collect { settings ->
                mutableState.update {
                    it.copy(
                        savedAddress = settings.haloAddress,
                        autoConnect = settings.haloAutoConnect,
                    )
                }
            }
        }
        scope.launch {
            val settings = settingsRepository.settings.first { it.loadState == SettingsLoadState.Ready }
            val saved = settings.haloAddress
            if (settings.haloAutoConnect && saved != null && hasConnectPermission() && linkJob == null) {
                connect(saved)
            }
        }
    }

    /** Shows [text] on the glasses now and after every reconnection, as [GlassesDisplay.showText]. */
    fun show(text: String) {
        this.text.value = text
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanned.clear()
        mutableState.update { it.copy(scanning = true, devices = emptyList(), error = null) }
        scanJob =
            scope.launch {
                connection
                    .scan()
                    .catch { error ->
                        mutableState.update { it.copy(scanning = false, error = error.message ?: "Scan failed") }
                    }.filter { it.type == HaloDeviceType.HALO }
                    .collect { result ->
                        val item = result.toItem()
                        scanned[item.address] = result.device
                        mutableState.update { current ->
                            current.copy(
                                devices =
                                    (current.devices.filterNot { it.address == item.address } + item)
                                        .sortedWith(compareBy({ it.name }, { it.address })),
                            )
                        }
                    }
            }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        mutableState.update { it.copy(scanning = false) }
    }

    /** Saves the glasses at [address], a scan result or the saved device, and keeps them connected. */
    fun connect(address: String) {
        if (!hasConnectPermission()) {
            mutableState.update { it.copy(error = "Allow Nearby devices to connect to Halo") }
            return
        }
        val device =
            scanned[address]
                ?: appContext
                    .getSystemService(BluetoothManager::class.java)
                    .adapter
                    ?.getRemoteDevice(address)
        if (device == null) {
            mutableState.update { it.copy(error = "Bluetooth is not available") }
            return
        }
        stopScan()
        mutableState.update { it.copy(link = GlassesLink.Connecting, error = null) }
        replaceLink { HaloGlasses.connect(appContext, connection, device) }
        scope.launch { settingsRepository.updateHaloAddress(address) }
    }

    /** Stops the connection and its reconnection, clearing the display when the glasses are reachable. */
    fun disconnect() {
        replaceLink(null)
        mutableState.update { it.copy(link = GlassesLink.Disconnected) }
    }

    fun forget() {
        disconnect()
        scope.launch { settingsRepository.updateHaloAddress(null) }
    }

    fun updateAutoConnect(enabled: Boolean) {
        scope.launch { settingsRepository.updateHaloAutoConnect(enabled) }
    }

    override fun close() {
        stopScan()
        scope.cancel()
    }

    // The previous link finishes clearing and closing its glasses before the next one connects.
    private fun replaceLink(connect: (suspend () -> GlassesDisplay)?) {
        val previous = linkJob
        previous?.cancel()
        linkJob =
            connect?.let {
                scope.launch {
                    previous?.join()
                    keepConnected(it)
                }
            }
    }

    // Runs until cancelled, or until a permanent failure leaves the glasses disconnected.
    private suspend fun keepConnected(connect: suspend () -> GlassesDisplay) {
        var retryDelayMs = RECONNECT_INITIAL_DELAY_MS
        while (true) {
            // A retry keeps showing why the previous attempt ended until this one connects.
            mutableState.update {
                if (it.link is GlassesLink.Reconnecting) {
                    it
                } else {
                    it.copy(
                        link = GlassesLink.Connecting,
                    )
                }
            }
            val problem =
                try {
                    val glasses = connect()
                    retryDelayMs = RECONNECT_INITIAL_DELAY_MS
                    mutableState.update { it.copy(link = GlassesLink.Connected) }
                    showUntilClosed(glasses)
                } catch (e: GlassesException) {
                    Log.w(TAG, "Glasses connection failed: ${e.message}")
                    if (e.permanent) {
                        currentCoroutineContext().ensureActive()
                        mutableState.update { it.copy(link = GlassesLink.Disconnected, error = e.message) }
                        return
                    }
                    GlassesProblem.UNREACHABLE
                }
            // A disconnect or a switch to other glasses cancels this link; it must not report a retry.
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(link = GlassesLink.Reconnecting(problem)) }
            // TODO: While the glasses stay off, each attempt is a direct connectGatt bounded by the SDK's
            // 15 s step timeout, repeated every 60 s for the process lifetime. Add a background connect
            // mode (connectGatt autoConnect = true) to HaloConnection and use it for retries.
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        }
    }

    // Shows each text until the connection ends, and returns why it ended. Cancellation clears the
    // display, best effort, and every exit closes the glasses.
    private suspend fun showUntilClosed(glasses: GlassesDisplay): GlassesProblem =
        try {
            coroutineScope {
                val drawing = launch { text.collect(glasses::showText) }
                val cause = glasses.closeCause.filterNotNull().first()
                drawing.cancel()
                Log.w(TAG, "Glasses connection ended: ${cause.message}")
                GlassesProblem.UNREACHABLE
            }
        } catch (e: GlassesException) {
            Log.w(TAG, "Glasses stopped responding: ${e.message}")
            if (glasses.closeCause.value == null) GlassesProblem.UNRESPONSIVE else GlassesProblem.UNREACHABLE
        } finally {
            withContext(NonCancellable) {
                if (glasses.closeCause.value == null) {
                    withTimeoutOrNull(CLEAR_ON_DISCONNECT_TIMEOUT_MS) {
                        try {
                            glasses.showText("")
                        } catch (_: GlassesException) {
                            // The display keeps its last frame; nothing else can clear it.
                        }
                    }
                }
                glasses.close()
            }
        }

    private fun hasConnectPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

private const val TAG = "GoModeGlasses"

// HaloConnection.scan reports only devices whose name it read, so the name is available here.
@SuppressLint("MissingPermission") // Scanning requires BLUETOOTH_CONNECT for names, which the scan already read.
private fun HaloScannedDevice.toItem(): ScannedGlasses =
    ScannedGlasses(address = device.address, name = device.name ?: "Halo", rssi = rssi)
