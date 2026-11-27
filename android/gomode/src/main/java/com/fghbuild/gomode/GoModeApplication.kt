// Keeps native shell owners across Activity replacement without initializing unused owners during shutdown.
package com.fghbuild.gomode

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Configuration
import com.fghbuild.eveng2.G2GattConnector
import com.fghbuild.eveng2.G2Scanner
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.glasses.GlassesOwner
import com.fghbuild.gomode.glasses.followGlassesMicrophone
import com.fghbuild.gomode.sdk.v1.Display
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.gomode.voice.CarDialVoiceOwner
import com.fghbuild.gomode.voice.LocalModelDownloads
import com.fghbuild.gomode.voice.LocalModelLoadDiagnostics
import com.fghbuild.gomode.voice.LocalModelOwner
import com.fghbuild.gomode.voice.VoiceAudioInput
import com.fghbuild.gomode.voice.VoiceIssues
import com.fghbuild.halo.ble.HaloConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

internal val Context.goModeSettingsDataStore by preferencesDataStore(name = "gomode_settings")

class GoModeApplication :
    Application(),
    Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    // The process's lifetime, within which the glasses owner runs; cancelling it ends that owner.
    private val scope = MainScope()

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

    // The shell's voice problems, across sessions and Activities; see VoiceIssues.
    internal val voiceIssues = VoiceIssues()

    // The source every voice session hears, across sessions and Activities.
    internal val voiceAudioInput = VoiceAudioInput()

    override fun onCreate() {
        super.onCreate()
        // Recover the last load before settings can initiate another preparation.
        modelLoadDiagnostics
        // TODO(2026-12-04): delete this cleanup once this date has passed and two release tags contain it.
        // Releases before it kept the five newest voice failures in this file; nothing reads or writes it now.
        scope.launch(Dispatchers.IO) { AtomicFile(File(noBackupFilesDir, "voice-errors.json")).delete() }
    }

    internal suspend fun removeLocalModel(model: LocalLlmModel) {
        localModelOwner.removeModel(model) { modelDownloads.removeFiles(model) }
    }

    private val serviceShellOwnerDelegate =
        lazy { ServiceShellOwner(this, settingsRepository, ::connectedGlassesDisplay) }
    internal val serviceShellOwner by serviceShellOwnerDelegate
    private val carDialVoiceOwnerDelegate =
        lazy {
            CarDialVoiceOwner(this, settingsRepository, localModelOwner, serviceShellOwner, glassesOwner, voiceIssues)
        }
    internal val carDialVoiceOwner by carDialVoiceOwnerDelegate

    // Voice follows the owner's microphone from its creation: no glasses connect before then.
    private val glassesOwnerDelegate =
        lazy {
            GlassesOwner(
                scope,
                this,
                settingsRepository,
                HaloConnection(this),
                G2Scanner(this),
                G2GattConnector(this),
                voiceIssues,
            ).also { owner ->
                val enabled = settingsRepository.settings.map { it.glassesMicrophoneForVoice }
                // Sinks meter, duck, and buffer each block off the main thread.
                val streams = CoroutineScope(scope.coroutineContext + Dispatchers.Default)
                scope.launch { followGlassesMicrophone(voiceAudioInput, enabled, owner.microphone, streams) }
            }
        }
    internal val glassesOwner by glassesOwnerDelegate

    // An owner that does not exist yet has no glasses connected; reading the display must not create one.
    private fun connectedGlassesDisplay(): Display? =
        if (glassesOwnerDelegate.isInitialized()) glassesOwner.connectedDisplay else null

    override fun onTerminate() {
        if (modelLoadDiagnosticsDelegate.isInitialized()) modelLoadDiagnostics.close()
        if (carDialVoiceOwnerDelegate.isInitialized()) carDialVoiceOwner.close()
        if (serviceShellOwnerDelegate.isInitialized()) serviceShellOwner.close()
        if (modelDownloadsDelegate.isInitialized()) modelDownloads.close()
        if (localModelOwnerDelegate.isInitialized()) localModelOwner.close()
        scope.cancel()
        super.onTerminate()
    }
}
