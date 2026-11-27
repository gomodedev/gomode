// Shared voice controller and ordered quiet-context/actionable-alert buffering.
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

    fun injectText(
        text: String,
        generate: Boolean = false,
    )

    /**
     * contextChanged reports that the conversation's service context changed. The session sends
     * VoiceConversation.contextUpdate() once the model can take it, so the diff is computed at
     * delivery.
     */
    fun contextChanged()

    fun setError(message: String)

    /** dismissError hides the current failure; [VoiceErrorLog] keeps it reviewable in settings. */
    fun dismissError()

    /** close ends the session and releases resources the controller owns. */
    fun close()
}

internal data class VoiceContextUpdate(
    val text: String,
    val generate: Boolean,
)

// Holds what reaches the model once the active turn ends. The conversation's context is diffed
// when this drains, against what the model already received, so each changed section arrives
// once, newest. Injected texts and alerts keep their order.
internal class PendingVoiceContext(
    private val contextUpdate: () -> VoiceContextUpdate?,
) {
    private var contextChanged = false
    private val texts = mutableListOf<VoiceContextUpdate>()

    fun markContextChanged() {
        contextChanged = true
    }

    fun add(
        text: String,
        generate: Boolean,
    ): Boolean {
        if (texts.size >= MAX_PENDING_TEXTS) return false
        texts.add(VoiceContextUpdate(text, generate))
        return true
    }

    fun isNotEmpty() = contextChanged || texts.isNotEmpty()

    fun hasAlerts() = texts.any { it.generate }

    fun clear() {
        contextChanged = false
        texts.clear()
    }

    fun drain(): VoiceContextUpdate? {
        val context = if (contextChanged) contextUpdate() else null
        val parts = listOfNotNull(context) + texts
        clear()
        if (parts.isEmpty()) return null
        return VoiceContextUpdate(parts.joinToString("\n\n") { it.text }, parts.any { it.generate })
    }

    private companion object {
        const val MAX_PENDING_TEXTS = 64
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
