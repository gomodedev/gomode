// G2Microphone: the Even G2 microphone's LC3 packets on the audio characteristic, without duplicates, as 16 kHz PCM.
//
// While an Even Hub page is open, the microphone command (G2EvenHub.microphone) starts and stops the
// microphone. The glasses then notify a packet every 50 ms on characteristic 6402 of each arm, as
// faceclaw (GPL-3.0) and the even-g2-protocol Python library (MIT) enable it on both arms; see
// NOTICE in this module. A packet is PACKET_BYTES long: five LC3 frames of 40 bytes, each 10 ms of
// 16 kHz mono, then a signed 16-bit little-endian signal-strength ratio, a signed 16-bit
// little-endian direction of arrival in degrees, and an 8-bit counter that wraps at 255, as faceclaw's
// Lc3PacketFramer.kt reads it after the glasses firmware's service_audio.c.
//
// Either arm may relay the same packet, so G2MicrophoneStream, ported and modified from faceclaw's
// Lc3PacketFramer.kt, keeps one counter across both: a counter gap of 0 or of LATE_GAP and more is a
// copy or a late packet and is dropped before it reaches the decoder, and a gap of n counts n - 1
// lost packets. Nothing fills a lost packet; the next one follows the last decoded one at once. A
// frame whose bitstream is invalid is concealed (G2Lc3Decoder). One stream lasts from one start of
// the microphone to its stop.

package com.fghbuild.eveng2

object G2Microphone {
    internal const val FRAMES_PER_PACKET = 5

    /** Samples per second of the PCM: mono, signed 16-bit. */
    const val SAMPLE_RATE = 16_000

    /** Samples that one packet decodes to: 50 ms. */
    const val SAMPLES_PER_PACKET = FRAMES_PER_PACKET * G2Lc3Decoder.SAMPLES

    internal const val FRAME_BYTES = 40
    internal const val PACKET_BYTES = 205
    internal const val COUNTER_OFFSET = 204

    /** A counter gap from this one up is a late copy, not a loss. */
    internal const val LATE_GAP = 128
}

/** Decodes one stream of microphone packets from both arms; one caller at a time. */
internal class G2MicrophoneStream {
    private val decoder = G2Lc3Decoder()
    private var lastCounter = -1
    private val received = mutableMapOf<G2Side, Long>()
    private var decoded = 0L
    private var duplicates = 0L
    private var missing = 0L
    private var malformed = 0L
    private var concealed = 0L

    /** The PCM of [packet] from [side], or null when it is malformed, a copy, or late. */
    fun accept(
        side: G2Side,
        packet: ByteArray,
    ): ShortArray? {
        received[side] = (received[side] ?: 0) + 1
        if (packet.size != G2Microphone.PACKET_BYTES) {
            malformed++
            return null
        }
        val counter = packet[G2Microphone.COUNTER_OFFSET].toInt() and 0xFF
        if (lastCounter >= 0) {
            val gap = (counter - lastCounter) and 0xFF
            if (gap == 0 || gap >= G2Microphone.LATE_GAP) {
                duplicates++
                return null
            }
            missing += gap - 1
        }
        lastCounter = counter
        decoded++
        val pcm = ShortArray(G2Microphone.SAMPLES_PER_PACKET)
        for (frame in 0 until G2Microphone.FRAMES_PER_PACKET) {
            val valid =
                decoder.decode(
                    packet,
                    frame * G2Microphone.FRAME_BYTES,
                    G2Microphone.FRAME_BYTES,
                    pcm,
                    frame * G2Lc3Decoder.SAMPLES,
                )
            if (!valid) concealed++
        }
        return pcm
    }

    /** The counts so far, for the host's log. */
    override fun toString(): String =
        "received right ${received[G2Side.RIGHT] ?: 0} left ${received[G2Side.LEFT] ?: 0}, decoded $decoded, " +
            "duplicate $duplicates, missing $missing, malformed $malformed, concealed frames $concealed"
}
