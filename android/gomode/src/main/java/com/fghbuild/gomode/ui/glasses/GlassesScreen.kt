// Compose glasses screen: scans for Halo and Even G2, connects, forgets, and sets auto-connect through GlassesOwner.
package com.fghbuild.gomode.ui.glasses

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fghbuild.gomode.data.GlassesDevice
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
                headlineContent = { Text("Auto-connect") },
                supportingContent = { Text("Connect to the saved glasses now and whenever Go Mode opens.") },
                trailingContent = {
                    Switch(
                        checked = state.autoConnect,
                        onCheckedChange = owner::updateAutoConnect,
                        modifier = Modifier.testTag("gomode-glasses-auto-connect"),
                    )
                },
            )

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

// The saved glasses, their link, and their actions as icon buttons on the right. The model sits
// above the identity so the identity stays on one line; the status wraps beside the buttons, so a
// long retry reason never pushes them off the row.
@Composable
private fun SavedGlassesRow(
    saved: GlassesDevice,
    state: GlassesState,
    owner: GlassesOwner,
    onConnect: () -> Unit,
    onOpenInputs: () -> Unit,
) {
    ListItem(
        overlineContent = { Text(saved.model()) },
        headlineContent = {
            val enabled = state.link is GlassesLink.Connected || state.logoPinned
            val label = saved.identity()
            // A double tap shows or hides the Go Mode logo on the glasses; accessibility services get
            // it as the name's click action.
            Text(
                label,
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
        },
        supportingContent = {
            Text(state.link.label(), modifier = Modifier.testTag("gomode-glasses-link"))
        },
        leadingContent = {
            Icon(state.link.icon(), contentDescription = null, tint = state.link.tint())
        },
        trailingContent = {
            Row {
                LabeledIconButton(Icons.Filled.TouchApp, "Input events", "gomode-glasses-open-inputs", onOpenInputs)
                if (state.link == GlassesLink.Disconnected) {
                    LabeledIconButton(Icons.Filled.Link, "Connect", "gomode-glasses-connect", onConnect)
                } else {
                    LabeledIconButton(
                        Icons.Filled.LinkOff,
                        "Disconnect",
                        "gomode-glasses-disconnect",
                        owner::disconnect,
                    )
                }
                LabeledIconButton(Icons.Filled.Delete, "Forget", "gomode-glasses-forget", owner::forget)
            }
        },
    )
}

// An icon button that [label] names for accessibility and, on a long press, in a tooltip.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabeledIconButton(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
            Icon(icon, contentDescription = label)
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
        headlineContent = { Text(scanned.name, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(detail) },
        trailingContent = {
            Button(onClick = onConnect, enabled = !linked) {
                Text("Connect")
            }
        },
    )
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

private fun GlassesLink.icon(): ImageVector =
    when (this) {
        is GlassesLink.Connected -> Icons.Filled.BluetoothConnected
        GlassesLink.Disconnected -> Icons.Filled.Bluetooth
        else -> Icons.AutoMirrored.Filled.BluetoothSearching
    }

@Composable
private fun GlassesLink.tint() =
    when (this) {
        is GlassesLink.Connected -> MaterialTheme.colorScheme.primary
        is GlassesLink.Reconnecting -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

// The model above it names the glasses the link is for: the owner saves the glasses it connects to.
private fun GlassesLink.label(): String =
    when (this) {
        GlassesLink.Disconnected -> {
            "Disconnected"
        }

        GlassesLink.Connecting -> {
            "Waiting for pairing or connection"
        }

        is GlassesLink.Connected -> {
            "Connected"
        }

        is GlassesLink.Reconnecting -> {
            when (problem) {
                GlassesProblem.UNREACHABLE -> "Not reachable. Turn them on and keep them close. Retrying."
                GlassesProblem.UNRESPONSIVE -> "Stopped responding. Retrying."
            }
        }
    }
