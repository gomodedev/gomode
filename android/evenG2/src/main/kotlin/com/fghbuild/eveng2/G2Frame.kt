// G2Frame: Even G2 packet framing, CRC-16/CCITT, multi-packet split, and reassembly of glasses replies.
//
// Ported and modified from G2EvenHubTransport.kt and G2BusinessAuthCodec.kt of the Even
// Realities enterprise SDK 0.1.0-beta.14 (Apache-2.0, Copyright 2026 Hong Kong Even Realities
// Limited); see NOTICE in this module. Changes: generalized from SID 0xE0 to every service,
// header flag and address made parameters, stale partial messages evicted by count instead
// of by clock.
//
// Wire contract, one packet:
//
//     AA <address> <sync> <length> <total> <serial> <sid> <flag> <body…> [crc16 LE]
//
// - address: 0x21 phone to glasses; replies use 0x12 (0x14 and 0x15 also occur).
// - sync: per-message sequence byte; every packet of one message repeats it.
// - length: body bytes in this packet, plus 2 on the final packet for the CRC.
// - total, serial: packet count and 1-based packet number.
// - sid: service ID; flag: 0x20 syncs a request to both arms, bit 0 marks an unsolicited
//   message, bits 1-4 carry a nonzero result code on a failed reply, bits 6-7 are zero.
// - crc16: CRC-16/CCITT-FALSE (polynomial 0x1021, init 0xFFFF) over the complete protobuf
//   payload, little-endian, only after the final packet's body.

package com.fghbuild.eveng2

import java.io.ByteArrayOutputStream

/** One complete message from the glasses. A failed reply has a nonzero [result] and no payload. */
class G2Message(
    val sync: Int,
    val sid: Int,
    val flag: Int,
    val payload: ByteArray,
) {
    /** Result code in header flag bits 1-4; 0 is success. */
    val result: Int get() = (flag ushr 1) and 0x0F

    /** True for events and pushes the glasses send on their own. */
    val notify: Boolean get() = flag and G2Protocol.FLAG_NOTIFY != 0
}

object G2Frame {
    /** Largest protobuf payload of one message. */
    const val MAX_PAYLOAD = 32_768

    private const val HEADER = 8
    private const val MAX_PACKETS = 255

    // A packet holds at most 139 body bytes: 147 bytes, an ATT write at the minimum MTU 150.
    // The final packet keeps two of them for the CRC and at least one body byte, so the CRC is
    // never split across packets.
    private const val MAX_BODY = 139
    private const val MAX_FINAL_BODY = MAX_BODY - 2

    /** Split [payload] into the packets of one phone-to-glasses message. */
    fun packets(
        payload: ByteArray,
        sid: Int,
        flag: Int,
        sync: Int,
    ): List<ByteArray> {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD) { "payload size ${payload.size}" }
        require(sid in 0..0xFF && flag in 0..0xFF && sync in 0..0xFF)
        val parts = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val remaining = payload.size - offset
            val count = if (remaining <= MAX_FINAL_BODY) remaining else minOf(MAX_BODY, remaining - 1)
            parts += payload.copyOfRange(offset, offset + count)
            offset += count
        }
        check(parts.size <= MAX_PACKETS)
        val crc = crc16(payload)
        return parts.mapIndexed { index, part ->
            val last = index == parts.lastIndex
            val header =
                byteArrayOf(
                    0xAA.toByte(),
                    G2Protocol.ADDRESS_TO_GLASSES.toByte(),
                    sync.toByte(),
                    (part.size + if (last) 2 else 0).toByte(),
                    parts.size.toByte(),
                    (index + 1).toByte(),
                    sid.toByte(),
                    flag.toByte(),
                )
            header + part + if (last) byteArrayOf(crc.toByte(), (crc ushr 8).toByte()) else byteArrayOf()
        }
    }

    /** CRC-16/CCITT-FALSE: polynomial 0x1021, initial value 0xFFFF, no reflection, no final XOR. */
    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = ((crc shl 8) or (crc ushr 8)) and 0xFFFF
            crc = crc xor (byte.toInt() and 0xFF)
            crc = crc xor ((crc and 0xFF) ushr 4)
            crc = crc xor ((crc shl 12) and 0xFFFF)
            crc = crc xor (((crc and 0xFF) shl 5) and 0xFFFF)
        }
        return crc
    }
}

/**
 * Joins the packets of glasses-to-phone messages from one arm. Each connection owns one
 * assembler, so a dropped link discards every partial message.
 */
class G2FrameAssembler {
    private class Stream(
        val total: Int,
        var next: Int,
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream(),
    )

    // Insertion order is age: when full, the oldest partial message is evicted.
    private val streams = LinkedHashMap<Long, Stream>()

    /** Accept one notification; returns a message once its final packet arrives with a valid CRC. */
    fun accept(data: ByteArray): G2Message? {
        if (data.size < HEADER || data[0].u != 0xAA) return null
        val address = data[1].u
        val length = data[3].u
        val flag = data[7].u
        if (address ushr 4 != 1 || address and 0x0F !in REPLY_ADDRESSES || flag and 0xC0 != 0) return null
        // ATT notifications may carry padding after the declared packet; length bounds the packet.
        if (data.size < HEADER + length) return null
        val sync = data[2].u
        val total = data[4].u
        val serial = data[5].u
        val sid = data[6].u
        val key = (address.toLong() shl 24) or (sync.toLong() shl 16) or (flag.toLong() shl 8) or sid.toLong()
        if (total == 0 || serial !in 1..total) {
            streams.remove(key)
            return null
        }
        if ((flag ushr 1) and 0x0F != 0) {
            // A failed reply carries no payload and is never split.
            streams.remove(key)
            return if (total == 1) G2Message(sync, sid, flag, byteArrayOf()) else null
        }
        val last = serial == total
        if (length < if (last) 3 else 1) {
            streams.remove(key)
            return null
        }
        val end = HEADER + length - if (last) 2 else 0
        val stream =
            if (serial == 1) {
                // A restarted message with the same identity is ambiguous: drop both.
                if (streams.remove(key) != null) return null
                if (streams.size >= MAX_STREAMS) streams.remove(streams.keys.first())
                Stream(total, 1).also { streams[key] = it }
            } else {
                streams[key] ?: return null
            }
        if (stream.total != total ||
            stream.next != serial ||
            stream.bytes.size() + end - HEADER > G2Frame.MAX_PAYLOAD
        ) {
            streams.remove(key)
            return null
        }
        stream.bytes.write(data, HEADER, end - HEADER)
        stream.next++
        if (!last) return null
        streams.remove(key)
        val payload = stream.bytes.toByteArray()
        val crc = data[end].u or (data[end + 1].u shl 8)
        if (G2Frame.crc16(payload) != crc) return null
        return G2Message(sync, sid, flag, payload)
    }

    private companion object {
        const val HEADER = 8
        const val MAX_STREAMS = 4
        val REPLY_ADDRESSES = setOf(2, 4, 5)
    }
}

private val Byte.u: Int get() = toInt() and 0xFF
