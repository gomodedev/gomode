// G2CfwStream: Faceclaw custom firmware's private message stream on SID 0xF0: records, zlib, packets, and ACKs.
//
// Ported and modified from CfwTransport.kt of jimrandomh/faceclaw (GPL-3.0); the receiver is
// g2flash's patches/message_transport.c and transport_compression.c (GPL-3.0); see NOTICE in this
// module. Changes: one compression context per session instead of per arm, a stream per message.
//
// One message, at most 65535 bytes, travels as one record:
//
//     <flags> <wire length u16 LE> <CRC-16 of the decoded message u16 LE> <body>
//
// flags: lenses (1 left, 2 right, 3 both), 4 the body is zlib, 8 reset the zlib context first. A
// compressed body is the output of one persistent RFC 1950 deflater, ended by a SYNC_FLUSH, so
// each record reuses the history of the ones before it. The first record of a session sets 8; a
// message that deflates past 65535 bytes goes uncompressed with 8 set, and so does the record
// after it. The record is cut into packets of at most 252 bytes that fit the ATT MTU, each its
// own Even G2 packet:
//
//     AA 21 <sequence> <count + 3> 01 01 F0 00 <options> <count bytes> <CRC-16 LE of options and bytes>
//
// options: the record's lenses, 0x80 on the first packet (start a stream at this sequence) and
// 0x40 on the last; the sequence goes up by one per packet. Every message here starts its own
// stream, so its ACKs name message 0 of the stream whose first sequence it had. The arm that
// receives the packets hands them to the other lens over the glasses' own bridge, and each lens
// that processes the message answers through the receiving arm, one packet on SID 0xF0:
//
//     <1 ACK | 3 NACK> <stream> <message u16> <lens> <decoded size u16> <CRC u16> [3 × 7 history bytes]
//
// An ACK may repeat up to three earlier successes; a NACK, for a malformed, undecodable, or
// refused message, also invalidates the receiver's zlib context. The CRC is CRC-16/CCITT-FALSE,
// as in G2Frame.

package com.fghbuild.eveng2

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** One lens's answer to a message of the private stream. */
internal data class G2CfwAck(
    val nack: Boolean,
    val stream: Int,
    val message: Int,
    val lens: Int,
    val size: Int,
    val checksum: Int,
)

/** The private stream of one session: one zlib context for every message, each drawn by both lenses. */
internal class G2CfwStream : AutoCloseable {
    private var deflater: Deflater? = null
    private var resetPending = true

    /** The packets of [message] for a link of [mtu], starting at sequence [stream]. */
    fun packets(
        message: ByteArray,
        stream: Int,
        mtu: Int,
    ): List<ByteArray> = split(record(message), stream, mtu)

    // Start a new zlib context with the next record.
    private fun reset() {
        deflater?.reset()
        resetPending = true
    }

    override fun close() {
        deflater?.end()
        deflater = null
        resetPending = true
    }

    private fun record(message: ByteArray): ByteArray {
        require(message.size <= MAX_MESSAGE) { "message size ${message.size}" }
        val reset = resetPending
        val encoder = deflater ?: Deflater().also { deflater = it }
        if (reset) encoder.reset()
        var body = syncFlush(encoder, message)
        var flags = BOTH or COMPRESSED or if (reset) RESET_CONTEXT else 0
        // A repeated empty sync flush produces nothing to inflate.
        if (body.isEmpty()) flags = flags and COMPRESSED.inv()
        if (body.size > MAX_MESSAGE) {
            // The deflate advanced the history the receiver will not see: restart both ends.
            reset()
            body = message
            flags = BOTH or RESET_CONTEXT
        } else {
            resetPending = false
        }
        return record(body, G2Frame.crc16(message), flags)
    }

    companion object {
        const val LEFT = 1
        const val RIGHT = 2
        const val BOTH = 3
        const val COMPRESSED = 4
        const val RESET_CONTEXT = 8
        const val MAX_MESSAGE = 65_535

        private const val ACK = 1
        private const val NACK = 3
        private const val ACK_SIZE = 9
        private const val HISTORY_ENTRY = 7
        private const val MAX_HISTORY = 3
        private const val FIRST = 0x80
        private const val LAST = 0x40
        private const val MAX_PACKET_BODY = 252

        // Header and CRC of a packet, and the ATT write header.
        private const val PACKET_OVERHEAD = 11
        private const val ATT_HEADER = 3

        /** `<flags> <wire length> <decoded CRC> <body>`. */
        fun record(
            body: ByteArray,
            checksum: Int,
            flags: Int,
        ): ByteArray {
            require(body.size <= MAX_MESSAGE && checksum in 0..0xFFFF && flags in 0..0xFF)
            return byteArrayOf(
                flags.toByte(),
                body.size.toByte(),
                (body.size ushr 8).toByte(),
                checksum.toByte(),
                (checksum ushr 8).toByte(),
            ) +
                body
        }

        /** The packets of [record] for a link of [mtu], the first at sequence [stream]. */
        fun split(
            record: ByteArray,
            stream: Int,
            mtu: Int,
        ): List<ByteArray> {
            require(stream in 0..0xFF && mtu >= PACKET_OVERHEAD + ATT_HEADER + 1)
            val lenses = record[0].toInt() and BOTH
            val capacity = minOf(MAX_PACKET_BODY, mtu - ATT_HEADER - PACKET_OVERHEAD)
            return record.indices.step(capacity).mapIndexed { index, offset ->
                val count = minOf(capacity, record.size - offset)
                val options =
                    lenses or (if (offset == 0) FIRST else 0) or (if (offset + count == record.size) LAST else 0)
                val signed = byteArrayOf(options.toByte()) + record.copyOfRange(offset, offset + count)
                val crc = G2Frame.crc16(signed)
                byteArrayOf(
                    0xAA.toByte(),
                    G2Protocol.ADDRESS_TO_GLASSES.toByte(),
                    (stream + index).toByte(),
                    (count + 3).toByte(),
                    1,
                    1,
                    G2Protocol.SID_CFW.toByte(),
                    0,
                ) +
                    signed +
                    byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
            }
        }

        /** The answer [message] carries, or null when it is not a well-formed one. */
        fun ack(message: G2Message): G2CfwAck? {
            val bytes = message.payload
            if (message.sid != G2Protocol.SID_CFW || message.result != 0) return null
            val history = bytes.size - ACK_SIZE
            if (history < 0 || history % HISTORY_ENTRY != 0 || history / HISTORY_ENTRY > MAX_HISTORY) return null
            val kind = bytes[0].u
            val lens = bytes[4].u
            if ((kind != ACK && kind != NACK) || (kind == NACK && history != 0) || lens !in LEFT..RIGHT) return null
            return G2CfwAck(kind == NACK, bytes[1].u, u16(bytes, 2), lens, u16(bytes, 5), u16(bytes, 7))
        }

        private fun syncFlush(
            deflater: Deflater,
            message: ByteArray,
        ): ByteArray {
            deflater.setInput(message)
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            do {
                val count = deflater.deflate(chunk, 0, chunk.size, Deflater.SYNC_FLUSH)
                out.write(chunk, 0, count)
            } while (count == chunk.size)
            return out.toByteArray()
        }

        private fun u16(
            bytes: ByteArray,
            offset: Int,
        ): Int = bytes[offset].u or (bytes[offset + 1].u shl 8)
    }
}

private val Byte.u: Int get() = toInt() and 0xFF
