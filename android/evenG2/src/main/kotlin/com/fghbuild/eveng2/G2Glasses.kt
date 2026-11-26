// G2Glasses: one Even G2 session per connect() showing text, or images on custom firmware, until a typed close cause.
//
// Entry point of the SDK. connect() publishes G2State.Connecting before it returns and runs one
// G2Session in the owner's scope: the state becomes G2State.Ready, naming the firmware the
// session found, each time the glasses acknowledge what they show, and G2State.Closed with the
// session's G2CloseCause when it ends. The SDK never reconnects; the host decides whether and
// when to call connect() again, for example after a retryable cause. A session creates its page
// again each time the glasses close it; see G2Session. Request and page session IDs stay unique
// across the sessions of one G2Glasses, and each page starts with the latest content from show().
// A superseded session never publishes state. A replaced or stopped session stays in `job` until
// it has finished, so a later connect() joins it and never runs two sessions at once. An exception
// that escapes a session ends it with G2CloseCause.Failed. gestures, events, battery, sensors, and
// microphone report the current session's only; battery and sensors are empty again when a session
// starts or ends. The microphone, and the sensors that cost power, run while anything collects them, in
// whichever session runs then.

package com.fghbuild.eveng2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class G2Glasses(
    private val connector: G2ArmConnector,
    private val scope: CoroutineScope,
    /** The text container each session's page shows. */
    val page: G2TextPage = G2TextPage(),
    /**
     * Diagnostics for the host's log: each page's request, acknowledgement, close, and reopen
     * delay, each battery round and reply, and each microphone start, with its first packet, and stop.
     */
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<G2State>(G2State.Idle)
    private val desired = MutableStateFlow<G2Content>(G2Content.Text(""))
    private val ids = G2Ids()
    private var job: Job? = null
    private var generation = 0L

    val state: StateFlow<G2State> = mutableState.asStateFlow()

    private val mutableBattery = MutableStateFlow(G2Battery())

    /**
     * The current session's battery readings, which it polls while connected; see G2Session. Each
     * part is null until its first reading, and all are between sessions.
     */
    val battery: StateFlow<G2Battery> = mutableBattery.asStateFlow()

    private val mutableGestures = MutableSharedFlow<G2Gesture>(extraBufferCapacity = GESTURE_BUFFER)

    /**
     * Each click, double click, long press, and scroll of a touchpad or an R1 ring while a page is open; see
     * [G2EvenHub.gesture]. A gesture that nobody collects is lost.
     */
    val gestures: SharedFlow<G2Gesture> = mutableGestures.asSharedFlow()

    private val mutableEvents = MutableSharedFlow<G2InputEvent>(extraBufferCapacity = EVENT_BUFFER)

    /**
     * Each input and system event while a page is open, IMU samples aside, gestures included, for a
     * host that shows the wearer's input; [gestures] alone says which ones are deliberate gestures. An
     * event that nobody collects is lost.
     */
    val events: SharedFlow<G2InputEvent> = mutableEvents.asSharedFlow()

    private val streamers = MutableStateFlow(0)
    private val mutableSensors = MutableStateFlow(G2Sensors())

    /**
     * The current session's sensor readings, each null until its first; see [G2Sensors]. Collecting this
     * flow streams the accelerometer while a page is open, and on Faceclaw firmware the light sensor and
     * compass; their readings are null again once the last collector leaves. Whether the glasses are
     * worn and where the head went come without collectors.
     */
    val sensors: Flow<G2Sensors> =
        mutableSensors
            .onSubscription { streamers.update { it + 1 } }
            .onCompletion { streamers.update { it - 1 } }

    private val listeners = MutableStateFlow(0)
    private val mutableAudio =
        MutableSharedFlow<ShortArray>(extraBufferCapacity = AUDIO_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * The glasses' microphone as [G2Microphone.SAMPLE_RATE] Hz mono PCM, one array of
     * [G2Microphone.SAMPLES_PER_PACKET] samples per 50 ms packet; collectors share each array and must
     * not change it. The microphone runs while a page is open and anything collects this flow; see
     * [G2Microphone]. A collector that lags more than a second behind loses the oldest packets, and
     * nothing stands in for a lost packet. The flow never completes on its own: audio pauses while no
     * page is open and goes on in the next session.
     */
    val microphone: Flow<ShortArray> =
        mutableAudio
            .onSubscription { listeners.update { it + 1 } }
            .onCompletion { listeners.update { it - 1 } }

    /**
     * Run one session with [pair], replacing any previous one. The state is [G2State.Connecting]
     * when this returns, so every later state belongs to this session until the next [connect] or
     * [disconnect]. A [background] session waits without a deadline for the arms to advertise,
     * through Android's background connection; use it to reconnect a pair that went away. The first
     * connection to a scanned pair is direct; see [G2GattConnector].
     */
    fun connect(
        pair: G2Pair,
        background: Boolean = false,
    ) {
        synchronized(lock) {
            val previous = job
            val current = ++generation
            mutableState.value = G2State.Connecting(pair)
            mutableBattery.value = G2Battery()
            mutableSensors.value = G2Sensors()
            job =
                scope.launch {
                    previous?.cancelAndJoin()
                    runSession(current, pair, background)
                }
        }
    }

    /**
     * Show [text], at most [G2Display.MAX_TEXT_BYTES] UTF-8 bytes. It is updated in place while
     * a page is open and used by every page a later session creates. Stock firmware lays it out in
     * the [page]'s text container; [G2Firmware.Faceclaw] draws it on the whole [G2Panel], wrapped
     * as a container of the panel's width would wrap it, and drops the lines below its height.
     */
    fun show(text: String) {
        G2EvenHub.requireText(text)
        desired.value = G2Content.Text(text)
    }

    /**
     * Show [image], centred on the [G2Panel], while a session runs on [G2Firmware.Faceclaw] and in
     * every later session. Stock firmware draws no images, so a session on it shows empty text.
     */
    fun show(image: G2Image) {
        desired.value = G2Content.Image(image)
    }

    /** Close the page and both links; the state becomes [G2State.Closed] with [G2CloseCause.Stopped]. */
    suspend fun disconnect() {
        val (stopping, current) =
            synchronized(lock) {
                job?.cancel()
                job to ++generation
            }
        stopping?.join()
        synchronized(lock) {
            if (job === stopping) job = null
        }
        publish(current, G2State.Closed(G2CloseCause.Stopped))
    }

    // A host connector may throw anything; the session still ends with a cause.
    private suspend fun runSession(
        current: Long,
        pair: G2Pair,
        background: Boolean,
    ) {
        val session =
            G2Session(
                pair,
                connector,
                background,
                page,
                ids,
                desired,
                listeners,
                streamers,
                onReady = { firmware, shown -> publish(current, G2State.Ready(pair, firmware, shown)) },
                onGesture = { gesture(current, it) },
                onEvent = { event(current, it) },
                onBattery = { battery(current, it) },
                onSensors = { sensors(current, it) },
                onAudio = { audio(current, it) },
                log = log,
            )
        val cause =
            try {
                session.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                G2CloseCause.Failed(e)
            }
        publish(current, G2State.Closed(cause))
    }

    private fun publish(
        current: Long,
        state: G2State,
    ) {
        synchronized(lock) {
            if (current == generation) {
                mutableState.value = state
                if (state is G2State.Closed) {
                    mutableBattery.value = G2Battery()
                    mutableSensors.value = G2Sensors()
                }
            }
        }
    }

    private fun battery(
        current: Long,
        battery: G2Battery,
    ) {
        synchronized(lock) {
            if (current == generation) mutableBattery.value = battery
        }
    }

    private fun sensors(
        current: Long,
        sensors: G2Sensors,
    ) {
        synchronized(lock) {
            if (current == generation) mutableSensors.value = sensors
        }
    }

    private fun gesture(
        current: Long,
        gesture: G2Gesture,
    ) {
        synchronized(lock) {
            if (current == generation) mutableGestures.tryEmit(gesture)
        }
    }

    private fun event(
        current: Long,
        event: G2InputEvent,
    ) {
        synchronized(lock) {
            if (current == generation) mutableEvents.tryEmit(event)
        }
    }

    private fun audio(
        current: Long,
        pcm: ShortArray,
    ) {
        synchronized(lock) {
            if (current == generation) mutableAudio.tryEmit(pcm)
        }
    }

    private companion object {
        // Gestures a slow collector may lag behind; a person gestures far slower than one is handled.
        const val GESTURE_BUFFER = 8

        // One gesture sends up to four events, such as a release, a double click, and two scrolls.
        const val EVENT_BUFFER = 16

        // One second of microphone packets.
        const val AUDIO_BUFFER = 20
    }
}
