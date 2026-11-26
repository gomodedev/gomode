// G2Glasses: one Even G2 session per connect() showing text, or images on custom firmware, until a typed close cause.
//
// Entry point of the SDK. connect() publishes G2State.Connecting before it returns and runs one
// G2Session in the owner's scope: the state becomes G2State.Ready, naming the firmware the
// session found, each time the glasses acknowledge what they show, and G2State.Closed with the
// session's G2CloseCause when it ends. The SDK never reconnects; the host decides whether and
// when to call connect() again, for example after a retryable cause. Request and page session
// IDs stay unique across the sessions of one G2Glasses, and each page starts with the latest
// content from show(). A superseded session never publishes state. A replaced or stopped session
// stays in `job` until it has finished, so a later connect() joins it and never runs two
// sessions at once. An exception that escapes a session ends it with G2CloseCause.Failed.
// presses and events report the current session's only.

package com.fghbuild.eveng2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class G2Glasses(
    private val connector: G2ArmConnector,
    private val scope: CoroutineScope,
    /** The text container each session's page shows. */
    val page: G2TextPage = G2TextPage(),
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<G2State>(G2State.Idle)
    private val desired = MutableStateFlow<G2Content>(G2Content.Text(""))
    private val ids = G2Ids()
    private var job: Job? = null
    private var generation = 0L

    val state: StateFlow<G2State> = mutableState.asStateFlow()

    private val mutablePresses = MutableSharedFlow<Unit>(extraBufferCapacity = PRESS_BUFFER)

    /**
     * Each press of a touchpad or an R1 ring while a page is open; see [G2EvenHub.press]. Other
     * gestures are not reported, and a press that nobody collects is lost.
     */
    val presses: SharedFlow<Unit> = mutablePresses.asSharedFlow()

    private val mutableEvents = MutableSharedFlow<G2InputEvent>(extraBufferCapacity = EVENT_BUFFER)

    /**
     * Each input and system event while a page is open, IMU samples aside, presses included, for a
     * host that shows the wearer's gestures; [presses] alone says which ones press. An event that
     * nobody collects is lost.
     */
    val events: SharedFlow<G2InputEvent> = mutableEvents.asSharedFlow()

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
                onReady = { firmware, shown -> publish(current, G2State.Ready(pair, firmware, shown)) },
                onPress = { press(current) },
                onEvent = { event(current, it) },
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
            if (current == generation) mutableState.value = state
        }
    }

    private fun press(current: Long) {
        synchronized(lock) {
            if (current == generation) mutablePresses.tryEmit(Unit)
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

    private companion object {
        // Presses a slow collector may lag behind; a person presses far slower than one is handled.
        const val PRESS_BUFFER = 8

        // One gesture sends up to four events, such as a release, a double click, and two scrolls.
        const val EVENT_BUFFER = 16
    }
}
