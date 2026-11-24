// G2Prelude: the session prelude on SID 0x01 that precedes Even Hub pages, and its ACK.
//
// From the even-g2-protocol Python library (MIT, PRELUDE_F5872 in widgets/evenhub.py), which
// reports it live-confirmed; see NOTICE in this module. The faceclaw client (GPL, behaviour
// only) requires its ACK before any page.
//
// Request, one packet, header flag 0x20, fixed sync 0x92:
//
//     {1: 2, 2: 156 (fixed magic), 4: {3: {2: {2: {1: 0, 2: 0}}}}}
//
// The ACK is any SID 0x01 reply whose field 2 echoes 156.
//
// TODO(hardware): Nobody has documented which SID 0x01 messages consumer firmware needs before
// an Even Hub page, or what this one selects; both sources send exactly this frame. On a real
// pair, check whether a page is accepted without the prelude and whether the ACK always
// arrives; drop the prelude or its ACK wait if either is unnecessary.

package com.fghbuild.eveng2

object G2Prelude {
    private const val MAGIC = 156L
    private const val SYNC = 0x92

    /** The prelude packet, byte for byte as the Python library sends it. */
    fun frame(): ByteArray {
        val payload =
            protobuf {
                scalar(1, 2)
                scalar(2, MAGIC)
                bytes(
                    4,
                    protobuf {
                        bytes(
                            3,
                            protobuf {
                                bytes(
                                    2,
                                    protobuf {
                                        bytes(
                                            2,
                                            protobuf {
                                                scalar(1, 0)
                                                scalar(2, 0)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }
        return G2Frame.packets(payload, G2Protocol.SID_PRELUDE, G2Protocol.FLAG_SYNC_BOTH, SYNC).single()
    }

    /** True when [message] acknowledges the prelude. */
    fun isAck(message: G2Message): Boolean {
        if (message.sid != G2Protocol.SID_PRELUDE || message.result != 0) return false
        return G2ProtoReader.fields(message.payload)?.scalar(2) == MAGIC
    }
}
