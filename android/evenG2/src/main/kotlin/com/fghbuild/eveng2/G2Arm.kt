// G2Arm: Even G2 arms and pairs, advertisement parsing, and grouping of scanned arms into pairs.
//
// Each arm advertises as `Even G2_<pair>_<L|R>_<suffix>`, where the suffix is the last three
// bytes of its address, plus manufacturer data under company 0x5245 ("RE"):
// <serial ASCII> <6-byte address, little-endian> <flag>. Both arms of one pair carry the same
// serial, so it groups them; the name's pair field is the fallback. Source: the even-g2-protocol
// Python library (MIT); see NOTICE in this module.
//
// TODO(hardware): i-soxi's notes call the name's pair field a model variant, which two nearby
// pairs could share; the Python library calls it the pair ID. Confirm on a real pair that the
// serial is present in both arms' advertisements as Android reports them, so the fallback
// never merges two pairs.

package com.fghbuild.eveng2

enum class G2Side { LEFT, RIGHT }

/** One arm: a separate BLE peripheral. */
data class G2Arm(
    val side: G2Side,
    val address: String,
    val name: String,
)

/** Both arms of one pair of glasses; [id] is the serial, or the name's pair field without one. */
data class G2Pair(
    val id: String,
    val left: G2Arm,
    val right: G2Arm,
) {
    init {
        require(left.side == G2Side.LEFT && right.side == G2Side.RIGHT)
    }
}

/** An arm heard in a scan, with the key that groups it with its other arm. */
data class G2Advertisement(
    val arm: G2Arm,
    val pairKey: String,
)

object G2Advertising {
    const val MANUFACTURER_ID = 0x5245

    private val NAME = Regex("""^Even G2_(\w+?)_([LR])_([0-9A-Fa-f]{6})$""")
    private val SERIAL = Regex("""^[0-9A-Za-z]{4,32}$""")
    private const val ADDRESS_AND_FLAG = 7

    /** Parse one advertisement; null when it is not an Even G2 arm. */
    fun parse(
        name: String?,
        address: String,
        manufacturerData: ByteArray?,
    ): G2Advertisement? {
        val match = NAME.matchEntire(name ?: return null) ?: return null
        val side = if (match.groupValues[2] == "L") G2Side.LEFT else G2Side.RIGHT
        val serial =
            manufacturerData
                ?.takeIf { it.size > ADDRESS_AND_FLAG }
                ?.let { String(it, 0, it.size - ADDRESS_AND_FLAG, Charsets.US_ASCII) }
                ?.takeIf { SERIAL.matches(it) }
        return G2Advertisement(G2Arm(side, address, name), serial ?: match.groupValues[1])
    }
}

/** Groups scanned arms into pairs; not thread-safe. */
class G2PairCollector {
    private val left = HashMap<String, G2Arm>()
    private val right = HashMap<String, G2Arm>()
    private val reported = HashMap<String, G2Pair>()

    /** Record [advertisement]; returns its pair when it becomes complete or an arm's address changes. */
    fun add(advertisement: G2Advertisement): G2Pair? {
        val key = advertisement.pairKey
        val arm = advertisement.arm
        if (arm.side == G2Side.LEFT) left[key] = arm else right[key] = arm
        val pair = G2Pair(key, left[key] ?: return null, right[key] ?: return null)
        if (reported[key] == pair) return null
        reported[key] = pair
        return pair
    }
}
