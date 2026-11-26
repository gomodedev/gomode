// The shell actions that glasses gestures trigger, and the one map from each model's gestures to them.
//
// The owner's map, to revise after use: on Even G2, a double tap of either temple starts or stops
// voice, a long press of either mutes or unmutes the microphone, and a single tap, the likeliest
// mis-tap, acts on nothing. The R1 ring extends it the same way: its double tap and long press act
// as a temple's, and its single tap acts on nothing. Stock firmware opens its own
// menu on a long press, so only Faceclaw firmware, which forwards the long press instead, mutes.
// Halo's one reported gesture, a single press of its button, starts or stops voice.
package com.fghbuild.gomode.glasses

import android.content.Context
import com.fghbuild.eveng2.G2Firmware
import com.fghbuild.eveng2.G2Gesture
import com.fghbuild.eveng2.G2GestureType
import com.fghbuild.gomode.R
import com.fghbuild.gomode.voice.MicrophoneMode
import com.fghbuild.gomode.voice.VoiceSessionController
import com.fghbuild.halo.msg.ClickType

/** What a bound glasses gesture asks the shell to do. */
internal enum class GlassesAction {
    // Mute the microphone of the running voice until unmuted, or unmute it.
    TOGGLE_MUTE,

    // Start voice, or end the running voice or car call.
    TOGGLE_VOICE,
}

/** The action that Even G2 [gesture] triggers on [firmware], or null when it acts on nothing. */
internal fun g2Action(
    gesture: G2Gesture,
    firmware: G2Firmware,
): GlassesAction? =
    when (gesture.type) {
        G2GestureType.CLICK -> null
        G2GestureType.DOUBLE_CLICK -> GlassesAction.TOGGLE_VOICE
        G2GestureType.LONG_PRESS -> GlassesAction.TOGGLE_MUTE.takeIf { firmware is G2Firmware.Faceclaw }
    }

/** The action that a Halo button press of [click] triggers, or null when it acts on nothing. */
internal fun haloAction(click: ClickType): GlassesAction? =
    if (click == ClickType.SINGLE) GlassesAction.TOGGLE_VOICE else null

/**
 * Mutes [session]'s microphone until the next unmute when it listens, unmutes it otherwise, as a
 * [GlassesAction.TOGGLE_MUTE] asks, and says which on [glasses].
 */
internal fun toggleMute(
    session: VoiceSessionController,
    glasses: GlassesOwner,
    context: Context,
) {
    val muting = !session.state.value.muted
    session.setMicrophoneMode(if (muting) MicrophoneMode.MUTED else MicrophoneMode.LISTENING)
    glasses.notify(context.getString(if (muting) R.string.glasses_notice_muted else R.string.glasses_notice_unmuted))
}
