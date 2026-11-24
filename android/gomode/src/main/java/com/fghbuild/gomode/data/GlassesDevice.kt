// The glasses Go Mode connects to: a Halo by address, or an Even G2 pair by both arms.
package com.fghbuild.gomode.data

import com.fghbuild.eveng2.G2Pair

sealed interface GlassesDevice {
    data class Halo(
        val address: String,
    ) : GlassesDevice

    data class EvenG2(
        val pair: G2Pair,
    ) : GlassesDevice
}
