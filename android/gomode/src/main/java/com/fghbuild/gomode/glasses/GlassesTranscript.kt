// Picks the voice transcript lines that fit on glasses and feeds them while a session runs.
//
// Glasses are read at a glance and hold a few short lines: Halo fits about eight lines of 8 to 18
// characters in its circle, Even G2 several times that. They show the current exchange only: the user's newest utterance, or the
// live recognition draft while the user speaks, then the assistant's reply to it. Each speaker change
// starts a line; the user's line starts with "You: ", as on the phone, and the reply is unlabelled
// because it is the main content. Line feeds inside a turn become spaces so they do not waste lines.
// Displays cut text that overflows at the bottom, so the newest words must come first in the budget,
// the connected display's text capacity: past it, the oldest words are cut and an ellipsis marks the
// cut. Reasoning is never shown.
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

// A cut turn shorter than this reads as noise; the newer turn keeps the whole budget instead.
private const val MIN_CUT_TURN_CHARS = 16

private const val USER_LABEL = "You: "
private const val ELLIPSIS = "…"
private val WHITESPACE = Regex("\\s+")

/** The text the glasses show for [state]: the current exchange, its newest [maxChars] characters at most. */
internal fun glassesTranscriptText(
    state: VoiceState,
    maxChars: Int,
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
    return fitNewest(turns, maxChars)
}

private fun fitNewest(
    turns: List<Pair<TranscriptSpeaker, String>>,
    maxChars: Int,
): String {
    val lines = ArrayDeque<String>()
    var remaining = maxChars
    for ((speaker, text) in turns.asReversed()) {
        val label = if (speaker == TranscriptSpeaker.USER) USER_LABEL else ""
        if (label.length + text.length <= remaining) {
            lines.addFirst(label + text)
            remaining -= label.length + text.length + 1
            continue
        }
        val room = remaining - label.length - ELLIPSIS.length
        if (room >= MIN_CUT_TURN_CHARS || lines.isEmpty()) lines.addFirst(label + ELLIPSIS + tail(text, room))
        break
    }
    return lines.joinToString("\n")
}

// The last [max] characters of [text], starting at a word when one starts there.
private fun tail(
    text: String,
    max: Int,
): String {
    val cut = text.substring(text.length - max)
    val space = cut.indexOf(' ')
    return if (space in 0 until cut.lastIndex) cut.substring(space + 1) else cut
}

/**
 * Shows the transcript of [voice] through [show] while the session is active, [enabled] holds, and
 * glasses are connected, fitted to their [capacity] in characters, null without glasses. Clears it
 * when the session ends, [enabled] turns off, the glasses disconnect, or the caller cancels. Frames
 * follow the transcript at most once per [GLASSES_FRAME_INTERVAL_MS], always ending on the newest; a
 * state change that leaves the text as it was, such as the microphone level, sends nothing. Returns
 * only when cancelled.
 */
internal suspend fun showVoiceTranscript(
    voice: Flow<VoiceState>,
    enabled: Flow<Boolean>,
    capacity: Flow<Int?>,
    show: (String) -> Unit,
) {
    var showing = false
    try {
        combine(voice, enabled, capacity) { state, on, chars ->
            if (on && state.active && chars != null) glassesTranscriptText(state, chars) else null
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
