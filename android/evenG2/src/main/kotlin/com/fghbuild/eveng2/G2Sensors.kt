// G2Sensors: worn, head, accelerometer, and on Faceclaw firmware light and compass, as an Even G2 session reports them.
//
// Ported and modified from parseWearState, buildSetWearDetection, buildImuControl, and readFloatFieldValue of
// BleProtocol.kt, the IMU sample of G2Event.kt, and the frame of session/ImuOrientation.kt of
// jimrandomh/faceclaw (GPL-3.0), and from its parseFaceclawAlsReport and parseCompassPayload of
// BleProtocol.kt, app/native/ambient-light-types.ts, alsPassivePayload of
// session/GlassesSessionBrightness.kt, and enqueueCompassControlLocked of session/GlassesSessionSend.kt;
// g2flash's patches/settings_ext.c, gesture_fwd.c, als_sensor.c, compass.c, and zlib_glue.c (GPL-3.0)
// are the firmware side. See NOTICE in this module. None of it has been observed on a pair yet.
//
// - Worn: the stock OnboardingDataPackage event on SID 0x10, {1: 3 (EVENT), 2: magic, 5: {1: 1
//   (GLS_WEAR_STATUS), 2: 1 worn or 0 not}}. faceclaw reads it from either arm and whatever the
//   header flag, since stock firmware sends it with flag 0x00. g2flash says the stock sender first
//   checks which app runs, and its own faceclaw_send_wear_event sends the same bytes from the right
//   arm on Faceclaw firmware in answer to G2Settings.WEAR_QUERY, which the session sends once per
//   connection so the state shows before the wearer moves. Before that query the session turns the
//   stock wear detector on with faceclaw's settings write (G2Settings.enableWearDetection), as
//   faceclaw's enableWearDetectionAndRequestState does: the setting stays on after the session.
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
// - Ambient light, on Faceclaw firmware: private-stream message 16 (G2CfwStream). [16, 1, flags,
//   interval u16 LE, minimum change u16 LE, heartbeat u16 LE] starts passive polling of the master
//   (right) lens's OPT3001 sensor every interval ms (clamped to 100..5000), reporting each reading that
//   moved by the minimum change and at least every heartbeat ms; flags bit 0 stops it when the
//   framebuffer lease ends. [16, 2] stops it. The session sends faceclaw's 500 ms, 0, and 1000 ms. While
//   it polls, stock auto-brightness stops adjusting the panel (als_sensor.c), so the panel keeps its
//   brightness while the input events screen is open. Reports are settings pushes on SID 0x09, {1: 3,
//   2: 0, 105: 24 bytes}: 'A' 'L' 1 <reason> <flags> <stock status> <value u32 LE> <peak u32 LE> ...;
//   als_sensor.c says the value is the stock driver's, roughly tenths of a lux.
// - Compass, on Faceclaw firmware: private-stream message 10. [10, 2, interval u16 LE, minimum change
//   u16 LE] starts the stock compass on the right lens with its report filter (interval clamped to
//   50..2000 ms), and [10, 0] stops it; the session sends faceclaw's 100 ms and 0 degrees. Nothing but
//   that stop or a CFW cleanup stops it, not the lease. The right arm then notifies the stock heading
//   on SID 0x08, {1: 15, 2: 0, 10: {1: degrees 0..359}, 100: 12 diagnostic bytes}; compass.c writes the
//   heading field even for 0.
//
// On a pair on 2.3.0.24 with Faceclaw/45, the right arm answered the wear query and sent each wear
// change while Go Mode's page was open, once the session had turned the detector on.
// TODO(hardware): Unverified on a pair: that stock firmware sends the wear event while Go Mode's
// page is open, that the IMU start
// streams samples at pace 200 and how often, and whether a closed page stops the stream. The
// session logs each wear event with its arm, each IMU start and stop with its reply and sample
// count, the first sample of each start in hex, and the samples that arrived while it took the
// stream for off. On a pair, take the glasses off and on with the input events screen open on
// stock and Faceclaw firmware, then tilt the head and move the glasses. If samples arrive while the
// stream is off, a closed page keeps it on, and G2Session must stop it before the next page.
// Also unverified: that both lenses acknowledge the light and compass controls, that light reports
// and headings arrive at the rates asked, what the light value is in lux, and whether the heading is
// magnetic or true north. The session logs each control's ACK, NACK, or silence, the first report and
// heading of each start in hex, and their counts at the stop. Cover the master (right) temple and turn
// around with the input events screen open on Faceclaw firmware.

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
 * sample while [G2Glasses.sensors] is collected. On Faceclaw firmware, also while collected: [light], the
 * ambient light sensor's reading in the stock driver's units, roughly tenths of a lux, and [heading], the
 * stock compass's heading in degrees, 0 to 359.
 */
data class G2Sensors(
    val wearing: Boolean? = null,
    val head: G2Head? = null,
    val acceleration: G2Acceleration? = null,
    val light: Long? = null,
    val heading: Int? = null,
)

internal object G2SensorCodec {
    private const val EVENT = 3L
    private const val EVENT_FIELD = 5
    private const val WEAR_STATUS = 1L
    private const val IMU_COMMAND = 19L
    private const val IMU_BRANCH = 22

    /** The pace faceclaw streams at: a firmware code, not a rate. */
    private const val IMU_PACE = 200L

    private const val LIGHT_MESSAGE = 16
    private const val LIGHT_START = 1
    private const val LIGHT_STOP = 2
    private const val LIGHT_BOUND_TO_LEASE = 1
    private const val LIGHT_INTERVAL_MS = 500
    private const val LIGHT_MIN_CHANGE = 0
    private const val LIGHT_HEARTBEAT_MS = 1_000
    private const val LIGHT_FIELD = 105
    private const val LIGHT_REPORT_SIZE = 24
    private const val LIGHT_VALUE = 6
    private val LIGHT_HEADER = byteArrayOf('A'.code.toByte(), 'L'.code.toByte(), 1)

    private const val COMPASS_MESSAGE = 10
    private const val COMPASS_STOP = 0
    private const val COMPASS_START = 2
    private const val COMPASS_INTERVAL_MS = 100
    private const val COMPASS_MIN_CHANGE = 0
    private const val COMPASS_CHANGED = 15L
    private const val COMPASS_FIELD = 10

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

    /** The private-stream message that starts the light sensor's passive polling when [on], else stops it. */
    fun ambientLight(on: Boolean): ByteArray =
        if (on) {
            byteArrayOf(LIGHT_MESSAGE.toByte(), LIGHT_START.toByte(), LIGHT_BOUND_TO_LEASE.toByte()) +
                u16(LIGHT_INTERVAL_MS) +
                u16(LIGHT_MIN_CHANGE) +
                u16(LIGHT_HEARTBEAT_MS)
        } else {
            byteArrayOf(LIGHT_MESSAGE.toByte(), LIGHT_STOP.toByte())
        }

    /** The private-stream message that starts the compass when [on], else stops it. */
    fun compass(on: Boolean): ByteArray =
        if (on) {
            byteArrayOf(COMPASS_MESSAGE.toByte(), COMPASS_START.toByte()) +
                u16(COMPASS_INTERVAL_MS) +
                u16(COMPASS_MIN_CHANGE)
        } else {
            byteArrayOf(COMPASS_MESSAGE.toByte(), COMPASS_STOP.toByte())
        }

    /** The light reading that [message] reports, when it is a light report; null otherwise. */
    fun light(message: G2Message): Long? {
        if (message.sid != G2Protocol.SID_SETTINGS || message.result != 0) return null
        val root = G2ProtoReader.all(message.payload) ?: return null
        val report = root.first(LIGHT_FIELD)?.bytes ?: return null
        val header = report.copyOf(LIGHT_HEADER.size)
        if (report.size < LIGHT_REPORT_SIZE || !header.contentEquals(LIGHT_HEADER)) return null
        return (0 until Int.SIZE_BYTES).sumOf { (report[LIGHT_VALUE + it].toLong() and 0xFF) shl (8 * it) }
    }

    /** The heading in degrees that [message] reports, when it is a compass notification; null otherwise. */
    fun heading(message: G2Message): Int? {
        if (message.sid != G2Protocol.SID_NAVIGATION || message.result != 0) return null
        val root = G2ProtoReader.all(message.payload) ?: return null
        if (root.first(1)?.scalar != COMPASS_CHANGED) return null
        val compass = G2ProtoReader.all(root.first(COMPASS_FIELD)?.bytes ?: return null) ?: return null
        return compass
            .first(1)
            ?.scalar
            ?.takeIf { it in 0..359 }
            ?.toInt()
    }

    private fun u16(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    // The first field [number] of a leniently read message level.
    private fun List<Pair<Int, G2ProtoField>>.first(number: Int): G2ProtoField? =
        firstOrNull { it.first == number }?.second
}
