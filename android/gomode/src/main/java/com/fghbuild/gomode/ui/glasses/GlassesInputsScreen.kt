// Compose screen for trying glasses input: the latest gestures, mirrored on the glasses, and a microphone recording.
package com.fghbuild.gomode.ui.glasses

import android.media.MediaPlayer
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fghbuild.gomode.R
import com.fghbuild.gomode.glasses.GLASSES_RECORDING_SECONDS
import com.fghbuild.gomode.glasses.GlassesGesture
import com.fghbuild.gomode.glasses.GlassesInput
import com.fghbuild.gomode.glasses.GlassesOwner
import com.fghbuild.gomode.glasses.recordGlassesMicrophone
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassesInputsScreen(
    owner: GlassesOwner,
    onNavigateBack: () -> Unit,
) {
    // Started, not composed: a screen left in the background must not keep gestures from their actions.
    LifecycleStartEffect(owner) {
        val inspection = owner.inspectInputs()
        onStopOrDispose { inspection.close() }
    }
    BackHandler(onBack = onNavigateBack)
    val inputs by owner.inputs.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Input events") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("gomode-glasses-inputs"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Gestures do nothing else while this screen is open. The glasses show the same list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            MicrophoneRecorder(owner)
            if (inputs.isEmpty()) {
                Text("No input yet. Try a gesture.", style = MaterialTheme.typography.bodyMedium)
            }
            inputs.forEach { InputRow(it) }
        }
    }
}

// Records the glasses' microphone to a file that adb can pull; the log names its path. Play, shown once
// this screen has recorded audio, plays the latest recording; a playback failure has its own line.
@Composable
private fun MicrophoneRecorder(owner: GlassesOwner) {
    val microphone by owner.microphone.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(context) { File(context.getExternalFilesDir(null) ?: context.filesDir, RECORDING_FILE) }
    var recording by remember { mutableStateOf(false) }
    var recorded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playbackFailure by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val stop = {
        player?.release()
        player = null
    }
    Column(
        modifier = Modifier.padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val glasses = microphone ?: return@OutlinedButton
                    stop()
                    recording = true
                    recorded = false
                    playbackFailure = null
                    status = "Recording…"
                    scope.launch {
                        status =
                            try {
                                val seconds = recordGlassesMicrophone(glasses.microphone, file)
                                Log.i(TAG, "Saved %.1f s of the glasses microphone to %s".format(seconds, file))
                                recorded = seconds > 0.0
                                if (recorded) "Saved %.1f s".format(seconds) else NO_AUDIO
                            } catch (e: IOException) {
                                Log.w(TAG, "Glasses recording failed", e)
                                "Recording failed: ${e.message}"
                            } finally {
                                recording = false
                            }
                    }
                },
                enabled = microphone != null && !recording,
                modifier = Modifier.testTag("gomode-glasses-record"),
            ) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Record $GLASSES_RECORDING_SECONDS s", modifier = Modifier.padding(start = 8.dp))
            }
            if (recorded) {
                val playing = player != null
                IconButton(
                    onClick = {
                        if (playing) {
                            stop()
                        } else {
                            playbackFailure = null
                            player =
                                play(file) { failure ->
                                    player = null
                                    playbackFailure = failure
                                }
                        }
                    },
                    modifier = Modifier.testTag("gomode-glasses-play"),
                ) {
                    Icon(
                        painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play_arrow),
                        contentDescription = if (playing) "Stop playback" else "Play recording",
                    )
                }
            }
        }
        val shown = status ?: "Connect glasses with a microphone to record it.".takeIf { microphone == null }
        shown?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("gomode-glasses-record-status"),
            )
        }
        playbackFailure?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("gomode-glasses-playback-status"),
            )
        }
    }
}

// Starts playing [file] and returns its player, which releases itself at the end; null when it cannot
// start. [onEnd] runs once playback ends, with the failure's description or null after the last sample.
private fun play(
    file: File,
    onEnd: (failure: String?) -> Unit,
): MediaPlayer? {
    val player = MediaPlayer()
    player.setOnPreparedListener { it.start() }
    player.setOnCompletionListener {
        it.release()
        onEnd(null)
    }
    player.setOnErrorListener { it, what, extra ->
        Log.w(TAG, "Glasses recording playback failed: what $what, extra $extra")
        it.release()
        onEnd("Playback failed: error $what, $extra.")
        true
    }
    return try {
        player.setDataSource(file.path)
        player.prepareAsync()
        player
    } catch (e: IOException) {
        Log.w(TAG, "Glasses recording playback failed", e)
        player.release()
        onEnd("Playback failed: ${e.message}")
        null
    }
}

// One line: the gesture's icon and name, then its time.
@Composable
private fun InputRow(input: GlassesInput) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("gomode-glasses-input"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(input.event.gesture.icon()), contentDescription = null)
        Text(
            text = input.event.label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = inputTime(input.atMillis),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@DrawableRes
private fun GlassesGesture.icon(): Int =
    when (this) {
        GlassesGesture.TAP -> R.drawable.ic_touch_app
        GlassesGesture.DOUBLE_TAP -> R.drawable.ic_ads_click
        GlassesGesture.HOLD -> R.drawable.ic_pan_tool
        GlassesGesture.RELEASE -> R.drawable.ic_do_not_touch
        GlassesGesture.TAP_THEN_HOLD -> R.drawable.ic_gesture
        GlassesGesture.SCROLL_UP -> R.drawable.ic_swipe_up
        GlassesGesture.SCROLL_DOWN -> R.drawable.ic_swipe_down
        GlassesGesture.HEAD_UP -> R.drawable.ic_north
        GlassesGesture.HEAD_DOWN -> R.drawable.ic_south
        GlassesGesture.RING_TOUCH -> R.drawable.ic_adjust
        GlassesGesture.PAGE_SHOWN -> R.drawable.ic_visibility
        GlassesGesture.PAGE_HIDDEN -> R.drawable.ic_visibility_off
        GlassesGesture.PAGE_CLOSED -> R.drawable.ic_logout
        GlassesGesture.OTHER -> R.drawable.ic_question_mark
    }

private const val TAG = "GoModeGlasses"

// One file, replaced by each recording, so `adb pull` always finds the latest at the same path.
private const val RECORDING_FILE = "glasses-microphone.wav"

private const val NO_AUDIO = "No audio came from the glasses."

// To the nearest 10 ms: one gesture sends several events within a few hundred milliseconds, and
// a BLE connection interval of 7.5 ms makes finer digits noise.
private val InputTime = DateTimeFormatter.ofPattern("HH:mm:ss.SS").withZone(ZoneId.systemDefault())

private fun inputTime(atMillis: Long): String = InputTime.format(Instant.ofEpochMilli((atMillis + 5) / 10 * 10))
