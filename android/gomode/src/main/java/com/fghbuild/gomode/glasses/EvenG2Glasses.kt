// Even G2's GlassesDisplay: one firmware text container, created on connect and updated in place.
//
// Reconnection has one owner, GlassesOwner, for every glasses model: one backoff, one problem
// report, one stop on a permanent failure. G2Glasses can reconnect on its own, so each GlassesOwner
// attempt runs it for a single session. connect() returns at the first G2State.Ready and fails at
// the first Reconnecting or Closed state; once connected, the first such state sets closeCause.
// Either way GlassesOwner closes these glasses, and close() stops G2Glasses while it waits out its
// first backoff delay, before it reconnects. A retryable G2CloseCause is a retryable
// GlassesException; the others, such as the wearer closing the page, are permanent.
//
// GlassesOwner keeps one G2Glasses across attempts, so request and page session IDs never repeat
// on the glasses. Every attempt ends in disconnect(), so each starts from Idle or
// Closed(Stopped), and the first other state belongs to it.
package com.fghbuild.gomode.glasses

import com.fghbuild.eveng2.G2CloseCause
import com.fghbuild.eveng2.G2Display
import com.fghbuild.eveng2.G2Glasses
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.G2State
import com.fghbuild.gomode.sdk.v1.Display
import com.fghbuild.gomode.sdk.v1.DisplayColorSpace
import com.fghbuild.gomode.sdk.v1.DisplayShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Even G2's display of one eye as MCP requests declare it: 16 grey levels, which the glasses show in green. */
internal val G2_DISPLAY =
    Display(
        width = G2Display.WIDTH_PX,
        height = G2Display.HEIGHT_PX,
        shape = DisplayShape.Rectangle,
        colorSpace = DisplayColorSpace.Luminance,
        bitsPerPixel = bitsPerPixel(G2Display.GREY_LEVELS),
    )

// TODO(hardware): An unmeasured estimate. Hypothesis: the firmware font sets about 9 lines of about
// 40 characters in the 576×288 container, and wrapping at words loses some of each line, so 240
// characters keep a margin. No source states the font's metrics. On a real pair, show transcripts of
// 200 to 400 characters of ordinary prose through EvenG2Glasses and note where the firmware cuts or
// scrolls them; set this to about 90% of the largest that shows whole.
internal const val G2_TEXT_CAPACITY = 240

internal class EvenG2Glasses private constructor(
    private val glasses: G2Glasses,
) : GlassesDisplay {
    private val ended = MutableStateFlow<GlassesException?>(null)
    private var watcher: Job? = null

    override val description = G2_DISPLAY
    override val textCapacity = G2_TEXT_CAPACITY
    override val closeCause: StateFlow<Exception?> = ended.asStateFlow()

    /** Shows [text], cut to the firmware's limit, and waits until the glasses acknowledge it. */
    override suspend fun showText(text: String) {
        val shown = g2Text(text)
        glasses.show(shown)
        val state = glasses.state.first { (it is G2State.Ready && it.text == shown) || it.endCause() != null }
        state.endCause()?.let { throw end(it) }
    }

    override suspend fun close() {
        watcher?.cancel()
        glasses.disconnect()
        end(G2CloseCause.Stopped)
    }

    // The first end wins, so closeCause and a failing showText agree.
    private fun end(cause: G2CloseCause): GlassesException {
        ended.compareAndSet(null, cause.toGlassesException())
        return checkNotNull(ended.value)
    }

    companion object {
        /**
         * Opens a session of [glasses] with [pair], which shows the text [G2Glasses.show] last set.
         * [scope] watches the connection until [close]. The caller holds BLUETOOTH_CONNECT. A failure
         * or cancellation disconnects.
         */
        suspend fun connect(
            glasses: G2Glasses,
            pair: G2Pair,
            scope: CoroutineScope,
        ): EvenG2Glasses {
            var connected: EvenG2Glasses? = null
            try {
                glasses.connect(pair)
                val first = glasses.state.first { it is G2State.Ready || it.endCause() != null }
                first.endCause()?.let { throw it.toGlassesException() }
                return EvenG2Glasses(glasses).also { display ->
                    display.watcher =
                        scope.launch {
                            display.end(checkNotNull(glasses.state.first { it.endCause() != null }.endCause()))
                        }
                    connected = display
                }
            } finally {
                if (connected == null) withContext(NonCancellable) { glasses.disconnect() }
            }
        }
    }
}

/**
 * [text] as the firmware takes it: valid UTF-8 without NUL, at most [G2Display.MAX_TEXT_BYTES]. Like
 * a page too short for its text, the firmware limit cuts the end.
 */
internal fun g2Text(text: String): String {
    // Encoding replaces unpaired surrogates, which UTF-8 cannot carry.
    val bytes = text.replace("\u0000", "").toByteArray()
    if (bytes.size <= G2Display.MAX_TEXT_BYTES) return bytes.decodeToString()
    var end = G2Display.MAX_TEXT_BYTES
    // Cut before a code point's continuation bytes.
    while (bytes[end].toInt() and 0xC0 == 0x80) end--
    return bytes.decodeToString(0, end)
}

// Why this attempt's session ended, or null while it runs. Closed(Stopped) is the previous
// attempt's disconnect.
private fun G2State.endCause(): G2CloseCause? =
    when (this) {
        is G2State.Reconnecting -> cause
        is G2State.Closed -> cause.takeIf { it != G2CloseCause.Stopped }
        else -> null
    }

private fun G2CloseCause.toGlassesException(): GlassesException {
    val problem =
        when (this) {
            is G2CloseCause.ConnectFailed, is G2CloseCause.Disconnected, is G2CloseCause.WriteFailed -> {
                GlassesProblem.UNREACHABLE
            }

            else -> {
                GlassesProblem.UNRESPONSIVE
            }
        }
    val message =
        when (this) {
            is G2CloseCause.ConnectFailed -> {
                "Even G2 ${side.label()} arm did not connect: $reason"
            }

            is G2CloseCause.Disconnected -> {
                "Even G2 ${side.label()} arm disconnected (status=$status)"
            }

            is G2CloseCause.WriteFailed -> {
                "Even G2 ${side.label()} arm did not take a write: $reason"
            }

            is G2CloseCause.AuthTimedOut -> {
                "Even G2 ${side.label()} arm did not accept Go Mode"
            }

            G2CloseCause.PreludeTimedOut -> {
                "Even G2 did not answer the session prelude"
            }

            is G2CloseCause.CommandTimedOut -> {
                "Even G2 did not answer ${command.name}"
            }

            is G2CloseCause.CommandRejected -> {
                "Even G2 refused ${command.name} (result=$result)"
            }

            is G2CloseCause.PageExited -> {
                if (abnormal) "Even G2 closed Go Mode's page" else "Go Mode was closed on the glasses"
            }

            is G2CloseCause.Failed -> {
                "Could not use Even G2: ${error.message}"
            }

            G2CloseCause.Stopped -> {
                "Disconnected"
            }
        }
    return GlassesException(message, (this as? G2CloseCause.Failed)?.error, permanent = !retryable, problem = problem)
}

private fun G2Side.label(): String = name.lowercase()
