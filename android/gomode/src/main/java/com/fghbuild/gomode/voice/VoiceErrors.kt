// Fixed voice failure messages and the description of an unexpected failure.
package com.fghbuild.gomode.voice

internal const val VOICE_CALL_PREEMPTED_ERROR = "Voice ended because another call started."
internal const val VOICE_CONVERSATION_ENDED_ERROR = "Voice ended because the service sign-in changed."
internal const val VOICE_AUDIO_FOCUS_LOST_ERROR = "Voice ended because another app took over the audio."
internal const val VOICE_BLUETOOTH_LOST_ERROR = "Voice ended because the Bluetooth device disconnected."
internal const val VOICE_MESSAGE_DELIVERY_ERROR = "Couldn't send a voice message. Reconnect voice."
internal const val VOICE_TOOL_RESULT_DELIVERY_ERROR =
    "Couldn't deliver the action's result. Reconnect voice. The action may have completed; check before repeating it."

/** Names a failure cause without rendering a null or blank message. */
internal fun describeFailure(failure: Throwable): String =
    failure.message?.takeIf { it.isNotBlank() } ?: (failure::class.simpleName ?: "Unknown error")
