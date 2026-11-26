// Owns the one pair of glasses across Activities: scanning, the saved device, auto-connect, reconnection, frames, input.
package com.fghbuild.gomode.glasses

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.fghbuild.eveng2.G2ArmConnector
import com.fghbuild.eveng2.G2Glasses
import com.fghbuild.eveng2.G2PairScanner
import com.fghbuild.gomode.R
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
import kotlin.math.roundToInt

internal const val RECONNECT_INITIAL_DELAY_MS = 2_000L
internal const val RECONNECT_MAX_DELAY_MS = 60_000L

// Bounds the best-effort clear when the user disconnects; the glasses may already be out of range.
private const val CLEAR_ON_DISCONNECT_TIMEOUT_MS = 3_000L

/** How long a [GlassesOwner.notify] notice stays, long enough to read at a glance. */
internal const val GLASSES_NOTICE_MS = 4_000L

// Actions a slow collector may lag behind; a person gestures far slower than one is handled.
private const val ACTION_BUFFER = 8

/** How many of the latest [GlassesOwner.inputs] it keeps. */
internal const val GLASSES_INPUT_HISTORY = 20

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
    // Whether GlassesOwner.toggleLogo keeps the Go Mode logo up on the glasses.
    val logoPinned: Boolean = false,
    // The connected glasses' GlassesDisplay.batteries; empty without a connection.
    val batteries: List<GlassesBattery> = emptyList(),
)

/**
 * The application owns one so the glasses stay connected across Activity replacement. One pair, a
 * Halo or an Even G2, is saved and connected at a time; connecting to another replaces it. A
 * connection to the saved glasses lasts until [disconnect], [forget], the end of [parentScope], or
 * a permanent failure. This is the only reconnection policy for either model: each attempt is one
 * session of the model's SDK. Auto-connect starts that connection when [start] first sees the saved
 * settings, and when [updateAutoConnect] turns it on while no connection runs. Every connection
 * shows the latest [show] frame, or a [notify] notice over it, reports its [actions] and [scrolls], keeps its
 * battery readings in [state] for every screen that shows them, and offers its [microphone] and
 * [sensing], if it has them, and turns head movements into [headFlicks] while a voice owner asks for
 * them through [detectHeadFlicks]. While that frame is blank, a connection first plays a short greeting
 * that confirms it on the glasses; content that is not blank, or a notice, ends it. Callers hold
 * BLUETOOTH_SCAN before [startScan] and BLUETOOTH_CONNECT before [connect]; the owner checks
 * BLUETOOTH_CONNECT again before connecting on its own.
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
    private val g2 = G2Glasses(g2Connector, scope, log = { Log.i(TAG, it) })

    // Halo devices a scan found by address; connecting prefers them to a device made from the address.
    private val scanned = mutableMapOf<String, BluetoothDevice>()
    private val frame = MutableStateFlow<GlassesFrame>(GlassesFrame.Text(""))
    private val notice = MutableStateFlow<String?>(null)
    private var noticeJob: Job? = null

    // The greeting frame over [frame], set by the connection that plays it.
    private val greeting = MutableStateFlow<GlassesFrame?>(null)
    private var greetingJob: Job? = null

    // [toggleLogo]'s requests to play the greeting, which only a connection collects.
    private val logoRequests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutableActions = MutableSharedFlow<GlassesAction>(extraBufferCapacity = ACTION_BUFFER)
    private val mutableScrolls = MutableSharedFlow<GlassesScroll>(extraBufferCapacity = ACTION_BUFFER)
    private val mutableHeadFlicks = MutableSharedFlow<HeadFlick>(extraBufferCapacity = ACTION_BUFFER)
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

    private val mutableInputs = MutableStateFlow<List<GlassesInput>>(emptyList())

    /**
     * The latest [GLASSES_INPUT_HISTORY] [GlassesDisplay.events] of every connection, newest first,
     * so the wearer sees what the glasses report for each gesture.
     */
    val inputs: StateFlow<List<GlassesInput>> = mutableInputs.asStateFlow()

    // Open inspectInputs handles; main thread only.
    private val inspections = MutableStateFlow(0)

    private val mutableState = MutableStateFlow(GlassesState())
    val state: StateFlow<GlassesState> = mutableState.asStateFlow()

    private val mutableMicrophone = MutableStateFlow<GlassesMicrophone?>(null)

    /** The connected glasses' microphone, or null while no connected glasses have one. */
    val microphone: StateFlow<GlassesMicrophone?> = mutableMicrophone.asStateFlow()

    private val mutableSensing = MutableStateFlow<GlassesSensing?>(null)

    /** The connected glasses' sensors, or null while no connected glasses report any. */
    val sensing: StateFlow<GlassesSensing?> = mutableSensing.asStateFlow()

    /** The connected glasses' display, or null without a connection; any thread may read it. */
    val connectedDisplay: Display?
        get() = (state.value.link as? GlassesLink.Connected)?.display

    /** The connected glasses' display and [GlassesDisplay.screenFit], or null without a connection. */
    val screen: Flow<GlassesLink.Connected?> =
        state.map { it.link as? GlassesLink.Connected }.distinctUntilChanged()

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
            if (settings.glassesAutoConnect && saved != null && hasConnectPermission() && !linkRuns) connect(saved)
        }
    }

    /**
     * Each action of the connected glasses that the [takeActionsFirst] handler leaves; see
     * [GlassesDisplay.actions], while nobody [inspectInputs]. While nothing collects them, as when
     * no Activity is open to start voice, an action instead shows a notice that says why it cannot act.
     */
    val actions: SharedFlow<GlassesAction> = mutableActions.asSharedFlow()

    /**
     * Each scroll of the connected glasses, see [GlassesDisplay.scrolls], while nobody [inspectInputs].
     * A scroll that nothing collects, as when no held text shows, is lost.
     */
    val scrolls: SharedFlow<GlassesScroll> = mutableScrolls.asSharedFlow()

    /**
     * Each head flick of the connected glasses while [detectHeadFlicks] runs, which [inputs] also lists.
     * No action is bound to one yet. A flick that nothing collects is lost.
     */
    val headFlicks: SharedFlow<HeadFlick> = mutableHeadFlicks.asSharedFlow()

    /**
     * Until cancelled, runs the motion sensors of the connected glasses while [active] holds, a voice
     * session's, and reports each head flick that [HeadFlickDetector] finds in [headFlicks] and [inputs].
     * [GlassesSensing.motion] leaves the light sensor off, so the glasses keep their auto-brightness; the
     * sensors stop when [active] turns false or the glasses disconnect. Readings while the glasses are
     * off the head reset the detector. Main thread only.
     */
    suspend fun detectHeadFlicks(active: Flow<Boolean>) {
        combine(active, sensing) { on, glasses -> glasses.takeIf { on } }
            .distinctUntilChanged()
            .collectLatest { glasses -> if (glasses != null) detectHeadFlicks(glasses) }
    }

    private suspend fun detectHeadFlicks(glasses: GlassesSensing) {
        Log.i(TAG, "Head flicks: motion sensors on")
        val detector = HeadFlickDetector { Log.i(TAG, it) }
        try {
            glasses.motion.collect { sensors ->
                if (sensors.worn == false) {
                    detector.reset()
                    return@collect
                }
                val pitch = sensors.acceleration?.let(::pitchDegrees)
                val flick = detector.accept(SystemClock.elapsedRealtime(), pitch, sensors.heading) ?: return@collect
                val degrees = flick.degrees.roundToInt()
                Log.i(TAG, "Head flick ${flick.direction.label}: $degrees° in ${flick.durationMs} ms")
                mutableHeadFlicks.tryEmit(flick)
                record(GlassesEvent(GlassesGesture.HEAD_FLICK, direction = flick.direction))
            }
        } finally {
            Log.i(TAG, "Head flicks: motion sensors off")
        }
    }

    // Main thread only, like every action.
    private var firstActionHandler: ((GlassesAction) -> Boolean)? = null

    /**
     * Until the returned handle closes, the wearer tries gestures: no handler, collector, or notice
     * acts on an action, and the connected glasses show the latest
     * [inputs] over everything else. Handles nest; closing one twice releases it once. Main thread only.
     */
    fun inspectInputs(): AutoCloseable {
        inspections.update { it + 1 }
        var open = true
        return AutoCloseable {
            if (open) {
                open = false
                inspections.update { it - 1 }
            }
        }
    }

    /**
     * Offers each action to [handler] before [actions]; it returns whether it acted on the action.
     * Work that outlives every Activity, such as a car call, ends through it. Null removes the handler.
     */
    fun takeActionsFirst(handler: ((GlassesAction) -> Boolean)?) {
        firstActionHandler = handler
    }

    /**
     * Shows [frame] on the glasses now and after every reconnection, as [GlassesDisplay.showText] or
     * [GlassesImageDisplay.showImage]. An image or text that is not blank ends a [notify] notice and
     * the connection's greeting.
     */
    fun show(frame: GlassesFrame) {
        this.frame.value = frame
        if (frame !is GlassesFrame.Text || frame.text.isNotBlank()) {
            endNotice()
            endGreeting()
        }
    }

    /**
     * Shows [message] over the [show] frame for [GLASSES_NOTICE_MS], until [show] sends an image or text
     * that is not blank, or until the next notice: feedback on the glasses for a gesture's action.
     */
    fun notify(message: String) {
        endNotice()
        endGreeting()
        notice.value = message
        noticeJob =
            scope.launch {
                delay(GLASSES_NOTICE_MS)
                notice.value = null
            }
    }

    /**
     * On the connected glasses, plays the connection greeting again and keeps its last frame, the logo,
     * up without a deadline; the next call takes it down. Content from [show] or a [notify] notice also
     * ends it. Does nothing without a connection.
     */
    fun toggleLogo() {
        if (state.value.logoPinned) {
            endGreeting()
        } else if (state.value.link is GlassesLink.Connected) {
            endNotice()
            mutableState.update { it.copy(logoPinned = true) }
            logoRequests.tryEmit(Unit)
        }
    }

    private fun endGreeting() {
        mutableState.update { it.copy(logoPinned = false) }
        greetingJob?.cancel()
    }

    private fun endNotice() {
        noticeJob?.cancel()
        noticeJob = null
        notice.value = null
    }

    private fun record(event: GlassesEvent) {
        Log.i(TAG, "Glasses input: ${event.label}")
        val shown = GlassesInput(System.currentTimeMillis(), event)
        mutableInputs.update { (listOf(shown) + it).take(GLASSES_INPUT_HISTORY) }
    }

    private fun act(action: GlassesAction) {
        when {
            inspections.value > 0 -> {}

            firstActionHandler?.invoke(action) == true -> {}

            mutableActions.subscriptionCount.value == 0 -> {
                val notice =
                    when (action) {
                        GlassesAction.TOGGLE_MUTE -> R.string.glasses_notice_voice_off
                        GlassesAction.TOGGLE_VOICE -> R.string.glasses_notice_open_app
                    }
                notify(appContext.getString(notice))
            }

            else -> {
                mutableActions.tryEmit(action)
            }
        }
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

    /**
     * Saves [enabled]. Turning auto-connect on also connects to the saved glasses unless a connection
     * runs; like [connect], it reports a missing BLUETOOTH_CONNECT as the error.
     */
    fun updateAutoConnect(enabled: Boolean) {
        scope.launch { settingsRepository.updateGlassesAutoConnect(enabled) }
        val saved = settingsRepository.settings.value.glasses
        if (enabled && saved != null && !linkRuns) connect(saved)
    }

    // Whether a connection to glasses is connecting, connected, or waiting to reconnect.
    private val linkRuns: Boolean get() = linkJob?.isActive == true

    // The previous link finishes clearing and closing its glasses before the next one connects.
    // A pinned logo survives automatic reconnection, not a deliberate new link or disconnection. The
    // batteries go at once, before the previous link has finished.
    private fun replaceLink(connect: (suspend (background: Boolean) -> GlassesDisplay)?) {
        mutableState.update { it.copy(logoPinned = false, batteries = emptyList()) }
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

    // Shows each frame, reports each action, and keeps the state's batteries, the microphone, and the
    // sensors until the connection ends, and returns why it ended: the glasses' close cause once they
    // report one. Cancellation clears the display, best effort, and every exit empties the batteries,
    // drops the microphone and the sensors, and closes the glasses.
    private suspend fun showUntilClosed(glasses: GlassesDisplay): GlassesException =
        try {
            mutableMicrophone.value = glasses as? GlassesMicrophone
            mutableSensing.value = glasses as? GlassesSensing
            coroutineScope {
                if (state.value.logoPinned || frame.value.let { it is GlassesFrame.Text && it.text.isBlank() }) {
                    greetingJob = launch { playGreeting(glasses) }
                }
                val greetings =
                    launch {
                        logoRequests.collect {
                            greetingJob?.cancel()
                            greetingJob = launch { playGreeting(glasses) }
                        }
                    }
                val drawing =
                    launch {
                        val inspected =
                            combine(inspections, mutableInputs) { count, inputs -> inputs.takeIf { count > 0 } }
                        combine(frame, greeting, notice, inspected) { frame, greeting, notice, inputs ->
                            inputs?.let { GlassesFrame.Text(inspectionText(it, glasses.screenFit)) }
                                ?: notice?.let(GlassesFrame::Text)
                                ?: greeting
                                ?: frame
                        }.distinctUntilChanged()
                            .collect { shown ->
                                when (shown) {
                                    is GlassesFrame.Text -> {
                                        glasses.showText(shown.text)
                                    }

                                    is GlassesFrame.Image -> {
                                        // An image shown before the switch to glasses without images clears them.
                                        if (glasses is GlassesImageDisplay) {
                                            glasses.showImage(
                                                shown.image,
                                            )
                                        } else {
                                            glasses.showText("")
                                        }
                                    }
                                }
                            }
                    }
                val acting =
                    launch {
                        launch { glasses.actions.collect(::act) }
                        glasses.scrolls.collect { if (inspections.value == 0) mutableScrolls.tryEmit(it) }
                    }
                val listening = launch { glasses.events.collect(::record) }
                val metering =
                    launch {
                        glasses.batteries.collect { batteries ->
                            mutableState.update { it.copy(batteries = batteries) }
                        }
                    }
                val cause = glasses.closeCause.filterNotNull().first()
                drawing.cancel()
                acting.cancel()
                listening.cancel()
                metering.cancel()
                greetings.cancel()
                Log.w(TAG, "Glasses connection ended: ${cause.message}")
                cause.asGlassesException()
            }
        } catch (e: GlassesException) {
            Log.w(TAG, "Glasses stopped responding: ${e.message}")
            glasses.closeCause.value?.asGlassesException() ?: e
        } finally {
            mutableState.update { it.copy(batteries = emptyList()) }
            mutableMicrophone.value = null
            mutableSensing.value = null
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

    // Plays the greeting on [glasses] over the frame, then yields to it, unless the logo is pinned.
    private suspend fun playGreeting(glasses: GlassesDisplay) {
        val steps =
            greetingSteps(
                images = glasses is GlassesImageDisplay,
                width = glasses.description.width,
                height = glasses.description.height,
                animate = ValueAnimator.areAnimatorsEnabled(),
            )
        try {
            for (step in steps) {
                greeting.value = step.frame
                delay(step.durationMs)
            }
            // A pinned logo stays until toggleLogo, content, a notice, or the connection's end cancels it.
            if (state.value.logoPinned) awaitCancellation()
        } finally {
            greeting.value = null
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
