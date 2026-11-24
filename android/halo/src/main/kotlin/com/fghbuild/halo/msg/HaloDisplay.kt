// HaloDisplay: geometry and colours of the Halo display that typed messages draw on.
package com.fghbuild.halo.msg

object HaloDisplay {
    /** Halo's display is a circle inscribed in a square this many pixels wide and high. */
    const val SIZE_PX = 256

    /** Colours one sprite shows, each chosen from 24-bit RGB; a pixel indexes one in 4 bits. */
    const val COLORS = 16
}
