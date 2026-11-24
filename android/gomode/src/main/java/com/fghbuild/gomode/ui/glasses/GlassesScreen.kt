// Compose glasses screen: scans for Halo and Even G2, connects, forgets, and sets auto-connect through GlassesOwner.
package com.fghbuild.gomode.ui.glasses

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fghbuild.gomode.data.GlassesDevice
import com.fghbuild.gomode.glasses.GlassesLink
import com.fghbuild.gomode.glasses.GlassesOwner
import com.fghbuild.gomode.glasses.GlassesProblem
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
            // The status takes the row's width, and its action sits below it, so a long retry reason
            // wraps without squeezing the action.
            ListItem(
                headlineContent = { Text("Connection") },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.link.label(saved), modifier = Modifier.testTag("gomode-glasses-link"))
                        if (state.link != GlassesLink.Disconnected) {
                            OutlinedButton(
                                onClick = owner::disconnect,
                                modifier = Modifier.testTag("gomode-glasses-disconnect"),
                            ) {
                                Text("Disconnect")
                            }
                        } else if (saved != null) {
                            Button(
                                onClick = { withBluetooth { owner.connect(saved) } },
                                modifier = Modifier.testTag("gomode-glasses-connect"),
                            ) {
                                Text("Connect")
                            }
                        }
                    }
                },
                leadingContent = {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = state.link.tint())
                },
            )

            ListItem(
                headlineContent = { Text("Auto-connect") },
                supportingContent = { Text("Connect to the saved glasses when Go Mode opens.") },
                trailingContent = {
                    Switch(
                        checked = state.autoConnect,
                        onCheckedChange = owner::updateAutoConnect,
                        modifier = Modifier.testTag("gomode-glasses-auto-connect"),
                    )
                },
            )

            if (saved != null) {
                ListItem(
                    headlineContent = { Text("Saved glasses") },
                    supportingContent = { Text(saved.label(), modifier = Modifier.testTag("gomode-glasses-saved")) },
                    trailingContent = {
                        TextButton(onClick = owner::forget, modifier = Modifier.testTag("gomode-glasses-forget")) {
                            Text("Forget")
                        }
                    },
                )
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

private fun GlassesDevice?.model(): String =
    when (this) {
        is GlassesDevice.Halo -> "Halo"
        is GlassesDevice.EvenG2 -> "Even G2"
        null -> "Glasses"
    }

@Composable
private fun GlassesLink.tint() =
    when (this) {
        is GlassesLink.Connected -> MaterialTheme.colorScheme.primary
        is GlassesLink.Reconnecting -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

// [saved] names the glasses the link is for: the owner saves the glasses it connects to.
private fun GlassesLink.label(saved: GlassesDevice?): String =
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
                GlassesProblem.UNREACHABLE -> "${saved.model()} not reachable. Turn it on and keep it close. Retrying."
                GlassesProblem.UNRESPONSIVE -> "${saved.model()} stopped responding. Retrying."
            }
        }
    }
