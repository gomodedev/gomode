// Compose screen for trying glasses gestures: the latest inputs, mirrored on the glasses, with actions held.
package com.fghbuild.gomode.ui.glasses

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fghbuild.gomode.R
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

// To the nearest 10 ms: one gesture sends several events within a few hundred milliseconds, and
// a BLE connection interval of 7.5 ms makes finer digits noise.
private val InputTime = DateTimeFormatter.ofPattern("HH:mm:ss.SS").withZone(ZoneId.systemDefault())

private fun inputTime(atMillis: Long): String = InputTime.format(Instant.ofEpochMilli((atMillis + 5) / 10 * 10))
