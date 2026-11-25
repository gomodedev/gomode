// HaloConnection: BLE scanning, connection, bonding, MTU negotiation, service discovery, and GATT writes for Halo/Frame devices.
@file:Suppress("MissingPermission") // Library: permissions are the consuming app's responsibility.

package com.fghbuild.halo.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

open class HaloConnection(
    private val context: Context,
) {
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val adapter: BluetoothAdapter
        get() =
            bluetoothManager.adapter
                ?: throw HaloException("Bluetooth not available on this device")

    @Volatile
    private var activeGatt: BluetoothGatt? = null

    // The close cause of the device returned by the latest connect; replaced by each connect.
    @Volatile
    private var linkClosed = MutableStateFlow<HaloException?>(null)

    @Volatile
    private var currentGattCallback: BluetoothGattCallback? = null

    /** Negotiated MTU — stored after connection, used by payload limit calculations. */
    private var negotiatedMtu: Int = 23

    // ---- Scanning ----

    open fun scan(): Flow<HaloScannedDevice> =
        callbackFlow {
            val scanner =
                adapter.bluetoothLeScanner
                    ?: throw HaloException("BLE scanner not available")

            val filters =
                listOf(
                    ScanFilter.Builder().setServiceUuid(ParcelUuid(HaloServiceDiscovery.LUA_SERVICE)).build(),
                    ScanFilter.Builder().setServiceUuid(ParcelUuid(HaloServiceDiscovery.OTA_SERVICE)).build(),
                    ScanFilter.Builder().setServiceUuid(ParcelUuid(HaloServiceDiscovery.LEGACY_DFU_SERVICE)).build(),
                )

            val settings =
                ScanSettings
                    .Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        val name = result.device.name ?: return
                        val type =
                            when {
                                name.startsWith("Halo ") -> HaloDeviceType.HALO
                                name.startsWith("Frame ") -> HaloDeviceType.FRAME
                                else -> return
                            }
                        trySend(HaloScannedDevice(result.device, result.rssi, type))
                    }

                    override fun onScanFailed(errorCode: Int) {
                        close(HaloException("BLE scan failed with code $errorCode"))
                    }
                }

            scanner.startScan(filters, settings, callback)
            awaitClose {
                try {
                    scanner.stopScan(callback)
                } catch (_: Exception) {
                }
            }
        }

    // ---- Connection ----

    /**
     * Overridable for testing: create the GATT connection and wait until its link is up, without a
     * deadline of its own. [autoConnect] selects Android's background connection; see [connect]. A
     * GATT client that does not connect, by early disconnection or cancellation, is closed so
     * retries cannot leak clients.
     */
    protected open suspend fun connectGatt(
        device: BluetoothDevice,
        autoConnect: Boolean,
    ): BluetoothGatt =
        suspendCancellableCoroutine { cont ->
            val gatt: BluetoothGatt? =
                device.connectGatt(
                    context,
                    autoConnect,
                    object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(
                            g: BluetoothGatt,
                            status: Int,
                            newState: Int,
                        ) {
                            when (newState) {
                                BluetoothProfile.STATE_CONNECTED -> {
                                    if (cont.isActive) {
                                        activeGatt = g
                                        // Cancellation can still drop the result after this resume.
                                        cont.resume(g) { _, _, _ -> closeGatt(g) }
                                    }
                                }

                                BluetoothProfile.STATE_DISCONNECTED -> {
                                    if (cont.isActive) {
                                        closeGatt(g)
                                        cont.resumeWithException(
                                            HaloException("Disconnected during connection (status=$status)"),
                                        )
                                    } else if (g === activeGatt) {
                                        // A late callback from an earlier connection's GATT is not this link's.
                                        linkClosed.compareAndSet(
                                            null,
                                            HaloException("Device disconnected (status=$status)"),
                                        )
                                    }
                                }
                            }
                            currentGattCallback?.onConnectionStateChange(g, status, newState)
                        }

                        override fun onMtuChanged(
                            g: BluetoothGatt,
                            mtu: Int,
                            status: Int,
                        ) {
                            currentGattCallback?.onMtuChanged(g, mtu, status)
                        }

                        override fun onServicesDiscovered(
                            g: BluetoothGatt,
                            status: Int,
                        ) {
                            currentGattCallback?.onServicesDiscovered(g, status)
                        }

                        override fun onCharacteristicWrite(
                            g: BluetoothGatt,
                            c: BluetoothGattCharacteristic,
                            status: Int,
                        ) {
                            currentGattCallback?.onCharacteristicWrite(g, c, status)
                        }

                        override fun onCharacteristicChanged(
                            g: BluetoothGatt,
                            c: BluetoothGattCharacteristic,
                            value: ByteArray,
                        ) {
                            currentGattCallback?.onCharacteristicChanged(g, c, value)
                        }

                        // The callback deprecated in API 33, with its deprecated characteristic value.
                        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                        override fun onCharacteristicChanged(
                            g: BluetoothGatt,
                            c: BluetoothGattCharacteristic,
                        ) {
                            currentGattCallback?.onCharacteristicChanged(g, c, c.value)
                        }
                    },
                    BluetoothDevice.TRANSPORT_LE,
                )
            if (gatt == null) {
                cont.resumeWithException(HaloException("connectGatt returned null"))
            } else {
                // Runs on a direct connection's timeout too: withTimeoutOrNull cancels this coroutine.
                cont.invokeOnCancellation { closeGatt(gatt) }
            }
        }

    /** Overridable for testing: store the negotiated MTU. */
    protected open fun onMtuNegotiated(mtu: Int) {
        negotiatedMtu = mtu
    }

    private suspend fun requestMtu(
        gatt: BluetoothGatt,
        timeoutMs: Long,
    ) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                val previousCallback = currentGattCallback
                currentGattCallback =
                    object : BluetoothGattCallback() {
                        override fun onMtuChanged(
                            g: BluetoothGatt,
                            mtu: Int,
                            status: Int,
                        ) {
                            currentGattCallback = previousCallback
                            if (status == BluetoothGatt.GATT_SUCCESS) onMtuNegotiated(mtu)
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                cont.invokeOnCancellation { currentGattCallback = previousCallback }
                if (!gatt.requestMtu(517)) {
                    currentGattCallback = previousCallback
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        } ?: throw HaloException("MTU negotiation timed out after ${timeoutMs}ms")
    }

    /**
     * Connects to [device], a scan result's or one the system knows by address, and returns it ready
     * for Lua and typed messages. The returned device's [HaloDevice.closeCause] reports when the link
     * ends. One connection at a time: connecting again replaces the previous device's link reporting,
     * so [disconnect] first.
     *
     * A direct connection fails when the link is not up within [timeoutMs]; use it for a device that
     * was just scanned or chosen. A [background] connection is Android's autoConnect: the controller
     * adds the device to its LE accept list and connects when it next advertises, with no deadline,
     * at a lower scan duty cycle than a direct attempt. Use it to reconnect a device that went away.
     * The accept-list entry takes its address type, and the identity of a device with private
     * addresses, from the stack's record of the device; a bond keeps that record across Bluetooth
     * restarts. A background connection therefore suits a device this method connected before, which
     * requests the bond. Either way, the steps after the link is up each take at most [timeoutMs], and
     * cancellation releases the GATT client, which also removes a pending background connection.
     *
     * TODO(hardware): Unverified on a Halo: whether it advertises a public, static random, or
     * resolvable private address; whether its bond holds after the Halo restarts, so a background
     * connection still matches it; and how long a background connection takes once a Halo is turned
     * on. Log the address type of a scan result and time several reconnections after power cycles.
     */
    open suspend fun connect(
        device: BluetoothDevice,
        timeoutMs: Long = 15_000,
        background: Boolean = false,
    ): HaloDevice {
        linkClosed = MutableStateFlow(null)
        val gatt =
            if (background) {
                connectGatt(device, autoConnect = true)
            } else {
                withTimeoutOrNull(timeoutMs) { connectGatt(device, autoConnect = false) }
                    ?: throw HaloException("Connection timed out after ${timeoutMs}ms")
            }

        return try {
            gatt.device.createBond()
            try {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } catch (
                _: SecurityException,
            ) {
            }

            requestMtu(gatt, timeoutMs)
            enableServices(device, gatt, timeoutMs)
        } catch (e: CancellationException) {
            closeGatt(gatt)
            throw e
        } catch (e: HaloException) {
            closeGatt(gatt)
            throw e
        } catch (e: Exception) {
            closeGatt(gatt)
            throw HaloException("Connection failed: ${e.message}", e)
        }
    }

    // Releases a GATT client that will not carry this connection; Android keeps a small, shared pool.
    // A GATT client that already failed or closed has nothing left to release.
    private fun closeGatt(gatt: BluetoothGatt) {
        if (activeGatt === gatt) activeGatt = null
        try {
            gatt.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt.close()
        } catch (_: Exception) {
        }
    }

    /** Overridable for testing: create a HaloDevice from discovered services. */
    protected open fun createHaloDevice(
        platformDevice: BluetoothDevice,
        type: HaloDeviceType,
        txChar: BluetoothGattCharacteristic,
        rxChar: BluetoothGattCharacteristic,
        audioTxChar: BluetoothGattCharacteristic?,
        limits: HaloServiceDiscovery.PayloadLimits,
        notificationsFlow: Flow<ByteArray>,
        closeCause: StateFlow<HaloException?>,
    ): HaloDevice =
        HaloDevice(
            platformDevice = platformDevice,
            type = type,
            txChar = txChar,
            rxChar = rxChar,
            audioTxChar = audioTxChar,
            maxStringLen = limits.maxStringLen,
            maxDataLen = limits.maxDataLen,
            rawNotifications = notificationsFlow,
            closeCause = closeCause,
        )

    /** Overridable for testing: wire the writeSink to GATT writes. */
    protected open fun wireWriteSink(
        device: HaloDevice,
        gatt: BluetoothGatt,
    ) {
        @Suppress("DEPRECATION")
        device.writeSink = { write ->
            suspendCancellableCoroutine<Unit> { cont ->
                val char = write.characteristic
                char.setWriteType(write.writeType)
                char.setValue(write.data)

                val previousCallback = currentGattCallback
                currentGattCallback =
                    object : BluetoothGattCallback() {
                        override fun onCharacteristicWrite(
                            g: BluetoothGatt,
                            c: BluetoothGattCharacteristic,
                            status: Int,
                        ) {
                            currentGattCallback = previousCallback
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                if (cont.isActive) cont.resume(Unit)
                            } else {
                                if (cont.isActive) {
                                    cont.resumeWithException(
                                        HaloException("GATT write failed with status=$status"),
                                    )
                                }
                            }
                        }

                        override fun onCharacteristicChanged(
                            g: BluetoothGatt,
                            c: BluetoothGattCharacteristic,
                            value: ByteArray,
                        ) {
                            previousCallback?.onCharacteristicChanged(g, c, value)
                        }

                        override fun onConnectionStateChange(
                            g: BluetoothGatt,
                            status: Int,
                            newState: Int,
                        ) {
                            previousCallback?.onConnectionStateChange(g, status, newState)
                        }
                    }

                val success = gatt.writeCharacteristic(char)
                if (!success) {
                    currentGattCallback = previousCallback
                    if (cont.isActive) {
                        cont.resumeWithException(
                            HaloException("writeCharacteristic returned false"),
                        )
                    }
                }
            }
        }
    }

    private suspend fun enableServices(
        device: BluetoothDevice,
        gatt: BluetoothGatt,
        timeoutMs: Long,
    ): HaloDevice {
        val services =
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<List<android.bluetooth.BluetoothGattService>> { cont ->
                    currentGattCallback =
                        object : BluetoothGattCallback() {
                            override fun onServicesDiscovered(
                                g: BluetoothGatt,
                                status: Int,
                            ) {
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    cont.resume(g.services)
                                } else {
                                    cont.resumeWithException(
                                        HaloException("Service discovery failed with status=$status"),
                                    )
                                }
                            }

                            override fun onConnectionStateChange(
                                g: BluetoothGatt,
                                status: Int,
                                newState: Int,
                            ) {
                                if (newState ==
                                    BluetoothProfile.STATE_DISCONNECTED
                                ) { // errors surface through Flow
                                }
                            }
                        }
                    if (gatt.services.isEmpty()) {
                        if (!gatt.discoverServices()) {
                            cont.resumeWithException(HaloException("Service discovery initiation failed"))
                        }
                    } else {
                        cont.resume(gatt.services)
                    }
                }
            } ?: throw HaloException("Service discovery timed out after ${timeoutMs}ms")

        val luaService =
            HaloServiceDiscovery.findLuaService(services)
                ?: throw HaloException("Lua service not found on device")

        val deviceType = HaloServiceDiscovery.deviceType(luaService)
        val (txChar, rxChar, audioTxChar) = HaloServiceDiscovery.requiredCharacteristics(luaService)
        val limits = HaloServiceDiscovery.payloadLimits(negotiatedMtu, deviceType)

        // Enable RX notifications.
        val notificationsFlow: Flow<ByteArray> =
            callbackFlow {
                currentGattCallback =
                    object : BluetoothGattCallback() {
                        override fun onCharacteristicChanged(
                            g: BluetoothGatt,
                            c: BluetoothGattCharacteristic,
                            value: ByteArray,
                        ) {
                            if (c.uuid == HaloServiceDiscovery.LUA_RX_CHAR) trySend(value)
                        }

                        override fun onConnectionStateChange(
                            g: BluetoothGatt,
                            status: Int,
                            newState: Int,
                        ) {
                            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                close(HaloException("Device disconnected (status=$status)"))
                            }
                        }
                    }

                if (!gatt.setCharacteristicNotification(rxChar, true)) {
                    close(HaloException("Failed to enable RX notifications"))
                }
                awaitClose {
                    try {
                        gatt.setCharacteristicNotification(rxChar, false)
                    } catch (_: Exception) {
                    }
                }
            }

        val haloDevice =
            createHaloDevice(device, deviceType, txChar, rxChar, audioTxChar, limits, notificationsFlow, linkClosed)
        wireWriteSink(haloDevice, gatt)
        return haloDevice
    }

    // ---- Disconnect ----

    /** Ends the current link; its device's [HaloDevice.closeCause] reports the disconnect. */
    open suspend fun disconnect() {
        linkClosed.compareAndSet(null, HaloException("Disconnected by the host"))
        activeGatt?.let(::closeGatt)
        currentGattCallback = null
    }

    // ---- System-connected device query ----

    fun getSystemConnectedDevice(address: String): BluetoothDevice? =
        try {
            bluetoothManager.getConnectedDevices(BluetoothProfile.GATT).find { it.address == address }
        } catch (e: SecurityException) {
            null
        }
}
