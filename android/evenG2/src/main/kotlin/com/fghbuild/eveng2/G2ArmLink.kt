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

    /** The ATT MTU of the link, at least [G2Protocol.MIN_MTU]. */
    val mtu: Int

    /**
     * Notification values of the notify characteristic in arrival order. The channel closes
     * with [G2LinkLostException] when the link drops.
     */
    val notifications: ReceiveChannel<ByteArray>

    /**
     * Notification values of the audio characteristic, [G2Microphone]'s packets, in arrival order;
     * none when the arm has no such characteristic. The channel closes with the link.
     */
    val audio: ReceiveChannel<ByteArray>

    /** Write one packet to the write characteristic without response; throws [G2Exception]. */
    suspend fun write(packet: ByteArray)

    /** Disconnect and release the link; idempotent. */
    fun close()
}

/** Bonds with and opens [G2ArmLink]s to arms; throws [G2Exception] on failure. */
interface G2ArmConnector {
    /**
     * Bond with [arm] unless a bond exists. An arm drops a link without a bond, so a session bonds
     * both arms, one at a time, before it connects either. The platform may ask the wearer to
     * consent and bounds the wait itself; there is no other deadline.
     */
    suspend fun bond(arm: G2Arm)

    /**
     * Open a link to a bonded [arm]; closes a partial link on cancellation. A direct connection
     * fails when the arm does not answer within the platform's deadline; [G2Session] also bounds
     * it. A [background] connection waits without a deadline until the arm advertises, then sets
     * the link up within [G2Protocol.CONNECT_TIMEOUT_MS].
     */
    suspend fun connect(
        arm: G2Arm,
        background: Boolean,
    ): G2ArmLink
}
