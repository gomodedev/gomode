// G2ArmLink: transport contract of one connected Even G2 arm, its connector, and transport errors.

package com.fghbuild.eveng2

import kotlinx.coroutines.channels.ReceiveChannel

/** Transport failure of one arm. */
open class G2Exception(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The arm's link dropped; [status] is the platform's disconnect status. */
class G2LinkLostException(
    val status: Int,
) : G2Exception("link lost (status=$status)")

/**
 * One connected arm with notifications enabled. [G2Glasses] owns it from [G2ArmConnector.connect]
 * until it calls [close].
 */
interface G2ArmLink {
    val side: G2Side

    /**
     * Notification values of the notify characteristic in arrival order. The channel closes
     * with [G2LinkLostException] when the link drops.
     */
    val notifications: ReceiveChannel<ByteArray>

    /** Write one packet to the write characteristic without response; throws [G2Exception]. */
    suspend fun write(packet: ByteArray)

    /** Disconnect and release the link; idempotent. */
    fun close()
}

/**
 * Opens a [G2ArmLink] to one arm; throws [G2Exception] on failure and closes partial links on
 * cancellation. A direct connection fails when the arm does not answer within the platform's
 * deadline; [G2Session] also bounds it. A [background] connection waits without a deadline until
 * the arm advertises, then sets the link up within [G2Protocol.CONNECT_TIMEOUT_MS].
 */
fun interface G2ArmConnector {
    suspend fun connect(
        arm: G2Arm,
        background: Boolean,
    ): G2ArmLink
}
