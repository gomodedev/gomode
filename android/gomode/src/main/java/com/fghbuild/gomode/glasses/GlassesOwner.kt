// Owns the one pair of glasses across Activities: scanning, the saved device, auto-connect, reconnection, and text.
package com.fghbuild.gomode.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.fghbuild.eveng2.G2ArmConnector
import com.fghbuild.eveng2.G2Glasses
import com.fghbuild.eveng2.G2PairScanner
import com.fghbuild.gomode.data.GlassesDevice
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.sdk.v1.Display
import com.fghbuild.halo.ble.HaloConnection
import com.fghbuild.halo.ble.HaloDeviceType
import com.fghbuild.halo.ble.HaloScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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

    /** The glasses are up and show [display], which fits [textCapacity] characters of prose. */
    data class Connected(
        val display: Display,
        val textCapacity: Int,
    ) : GlassesLink

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

/** Glasses a scan found; [rssi] is null for an Even G2 pair, whose arms each have their own. */
internal data class ScannedGlasses(
    val device: GlassesDevice,
    val name: String,
    val rssi: Int?,
)

internal data class GlassesState(
    val link: GlassesLink = GlassesLink.Disconnected,
    val scanning: Boolean = false,
    val devices: List<ScannedGlasses> = emptyList(),
    val saved: GlassesDevice? = null,
    val autoConnect: Boolean = false,
    // A failure that stopped scanning or prevented a connection attempt.
    val error: String? = null,
)

/**
 * The application owns one so the glasses stay connected across Activity replacement. One pair, a
 * Halo or an Even G2, is saved and connected at a time; connecting to another replaces it. A
 * connection to the saved glasses lasts until [disconnect], [forget], the end of [parentScope], or
 * a permanent failure, reconnecting in between with a delay that doubles from
 * [RECONNECT_INITIAL_DELAY_MS] to [RECONNECT_MAX_DELAY_MS] and resets after each successful
 * connection. This is the only reconnection policy for either model; see EvenG2Glasses.kt for how
 * it replaces G2Glasses' own. Auto-connect starts that connection when [start] first sees the saved
 * settings. Every connection shows the latest [show] text. Callers hold BLUETOOTH_SCAN before
 * [startScan] and BLUETOOTH_CONNECT before [connect]; the owner checks BLUETOOTH_CONNECT again before
 * connecting on its own.
 *
 * Halo scans and connects through [halo], Even G2 through [g2Scanner] and [g2Connector]; the link
 * logic depends on [GlassesDisplay] alone. A scan looks for both models at once.
 *
 * The owner's coroutines, including a connected Even G2's heartbeat, run on the main thread as
 * children of [parentScope], the application's: cancelling it ends the owner and its connection.
 */
internal class GlassesOwner(
    parentScope: CoroutineScope,
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val halo: HaloConnection,
    private val g2Scanner: G2PairScanner,
    g2Connector: G2ArmConnector,
) {
    private val appContext = context.applicationContext
    private val scope =
        parentScope.coroutineContext.let { parent ->
            CoroutineScope(parent + SupervisorJob(parent[Job]) + Dispatchers.Main.immediate)
        }
    private val g2 = G2Glasses(g2Connector, scope)

    // Halo devices a scan found by address; connecting prefers them to a device made from the address.
    private val scanned = mutableMapOf<String, BluetoothDevice>()
    private val text = MutableStateFlow("")
    private var scanJob: Job? = null
    private var linkJob: Job? = null
    private var started = false

    private val mutableState = MutableStateFlow(GlassesState())
    val state: StateFlow<GlassesState> = mutableState.asStateFlow()

    /** The connected glasses' display, or null without a connection; any thread may read it. */
    val connectedDisplay: Display?
        get() = (state.value.link as? GlassesLink.Connected)?.display

    /** The connected glasses' [GlassesDisplay.textCapacity], or null without a connection. */
    val textCapacity: Flow<Int?> =
        state.map { (it.link as? GlassesLink.Connected)?.textCapacity }.distinctUntilChanged()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            settingsRepository.settings.collect { settings ->
                mutableState.update { it.copy(saved = settings.glasses, autoConnect = settings.glassesAutoConnect) }
            }
        }
        scope.launch {
            val settings = settingsRepository.settings.first { it.loadState == SettingsLoadState.Ready }
            val saved = settings.glasses
            if (settings.glassesAutoConnect && saved != null && hasConnectPermission() && linkJob == null) {
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
        val halos =
            halo
                .scan()
                .filter { it.type == HaloDeviceType.HALO }
                .map { result -> result.toItem().also { scanned[result.device.address] = result.device } }
        val g2Pairs =
            g2Scanner
                .pairs()
                .map { ScannedGlasses(GlassesDevice.EvenG2(it), "Even G2 ${it.id}", null) }
        scanJob =
            scope.launch {
                merge(halos, g2Pairs)
                    .catch { error ->
                        mutableState.update { it.copy(scanning = false, error = error.message ?: "Scan failed") }
                    }.collect { item ->
                        val key = item.device.key()
                        mutableState.update { current ->
                            current.copy(
                                devices =
                                    (current.devices.filterNot { it.device.key() == key } + item)
                                        .sortedWith(compareBy({ it.name }, { it.device.key() })),
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

    /**
     * Saves [device], a scan result or the saved glasses, in place of the saved glasses and keeps it
     * connected, disconnecting the glasses connected before.
     */
    fun connect(device: GlassesDevice) {
        if (!hasConnectPermission()) {
            mutableState.update { it.copy(error = "Allow Nearby devices to connect to glasses") }
            return
        }
        val open: suspend () -> GlassesDisplay =
            when (device) {
                is GlassesDevice.Halo -> {
                    val bluetooth =
                        scanned[device.address]
                            ?: appContext
                                .getSystemService(BluetoothManager::class.java)
                                .adapter
                                ?.getRemoteDevice(device.address)
                    if (bluetooth == null) {
                        mutableState.update { it.copy(error = "Bluetooth is not available") }
                        return
                    }
                    suspend { HaloGlasses.connect(appContext, halo, bluetooth) }
                }

                is GlassesDevice.EvenG2 -> {
                    suspend { EvenG2Glasses.connect(g2, device.pair, scope) }
                }
            }
        stopScan()
        mutableState.update { it.copy(link = GlassesLink.Connecting, error = null) }
        replaceLink(open)
        scope.launch { settingsRepository.updateGlasses(device) }
    }

    /** Stops the connection and its reconnection, clearing the display when the glasses are reachable. */
    fun disconnect() {
        replaceLink(null)
        mutableState.update { it.copy(link = GlassesLink.Disconnected) }
    }

    fun forget() {
        disconnect()
        scope.launch { settingsRepository.updateGlasses(null) }
    }

    fun updateAutoConnect(enabled: Boolean) {
        scope.launch { settingsRepository.updateGlassesAutoConnect(enabled) }
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
            val ending =
                try {
                    val glasses = connect()
                    retryDelayMs = RECONNECT_INITIAL_DELAY_MS
                    mutableState.update {
                        it.copy(link = GlassesLink.Connected(glasses.description, glasses.textCapacity))
                    }
                    showUntilClosed(glasses)
                } catch (e: GlassesException) {
                    Log.w(TAG, "Glasses connection failed: ${e.message}")
                    e
                }
            if (ending.permanent) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(link = GlassesLink.Disconnected, error = ending.message) }
                return
            }
            // A disconnect or a switch to other glasses cancels this link; it must not report a retry.
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(link = GlassesLink.Reconnecting(ending.problem)) }
            // TODO: While the glasses stay off, each attempt is a direct connectGatt bounded by the SDK's
            // timeout (HaloConnection's 15 s step, G2Protocol.CONNECT_TIMEOUT_MS), repeated every 60 s for
            // the process lifetime. Add a background connect mode (connectGatt autoConnect = true) to
            // HaloConnection and G2GattConnector and use it for retries.
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        }
    }

    // Shows each text until the connection ends, and returns why it ended: the glasses' close cause
    // once they report one. Cancellation clears the display, best effort, and every exit closes the
    // glasses.
    private suspend fun showUntilClosed(glasses: GlassesDisplay): GlassesException =
        try {
            coroutineScope {
                val drawing = launch { text.collect(glasses::showText) }
                val cause = glasses.closeCause.filterNotNull().first()
                drawing.cancel()
                Log.w(TAG, "Glasses connection ended: ${cause.message}")
                cause.asGlassesException()
            }
        } catch (e: GlassesException) {
            Log.w(TAG, "Glasses stopped responding: ${e.message}")
            glasses.closeCause.value?.asGlassesException() ?: e
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

// A transport's own close cause, such as a dropped Halo link, is a retryable loss of reach.
private fun Exception.asGlassesException(): GlassesException =
    this as? GlassesException ?: GlassesException(message ?: "Disconnected", this)

// Identifies scan results of the same glasses across reports.
private fun GlassesDevice.key(): String =
    when (this) {
        is GlassesDevice.Halo -> "halo/$address"
        is GlassesDevice.EvenG2 -> "even-g2/${pair.id}"
    }

// HaloConnection.scan reports only devices whose name it read, so the name is available here.
@SuppressLint("MissingPermission") // Scanning requires BLUETOOTH_CONNECT for names, which the scan already read.
private fun HaloScannedDevice.toItem(): ScannedGlasses =
    ScannedGlasses(GlassesDevice.Halo(device.address), name = device.name ?: "Halo", rssi = rssi)
