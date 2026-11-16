// Device speaking-speed dropdown with the web's speed steps and persistence errors.
package com.fghbuild.gomode.ui.settings

import androidx.compose.runtime.Composable
import com.fghbuild.gomode.data.DEVICE_SPEECH_RATES

internal fun deviceSpeechRateLabel(rate: Float): String = "${rate.toString().removeSuffix(".0")}×"

@Composable
internal fun DeviceSpeechRateSetting(
    rate: Float,
    enabled: Boolean,
    save: suspend (rate: Float) -> Unit,
) {
    val saver = rememberPickerSave("speaking speed")
    SettingsPicker(
        label = "Speaking speed",
        value = deviceSpeechRateLabel(rate),
        enabled = enabled && !saver.saving,
        tag = "gomode-device-speech-rate",
        menuTag = "gomode-device-speech-rates",
        error = saver.error,
        errorTag = "gomode-device-speech-rate-error",
        items =
            DEVICE_SPEECH_RATES.map { choice ->
                SettingsPickerItem(
                    deviceSpeechRateLabel(choice),
                    selected = choice == rate,
                    tag = "gomode-device-speech-rate-$choice",
                ) { saver.launch { save(choice) } }
            },
    )
}
