// G2Glasses: keeps one Even G2 pair connected with a text page, reconnecting after retryable close causes.
//
// Entry point of the SDK. connect() starts a supervisor in the owner's scope: it runs one
// G2Session at a time and, when a session ends with a retryable G2CloseCause, waits the next
// G2Protocol.RECONNECT_BACKOFF_MS delay and runs another. Each new session recreates the page
// with the latest text from show(). A session that reaches G2State.Ready resets the backoff.
// A superseded supervisor never publishes state. A replaced or stopped supervisor stays in `job`
// until it has finished, so a later connect() joins it and never runs two sessions at once.
// An exception that escapes a session ends the connection with G2CloseCause.Failed.

package com.fghbuild.eveng2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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

    /** Connect to [pair] and keep it connected, replacing any previous connection. */
    fun connect(pair: G2Pair) {
        synchronized(lock) {
            val previous = job
            val current = ++generation
            job =
                scope.launch {
                    previous?.cancelAndJoin()
                    supervise(current, pair)
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

    @Suppress("TooGenericExceptionCaught") // An escaping session exception would stop reconnecting.
    private suspend fun supervise(
        current: Long,
        pair: G2Pair,
    ) {
        var failures = 0
        while (true) {
            publish(current, G2State.Connecting(pair, failures + 1))
            var ready = false
            val session =
                G2Session(pair, connector, page, ids, desired) { text ->
                    ready = true
                    publish(current, G2State.Ready(pair, text))
                }
            val cause =
                try {
                    session.run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    G2CloseCause.Failed(e)
                }
            if (ready) failures = 0
            if (!cause.retryable) {
                publish(current, G2State.Closed(cause))
                return
            }
            val backoff = G2Protocol.RECONNECT_BACKOFF_MS
            val delayMs = backoff[minOf(failures, backoff.lastIndex)]
            failures++
            publish(current, G2State.Reconnecting(pair, cause, delayMs))
            delay(delayMs)
        }
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
