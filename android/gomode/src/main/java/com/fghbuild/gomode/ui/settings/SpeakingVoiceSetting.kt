// Gateway speaking-voice dropdown with the effective voice checked and persistence errors.
//
// It lists the voices of the resolved choice, or of the synthesis model a composable choice speaks
// through, in the voice language. The empty choice names the voice the gateway uses when the catalog
// marks one, and reads "Provider default" otherwise. A saved voice the gateway no longer offers
// shows as that default, which sessions use.
package com.fghbuild.gomode.ui.settings

import androidx.compose.runtime.Composable
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.SpeakingVoiceOwner
import com.fghbuild.gomode.voice.GatewayVoiceChoices
import com.fghbuild.gomode.voice.ResolvedVoiceSetup

@Composable
internal fun SpeakingVoiceSetting(
    choices: GatewayVoiceChoices,
    setup: ResolvedVoiceSetup,
    settings: SettingsState,
    enabled: Boolean,
    save: suspend (owner: SpeakingVoiceOwner, voiceName: String) -> Unit,
) {
    val voices = choices.speakingVoices(settings, setup)
    val saver = rememberPickerSave("speaking voice", choices.gatewayURL, voices?.owner)
    if (voices == null || voices.names.isEmpty()) return
    val selected = choices.speakingVoice(settings, voices)
    val defaultLabel = if (voices.defaultName.isEmpty()) "Provider default" else "Default (${voices.defaultName})"
    SettingsPicker(
        label = "Speaking voice",
        value = selected.ifEmpty { defaultLabel },
        enabled = enabled && !saver.saving && settings.loadState == SettingsLoadState.Ready,
        tag = "gomode-speaking-voice",
        menuTag = "gomode-speaking-voices",
        error = saver.error,
        errorTag = "gomode-speaking-voice-save-error",
        items =
            (listOf("") + voices.names).map { name ->
                SettingsPickerItem(
                    text = name.ifEmpty { defaultLabel },
                    selected = name == selected,
                    tag = if (name.isEmpty()) "gomode-speaking-voice-default" else "gomode-speaking-voice-$name",
                ) { saver.launch { save(voices.owner, name) } }
            },
    )
}
