package com.lumais.blewfcsiram.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

enum class BleState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR }
enum class BeaconStatus(val value: Byte) { IDLE(0), RANGING(1), ERROR(2) }

class BleManager(
    private val context: Context,
    private val onStateChanged: (BleState) -> Unit,
    private val onBeaconStatus: (BeaconStatus) -> Unit,
    private val onMeasurementData: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit
) {
    companion object {
        const val CMD_START: Byte = 1
        const val CMD_STOP: Byte = 2

        val SERVICE_UUID: UUID = UUID.fromString("12345678-9ABC-DEF0-1234-56789ABCDEF0")
        val CHAR_COMMAND_UUID: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        val CHAR_STATUS_UUID: UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentState = BleState.DISCONNECTED
    private var lastBeaconStatus = BeaconStatus.IDLE

    // Accumulated measurement buffer while beacon is ranging
    private val measurementBuffer = mutableListOf<ByteArray>()
    private var isCollecting = false

    // ── Scan ──────────────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            onLog("Found device: ${device.address}")
            stopScan()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            onLog("Scan failed: $errorCode")
            setState(BleState.ERROR)
        }
    }

    private val scanTimeoutRunnable = Runnable {
        onLog("⏱ Scan timed out — no beacon found")
        stopScan()
        setState(BleState.DISCONNECTED)
    }

    fun startScan() {
        setState(BleState.SCANNING)
        measurementBuffer.clear()
        isCollecting = false

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        scanner?.stopScan(scanCallback)
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    private fun connectToDevice(device: BluetoothDevice) {
        setState(BleState.CONNECTING)
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    onLog("GATT connected, discovering services…")
                    g.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    cleanup()
                    setState(BleState.DISCONNECTED)
                }
                else -> {
                    onLog("GATT error: status=$status state=$newState")
                    cleanup()
                    setState(BleState.ERROR)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("Service discovery failed: $status")
                setState(BleState.ERROR)
                return
            }

            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                onLog("Target service not found!")
                setState(BleState.ERROR)
                return
            }

            commandChar = service.getCharacteristic(CHAR_COMMAND_UUID)
            statusChar = service.getCharacteristic(CHAR_STATUS_UUID)

            if (commandChar == null || statusChar == null) {
                onLog("Required characteristics not found!")
                setState(BleState.ERROR)
                return
            }

            // Subscribe to status notifications
            g.setCharacteristicNotification(statusChar, true)
            val descriptor = statusChar!!.getDescriptor(CCCD_UUID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }

            // Read current status
            g.readCharacteristic(statusChar)

            setState(BleState.CONNECTED)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHAR_STATUS_UUID) {
                @Suppress("DEPRECATION")
                handleStatusUpdate(characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHAR_STATUS_UUID) {
                handleStatusUpdate(value)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHAR_STATUS_UUID) {
                @Suppress("DEPRECATION")
                handleStatusUpdate(characteristic.value)
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHAR_STATUS_UUID) {
                handleStatusUpdate(value)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onLog("Command written successfully")
            } else {
                onLog("Command write failed: $status")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onLog("Notifications enabled for Status characteristic")
            } else {
                onLog("Failed to enable notifications: $status")
            }
        }
    }

    // ── Status handling ───────────────────────────────────────────────────────

    private fun handleStatusUpdate(value: ByteArray) {
        if (value.isEmpty()) return
        val status = when (value[0].toInt()) {
            0 -> BeaconStatus.IDLE
            1 -> BeaconStatus.RANGING
            2 -> BeaconStatus.ERROR
            else -> BeaconStatus.ERROR
        }

        // Detect transition from RANGING → IDLE/ERROR = measurement complete
        if (lastBeaconStatus == BeaconStatus.RANGING && status != BeaconStatus.RANGING && isCollecting) {
            finalizeMeasurement()
        }

        // Start collecting when ranging begins
        if (status == BeaconStatus.RANGING) {
            isCollecting = true
        }

        lastBeaconStatus = status
        onBeaconStatus(status)
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    fun sendCommand(cmd: Byte) {
        val g = gatt ?: run { onLog("Not connected"); return }
        val char = commandChar ?: run { onLog("Command char not ready"); return }

        if (cmd == CMD_STOP) {
            // We'll finalize once status changes; mark collecting off after stop
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            g.writeCharacteristic(char, byteArrayOf(cmd), writeType)
        } else {
            @Suppress("DEPRECATION")
            char.value = byteArrayOf(cmd)
            @Suppress("DEPRECATION")
            char.writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
    }

    // ── Measurement data ──────────────────────────────────────────────────────

    /**
     * Add a raw notification chunk to the buffer.
     * For a simple single-byte status characteristic, the "measurement data"
     * is the full notification history captured during a ranging session.
     */
    fun addMeasurementChunk(data: ByteArray) {
        if (isCollecting) measurementBuffer.add(data.clone())
    }

    private fun finalizeMeasurement() {
        if (measurementBuffer.isEmpty()) return
        // Concatenate all chunks into one byte array
        val total = measurementBuffer.sumOf { it.size }
        val combined = ByteArray(total)
        var offset = 0
        for (chunk in measurementBuffer) {
            chunk.copyInto(combined, offset)
            offset += chunk.size
        }
        measurementBuffer.clear()
        isCollecting = false
        onMeasurementData(combined)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun isConnected() = currentState == BleState.CONNECTED

    fun disconnect() {
        gatt?.disconnect()
    }

    fun close() {
        stopScan()
        gatt?.close()
        gatt = null
    }

    private fun cleanup() {
        commandChar = null
        statusChar = null
        gatt?.close()
        gatt = null
        if (isCollecting) finalizeMeasurement()
    }

    private fun setState(state: BleState) {
        currentState = state
        onStateChanged(state)
    }
}
