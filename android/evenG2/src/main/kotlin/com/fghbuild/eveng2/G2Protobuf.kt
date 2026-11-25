// G2Protobuf: minimal protobuf writer and strict reader for Even G2 messages.
//
// Ported and modified from the writer and field reader of G2EvenHubCodec.kt in the Even
// Realities enterprise SDK 0.1.0-beta.14 (Apache-2.0, Copyright 2026 Hong Kong Even Realities
// Limited); see NOTICE in this module.
//
// The reader fails closed: a repeated field number, an unsupported wire type, an overlong
// varint, a truncated field, or a scalar above uint32 rejects the whole message, so a reply
// cannot pass as another by protobuf default values. Its lenient form reads the settings reply,
// whose many fields only the firmware's schema knows, and still rejects a malformed encoding.

package com.fghbuild.eveng2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal const val UINT32_MAX = 0xFFFF_FFFFL

internal class G2ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun scalar(
        field: Int,
        value: Long,
    ) {
        varint(field.toLong() shl 3)
        varint(value)
    }

    fun bytes(
        field: Int,
        value: ByteArray,
    ) {
        varint((field.toLong() shl 3) or 2)
        varint(value.size.toLong())
        out.write(value)
    }

    private fun varint(value: Long) {
        var remaining = value
        while (remaining and -128L != 0L) {
            out.write(((remaining and 127) or 128).toInt())
            remaining = remaining ushr 7
        }
        out.write(remaining.toInt())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal fun protobuf(block: G2ProtoWriter.() -> Unit): ByteArray = G2ProtoWriter().apply(block).toByteArray()

/** One decoded field: a varint [scalar], length-delimited [bytes], or a skipped fixed64 or fixed32 (wire 1 or 5). */
internal class G2ProtoField(
    val wire: Int,
    val scalar: Long? = null,
    val bytes: ByteArray? = null,
)

internal object G2ProtoReader {
    /** Decode one message level; null when it is malformed. */
    fun fields(data: ByteArray): Map<Int, G2ProtoField>? {
        val result = LinkedHashMap<Int, G2ProtoField>()
        for ((number, field) in decode(data, lenient = false) ?: return null) {
            if (number in result) return null
            result[number] = field
        }
        return result
    }

    /**
     * Every field of one message level in order, for a message this SDK reads one or two fields of:
     * repeated numbers, scalars above uint32, and skipped fixed64 values (wire 1) are allowed. Null
     * when the encoding itself is malformed.
     */
    fun all(data: ByteArray): List<Pair<Int, G2ProtoField>>? = decode(data, lenient = true)

    private fun decode(
        data: ByteArray,
        lenient: Boolean,
    ): List<Pair<Int, G2ProtoField>>? {
        val result = mutableListOf<Pair<Int, G2ProtoField>>()
        var offset = 0

        fun varint(): Long? {
            var value = 0L
            for (shift in 0..63 step 7) {
                if (offset >= data.size) return null
                val byte = data[offset++].toInt() and 0xFF
                if (shift == 63 && byte > 1) return null
                value = value or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return value
            }
            return null
        }
        while (offset < data.size) {
            val key = varint() ?: return null
            val number = key ushr 3
            if (key < 0 || number <= 0 || number > Int.MAX_VALUE) return null
            result +=
                number.toInt() to
                when (val wire = (key and 7).toInt()) {
                    0 -> {
                        val scalar = varint() ?: return null
                        if (!lenient && scalar !in 0..UINT32_MAX) return null
                        G2ProtoField(wire, scalar = scalar)
                    }

                    1 -> {
                        if (!lenient || data.size - offset < 8) return null
                        offset += 8
                        G2ProtoField(wire)
                    }

                    2 -> {
                        val size = varint() ?: return null
                        if (size < 0 || size > data.size - offset) return null
                        G2ProtoField(wire, bytes = data.copyOfRange(offset, offset + size.toInt())).also {
                            offset += size.toInt()
                        }
                    }

                    5 -> {
                        // Floats (IMU samples) are skipped: no decoded message uses them.
                        if (data.size - offset < 4) return null
                        offset += 4
                        G2ProtoField(wire)
                    }

                    else -> {
                        return null
                    }
                }
        }
        return result
    }
}

/** The varint value of [field], or null when absent or of another wire type. */
internal fun Map<Int, G2ProtoField>.scalar(field: Int): Long? = get(field)?.takeIf { it.wire == 0 }?.scalar

/** The length-delimited value of [field], or null when absent or of another wire type. */
internal fun Map<Int, G2ProtoField>.bytes(field: Int): ByteArray? = get(field)?.takeIf { it.wire == 2 }?.bytes

/** Strict UTF-8 decoding: malformed input or an embedded NUL yields null. */
internal fun decodeUtf8(data: ByteArray): String? =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data))
            .toString()
            .takeIf { '\u0000' !in it }
    } catch (_: CharacterCodingException) {
        null
    }
