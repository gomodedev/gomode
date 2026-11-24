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

    /** Service ID of Even Hub pages, heartbeat, and page events. */
    const val SID_EVEN_HUB = 0xE0

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

    /**
     * How long AUTH may take on one arm. On firmware that requires an encrypted link the success
     * reply arrives only after pairing, which can wait on a system dialog.
     */
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

    /**
     * Delay before each consecutive reconnect; the last entry repeats. A session that reaches
     * [G2State.Ready] starts the sequence over.
     */
    val RECONNECT_BACKOFF_MS: List<Long> = listOf(2_000L, 5_000L, 10_000L, 30_000L)
}
