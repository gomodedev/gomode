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
// as GlassesImageDisplay.showImage fits it. Text shows in screens that glassesPages cuts and that advance
// on their own, each for its reading time, and start again after the last, so no gesture pages through
// them and each keeps its one action.
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

// A Halo page costs one 1-bpp image per line over BLE; recognition drafts change several times a second.
internal const val GLASSES_FRAME_INTERVAL_MS = 500L

// A cut turn shorter than this reads as noise; the newer turn keeps the whole screen instead.
private const val MIN_CUT_TURN_CHARS = 16

// A screen of tool text shows for its reading time: about 200 words a minute, at least GLASSES_PAGE_MIN_MS.
internal const val GLASSES_PAGE_MIN_MS = 4_000L
internal const val GLASSES_PAGE_MS_PER_CHAR = 60L

// Tool text past this many screens is not shown; a glance is not the place for a document.
internal const val GLASSES_MAX_PAGES = 20

// The widest page marker, which each screen of a paged text reserves room for below its text.
private const val PAGE_MARKER_PROBE = "$GLASSES_MAX_PAGES/$GLASSES_MAX_PAGES"

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
 * The screens that show [text] on a display that [fit] measures. A text that fits shows whole on one.
 * A longer one takes up to [GLASSES_MAX_PAGES] screens, each with the most words that fit above a
 * page marker such as "2/3"; a word too long for a screen is cut between characters.
 */
internal fun glassesPages(
    text: String,
    fit: ScreenFit,
): List<String> {
    val whole = text.trim()
    if (fit.fits(whole)) return listOf(whole)
    val pages = mutableListOf<String>()
    var rest = whole
    while (rest.isNotEmpty() && pages.size < GLASSES_MAX_PAGES) {
        val page = longestHeadThatFits(rest) { fit.fits("$it\n$PAGE_MARKER_PROBE") }
        pages += page
        rest = rest.substring(page.length).trimStart()
    }
    return pages.mapIndexed { index, page -> "$page\n${index + 1}/${pages.size}" }
}

// The longest start of [text], ending before whitespace, that [fits]; at least one code point.
private fun longestHeadThatFits(
    text: String,
    fits: (String) -> Boolean,
): String {
    val wordEnds =
        text.indices.filter { it > 0 && text[it].isWhitespace() && !text[it - 1].isWhitespace() } + text.length
    val words = longestFitting(wordEnds) { fits(text.substring(0, it)) }
    if (words != null) return text.substring(0, words)
    val codePointEnds = (1..text.length).filter { it == text.length || !text[it].isLowSurrogate() }
    return text.substring(0, longestFitting(codePointEnds) { fits(text.substring(0, it)) } ?: codePointEnds.first())
}

// The largest of the ascending [ends] that [fits], or null when none does; fitting holds for a prefix of them.
private fun longestFitting(
    ends: List<Int>,
    fits: (Int) -> Boolean,
): Int? {
    var fitting = -1
    var tooLong = ends.size
    while (tooLong - fitting > 1) {
        val middle = (fitting + tooLong) / 2
        if (fits(ends[middle])) fitting = middle else tooLong = middle
    }
    return ends.getOrNull(fitting)
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

// The frames of [view] in order: held text cycles through its screens, each for its reading time.
private fun frames(view: GlassesView?): Flow<GlassesFrame?> =
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
                    val pages = glassesPages(content.text, view.fit)
                    if (pages.size == 1) {
                        flowOf(GlassesFrame.Text(pages.single()))
                    } else {
                        flow {
                            while (true) {
                                for (page in pages) {
                                    emit(GlassesFrame.Text(page))
                                    delay(glassesPageMillis(page))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

/**
 * Shows what the glasses show for [voice] through [show] while the session is active and glasses
 * are connected, fitted to the connected [screen], null without glasses: the held user content of a
 * tool result, or else the transcript while [transcriptOn] holds. Clears the display when the session
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
    show: (GlassesFrame) -> Unit,
) {
    var showing = false
    try {
        combine(voice, transcriptOn, screen, ::glassesView)
            .distinctUntilChanged()
            .flatMapLatest(::frames)
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
