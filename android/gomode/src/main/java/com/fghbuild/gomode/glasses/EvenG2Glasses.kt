// Even G2's GlassesDisplay: one firmware text container, created on connect and updated in place.
//
// The page's text container captures the touchpads and an R1 ring, whose clicks are presses.
//
// Each GlassesOwner attempt is one G2Glasses session; GlassesOwner alone decides whether to start
// another. connect() returns at the session's first G2State.Ready and fails if it reaches
// G2State.Closed first; once connected, Closed sets closeCause. A retryable G2CloseCause is a
// retryable GlassesException; the others, such as the wearer closing the page, are permanent.
//
// GlassesOwner keeps one G2Glasses across attempts, so request and page session IDs never repeat
// on the glasses. G2Glasses.connect() publishes Connecting before it returns, so every state
// connect() and the watcher read belongs to this attempt.
package com.fghbuild.gomode.glasses

import com.fghbuild.eveng2.G2CloseCause
import com.fghbuild.eveng2.G2Display
import com.fghbuild.eveng2.G2Glasses
import com.fghbuild.eveng2.G2Pair
import com.fghbuild.eveng2.G2Side
import com.fghbuild.eveng2.G2State
import com.fghbuild.eveng2.G2TextPage
import com.fghbuild.gomode.sdk.v1.Display
import com.fghbuild.gomode.sdk.v1.DisplayColorSpace
import com.fghbuild.gomode.sdk.v1.DisplayShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
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

/**
 * One G2 text container: the firmware shows a text whole when [G2TextPage.fits] it as [g2Text]
 * sends it, before the byte limit cuts it.
 */
internal data class G2ScreenFit(
    val page: G2TextPage,
) : ScreenFit {
    override fun fits(text: String): Boolean = page.fits(utf8(text))
}

internal class EvenG2Glasses private constructor(
    private val glasses: G2Glasses,
) : GlassesDisplay {
    private val ended = MutableStateFlow<GlassesException?>(null)
    private var watcher: Job? = null

    override val description = G2_DISPLAY
    override val screenFit = G2ScreenFit(glasses.page)
    override val closeCause: StateFlow<Exception?> = ended.asStateFlow()
    override val presses: Flow<Unit> = glasses.presses

    /** Shows [text], cut to the firmware's limit, and waits until the glasses acknowledge it. */
    override suspend fun showText(text: String) {
        val shown = g2Text(text)
        glasses.show(shown)
        val state = glasses.state.first { (it is G2State.Ready && it.text == shown) || it is G2State.Closed }
        if (state is G2State.Closed) throw end(state.cause)
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
         * A [background] session waits for the arms to advertise; see [G2Glasses.connect]. [scope]
         * watches the connection until [close]. The caller holds BLUETOOTH_CONNECT. A failure or
         * cancellation disconnects.
         */
        suspend fun connect(
            glasses: G2Glasses,
            pair: G2Pair,
            background: Boolean,
            scope: CoroutineScope,
        ): EvenG2Glasses {
            var connected: EvenG2Glasses? = null
            try {
                // Before the first transcript frame measures on the main thread.
                G2TextPage.loadMetrics()
                glasses.connect(pair, background)
                val first = glasses.state.first { it is G2State.Ready || it is G2State.Closed }
                if (first is G2State.Closed) throw first.cause.toGlassesException()
                return EvenG2Glasses(glasses).also { display ->
                    display.watcher =
                        scope.launch {
                            val closed = glasses.state.filterIsInstance<G2State.Closed>().first()
                            display.end(closed.cause)
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
    val bytes = utf8(text).toByteArray()
    if (bytes.size <= G2Display.MAX_TEXT_BYTES) return bytes.decodeToString()
    var end = G2Display.MAX_TEXT_BYTES
    // Cut before a code point's continuation bytes.
    while (bytes[end].toInt() and 0xC0 == 0x80) end--
    return bytes.decodeToString(0, end)
}

// [text] without NUL and with unpaired surrogates, which UTF-8 cannot carry, replaced as encoding does.
private fun utf8(text: String): String = text.replace("\u0000", "").toByteArray().decodeToString()

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
