// Picks the voice transcript lines that fit on glasses and feeds them while a session runs.
//
// Glasses are read at a glance and hold a few short lines: Halo fits about eight lines of 8 to 18
// characters in its circle. They show the current exchange only: the user's newest utterance, or the
// live recognition draft while the user speaks, then the assistant's reply to it. Each speaker change
// starts a line; the user's line starts with "You: ", as on the phone, and the reply is unlabelled
// because it is the main content. Line feeds inside a turn become spaces so they do not waste lines.
// Displays cut text that overflows at the bottom, so the newest words must come first in the budget:
// past GLASSES_TRANSCRIPT_MAX_CHARS, the oldest words are cut and an ellipsis marks the cut. Reasoning
// is never shown.
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

// TODO: Size the budget from the connected display once GlassesDisplay describes it (PLAN_GLASSES.md,
// display-negotiation); Even G2's 576×288 page holds several times Halo's text. The budget fits a Halo
// page with ordinary prose (GlassesTranscriptTest); a run of wide capitals can still overflow it.
internal const val GLASSES_TRANSCRIPT_MAX_CHARS = 96

// A Halo page costs one 1-bpp image per line over BLE; recognition drafts change several times a second.
internal const val GLASSES_FRAME_INTERVAL_MS = 500L

// A cut turn shorter than this reads as noise; the newer turn keeps the whole budget instead.
private const val MIN_CUT_TURN_CHARS = 16

private const val USER_LABEL = "You: "
private const val ELLIPSIS = "…"
private val WHITESPACE = Regex("\\s+")

/** The text the glasses show for [state]: the current exchange, newest words kept. */
internal fun glassesTranscriptText(state: VoiceState): String {
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
    return fitNewest(turns)
}

private fun fitNewest(turns: List<Pair<TranscriptSpeaker, String>>): String {
    val lines = ArrayDeque<String>()
    var remaining = GLASSES_TRANSCRIPT_MAX_CHARS
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
 * Shows the transcript of [voice] through [show] while the session is active and [enabled] holds, and
 * clears it when the session ends, [enabled] turns off, or the caller cancels. Frames follow the
 * transcript at most once per [GLASSES_FRAME_INTERVAL_MS], always ending on the newest; a state change
 * that leaves the text as it was, such as the microphone level, sends nothing. Returns only when
 * cancelled.
 */
internal suspend fun showVoiceTranscript(
    voice: Flow<VoiceState>,
    enabled: Flow<Boolean>,
    show: (String) -> Unit,
) {
    var showing = false
    try {
        combine(voice, enabled) { state, on -> if (on && state.active) glassesTranscriptText(state) else null }
            .distinctUntilChanged()
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
