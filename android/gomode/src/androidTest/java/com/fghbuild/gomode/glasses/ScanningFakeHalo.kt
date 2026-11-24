// A fake Halo transport whose scan reports one Halo, since the emulator has no glasses to scan.
package com.fghbuild.gomode.glasses

import android.bluetooth.BluetoothManager
import android.content.Context
import com.fghbuild.halo.ble.HaloDeviceType
import com.fghbuild.halo.ble.HaloScannedDevice
import com.fghbuild.halo.testing.FakeHaloTransport
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class ScanningFakeHalo(
    context: Context,
    private val address: String,
) : FakeHaloTransport(context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    override fun scan(): Flow<HaloScannedDevice> =
        flow {
            emit(HaloScannedDevice(adapter.getRemoteDevice(address), rssi = -50, HaloDeviceType.HALO))
            awaitCancellation()
        }
}
