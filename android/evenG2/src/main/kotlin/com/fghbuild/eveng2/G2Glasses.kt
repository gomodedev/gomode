// G2Glasses: runs one Even G2 session per connect() with a text page, ending with a typed close cause.
//
// Entry point of the SDK. connect() publishes G2State.Connecting before it returns and runs one
// G2Session in the owner's scope: the state becomes G2State.Ready each time the glasses
// acknowledge a text, and G2State.Closed with the session's G2CloseCause when it ends. The SDK
// never reconnects; the host decides whether and when to call connect() again, for example
// after a retryable cause. Request and page session IDs stay unique across the sessions of one
// G2Glasses, and each page starts with the latest text from show(). A superseded session never
// publishes state. A replaced or stopped session stays in `job` until it has finished, so a
// later connect() joins it and never runs two sessions at once. An exception that escapes a
// session ends it with G2CloseCause.Failed.

package com.fghbuild.eveng2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class G2Glasses(
    private val connector: G2ArmConnector,
    private val scope: CoroutineScope,
    private val page: G2TextPage = G2TextPage(),
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<G2State>(G2State.Idle)
    private val desired = MutableStateFlow("")
    private val ids = G2Ids()
    private var job: Job? = null
    private var generation = 0L

    val state: StateFlow<G2State> = mutableState.asStateFlow()

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
     * a page is open and used by every page a later session creates.
     */
    fun show(text: String) {
        G2EvenHub.requireText(text)
        desired.value = text
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

    @Suppress("TooGenericExceptionCaught") // A host connector may throw anything; the session still ends with a cause.
    private suspend fun runSession(
        current: Long,
        pair: G2Pair,
        background: Boolean,
    ) {
        val session =
            G2Session(pair, connector, background, page, ids, desired) { publish(current, G2State.Ready(pair, it)) }
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
}
