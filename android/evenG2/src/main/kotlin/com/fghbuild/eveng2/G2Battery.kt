// G2Battery: the glasses' battery from the right arm's settings read (SID 0x09), and the case's CASE_INFO (SID 0x81).
//
// Ported and modified from BleProtocol.kt (parseSettingsBattery, buildCaseInfoQuery, parseCaseInfo,
// CaseInfoSnapshot), the case rules of session/GlassesSessionControls.kt (updateCaseInfoLocked), and
// the battery read of g2protocol/MessageBuilder.kt (batteryQuery) of jimrandomh/faceclaw (GPL-3.0);
// see NOTICE in this module. The fields are stock firmware's; g2flash does not change them.
//
// Every message is framed with header flag 0x20:
//
// - Glasses: G2Settings' read {1: 2, 2: magic, 4: {1: 1}} to the right arm, as faceclaw's session
//   sends it. faceclaw's flash prompt (FlashPromptFlow.kt, readBatteries) also reads the left arm
//   on its own link and reports that each arm answers with its own battery. A pair on 2.3.0.24 with
//   Faceclaw/45 contradicts it: the right arm answered the read sent on the left link, on the right
//   link, with a body identical to its reply to the right read apart from the magic, so the pair
//   reports no level of the left arm. The reply echoes the magic in field 2, and its field 4 holds
//   the level in percent in field 12, charging (1) in field 13, and silent mode in field 14, which
//   this SDK does not read. Proto3 drops zero fields, so a reply without field 12 carries no level
//   (or 0 %) and is no reading; one without field 13 is not charging.
// - Case: {1: 1 (CASE_INFO), 2: magic, 3: {}} to the right arm. A reply, and a push that the right
//   arm sends with flag 0x01 and a magic of its own, are {1: 1, 2: magic, 3: GlassesCaseInfo}:
//   {1: level 1..100, 2: case charging on USB (1), 3: lid closed (1), 4: glasses in the case (1),
//   5: error code, 0 for success}. faceclaw saw, on 2.3.0.24 / Faceclaw/38: in the case, the right
//   arm pushes one on every change (docking, the lid, USB, each percent); docking pushes {4: 1}
//   first and the level about 0.7 s later; out of the case it answers a read with an empty field 3,
//   since it cannot see the case. On a pair on 2.3.0.24 with Faceclaw/45 out of the case, it
//   answered {1: 95} without field 4, which reads as out of the case. Replies and pushes are read
//   alike, whatever their magic.
//
// G2Session polls both; see its header.

package com.fghbuild.eveng2

/** One battery: its level in [percent], 0 to 100, and whether it is [charging]. */
data class G2Charge(
    val percent: Int,
    val charging: Boolean,
)

/**
 * The batteries of a connected pair: the [glasses]' that the right arm reports, and the charging
 * [case]'s while the glasses sit in it. A null part is unknown: not answered yet in this session,
 * or, for the [case], the glasses are out of it.
 */
data class G2Battery(
    val glasses: G2Charge? = null,
    val case: G2Charge? = null,
)

/** Where the glasses are, as a CASE_INFO reply or push reports it. */
internal sealed interface G2CaseInfo {
    /** In the case, whose battery is [charge]. */
    data class InCase(
        val charge: G2Charge,
    ) : G2CaseInfo

    data object OutOfCase : G2CaseInfo
}

internal object G2BatteryCodec {
    private const val SETTINGS_REQUEST_FIELD = 4
    private const val LEVEL_FIELD = 12
    private const val CHARGING_FIELD = 13
    private const val CASE_INFO = 1L
    private const val CASE_INFO_FIELD = 3
    private const val CASE_LEVEL = 1
    private const val CASE_CHARGING = 2
    private const val CASE_IN_CASE = 4
    private const val CASE_ERROR = 5

    /** The CASE_INFO read. */
    fun caseRead(
        magic: Int,
        sync: Int,
    ): List<ByteArray> {
        require(magic in G2EvenHub.MAGIC_RANGE) { "magic $magic" }
        val payload =
            protobuf {
                scalar(1, CASE_INFO)
                scalar(2, magic.toLong())
                bytes(CASE_INFO_FIELD, byteArrayOf())
            }
        return G2Frame.packets(payload, G2Protocol.SID_CASE, G2Protocol.FLAG_SYNC_BOTH, sync)
    }

    /** The glasses' battery in [message] when it answers the settings read sent with [magic]; null otherwise. */
    fun glasses(
        message: G2Message,
        magic: Int,
    ): G2Charge? {
        if (message.sid != G2Protocol.SID_SETTINGS || message.result != 0) return null
        val root = G2ProtoReader.all(message.payload) ?: return null
        if (root.first(2)?.scalar != magic.toLong()) return null
        val request = root.first(SETTINGS_REQUEST_FIELD)?.bytes ?: return null
        val fields = G2ProtoReader.all(request) ?: return null
        val level = fields.first(LEVEL_FIELD)?.scalar?.takeIf { it in 1..100 } ?: return null
        return G2Charge(level.toInt(), charging = fields.first(CHARGING_FIELD)?.scalar == 1L)
    }

    /**
     * Where [message] places the glasses, when it is a CASE_INFO reply or push; null for any other
     * message, an error, and a reading in the case without a level, such as docking's first push.
     */
    fun case(message: G2Message): G2CaseInfo? {
        if (message.sid != G2Protocol.SID_CASE || message.result != 0) return null
        val root = G2ProtoReader.all(message.payload) ?: return null
        if (root.first(1)?.scalar != CASE_INFO) return null
        val info = G2ProtoReader.all(root.first(CASE_INFO_FIELD)?.bytes ?: return null) ?: return null
        if ((info.first(CASE_ERROR)?.scalar ?: 0L) != 0L) return null
        if ((info.first(CASE_IN_CASE)?.scalar ?: 0L) == 0L) return G2CaseInfo.OutOfCase
        val level = info.first(CASE_LEVEL)?.scalar?.takeIf { it in 1..100 } ?: return null
        return G2CaseInfo.InCase(G2Charge(level.toInt(), charging = info.first(CASE_CHARGING)?.scalar == 1L))
    }

    // The first field [number] of a leniently read message level.
    private fun List<Pair<Int, G2ProtoField>>.first(number: Int): G2ProtoField? =
        firstOrNull { it.first == number }?.second
}
