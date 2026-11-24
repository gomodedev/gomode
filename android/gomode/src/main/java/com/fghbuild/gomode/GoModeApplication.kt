// Keeps native shell owners across Activity replacement without initializing unused owners during shutdown.
package com.fghbuild.gomode

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Configuration
import com.fghbuild.eveng2.G2GattConnector
import com.fghbuild.eveng2.G2Scanner
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.glasses.GlassesOwner
import com.fghbuild.gomode.sdk.v1.Display
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.gomode.voice.CarDialVoiceOwner
import com.fghbuild.gomode.voice.LocalModelDownloads
import com.fghbuild.gomode.voice.LocalModelLoadDiagnostics
import com.fghbuild.gomode.voice.LocalModelOwner
import com.fghbuild.gomode.voice.VoiceErrorLog
import com.fghbuild.halo.ble.HaloConnection

internal val Context.goModeSettingsDataStore by preferencesDataStore(name = "gomode_settings")

class GoModeApplication :
    Application(),
    Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    internal val settingsRepository by lazy { SettingsRepository(goModeSettingsDataStore) }
    private val localModelOwnerDelegate: Lazy<LocalModelOwner> =
        lazy {
            LocalModelOwner(this, installation = modelDownloads.states)
        }
    internal val localModelOwner by localModelOwnerDelegate
    private val modelDownloadsDelegate: Lazy<LocalModelDownloads> =
        lazy {
            LocalModelDownloads(this) { model ->
                if (localModelOwnerDelegate.isInitialized()) localModelOwner.modelInstalled(model)
            }
        }
    internal val modelDownloads by modelDownloadsDelegate
    private val modelLoadDiagnosticsDelegate = lazy { LocalModelLoadDiagnostics(this) }
    internal val modelLoadDiagnostics by modelLoadDiagnosticsDelegate
    private val voiceErrorsDelegate = lazy { VoiceErrorLog(this) }
    internal val voiceErrors by voiceErrorsDelegate

    override fun onCreate() {
        super.onCreate()
        // Recover the last load before settings can initiate another preparation.
        modelLoadDiagnostics
    }

    internal suspend fun removeLocalModel(model: LocalLlmModel) {
        localModelOwner.removeModel(model) { modelDownloads.removeFiles(model) }
    }

    private val serviceShellOwnerDelegate =
        lazy { ServiceShellOwner(this, settingsRepository, ::connectedGlassesDisplay) }
    internal val serviceShellOwner by serviceShellOwnerDelegate
    private val carDialVoiceOwnerDelegate =
        lazy {
            CarDialVoiceOwner(this, settingsRepository, localModelOwner, serviceShellOwner, glassesOwner)
        }
    internal val carDialVoiceOwner by carDialVoiceOwnerDelegate
    private val glassesOwnerDelegate =
        lazy { GlassesOwner(this, settingsRepository, HaloConnection(this), G2Scanner(this), G2GattConnector(this)) }
    internal val glassesOwner by glassesOwnerDelegate

    // An owner that does not exist yet has no glasses connected; reading the display must not create one.
    private fun connectedGlassesDisplay(): Display? =
        if (glassesOwnerDelegate.isInitialized()) glassesOwner.connectedDisplay else null

    override fun onTerminate() {
        if (glassesOwnerDelegate.isInitialized()) glassesOwner.close()
        if (modelLoadDiagnosticsDelegate.isInitialized()) modelLoadDiagnostics.close()
        if (voiceErrorsDelegate.isInitialized()) voiceErrors.close()
        if (carDialVoiceOwnerDelegate.isInitialized()) carDialVoiceOwner.close()
        if (serviceShellOwnerDelegate.isInitialized()) serviceShellOwner.close()
        if (modelDownloadsDelegate.isInitialized()) modelDownloads.close()
        if (localModelOwnerDelegate.isInitialized()) localModelOwner.close()
        super.onTerminate()
    }
}
