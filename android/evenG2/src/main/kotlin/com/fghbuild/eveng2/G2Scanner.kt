// G2Scanner: Android BLE scan that reports complete Even G2 pairs as both arms are heard.
//
// TODO(hardware): The scan has no ScanFilter because no source documents a service UUID in the
// G2 advertisement, and Android stops unfiltered scans while the screen is off. On a real
// pair, record the advertisement (nRF Connect) and filter on its service UUID or on the
// manufacturer ID G2Advertising.MANUFACTURER_ID if either is present.
@file:Suppress("MissingPermission") // Library: the consuming app holds BLUETOOTH_CONNECT and BLUETOOTH_SCAN.

package com.fghbuild.eveng2

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class G2Scanner(
    private val context: Context,
) {
    /**
     * Each pair once both arms are heard, and again when an arm's address changes. Scanning
     * runs while the flow is collected. An arm stops advertising while any host is connected.
     */
    fun pairs(): Flow<G2Pair> =
        callbackFlow {
            val scanner =
                context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
                    ?: throw G2Exception("BLE scanner is not available")
            val collector = G2PairCollector()
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        val record = result.scanRecord
                        val advertisement =
                            G2Advertising.parse(
                                record?.deviceName ?: result.device.name,
                                result.device.address,
                                record?.getManufacturerSpecificData(G2Advertising.MANUFACTURER_ID),
                            ) ?: return
                        synchronized(collector) { collector.add(advertisement) }?.let { trySend(it) }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        close(G2Exception("BLE scan failed with code $errorCode"))
                    }
                }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            bluetoothCall("startScan") { scanner.startScan(null, settings, callback) }
            awaitClose { scanner.stopScan(callback) }
        }
}
