// Owns the Android voice problem list: one entry per source key, raised and resolved by its source.
//
// The list follows the browser's rules, which web/src/VoiceIssues.ts states:
//
// - Each key appears at most once, oldest first. Raising a present key replaces its entry in place,
//   so a retry that fails again stays one entry.
// - The source resolves its key when the cause clears: a route switch that succeeds, a session that
//   reaches session.ready, the next successful save of a settings field.
// - Dismissal resolves the shown keys at once. A condition that still holds returns when its source
//   meets it again.
// - A settings field problem shows at its control while the settings screen is shown and is listed
//   otherwise, never in both.
//
// Each raise is an occurrence with a new id. The phone keeps one entry per key, and the glasses
// (showVoiceIssues) draw each new id, so the wearer reads a retry that fails again. A source that
// derives a problem from state raises only when the message changes.
//
// GoModeApplication.voiceIssues is the shell's list: the shell's sessions, the settings screen, and
// CarDialVoiceOwner raise into it, the voice panel shows it, and the glasses mirror it. Each key has
// one writer. Each car call session raises into its own list, and CarDialVoiceOwner forwards it
// under the CAR_ keys, so a shell session that starts or ends never resolves a car call's problem.
// Provider text must not reach the glasses or a screen above keyguard: both show only fixedMessage
// keys of a car call. The set of keys is closed, so the list is bounded.
package com.fghbuild.gomode.voice

import com.fghbuild.gomode.data.SpeakingVoiceOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * The source of a problem; for a settings field, the field. [settingsField] keys show at their
 * control while settings are shown. [fixedMessage] keys carry only Go Mode's own text, never a
 * provider's or the platform's, so a surface above keyguard may show them. [carCall] keys hold a car
 * call's problems, which the glasses draw only when they are [fixedMessage].
 */
internal enum class VoiceIssueKey(
    val settingsField: Boolean = false,
    val fixedMessage: Boolean = false,
    val carCall: Boolean = false,
) {
    /** The call's audio route: a device that left, or a switch or listing that failed. */
    AUDIO_ROUTE(fixedMessage = true),

    /** A car call that could not start, as CarDialFailure names it. */
    CAR_CALL(fixedMessage = true, carCall = true),

    /** The car call session's [AUDIO_ROUTE]. */
    CAR_AUDIO_ROUTE(fixedMessage = true, carCall = true),

    /** The car call session's [CONNECTION], in the provider's words. */
    CAR_CONNECTION(carCall = true),

    /** The car call session's [GATEWAY], in the provider's words. */
    CAR_GATEWAY(carCall = true),

    /** The failure that ended the session. */
    CONNECTION,

    /** A recoverable gateway error; the session goes on. */
    GATEWAY,
    SAVE_DEVICE_SPEAKING_VOICE(settingsField = true),
    SAVE_DEVICE_SPEECH_RATE(settingsField = true),
    SAVE_LANGUAGE_MODEL(settingsField = true),
    SAVE_RECOGNITION(settingsField = true),
    SAVE_SETUP_MODE(settingsField = true),
    SAVE_SPEAKING_VOICE(settingsField = true),
    SAVE_SPEECH(settingsField = true),
}

/** What a settings field's save failure belongs to; a control resolves a failure of another origin. */
internal sealed interface VoiceIssueOrigin {
    /** The voice setup the failure was saved under. */
    val setup: Setup

    /** The voice setup of one service and sign-in. The bearer token is not part of it: saves do not use it. */
    data class Setup(
        val serviceID: String,
        val serviceURL: String,
        val authEpoch: Long,
    ) : VoiceIssueOrigin {
        override val setup: Setup get() = this
    }

    /** The speaking voice picker of [owner] at [gatewayURL], within [setup]. */
    data class SpeakingVoice(
        override val setup: Setup,
        val gatewayURL: String,
        val owner: SpeakingVoiceOwner?,
    ) : VoiceIssueOrigin
}

internal data class VoiceIssue(
    val key: VoiceIssueKey,
    val message: String,
    /** Unique per raise across the owner, so a repeated message is a new occurrence. */
    val id: Long,
    /** What a settings field's problem belongs to; null for any other problem and the device's pickers. */
    val origin: VoiceIssueOrigin? = null,
)

internal data class VoiceIssuesState(
    /** Every current problem, oldest first. */
    val entries: List<VoiceIssue> = emptyList(),
    /** Settings screens currently shown. */
    val settingsViews: Int = 0,
) {
    /** The problems a list shows: all of them, less the settings fields' while settings show them. */
    val listed: List<VoiceIssue>
        get() = if (settingsViews == 0) entries else entries.filterNot { it.key.settingsField }

    fun message(key: VoiceIssueKey): String? = entries.firstOrNull { it.key == key }?.message

    /** The problem of [key] that belongs to [origin]; a problem of another origin is stale. */
    fun message(
        key: VoiceIssueKey,
        origin: VoiceIssueOrigin?,
    ): String? = entries.firstOrNull { it.key == key && it.origin == origin }?.message
}

/** VoiceIssues holds every current voice problem of one list; any thread may call it. */
internal class VoiceIssues {
    private val ids = AtomicLong()
    private val mutableState = MutableStateFlow(VoiceIssuesState())
    val state: StateFlow<VoiceIssuesState> = mutableState.asStateFlow()

    fun raise(
        key: VoiceIssueKey,
        message: String,
        origin: VoiceIssueOrigin? = null,
    ) {
        val issue = VoiceIssue(key, message, ids.incrementAndGet(), origin)
        mutableState.update { current ->
            val index = current.entries.indexOfFirst { it.key == key }
            val entries =
                if (index < 0) {
                    current.entries + issue
                } else {
                    current.entries.mapIndexed { i, entry -> if (i == index) issue else entry }
                }
            current.copy(entries = entries)
        }
    }

    fun resolve(vararg keys: VoiceIssueKey) {
        mutableState.update { current ->
            if (current.entries.none { it.key in keys }) {
                current
            } else {
                current.copy(entries = current.entries.filterNot { it.key in keys })
            }
        }
    }

    /** Resolves [key] when its problem belongs to another origin than [origin]. */
    fun resolveStale(
        key: VoiceIssueKey,
        origin: VoiceIssueOrigin?,
    ) = resolveIf(key) { it != origin }

    /** Resolves [key] when [stale] holds for the origin of its problem. */
    fun resolveIf(
        key: VoiceIssueKey,
        stale: (origin: VoiceIssueOrigin?) -> Boolean,
    ) {
        mutableState.update { current ->
            if (current.entries.none { it.key == key && stale(it.origin) }) {
                current
            } else {
                current.copy(entries = current.entries.filterNot { it.key == key && stale(it.origin) })
            }
        }
    }

    /** Registers a shown settings screen; call the result once it hides. */
    fun showSettings(): () -> Unit {
        var shown = true
        mutableState.update { it.copy(settingsViews = it.settingsViews + 1) }
        return {
            if (shown) {
                shown = false
                mutableState.update { it.copy(settingsViews = it.settingsViews - 1) }
            }
        }
    }
}
