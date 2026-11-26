// G2State: Even G2 connection states and the typed causes that end a session.

package com.fghbuild.eveng2

/**
 * Why a session ended. A [retryable] cause, such as a dropped link, may clear on a later session; the
 * others fail the same way again or follow a deliberate stop, so a host does not retry them.
 */
sealed interface G2CloseCause {
    val retryable: Boolean

    /**
     * The arm did not bond with the host: the wearer refused the pairing request, did not answer it,
     * or the arm was out of range. Another attempt asks the wearer again, so a host does not retry.
     */
    data class PairingFailed(
        val side: G2Side,
        val reason: String,
    ) : G2CloseCause {
        override val retryable: Boolean get() = false
    }

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
     * Faceclaw firmware did not acknowledge a drawing message on both lenses within
     * [G2Protocol.COMMAND_TIMEOUT_MS], or a lens refused it ([rejected]).
     */
    data class DrawFailed(
        val rejected: Boolean,
    ) : G2CloseCause {
        override val retryable: Boolean get() = true
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

    /** Connecting, authenticating, and opening the page. */
    data class Connecting(
        val pair: G2Pair,
    ) : G2State

    /**
     * The page is open on [firmware] and shows [shown], acknowledged by the glasses. The state stays
     * while the session creates a page again after the glasses close one; see G2Session.
     */
    data class Ready(
        val pair: G2Pair,
        val firmware: G2Firmware,
        val shown: G2Content,
    ) : G2State

    /** The session ended with [cause]; only [G2Glasses.connect] starts another. */
    data class Closed(
        val cause: G2CloseCause,
    ) : G2State
}
