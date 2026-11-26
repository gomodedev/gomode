// Compose screen for trying glasses gestures: the latest inputs, mirrored on the glasses, with presses held.
package com.fghbuild.gomode.ui.glasses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.DoNotTouch
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.South
import androidx.compose.material.icons.filled.SwipeDown
import androidx.compose.material.icons.filled.SwipeUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fghbuild.gomode.glasses.GlassesGesture
import com.fghbuild.gomode.glasses.GlassesInput
import com.fghbuild.gomode.glasses.GlassesOwner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassesInputsScreen(
    owner: GlassesOwner,
    onNavigateBack: () -> Unit,
) {
    // Started, not composed: a screen left in the background must not keep presses from voice.
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            if (inputs.isEmpty()) {
                Text("No input yet. Try a gesture.", style = MaterialTheme.typography.bodyMedium)
            }
            inputs.forEach { InputRow(it) }
        }
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
        Icon(input.event.gesture.icon(), contentDescription = null)
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

private fun GlassesGesture.icon(): ImageVector =
    when (this) {
        GlassesGesture.TAP -> Icons.Filled.TouchApp
        GlassesGesture.DOUBLE_TAP -> Icons.Filled.AdsClick
        GlassesGesture.HOLD -> Icons.Filled.PanTool
        GlassesGesture.RELEASE -> Icons.Filled.DoNotTouch
        GlassesGesture.TAP_THEN_HOLD -> Icons.Filled.Gesture
        GlassesGesture.SCROLL_UP -> Icons.Filled.SwipeUp
        GlassesGesture.SCROLL_DOWN -> Icons.Filled.SwipeDown
        GlassesGesture.HEAD_UP -> Icons.Filled.North
        GlassesGesture.HEAD_DOWN -> Icons.Filled.South
        GlassesGesture.RING_TOUCH -> Icons.Filled.Adjust
        GlassesGesture.PAGE_SHOWN -> Icons.Filled.Visibility
        GlassesGesture.PAGE_HIDDEN -> Icons.Filled.VisibilityOff
        GlassesGesture.PAGE_CLOSED -> Icons.AutoMirrored.Filled.Logout
        GlassesGesture.OTHER -> Icons.Filled.QuestionMark
    }

// To the nearest 10 ms: one gesture sends several events within a few hundred milliseconds, and
// a BLE connection interval of 7.5 ms makes finer digits noise.
private val InputTime = DateTimeFormatter.ofPattern("HH:mm:ss.SS").withZone(ZoneId.systemDefault())

private fun inputTime(atMillis: Long): String = InputTime.format(Instant.ofEpochMilli((atMillis + 5) / 10 * 10))
