// Compose glasses screen: scans for Halo and Even G2, connects, forgets, and sets auto-connect through GlassesOwner.
package com.fghbuild.gomode.ui.glasses

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fghbuild.gomode.R
import com.fghbuild.gomode.data.GlassesDevice
import com.fghbuild.gomode.glasses.GlassesBattery
import com.fghbuild.gomode.glasses.GlassesLink
import com.fghbuild.gomode.glasses.GlassesOwner
import com.fghbuild.gomode.glasses.GlassesProblem
import com.fghbuild.gomode.glasses.GlassesState
import com.fghbuild.gomode.glasses.ScannedGlasses

private val BluetoothPermissions =
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassesScreen(
    owner: GlassesOwner,
    onNavigateBack: () -> Unit,
    onOpenInputs: () -> Unit,
) {
    val state by owner.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The action that waits for the Bluetooth permission prompt; it runs only when both are granted.
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            if (BluetoothPermissions.all { grants[it] == true }) pendingAction?.invoke()
            pendingAction = null
        }
    val hasBluetoothPermissions =
        BluetoothPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    val withBluetooth = { action: () -> Unit ->
        if (hasBluetoothPermissions) {
            action()
        } else if (!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            pendingAction = action
            permissionLauncher.launch(BluetoothPermissions)
        }
    }

    BackHandler(onBack = onNavigateBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Glasses") },
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
                    .testTag("gomode-glasses-screen"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val saved = state.saved
            if (saved != null) {
                SavedGlassesRow(
                    saved = saved,
                    state = state,
                    owner = owner,
                    onConnect = { withBluetooth { owner.connect(saved) } },
                    onOpenInputs = onOpenInputs,
                )
            }

            ListItem(
                supportingContent = { Text("Connect to the saved glasses now and whenever Go Mode opens.") },
                trailingContent = {
                    Switch(
                        checked = state.autoConnect,
                        onCheckedChange = owner::updateAutoConnect,
                        modifier = Modifier.testTag("gomode-glasses-auto-connect"),
                    )
                },
            ) {
                Text("Auto-connect")
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("gomode-glasses-error"),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (state.scanning) {
                OutlinedButton(onClick = owner::stopScan, modifier = Modifier.testTag("gomode-glasses-stop-scan")) {
                    Text("Stop scan")
                }
            } else {
                Button(
                    onClick = { withBluetooth(owner::startScan) },
                    modifier = Modifier.testTag("gomode-glasses-scan"),
                ) {
                    Text("Scan")
                }
            }

            if (!hasBluetoothPermissions) {
                Text(
                    text = "Bluetooth permissions are required to scan and connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Devices", style = MaterialTheme.typography.titleMedium)
            if (state.devices.isEmpty()) {
                Text(
                    text = "No Halo or Even G2 found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.devices.forEach { scanned ->
                GlassesRow(
                    scanned = scanned,
                    // The saved glasses already have the connection, or are getting it.
                    linked = scanned.device == saved && state.link != GlassesLink.Disconnected,
                    onConnect = { withBluetooth { owner.connect(scanned.device) } },
                )
            }
            if (state.devices.none { it.device is GlassesDevice.EvenG2 }) {
                // G2Scanner: an arm stops advertising while any host is connected to it.
                Text(
                    text = "Even G2 appears only while no other app is connected to it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("gomode-glasses-g2-hint"),
                )
            }
        }
    }
}

// The saved glasses: a dot for their link, the model as the headline, and their actions as icon
// buttons centred on that line. Below, from the headline's start across the row's full width, so
// nothing squeezes beside the buttons: the identity, such as a 14-character Even G2 serial, on one
// line that ends with "…" when it cannot fit; a link status the user can act on; and the batteries.
@Composable
private fun SavedGlassesRow(
    saved: GlassesDevice,
    state: GlassesState,
    owner: GlassesOwner,
    onConnect: () -> Unit,
    onOpenInputs: () -> Unit,
) {
    Column {
        ListItem(
            leadingContent = { LinkDot(state.link) },
            trailingContent = {
                Row {
                    LabeledIconButton(
                        R.drawable.ic_touch_app,
                        "Input events",
                        "gomode-glasses-open-inputs",
                        onOpenInputs,
                    )
                    if (state.link == GlassesLink.Disconnected) {
                        LabeledIconButton(R.drawable.ic_link, "Connect", "gomode-glasses-connect", onConnect)
                    } else {
                        LabeledIconButton(
                            R.drawable.ic_link_off,
                            "Disconnect",
                            "gomode-glasses-disconnect",
                            owner::disconnect,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    LabeledIconButton(R.drawable.ic_delete_filled, "Forget", "gomode-glasses-forget", owner::forget)
                }
            },
        ) {
            Text(saved.model())
        }
        val status = state.link.status()
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    // Into the list item's bottom padding, so the lines read as part of the item.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val lift = LIST_ITEM_PADDING.roundToPx()
                        layout(placeable.width, placeable.height - lift) { placeable.place(0, -lift) }
                    }
                    // The screen's own padding ends the lines.
                    .padding(start = HEADLINE_START, bottom = LIST_ITEM_PADDING),
        ) {
            val enabled = state.link is GlassesLink.Connected || state.logoPinned
            val label = saved.identity()
            // A double tap shows or hides the Go Mode logo on the glasses; accessibility services get
            // it as the name's click action.
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                // A serial of capitals and digits reads without the body style's letter spacing,
                // which would cost it the room of more than one character.
                letterSpacing = 0.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .testTag("gomode-glasses-saved")
                        .clearAndSetSemantics {
                            text = AnnotatedString(label)
                            if (enabled) {
                                onClick(label = if (state.logoPinned) "Hide logo" else "Show logo") {
                                    owner.toggleLogo()
                                    true
                                }
                            }
                        }.combinedClickable(
                            enabled = enabled,
                            onClick = {},
                            onDoubleClick = owner::toggleLogo,
                        ),
            )
            if (status != null) {
                Text(
                    status,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("gomode-glasses-link"),
                )
            }
            if (state.batteries.isNotEmpty()) GlassesBatteries(state.batteries)
        }
    }
}

// Green while connected and red otherwise, with the state in words for screen readers.
@Composable
private fun LinkDot(link: GlassesLink) {
    val color =
        when {
            link !is GlassesLink.Connected -> MaterialTheme.colorScheme.error
            isSystemInDarkTheme() -> DARK_CONNECTED
            else -> LIGHT_CONNECTED
        }
    val description = link.spoken()
    Box(
        Modifier
            .size(STATUS_DOT)
            .background(color, CircleShape)
            .testTag("gomode-glasses-link-state")
            .semantics { contentDescription = description },
    )
}

// At least 4.5:1 against the light and dark paper.
private val LIGHT_CONNECTED = Color(0xFF1E7F3C)
private val DARK_CONNECTED = Color(0xFF6FD48E)

private val STATUS_DOT = 12.dp

// The vertical padding of a Material list item.
private val LIST_ITEM_PADDING = 8.dp

// Where a Material list item's headline starts after the status dot: 16 dp padding, the dot, 16 dp.
private val HEADLINE_START = 16.dp + STATUS_DOT + 16.dp

// Each battery as its part and level, such as "Glasses 80%", after a bolt while it charges. A reading
// never breaks; the readings wrap between each other. A screen reader hears them all at once in words.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlassesBatteries(batteries: List<GlassesBattery>) {
    val description = batteries.joinToString(". ") { it.description }
    val style = MaterialTheme.typography.bodyMedium
    // The bolt is as large as the text beside it.
    val bolt = with(LocalDensity.current) { style.fontSize.toDp() }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .testTag("gomode-glasses-battery")
                .clearAndSetSemantics { contentDescription = description },
    ) {
        batteries.forEach { battery ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (battery.charging) {
                    Icon(
                        painterResource(R.drawable.ic_bolt),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(bolt),
                    )
                }
                Text(
                    "${battery.part.label} ${battery.percent}%",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = style,
                    softWrap = false,
                    maxLines = 1,
                )
            }
        }
    }
}

// An icon button that [label] names for accessibility and, on a long press, in a tooltip.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabeledIconButton(
    @DrawableRes iconRes: Int,
    label: String,
    tag: String,
    onClick: () -> Unit,
    tint: Color = LocalContentColor.current,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
            Icon(painterResource(iconRes), contentDescription = label, tint = tint)
        }
    }
}

@Composable
private fun GlassesRow(
    scanned: ScannedGlasses,
    linked: Boolean,
    onConnect: () -> Unit,
) {
    val detail =
        when (val device = scanned.device) {
            is GlassesDevice.Halo -> "${device.address} · ${scanned.rssi} dBm"

            // One arm per line: two addresses do not fit beside the button on one line.
            is GlassesDevice.EvenG2 -> "Left ${device.pair.left.address}\nRight ${device.pair.right.address}"
        }
    ListItem(
        modifier = Modifier.testTag("gomode-glasses-found"),
        supportingContent = { Text(detail) },
        trailingContent = {
            Button(onClick = onConnect, enabled = !linked) {
                Text("Connect")
            }
        },
    ) {
        Text(scanned.name, fontWeight = FontWeight.Medium)
    }
}

/** The model and identity of [this], as the glasses screen and settings name saved glasses. */
internal fun GlassesDevice.label(): String =
    when (this) {
        is GlassesDevice.Halo -> "Halo · $address"
        is GlassesDevice.EvenG2 -> "Even G2 ${pair.id}"
    }

// What tells saved glasses apart from others of their model.
private fun GlassesDevice.identity(): String =
    when (this) {
        is GlassesDevice.Halo -> address
        is GlassesDevice.EvenG2 -> pair.id
    }

private fun GlassesDevice.model(): String =
    when (this) {
        is GlassesDevice.Halo -> "Halo"
        is GlassesDevice.EvenG2 -> "Even G2"
    }

// The link as a screen reader says it.
private fun GlassesLink.spoken(): String =
    when (this) {
        GlassesLink.Disconnected -> "Not connected"
        GlassesLink.Connecting -> "Connecting"
        is GlassesLink.Connected -> "Connected"
        is GlassesLink.Reconnecting -> "Reconnecting"
    }

// What the user can do about the link, or null when the dot and the buttons say it all.
private fun GlassesLink.status(): String? =
    when (this) {
        GlassesLink.Disconnected, is GlassesLink.Connected -> {
            null
        }

        GlassesLink.Connecting -> {
            "Waiting for pairing or connection"
        }

        is GlassesLink.Reconnecting -> {
            when (problem) {
                GlassesProblem.UNREACHABLE -> "Not reachable. Turn them on and keep them close. Retrying."
                GlassesProblem.UNRESPONSIVE -> "Stopped responding. Retrying."
            }
        }
    }
