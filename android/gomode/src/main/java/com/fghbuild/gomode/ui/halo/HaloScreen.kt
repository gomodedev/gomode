// Compose Halo screen: scans, connects, forgets, and sets auto-connect through the app-scoped GlassesOwner.
package com.fghbuild.gomode.ui.halo

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
internal fun HaloScreen(
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
                title = { Text("Halo") },
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
                    .testTag("gomode-halo-screen"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val savedAddress = state.savedAddress
            ListItem(
                headlineContent = { Text("Connection") },
                supportingContent = {
                    Text(state.link.label(), modifier = Modifier.testTag("gomode-halo-link"))
                },
                leadingContent = {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = state.link.tint())
                },
                trailingContent = {
                    if (state.link != GlassesLink.Disconnected) {
                        OutlinedButton(
                            onClick = owner::disconnect,
                            modifier = Modifier.testTag("gomode-halo-disconnect"),
                        ) {
                            Text("Disconnect")
                        }
                    } else if (savedAddress != null) {
                        Button(
                            onClick = { withBluetooth { owner.connect(savedAddress) } },
                            modifier = Modifier.testTag("gomode-halo-connect"),
                        ) {
                            Text("Connect")
                        }
                    }
                },
            )

            ListItem(
                headlineContent = { Text("Auto-connect") },
                supportingContent = { Text("Connect to the saved Halo when Go Mode opens.") },
                trailingContent = {
                    Switch(
                        checked = state.autoConnect,
                        onCheckedChange = owner::updateAutoConnect,
                        modifier = Modifier.testTag("gomode-halo-auto-connect"),
                    )
                },
            )

            if (savedAddress != null) {
                ListItem(
                    headlineContent = { Text("Saved device") },
                    supportingContent = { Text(savedAddress) },
                    trailingContent = {
                        TextButton(onClick = owner::forget, modifier = Modifier.testTag("gomode-halo-forget")) {
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
                    modifier = Modifier.testTag("gomode-halo-error"),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (state.scanning) {
                OutlinedButton(onClick = owner::stopScan, modifier = Modifier.testTag("gomode-halo-stop-scan")) {
                    Text("Stop scan")
                }
            } else {
                Button(
                    onClick = { withBluetooth(owner::startScan) },
                    modifier = Modifier.testTag("gomode-halo-scan"),
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
                    text = "No Halo devices found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.devices.forEach { device ->
                    HaloDeviceRow(
                        device = device,
                        // The saved glasses already have the connection, or are getting it.
                        linked = device.address == savedAddress && state.link != GlassesLink.Disconnected,
                        onConnect = { withBluetooth { owner.connect(device.address) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun HaloDeviceRow(
    device: ScannedGlasses,
    linked: Boolean,
    onConnect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(device.name, fontWeight = FontWeight.Medium) },
        supportingContent = { Text("${device.address} - ${device.rssi} dBm") },
        trailingContent = {
            Button(onClick = onConnect, enabled = !linked) {
                Text("Connect")
            }
        },
    )
}

@Composable
private fun GlassesLink.tint() =
    when (this) {
        GlassesLink.Connected -> MaterialTheme.colorScheme.primary
        is GlassesLink.Reconnecting -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun GlassesLink.label(): String =
    when (this) {
        GlassesLink.Disconnected -> {
            "Disconnected"
        }

        GlassesLink.Connecting -> {
            "Waiting for pairing or connection"
        }

        GlassesLink.Connected -> {
            "Connected"
        }

        is GlassesLink.Reconnecting -> {
            when (problem) {
                GlassesProblem.UNREACHABLE -> "Halo not reachable. Turn it on and keep it close. Retrying."
                GlassesProblem.UNRESPONSIVE -> "Halo stopped responding. Retrying."
            }
        }
    }
