// G2InputEvent: one input or system event that an Even G2 arm reports while a page is open, and its gestures.

package com.fghbuild.eveng2

/**
 * One input or system event that [arm] reported while a page was open, IMU samples aside; the
 * header of G2EvenHub.kt holds the wire format. [type] is one of the type constants below, or a value
 * a later firmware adds. [source] is a system event's input, one of the source constants below or
 * another value, and null for a text event, which names none. Either arm may report input from
 * either temple.
 */
data class G2InputEvent(
    val arm: G2Side,
    val type: Long,
    val source: Long?,
) {
    companion object {
        const val CLICK = 0L
        const val SCROLL_UP = 1L
        const val SCROLL_DOWN = 2L
        const val DOUBLE_CLICK = 3L
        const val FOREGROUND_ENTER = 4L
        const val FOREGROUND_EXIT = 5L
        const val ABNORMAL_EXIT = 6L
        const val EXIT = 7L
        const val IMU_DATA = 8L

        // Faceclaw firmware under the framebuffer lease; see G2EvenHub.kt. RELEASE ends every touch.
        const val LONG_PRESS = 9L
        const val RELEASE = 10L
        const val TAP_THEN_HOLD = 11L
        const val HEAD_UP = 12L
        const val RING_TOUCH_DOWN = 14L
        const val HEAD_DOWN = 15L

        const val SOURCE_UNSPECIFIED = 0L
        const val SOURCE_RIGHT_TEMPLE = 1L
        const val SOURCE_RING = 2L
        const val SOURCE_LEFT_TEMPLE = 3L
    }
}

/**
 * A deliberate gesture on the open page, which [G2EvenHub.gesture] reads from an input event: its
 * [type], and its [source], null for a text event, which names none.
 */
data class G2Gesture(
    val type: G2GestureType,
    val source: G2GestureSource?,
)

enum class G2GestureType {
    CLICK,
    DOUBLE_CLICK,
    LONG_PRESS,
}

enum class G2GestureSource {
    LEFT_TEMPLE,
    RIGHT_TEMPLE,
    RING,
}
