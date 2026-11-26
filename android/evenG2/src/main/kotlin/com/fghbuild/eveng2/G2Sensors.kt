// G2Sensors: whether the glasses are worn, head up and down, and the accelerometer, as an Even G2 session reports them.
//
// Ported and modified from parseWearState, buildImuControl, and readFloatFieldValue of
// BleProtocol.kt, the IMU sample of G2Event.kt, and the frame of session/ImuOrientation.kt of
// jimrandomh/faceclaw (GPL-3.0); g2flash's patches/settings_ext.c and gesture_fwd.c (GPL-3.0) are
// the firmware side. See NOTICE in this module. None of it has been observed on a pair yet.
//
// - Worn: the stock OnboardingDataPackage event on SID 0x10, {1: 3 (EVENT), 2: magic, 5: {1: 1
//   (GLS_WEAR_STATUS), 2: 1 worn or 0 not}}. faceclaw reads it from either arm and whatever the
//   header flag, since stock firmware sends it with flag 0x00. g2flash says the stock sender first
//   checks which app runs, and its own faceclaw_send_wear_event sends the same bytes from the right
//   arm on Faceclaw firmware in answer to G2Settings.WEAR_QUERY, which the session sends once per
//   connection so the state shows before the wearer moves. faceclaw also turns the stock wear
//   detector on with a settings write; this SDK leaves the wearer's setting alone.
// - Head up and down: system events 12 and 15 (G2InputEvent.HEAD_UP and HEAD_DOWN), which only
//   Faceclaw firmware sends while a page is open (gesture_fwd.c), and only while the stock head-up
//   setting is on.
// - Accelerometer: Even Hub command 19, branch 22, {1: 1 to start or 0 to stop, 2: report pace
//   (start only)}, as faceclaw's buildImuControl writes it. faceclaw's accelerometer demo says the
//   pace is a code from 100 to 1000 in steps of 100, not a rate, and that any other value streams
//   all-zero samples; it uses 200, as Even's running-tracker app does. No source shows the reply:
//   G2Session takes any reply that echoes the magic and logs it. Samples arrive as system events of
//   type 8 (G2InputEvent.IMU_DATA) whose field 3 is {1: x, 2: y, 3: z} as fixed32 floats in g:
//   faceclaw reports captures with floats where the g2-kit proto declares doubles. faceclaw's
//   ImuOrientation.kt, measured on hardware, gives the frame: +x forward, +y to the wearer's left,
//   +z up through the top of the head; a level head reads (0, 0, 1).
//
// TODO(hardware): Unverified on a pair: that stock firmware sends the wear event while Go Mode's
// page is open, that Faceclaw/45 answers the wear query, which arm sends each, that the IMU start
// streams samples at pace 200 and how often, and whether a closed page stops the stream. The
// session logs each wear event with its arm, each IMU start and stop with its reply and sample
// count, the first sample of each start in hex, and the samples that arrived while it took the
// stream for off. On a pair, take the glasses off and on with the input events screen open on
// stock and Faceclaw firmware, then tilt the head and move the glasses. If samples arrive while the
// stream is off, a closed page keeps it on, and G2Session must stop it before the next page.

package com.fghbuild.eveng2

/** An accelerometer sample in g along the glasses' axes: [x] forward, [y] to the wearer's left, [z] up. */
data class G2Acceleration(
    val x: Float,
    val y: Float,
    val z: Float,
)

/** Where the wearer's head last went, as Faceclaw firmware reports it. */
enum class G2Head {
    UP,
    DOWN,
}

/**
 * The latest sensor readings of one session; each is null until its first reading. [wearing] is whether
 * the glasses are on the head, [head] where it last went, and [acceleration] the latest accelerometer
 * sample while [G2Glasses.sensors] is collected.
 */
data class G2Sensors(
    val wearing: Boolean? = null,
    val head: G2Head? = null,
    val acceleration: G2Acceleration? = null,
)

internal object G2SensorCodec {
    private const val EVENT = 3L
    private const val EVENT_FIELD = 5
    private const val WEAR_STATUS = 1L
    private const val IMU_COMMAND = 19L
    private const val IMU_BRANCH = 22

    /** The pace faceclaw streams at: a firmware code, not a rate. */
    private const val IMU_PACE = 200L

    /** Whether [message] reports the glasses worn, when it is a wear event; null otherwise. */
    fun wearing(message: G2Message): Boolean? {
        if (message.sid != G2Protocol.SID_ONBOARDING || message.result != 0) return null
        val root = G2ProtoReader.all(message.payload) ?: return null
        if (root.first(1)?.scalar != EVENT) return null
        val event = G2ProtoReader.all(root.first(EVENT_FIELD)?.bytes ?: return null) ?: return null
        if (event.first(1)?.scalar != WEAR_STATUS) return null
        return when (event.first(2)?.scalar ?: 0L) {
            0L -> false
            1L -> true
            else -> null
        }
    }

    /** Start the accelerometer's stream when [on], else stop it; the page must be open. */
    fun imu(
        on: Boolean,
        magic: Int,
        sync: Int,
    ): List<ByteArray> {
        val body =
            protobuf {
                scalar(1, if (on) 1 else 0)
                if (on) scalar(2, IMU_PACE)
            }
        return G2EvenHub.request(IMU_COMMAND, IMU_BRANCH, body, magic, sync)
    }

    // The first field [number] of a leniently read message level.
    private fun List<Pair<Int, G2ProtoField>>.first(number: Int): G2ProtoField? =
        firstOrNull { it.first == number }?.second
}
