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
// A voice owner calls showVoiceTranscript for the session it owns; the shell and car-dial owners never
// run active sessions at once. An idle session sends nothing, so it cannot clear the glasses while the
// other owner's session runs.
package com.fghbuild.gomode.glasses

import com.fghbuild.gomode.voice.TranscriptKind
import com.fghbuild.gomode.voice.TranscriptSpeaker
import com.fghbuild.gomode.voice.VoiceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

// A Halo page costs one 1-bpp image per line over BLE; recognition drafts change several times a second.
internal const val GLASSES_FRAME_INTERVAL_MS = 500L

// A cut turn shorter than this reads as noise; the newer turn keeps the whole screen instead.
private const val MIN_CUT_TURN_CHARS = 16

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
 * Shows the transcript of [voice] through [show] while the session is active, [enabled] holds, and
 * glasses are connected, fitted to their [fit], null without glasses. Clears it
 * when the session ends, [enabled] turns off, the glasses disconnect, or the caller cancels. Frames
 * follow the transcript at most once per [GLASSES_FRAME_INTERVAL_MS], always ending on the newest; a
 * state change that leaves the text as it was, such as the microphone level, sends nothing. Returns
 * only when cancelled.
 */
internal suspend fun showVoiceTranscript(
    voice: Flow<VoiceState>,
    enabled: Flow<Boolean>,
    fit: Flow<ScreenFit?>,
    show: (String) -> Unit,
) {
    var showing = false
    try {
        combine(voice, enabled, fit) { state, on, screen ->
            if (on && state.active && screen != null) glassesTranscriptText(state, screen) else null
        }.distinctUntilChanged()
            .conflate()
            .collect { text ->
                if (text != null) {
                    show(text)
                    showing = true
                    delay(GLASSES_FRAME_INTERVAL_MS)
                } else if (showing) {
                    show("")
                    showing = false
                }
            }
    } finally {
        if (showing) show("")
    }
}
