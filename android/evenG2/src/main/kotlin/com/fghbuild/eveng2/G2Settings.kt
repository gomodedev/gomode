// G2Settings: the settings read (SID 0x09) that tells stock from Faceclaw custom firmware, and its framebuffer lease.
//
// Ported and modified from BleProtocol.kt (buildSettingsQuery, parseSettingsFirmwareInfo,
// buildFaceclawWakeControl) and app/g2/firmware-compat.ts of jimrandomh/faceclaw (GPL-3.0); the
// wire format is g2flash's patches/settings_ext.c (GPL-3.0); see NOTICE in this module.
//
// Every message is one G2SettingPackage protobuf on SID 0x09, framed with header flag 0x20:
//
// - Read, to the right arm: {1: 2, 2: magic, 4: {1: 1}}. The reply echoes the magic in field 2 and
//   carries both arms' stock versions in field 4 (5 left, 6 right). g2flash's firmware appends
//   field 100, the revision string "Faceclaw/<n>"; stock firmware never sends it, and older
//   g2flash builds sent "EVENCFW/<ver> <feature tokens>". The reply has fields only the
//   firmware's schema names, so it is read leniently: this file uses only fields 2 and 100, and
//   G2BatteryCodec the battery that field 4 also holds.
// - Faceclaw control, to each arm, fire-and-forget (magic 0, nothing answers):
//   {1: 1, 2: 0, 101: ['F', 'C', 1, op, 0, 0]}. Op 5 arms or renews a 90-second framebuffer lease
//   under which the firmware draws the phone's frames on the whole panel and keeps stock widgets
//   from repainting over them; op 6 releases it and stock repaints resume. faceclaw renews it every
//   45 s on both arms. Op 7 asks the firmware to send its cached wear state as the stock wear event
//   (G2SensorCodec); faceclaw sends it to both arms, and g2flash sends the event from the right.
//
// g2flash bumps <n> on every change of its contract. This SDK draws on Faceclaw/45, the revision
// that scripts/g2_cfw.py's pinned g2flash commit builds, and on later ones: faceclaw accepts any
// revision at or above the one it needs, since revisions only add to the contract. Any other
// firmware gets the stock Even Hub page, which g2flash keeps for the Even app.

package com.fghbuild.eveng2

/** What the glasses' firmware draws for this SDK, as the settings read reports it. */
sealed interface G2Firmware {
    /**
     * Even Hub's text container only: stock firmware, whose [revision] is null, or custom firmware
     * whose revision string this SDK does not draw on.
     */
    data class EvenHub(
        val revision: String?,
    ) : G2Firmware

    /** g2flash's Faceclaw custom firmware at [revision], which draws on the whole [G2Panel]. */
    data class Faceclaw(
        val revision: Int,
    ) : G2Firmware

    companion object {
        /** The oldest Faceclaw revision whose drawing contract this SDK uses. */
        const val MIN_FACECLAW_REVISION = 45

        private const val FACECLAW_PREFIX = "Faceclaw/"

        /** The firmware that reports [revision] in settings field 100; null when it sends none. */
        fun of(revision: String?): G2Firmware {
            val text = revision?.trim().orEmpty()
            val number =
                text
                    .takeIf { it.startsWith(FACECLAW_PREFIX) }
                    ?.removePrefix(FACECLAW_PREFIX)
                    ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?.toIntOrNull()
            return number?.takeIf { it >= MIN_FACECLAW_REVISION }?.let(::Faceclaw) ?: EvenHub(text.ifEmpty { null })
        }
    }
}

internal object G2Settings {
    internal const val READ = 2L
    private const val WRITE = 1L
    internal const val REQUEST_FIELD = 4
    private const val REVISION_FIELD = 100
    private const val CONTROL_FIELD = 101
    private const val WRITE_FIELD = 3
    private const val WEAR_DETECTION_FIELD = 5

    /** Arms or renews the framebuffer lease. */
    const val LEASE_ACQUIRE = 5

    /** Releases the framebuffer lease. */
    const val LEASE_RELEASE = 6

    /** Asks for the wear state. */
    const val WEAR_QUERY = 7

    /** The firmware drops a lease this long after its last renewal. */
    const val LEASE_MS = 90_000L

    /** How often faceclaw renews the lease; half of [LEASE_MS]. */
    const val LEASE_RENEW_MS = 45_000L

    fun read(
        magic: Int,
        sync: Int,
    ): List<ByteArray> {
        require(magic in G2EvenHub.MAGIC_RANGE) { "magic $magic" }
        val payload =
            protobuf {
                scalar(1, READ)
                scalar(2, magic.toLong())
                bytes(REQUEST_FIELD, protobuf { scalar(1, 1) })
            }
        return G2Frame.packets(payload, G2Protocol.SID_SETTINGS, G2Protocol.FLAG_SYNC_BOTH, sync)
    }

    /** The firmware that [message] reports when it answers the read sent with [magic]; null when it does not. */
    fun firmware(
        message: G2Message,
        magic: Int,
    ): G2Firmware? {
        if (message.sid != G2Protocol.SID_SETTINGS || message.result != 0) return null
        val fields = G2ProtoReader.all(message.payload) ?: return null
        val echoed = fields.firstOrNull { (number, field) -> number == 2 && field.wire == 0 }?.second?.scalar
        if (echoed != magic.toLong()) return null
        val revision = fields.firstOrNull { (number, field) -> number == REVISION_FIELD && field.wire == 2 }
        return G2Firmware.of(revision?.second?.bytes?.let(::decodeUtf8))
    }

    /**
     * Turns the stock wear detector on (DeviceReceiveInfoFromAPP field 5), as faceclaw's
     * buildSetWearDetection writes it before its wear query: with the detector off, the firmware
     * reports the glasses not worn and sends no wear events.
     */
    fun enableWearDetection(
        magic: Int,
        sync: Int,
    ): List<ByteArray> {
        require(magic in G2EvenHub.MAGIC_RANGE) { "magic $magic" }
        val payload =
            protobuf {
                scalar(1, WRITE)
                scalar(2, magic.toLong())
                bytes(WRITE_FIELD, protobuf { bytes(WEAR_DETECTION_FIELD, protobuf { scalar(1, 1) }) })
            }
        return G2Frame.packets(payload, G2Protocol.SID_SETTINGS, G2Protocol.FLAG_SYNC_BOTH, sync)
    }

    /** Whether [message] is a settings reply that echoes [magic]. */
    fun answers(
        message: G2Message,
        magic: Int,
    ): Boolean {
        if (message.sid != G2Protocol.SID_SETTINGS) return false
        val fields = G2ProtoReader.all(message.payload) ?: return false
        return fields.any { (number, field) -> number == 2 && field.wire == 0 && field.scalar == magic.toLong() }
    }

    /** The Faceclaw control [op] for one arm. */
    fun control(
        op: Int,
        sync: Int,
    ): List<ByteArray> {
        require(op == LEASE_ACQUIRE || op == LEASE_RELEASE || op == WEAR_QUERY) { "control op $op" }
        val control = byteArrayOf('F'.code.toByte(), 'C'.code.toByte(), 1, op.toByte(), 0, 0)
        val payload =
            protobuf {
                scalar(1, WRITE)
                scalar(2, 0)
                bytes(CONTROL_FIELD, control)
            }
        return G2Frame.packets(payload, G2Protocol.SID_SETTINGS, G2Protocol.FLAG_SYNC_BOTH, sync)
    }
}
