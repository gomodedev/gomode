// G2Auth: the per-arm AUTH request on SID 0x80 and classification of its replies.
//
// Ported and modified from G2BusinessAuthCodec.kt of the Even Realities enterprise SDK
// 0.1.0-beta.14 (Apache-2.0, Copyright 2026 Hong Kong Even Realities Limited), with the success
// rule of the even-g2-protocol Python library (MIT); see NOTICE in this module.
//
// Request, one packet with header flag 0 (per arm, not synced):
//
//     {1: 4 (AUTH command), 2: magic, 3: {1: 1, 2: 4 (phoneType Android)}}
//
// The frame carries no secret. Replies on SID 0x80 take two shapes:
// - ACK (flag bit 0 clear): {1: 4, 2: magic, 3: AuthMgr}. Consumer firmware answers an empty
//   AuthMgr once the arm accepts the session; the Python library and the GPL faceclaw client
//   treat it as success. A non-empty AuthMgr without success is expected before the link is
//   encrypted, so it does not end the wait.
// - Push (flag bit 0 set): {1: 4, 2: device magic, 3: {1: 1}}, numbered by the glasses. The
//   enterprise SDK waits for it; it is accepted as success too.
//
// TODO(hardware): Consumer firmware behaviour is known only from the Python library (right
// arm, empty ACK observed; it sends the left arm's AUTH fire-and-forget and never listens on
// that arm) and faceclaw (both arms, empty ACK after encryption). Enterprise
// firmware sends the empty ACK before its success push, so on that firmware an empty ACK alone
// would be premature. On a real pair, log every SID 0x80 frame from both arms during connect:
// confirm that the left arm answers, whether a push follows the ACK, and whether an Even Hub
// page created right after the empty ACK is accepted. If a push always follows, require it.
//
// The arms require a bond: on a pair flashed with g2flash, an arm without a bond to the phone
// closed its link about 2 s after connecting (HCI reason 0x13), and Android asked the wearer to
// consent to pairing. G2ArmConnector.bond bonds both arms before G2Session connects them.

package com.fghbuild.eveng2

/** Outcome of one SID 0x80 reply. */
enum class G2AuthReply {
    AUTHENTICATED,

    /** A reply to this session that is not success yet, as before link encryption. */
    NOT_AUTHENTICATED,
}

object G2Auth {
    private const val AUTH_COMMAND = 4L
    private const val PHONE_TYPE_ANDROID = 4L
    private val ENVELOPE_FIELDS = setOf(1, 2, 3)
    private val AUTH_MGR_FIELDS = setOf(1, 2, 3)

    /** Magic values fit one varint byte, as the enterprise contract requires. */
    val MAGIC_RANGE = 1..127

    /** The single-packet AUTH request. */
    fun request(
        magic: Int,
        sync: Int,
    ): ByteArray {
        require(magic in MAGIC_RANGE) { "magic $magic" }
        val payload =
            protobuf {
                scalar(1, AUTH_COMMAND)
                scalar(2, magic.toLong())
                bytes(
                    3,
                    protobuf {
                        scalar(1, 1)
                        scalar(2, PHONE_TYPE_ANDROID)
                    },
                )
            }
        return G2Frame.packets(payload, G2Protocol.SID_DEVICE_CONFIG, 0, sync).single()
    }

    /**
     * Classify [message] against the magic values of the AUTH requests sent on this arm; null
     * when it is not an AUTH reply for this session.
     */
    fun reply(
        message: G2Message,
        magics: Set<Int>,
    ): G2AuthReply? {
        if (message.sid != G2Protocol.SID_DEVICE_CONFIG || message.result != 0) return null
        val envelope = G2ProtoReader.fields(message.payload) ?: return null
        if (envelope.keys.any { it !in ENVELOPE_FIELDS } || envelope.scalar(1) != AUTH_COMMAND) return null
        val auth = envelope.bytes(3)?.let(G2ProtoReader::fields) ?: return null
        if (auth.keys.any { it !in AUTH_MGR_FIELDS } || auth.values.any { it.wire != 0 }) return null
        val success = auth.scalar(1) == 1L && (auth.scalar(3) ?: 0L) == 0L
        if (message.notify) return if (success) G2AuthReply.AUTHENTICATED else G2AuthReply.NOT_AUTHENTICATED
        val magic = envelope.scalar(2) ?: return null
        if (magic.toInt() !in magics) return null
        return if (auth.isEmpty() || success) G2AuthReply.AUTHENTICATED else G2AuthReply.NOT_AUTHENTICATED
    }
}
