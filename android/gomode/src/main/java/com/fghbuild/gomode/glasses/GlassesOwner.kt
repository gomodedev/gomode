// Owns the one pair of glasses across Activities: scanning, the saved device, auto-connect, reconnection, and text.
package com.fghbuild.gomode.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
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

    /** The glasses are up and show [display], one screen of which shows what [screenFit] fits. */
    data class Connected(
        val display: Display,
        val screenFit: ScreenFit,
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
 * a permanent failure. This is the only reconnection policy for either model: each attempt is one
 * session of the model's SDK. Auto-connect starts that connection when [start] first sees the saved
 * settings. Every connection shows the latest [show] text. Callers hold BLUETOOTH_SCAN before
 * [startScan] and BLUETOOTH_CONNECT before [connect]; the owner checks BLUETOOTH_CONNECT again before
 * connecting on its own.
 *
 * The first attempt of a [connect] is direct, so a pair just scanned or chosen connects at once or
 * fails within the SDK's deadline. Every later attempt is a background connection: one pending GATT
 * client per radio link that Android's controller connects when the glasses next advertise, with no
 * deadline and no repeated attempts while they stay off or away. Each attempt follows a delay that
 * doubles from [RECONNECT_INITIAL_DELAY_MS] to [RECONNECT_MAX_DELAY_MS] and resets after each
 * successful connection: glasses that connect and then fail, such as an Even G2 that does not accept
 * AUTH, are not reconnected in a tight loop. A background attempt waits for Bluetooth to be on and
 * ends when Bluetooth turns off, since the stack's restart may forget pending connections; the next
 * attempt arms again once Bluetooth is back. Cancelling an attempt, by [disconnect], [forget],
 * [connect] to other glasses, or the end of [parentScope], releases its pending GATT clients.
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

    // Whether the Bluetooth adapter is on, from now on while collected.
    private val bluetoothOn: Flow<Boolean> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        trySend(state == BluetoothAdapter.STATE_ON)
                    }
                }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            trySend(appContext.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true)
            awaitClose { appContext.unregisterReceiver(receiver) }
        }.distinctUntilChanged()

    private val mutableState = MutableStateFlow(GlassesState())
    val state: StateFlow<GlassesState> = mutableState.asStateFlow()

    /** The connected glasses' display, or null without a connection; any thread may read it. */
    val connectedDisplay: Display?
        get() = (state.value.link as? GlassesLink.Connected)?.display

    /** The connected glasses' [GlassesDisplay.screenFit], or null without a connection. */
    val screenFit: Flow<ScreenFit?> =
        state.map { (it.link as? GlassesLink.Connected)?.screenFit }.distinctUntilChanged()

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
        val open: suspend (background: Boolean) -> GlassesDisplay =
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
                    { background -> HaloGlasses.connect(appContext, halo, bluetooth, background) }
                }

                is GlassesDevice.EvenG2 -> {
                    { background -> EvenG2Glasses.connect(g2, device.pair, background, scope) }
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
    private fun replaceLink(connect: (suspend (background: Boolean) -> GlassesDisplay)?) {
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
    private suspend fun keepConnected(connect: suspend (background: Boolean) -> GlassesDisplay) {
        var retryDelayMs = RECONNECT_INITIAL_DELAY_MS
        var background = false
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
                    val glasses = if (background) connectInBackground(connect) else connect(false)
                    retryDelayMs = RECONNECT_INITIAL_DELAY_MS
                    mutableState.update {
                        it.copy(link = GlassesLink.Connected(glasses.description, glasses.screenFit))
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
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
            background = true
        }
    }

    // A background attempt, armed while Bluetooth is on and abandoned when it turns off.
    // TODO(hardware): Hypothesis, unverified on a phone: turning Bluetooth off drops a pending
    // background connection without a callback, since AOSP's BluetoothGatt has no adapter-state
    // handling and the stack forgets its clients when it stops. Hence the re-arm here. On a phone,
    // arm a background connection, toggle Bluetooth, and check whether it still connects when the
    // glasses return; if it does, this watch only costs a re-arm.
    private suspend fun connectInBackground(connect: suspend (background: Boolean) -> GlassesDisplay): GlassesDisplay {
        bluetoothOn.first { it }
        return coroutineScope {
            val off =
                launch {
                    bluetoothOn.first { !it }
                    throw GlassesException("Bluetooth turned off")
                }
            try {
                connect(true)
            } finally {
                off.cancel()
            }
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
