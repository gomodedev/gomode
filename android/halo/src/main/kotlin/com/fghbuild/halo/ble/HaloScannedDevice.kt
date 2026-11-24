// Scan result produced by HaloConnection.scan, carrying the platform BluetoothDevice, RSSI, and advertised type.
package com.fghbuild.halo.ble

import android.bluetooth.BluetoothDevice

data class HaloScannedDevice(
    val device: BluetoothDevice,
    val rssi: Int,
    /** The type the advertised name announces: "Halo …" or "Frame …". */
    val type: HaloDeviceType,
)
