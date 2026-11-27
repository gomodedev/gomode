// Shared voice controller and buffering of service context changes and actionable alerts.
//
// Connected voice also reads gomode://notifications. The opening snapshot, repeated occurrence IDs,
// withdrawals, and expiry send nothing. Each new alert reaches the model as one "New notification:
// title: body [itemId]" line with context.update.generate: true, without inventing a user
// utterance. Native multi-server alerts carry the same server header as context. Updates wait for
// the active model, tool, and playback turn. Ending voice or replacing the authenticated scope
// stops monitoring.
package com.fghbuild.gomode.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * VoiceSessionController is the shell-facing surface of an active voice mode.
 *
 * A controller owns one session lifetime. Creating a new controller or calling
 * [connect] again replaces the previous session.
 */
internal interface VoiceSessionController {
    val state: StateFlow<VoiceState>

    /** The list this session raises its problems into, which its creator supplies; see VoiceIssues. */
    val issues: VoiceIssues

    // Recovery preserves transcript and conversation identity. A server handoff
    // supplies the existing ID to its new controller; an ordinary start makes one.
    fun connect(
        preserveTranscript: Boolean = false,
        conversationID: String? = null,
    )

    /** disconnect ends voice without an error; reason names the caller in the log. */
    fun disconnect(reason: String)

    fun setMicrophoneMode(mode: MicrophoneMode)

    fun selectAudioDevice(deviceId: Int)

    fun clearTranscript()

    /** announce sends an actionable service alert; the model responds to it once its turn ends. */
    fun announce(text: String)

    /**
     * contextChanged reports that the conversation's service context changed. The session sends
     * VoiceConversation.contextUpdate() once the model can take it, so the diff is computed at
     * delivery.
     */
    fun contextChanged()

    /** setError ends the session with [message], which [VoiceState.failure] and the CONNECTION issue carry. */
    fun setError(message: String)

    /** close ends the session and releases resources the controller owns. */
    fun close()
}

internal data class VoiceContextUpdate(
    val text: String,
    val generate: Boolean,
)

// Holds what reaches the model once the active turn ends. The conversation's context is diffed
// when this drains, against what the model already received, so each changed section arrives
// once, newest. Alerts keep their order after it.
internal class PendingVoiceContext(
    private val conversation: () -> VoiceConversation?,
) {
    private var contextChanged = false
    private val alerts = mutableListOf<String>()

    fun markContextChanged() {
        contextChanged = true
    }

    fun addAlert(text: String): Boolean {
        if (alerts.size >= MAX_PENDING_ALERTS) return false
        alerts.add(text)
        return true
    }

    fun isNotEmpty() = contextChanged || alerts.isNotEmpty()

    /** Whether drain() would ask the model to respond: an alert or an unrequested server switch. */
    fun announces() = alerts.isNotEmpty() || (contextChanged && conversation()?.announcesSwitch() == true)

    fun clear() {
        contextChanged = false
        alerts.clear()
    }

    fun drain(): VoiceContextUpdate? {
        val context = if (contextChanged) conversation()?.contextUpdate() else null
        val texts = listOfNotNull(context?.text) + alerts
        val generate = context?.generate == true || alerts.isNotEmpty()
        clear()
        if (texts.isEmpty()) return null
        return VoiceContextUpdate(texts.joinToString("\n\n"), generate)
    }

    private companion object {
        const val MAX_PENDING_ALERTS = 64
    }
}

/**
 * endForSignInChange ends voice after the service identity changed. An active
 * session reports why; an idle one has nothing to report.
 */
internal fun VoiceSessionController.endForSignInChange() {
    if (state.value.active) endForConversationChange() else disconnect("service sign-in changed while idle")
}

/**
 * endForConversationChange ends an active session whose service identity or
 * conversation changed underneath it, with an error naming the cause.
 */
internal fun VoiceSessionController.endForConversationChange() = setError(VOICE_CONVERSATION_ENDED_ERROR)

/**
 * endIfConversationChanged ends the session when [conversation] is no longer
 * current and reports whether it did. A null conversation never changes.
 */
internal fun VoiceSessionController.endIfConversationChanged(conversation: VoiceConversation?): Boolean {
    if (conversation?.isCurrent() != false) return false
    endForConversationChange()
    return true
}
