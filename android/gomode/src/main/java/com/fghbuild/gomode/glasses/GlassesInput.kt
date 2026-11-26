// The glasses input Go Mode lists for the wearer: gestures and system events by place, and the presses it acts on.
package com.fghbuild.gomode.glasses

/** What the wearer did, or what the glasses reported, as the input events screen names it. */
internal enum class GlassesGesture(
    val label: String,
) {
    // Go Mode's own: a gesture it acts on as a press.
    PRESS("Press"),
    TAP("Tap"),
    DOUBLE_TAP("Double tap"),
    HOLD("Hold"),
    RELEASE("Release"),
    TAP_THEN_HOLD("Tap then hold"),
    SCROLL_UP("Scroll up"),
    SCROLL_DOWN("Scroll down"),
    HEAD_UP("Head up"),
    HEAD_DOWN("Head down"),
    RING_TOUCH("Ring touch"),
    PAGE_SHOWN("Page shown"),
    PAGE_HIDDEN("Page hidden"),
    PAGE_CLOSED("Page closed"),
    OTHER("Event"),
}

/** Where an input came from, when the glasses say. */
internal enum class GlassesInputPlace(
    val label: String,
) {
    LEFT("left"),
    RIGHT("right"),
    RING("ring"),
}

/** One input: [code] keeps the glasses' own value of an [GlassesGesture.OTHER] event. */
internal data class GlassesEvent(
    val gesture: GlassesGesture,
    val place: GlassesInputPlace? = null,
    val code: Long? = null,
) {
    /** One short line, such as "Double tap · left". */
    val label: String
        get() {
            val name = if (gesture == GlassesGesture.OTHER) "${gesture.label} $code" else gesture.label
            return place?.let { "$name · ${it.label}" } ?: name
        }
}

/** [event] at [atMillis], wall-clock time. */
internal data class GlassesInput(
    val atMillis: Long,
    val event: GlassesEvent,
)

/** The press [GlassesOwner] records for each press it receives. */
internal val PRESS_EVENT = GlassesEvent(GlassesGesture.PRESS)

/**
 * The inputs, newest first, as the glasses show them while the wearer inspects them: a title, then
 * one input per line, as many as [fit] shows whole on one screen.
 */
internal fun inspectionText(
    inputs: List<GlassesInput>,
    fit: ScreenFit,
): String {
    val title = if (inputs.isEmpty()) "Input events\nTry a gesture." else "Input events"
    var text = title
    for (input in inputs) {
        val longer = "$text\n${input.event.label}"
        if (!fit.fits(longer)) break
        text = longer
    }
    return text
}
