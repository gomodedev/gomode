// G2Protocol: Even G2 GATT UUIDs, service IDs, header flags, MTU, and session timing.
//
// Contract (consumer firmware, no Even app): each arm is its own BLE peripheral. All
// characteristics share the base 00002760-08c2-11e1-9073-0e8ac72eXXXX: the phone writes
// commands without response to 5401 and receives replies and events as notifications on
// 5402. Both arms connect and authenticate; Even Hub commands go to the right arm, which
// relays to the left. Sources: the even-g2-protocol Python library (MIT) and the Even
// Realities enterprise SDK (Apache-2.0); see NOTICE in this module. Even documents none of
// this for consumer firmware, so a firmware update the Even app installs can break it.

package com.fghbuild.eveng2

import java.util.UUID

object G2Protocol {
    private fun uuid(suffix: Int): UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e%04x".format(suffix))

    /** Phone to glasses, write without response. */
    val WRITE_CHARACTERISTIC: UUID = uuid(0x5401)

    /** Glasses to phone, notify. */
    val NOTIFY_CHARACTERISTIC: UUID = uuid(0x5402)

    /** Client characteristic configuration descriptor that enables notifications. */
    val CLIENT_CONFIG_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** MTU the client requests; the Python library uses 512. */
    const val REQUESTED_MTU = 512

    /**
     * Smallest MTU the framing supports: [G2Frame] packets hold at most 147 bytes, an ATT write
     * of MTU 150. The enterprise contract states the same minimum.
     */
    const val MIN_MTU = 150

    /** Service ID of the session prelude (shared with the native dashboard). */
    const val SID_PRELUDE = 0x01

    /** Service ID of device configuration, which carries AUTH. */
    const val SID_DEVICE_CONFIG = 0x80

    /** Service ID of device settings, whose read reports the firmware; see [G2Settings]. */
    const val SID_SETTINGS = 0x09

    /** Service ID of Even Hub pages, heartbeat, and page events. */
    const val SID_EVEN_HUB = 0xE0

    /** Service ID of the charging case's state, which the right arm reports; see [G2BatteryCodec]. */
    const val SID_CASE = 0x81

    /** Service ID of Faceclaw custom firmware's private message stream; see [G2CfwStream]. */
    const val SID_CFW = 0xF0

    /** Header address byte of phone-to-glasses frames. */
    const val ADDRESS_TO_GLASSES = 0x21

    /**
     * Header flag of requests that the right arm syncs to both arms (the enterprise contract's
     * `syncBoth`). AUTH is per arm and uses 0.
     */
    const val FLAG_SYNC_BOTH = 0x20

    /** Header flag bit of unsolicited glasses-to-phone messages (events and pushes). */
    const val FLAG_NOTIFY = 0x01

    /** Connection, MTU, service discovery, and notification setup of one arm. */
    const val CONNECT_TIMEOUT_MS = 20_000L

    /** How long AUTH may take on one arm. */
    const val AUTH_TIMEOUT_MS = 30_000L

    /** AUTH is re-sent at this interval in case the first request preceded link encryption. */
    const val AUTH_RESEND_MS = 5_000L

    /** Reply deadline for the prelude and each Even Hub command. */
    const val COMMAND_TIMEOUT_MS = 3_000L

    // TODO(hardware): The glasses drop an Even Hub page whose heartbeat stops, but nobody has
    // measured after how long. The Python library sends every 4 s and reports a "connection
    // lost" screen after about 4 s of silence; the enterprise SDK sends 5 s after each ACK. This
    // uses the shorter interval. On a real pair, stop heartbeats and time the page's removal;
    // keep a margin of at least 2x below it.

    /** Interval between Even Hub heartbeats while a page is open. */
    const val HEARTBEAT_INTERVAL_MS = 4_000L

    /** How long from its create request a page must stay open for the next page to follow its exit at once. */
    const val PAGE_SETTLED_MS = 10_000L

    /** Pause before the page that follows one that closed before it settled; it doubles with each such page. */
    const val PAGE_RETURN_DELAY_MS = 1_000L

    /** Longest pause before a page that follows one that closed before it settled. */
    const val PAGE_RETURN_MAX_DELAY_MS = 8_000L

    /**
     * How long the session waits without drawing, Even Hub commands, or input before it reads the
     * batteries; faceclaw waits 5 s after the last input with nothing queued.
     */
    const val BATTERY_QUIET_MS = 5_000L

    /**
     * Interval between battery reads of worn glasses, as faceclaw's ConnectionOptions.kt sets it:
     * each round is three messages that compete with the wearer's frames.
     */
    const val BATTERY_POLL_MS = 5 * 60_000L

    /**
     * Interval between battery reads while an arm charges or the glasses sit in the case, as
     * faceclaw's: nobody wears them there, so the reads disturb nothing, and they show the charge
     * rising and its end.
     */
    const val BATTERY_CHARGING_POLL_MS = 30_000L
}
