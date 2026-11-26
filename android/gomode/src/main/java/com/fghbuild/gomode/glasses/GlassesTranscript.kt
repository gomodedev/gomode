// Picks the voice transcript lines that fit on glasses and feeds them while a session runs.
//
// Glasses are read at a glance and hold a few short lines: Halo about eight in its circle, Even G2
// ten wider ones. They show the current exchange only: the user's newest utterance, or the live
// recognition draft while the user speaks, then the assistant's reply to it. Each speaker change
// starts a line; the user's line starts with "You: ", as on the phone, and the reply is unlabelled
// because it is the main content. Line feeds inside a turn become spaces so they do not waste lines.
// Displays cut text that overflows at the bottom, so the text keeps the newest words that the
// connected display's ScreenFit says one screen shows whole: past them, the oldest words are cut and
// an ellipsis marks the cut. Reasoning is never shown.
//
// A tool result's user content, as VoiceState.heldUserContent holds it, replaces the transcript until
// the user's next turn starts, whether or not the transcript setting is on: its last block that the
// connected display draws, an image only where the display declares PNG. The image fills the display
// as GlassesImageDisplay.showImage fits it. A text that one screen shows whole shows alone. A longer
// one shows as a run of the lines ScreenFit.lines wraps it into, above a marker such as "12/80" that
// counts the lines read: glassesScreen picks the run. Screens advance on their own, each for its
// reading time, and start again after the last, until the wearer's first swipe; from then on, each
// GlassesScroll moves the run by one line and nothing else does, so the wearer reads at their own
// pace. Text past GLASSES_MAX_TEXT_CHARS is not shown.
//
// A voice owner calls showVoiceTranscript for the session it owns; the shell and car-dial owners never
// run active sessions at once. An idle session sends nothing, so it cannot clear the glasses while the
// other owner's session runs.
package com.fghbuild.gomode.glasses

import com.fghbuild.gomode.voice.TranscriptKind
import com.fghbuild.gomode.voice.TranscriptSpeaker
import com.fghbuild.gomode.voice.UserContent
import com.fghbuild.gomode.voice.VoiceState
import com.fghbuild.gomode.voice.heldUserContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

// A Halo page costs one 1-bpp image per line over BLE; recognition drafts change several times a second.
internal const val GLASSES_FRAME_INTERVAL_MS = 500L

// A cut turn shorter than this reads as noise; the newer turn keeps the whole screen instead.
private const val MIN_CUT_TURN_CHARS = 16

// A screen of tool text shows for its reading time: about 200 words a minute, at least GLASSES_PAGE_MIN_MS.
internal const val GLASSES_PAGE_MIN_MS = 4_000L
internal const val GLASSES_PAGE_MS_PER_CHAR = 60L

// Tool text past this many characters is not shown; it bounds wrapping, which runs on the main thread.
internal const val GLASSES_MAX_TEXT_CHARS = 64 * 1024

private const val USER_LABEL = "You: "
private const val ELLIPSIS = "…"
private val WHITESPACE = Regex("\\s+")

/** The text the glasses show for [state]: the newest words of the current exchange that [fit] shows whole. */
internal fun glassesTranscriptText(
    state: VoiceState,
    fit: ScreenFit,
): String {
    val spoken = state.transcript.filter { it.kind == TranscriptKind.TRANSCRIPT && it.text.isNotBlank() }
    val draft = state.transcriptDraft?.text?.takeIf { it.isNotBlank() }
    // A gateway draft is the user's newest utterance; device recognition keeps its draft in the transcript.
    val start = if (draft != null) spoken.size else spoken.indexOfLast { it.speaker == TranscriptSpeaker.USER }
    val current =
        spoken.drop(start.coerceAtLeast(0)).map { it.speaker to it.text } +
            listOfNotNull(draft?.let { TranscriptSpeaker.USER to it })
    val turns = mutableListOf<Pair<TranscriptSpeaker, String>>()
    for ((speaker, text) in current) {
        val words = text.trim().replace(WHITESPACE, " ")
        val last = turns.lastOrNull()
        if (last?.first == speaker) {
            turns[turns.lastIndex] = speaker to "${last.second} $words"
        } else {
            turns += speaker to words
        }
    }
    return fitNewest(turns, fit)
}

// Adds turns from the newest while the screen shows them whole; the oldest one shown may be cut.
private fun fitNewest(
    turns: List<Pair<TranscriptSpeaker, String>>,
    fit: ScreenFit,
): String {
    var shown = ""
    for ((speaker, text) in turns.asReversed()) {
        val label = if (speaker == TranscriptSpeaker.USER) USER_LABEL else ""
        val whole = above(label + text, shown)
        if (fit.fits(whole)) {
            shown = whole
            continue
        }
        val cut = newestThatFits(text) { fit.fits(above(label + ELLIPSIS + it, shown)) }
        if (cut.length >= MIN_CUT_TURN_CHARS || shown.isEmpty()) shown = above(label + ELLIPSIS + cut, shown)
        break
    }
    return shown
}

private fun above(
    line: String,
    below: String,
): String = if (below.isEmpty()) line else "$line\n$below"

// The longest end of [text] that [fits], found by bisecting where it starts; the whole text does not fit.
private fun newestThatFits(
    text: String,
    fits: (String) -> Boolean,
): String {
    var tooLong = 0
    var fitting = text.length
    while (fitting - tooLong > 1) {
        val start = (tooLong + fitting) / 2
        if (fits(tail(text, start))) fitting = start else tooLong = start
    }
    return tail(text, fitting)
}

// [text] from [start], moved to the next word when [start] falls inside a word that another follows,
// and past a low surrogate so a code point is never split.
private fun tail(
    text: String,
    start: Int,
): String {
    val space = if (start == 0 || text[start - 1] == ' ') -1 else text.indexOf(' ', start)
    val from =
        when {
            space in start until text.lastIndex -> space + 1
            start < text.length && text[start].isLowSurrogate() -> start + 1
            else -> start
        }
    return text.substring(from)
}

/**
 * The screen of [lines] that starts at line [top]: as many lines as [fit] shows whole above the marker
 * of the last one shown, such as "12/80", and at least one.
 */
internal fun glassesScreen(
    lines: List<String>,
    top: Int,
    fit: ScreenFit,
): GlassesScreen {
    fun text(end: Int) = lines.subList(top, end).joinToString("\n") + "\n$end/${lines.size}"
    var fitting = top + 1
    var tooLong = lines.size + 1
    while (tooLong - fitting > 1) {
        val middle = (fitting + tooLong) / 2
        if (fit.fits(text(middle))) fitting = middle else tooLong = middle
    }
    return GlassesScreen(text(fitting), top, fitting)
}

/** A screen of held text: its [text] shows lines from [top] until [end], exclusive. */
internal data class GlassesScreen(
    val text: String,
    val top: Int,
    val end: Int,
)

// [text] without its outer whitespace, cut to GLASSES_MAX_TEXT_CHARS with an ellipsis, never inside a code point.
private fun heldText(text: String): String {
    val whole = text.trim()
    if (whole.length <= GLASSES_MAX_TEXT_CHARS) return whole
    val end = if (whole[GLASSES_MAX_TEXT_CHARS].isLowSurrogate()) GLASSES_MAX_TEXT_CHARS - 1 else GLASSES_MAX_TEXT_CHARS
    return whole.substring(0, end) + ELLIPSIS
}

/** How long a screen of tool text shows: its reading time, at least [GLASSES_PAGE_MIN_MS]. */
internal fun glassesPageMillis(page: String): Long = maxOf(GLASSES_PAGE_MIN_MS, page.length * GLASSES_PAGE_MS_PER_CHAR)

// What the glasses show for one voice state, before held text becomes screens.
private sealed interface GlassesView {
    data class Transcript(
        val text: String,
    ) : GlassesView

    data class Held(
        val content: UserContent,
        val fit: ScreenFit,
    ) : GlassesView
}

private fun glassesView(
    state: VoiceState,
    transcriptOn: Boolean,
    screen: GlassesLink.Connected?,
): GlassesView? {
    if (!state.active || screen == null) return null
    val held = state.heldUserContent.lastOrNull { it is UserContent.Text || screen.display.drawsImages }
    return when {
        held != null -> GlassesView.Held(held, screen.screenFit)
        transcriptOn -> GlassesView.Transcript(glassesTranscriptText(state, screen.screenFit))
        else -> null
    }
}

// The frames of [view] in order: held text scrolls as the header says, following [scrolls].
private fun frames(
    view: GlassesView?,
    scrolls: Flow<GlassesScroll>,
): Flow<GlassesFrame?> =
    when (view) {
        null -> {
            flowOf(null)
        }

        is GlassesView.Transcript -> {
            flowOf(GlassesFrame.Text(view.text))
        }

        is GlassesView.Held -> {
            when (val content = view.content) {
                is UserContent.Image -> {
                    flowOf(GlassesFrame.Image(content.image))
                }

                is UserContent.Text -> {
                    val text = heldText(content.text)
                    if (view.fit.fits(
                            text,
                        )
                    ) {
                        flowOf(GlassesFrame.Text(text))
                    } else {
                        scrolled(view.fit.lines(text), view.fit, scrolls)
                    }
                }
            }
        }
    }

// The screens of [lines]: each in turn for its reading time until the first of [scrolls], then a line per scroll.
private fun scrolled(
    lines: List<String>,
    fit: ScreenFit,
    scrolls: Flow<GlassesScroll>,
): Flow<GlassesFrame> =
    channelFlow {
        var screen = glassesScreen(lines, 0, fit)
        send(GlassesFrame.Text(screen.text))
        val reading =
            launch {
                while (true) {
                    delay(glassesPageMillis(screen.text))
                    screen = glassesScreen(lines, if (screen.end < lines.size) screen.end else 0, fit)
                    send(GlassesFrame.Text(screen.text))
                }
            }
        scrolls.collect { scroll ->
            // Joined, so the screen the timer last set is the one a scroll moves.
            reading.cancelAndJoin()
            val top =
                when (scroll) {
                    GlassesScroll.FORWARD -> if (screen.end < lines.size) screen.top + 1 else screen.top
                    GlassesScroll.BACK -> (screen.top - 1).coerceAtLeast(0)
                }
            if (top != screen.top) {
                screen = glassesScreen(lines, top, fit)
                send(GlassesFrame.Text(screen.text))
            }
        }
    }

/**
 * Shows what the glasses show for [voice] through [show] while the session is active and glasses
 * are connected, fitted to the connected [screen], null without glasses: the held user content of a
 * tool result, which [scrolls] scroll, or else the transcript while [transcriptOn] holds. Clears the display when the session
 * ends, nothing is left to show, the glasses disconnect, or the caller cancels. Frames follow the
 * state at most once per [GLASSES_FRAME_INTERVAL_MS], always ending on the newest; a state change
 * that leaves the frame as it was, such as the microphone level, sends nothing. Returns only when
 * cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest restarts a held text's screens on each change.
internal suspend fun showVoiceTranscript(
    voice: Flow<VoiceState>,
    transcriptOn: Flow<Boolean>,
    screen: Flow<GlassesLink.Connected?>,
    scrolls: Flow<GlassesScroll>,
    show: (GlassesFrame) -> Unit,
) {
    var showing = false
    try {
        combine(voice, transcriptOn, screen, ::glassesView)
            .distinctUntilChanged()
            .flatMapLatest { frames(it, scrolls) }
            .conflate()
            .collect { frame ->
                if (frame != null) {
                    show(frame)
                    showing = true
                    delay(GLASSES_FRAME_INTERVAL_MS)
                } else if (showing) {
                    show(GlassesFrame.Text(""))
                    showing = false
                }
            }
    } finally {
        if (showing) show(GlassesFrame.Text(""))
    }
}
