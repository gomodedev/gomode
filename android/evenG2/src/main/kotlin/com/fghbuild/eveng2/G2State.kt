// G2State: Even G2 connection states and the typed causes that end a session.

package com.fghbuild.eveng2

/** Why a session ended. [retryable] causes lead to a reconnect; the others end the connection. */
sealed interface G2CloseCause {
    val retryable: Boolean

    /** The arm did not connect, negotiate MTU, expose its characteristics, or enable notifications. */
    data class ConnectFailed(
        val side: G2Side,
        val reason: String,
    ) : G2CloseCause {
        override val retryable: Boolean get() = true
    }

    /** The arm's link dropped; [status] is the platform's disconnect status, null when unknown. */
    data class Disconnected(
        val side: G2Side,
        val status: Int?,
    ) : G2CloseCause {
        override val retryable: Boolean get() = true
    }

    /** A write to the arm failed while its link was up. */
    data class WriteFailed(
        val side: G2Side,
        val reason: String,
    ) : G2CloseCause {
        override val retryable: Boolean get() = true
    }

    /** The arm did not report AUTH success within [G2Protocol.AUTH_TIMEOUT_MS]. */
    data class AuthTimedOut(
        val side: G2Side,
    ) : G2CloseCause {
        override val retryable: Boolean get() = true
    }

    /** The right arm did not acknowledge the prelude. */
    data object PreludeTimedOut : G2CloseCause {
        override val retryable: Boolean get() = true
    }

    /** The right arm did not answer an Even Hub command within [G2Protocol.COMMAND_TIMEOUT_MS]. */
    data class CommandTimedOut(
        val command: G2HubCommand,
    ) : G2CloseCause {
        override val retryable: Boolean get() = true
    }

    /** The right arm refused an Even Hub command; [result] is null for a malformed or header-level refusal. */
    data class CommandRejected(
        val command: G2HubCommand,
        val result: Long?,
    ) : G2CloseCause {
        override val retryable: Boolean get() = true
    }

    /**
     * The glasses closed the page. An exit (event 7) follows the wearer's action and ends the
     * connection; an abnormal exit (event 6) reconnects.
     */
    data class PageExited(
        val abnormal: Boolean,
    ) : G2CloseCause {
        override val retryable: Boolean get() = abnormal
    }

    /** An unexpected exception, for example from a host [G2ArmConnector], ended the session. */
    data class Failed(
        val error: Exception,
    ) : G2CloseCause {
        override val retryable: Boolean get() = false
    }

    /** The host called [G2Glasses.disconnect]. */
    data object Stopped : G2CloseCause {
        override val retryable: Boolean get() = false
    }
}

sealed interface G2State {
    data object Idle : G2State

    /** Connecting, authenticating, and opening the page; [attempt] counts from 1 since the last ready session. */
    data class Connecting(
        val pair: G2Pair,
        val attempt: Int,
    ) : G2State

    /** The page is open and shows [text], acknowledged by the glasses. */
    data class Ready(
        val pair: G2Pair,
        val text: String,
    ) : G2State

    /** The last session ended with [cause]; the next attempt starts after [delayMs]. */
    data class Reconnecting(
        val pair: G2Pair,
        val cause: G2CloseCause,
        val delayMs: Long,
    ) : G2State

    data class Closed(
        val cause: G2CloseCause,
    ) : G2State
}
