package com.lumais.blewfcsiram.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import java.nio.ByteBuffer
import java.util.UUID

enum class BleState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR }
enum class BeaconStatus(val value: Byte) { IDLE(0), RANGING(1), ERROR(2) }

fun UUID.reverseEndianness(): UUID {
    // 1. Extract the 16 bytes from the standard UUID structure
    val buffer = ByteBuffer.allocate(16)
    buffer.putLong(this.mostSignificantBits)
    buffer.putLong(this.leastSignificantBits)
    val bytes = buffer.array()

    // 2. Reverse the entire array completely (byte-by-byte inversion)
    bytes.reverse()

    // 3. Wrap it back up into a new Java UUID object
    val reversedBuffer = ByteBuffer.wrap(bytes)
    return UUID(reversedBuffer.long, reversedBuffer.long)
}

class BleManager(
    private val context: Context,
    private val onStateChanged: (BleState) -> Unit,
    private val onBeaconStatus: (BeaconStatus) -> Unit,
    private val onMeasurementData: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit
) {
    companion object {
        const val CMD_START: Byte = 1
        const val CMD_STOP: Byte  = 2

        // ── UUID byte order ────────────────────────────────────────────
        // UUID.fromString() stores the string representation canonically.
        // Android's ParcelUuid / ScanFilter compare using the standard Java UUID
        // equals(), so the string form "12345678-9ABC-DEF0-1234-56789ABCDEF0"
        // is correct for service discovery and ScanFilter.
        //
        // The little-endian wire bytes listed in the spec:
        //   F0 DE BC 9A 78 56 34 12 F0 DE BC 9A 78 56 34 12
        val SERVICE_UUID:      UUID = UUID.fromString("12345678-9ABC-DEF0-1234-56789ABCDEF0")
        // ── Characteristic UUIDs ───────────────────────────────────────
        // 0xFF01 = CMD  (Write / Write-No-Response)
        // 0xFF02 = Execution STATUS and log fetching (Read / Notify / Write) 
        // 0xFF03 = Detailed logs switching CONFIG (fill memory within several sec)
        // All three use the Bluetooth SIG base UUID:
        //   0000XXXX-0000-1000-8000-00805F9B34FB
        val CHAR_COMMAND_UUID: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        val CHAR_STATUS_UUID:  UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
        val CHAR_CONFIG_UUID:  UUID = UUID.fromString("0000FF03-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID:         UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val SCAN_TIMEOUT_MS   = 15_000L  // ms
        private const val CONNECT_TIMEOUT_MS = 8_000L  // give up & retry after 8 s
        private const val MAX_CONNECT_RETRIES = 3
        // ── MTU ────────────────────────────────────────────────────────
        // Request 185 bytes — comfortably above the 23-byte BLE minimum and
        // large enough to carry a full 24-byte CSIB record in one packet.
        private const val REQUESTED_MTU = 247  // 185

        // ── GATT status code reference (for logcat diagnosis) ─────────────────
        // 0   GATT_SUCCESS
        // 8   GATT_CONN_TIMEOUT          peripheral did not respond to connection request
        // 19  GATT_CONN_TERMINATE_PEER   peripheral closed the link
        // 22  GATT_CONN_TERMINATE_LOCAL  we closed it
        // 34  GATT_CONN_FAIL_ESTABLISH   link-layer failure (too far, interference)
        // 133 GATT_ERROR / BT_HCI_ERR_LMP_RESPONSE_TIMEOUT — most common on Android;
        //     usually caused by:
        //       a) stale GATT cache from a previous bonding / address mismatch
        //       b) connectGatt called from a non-main thread
        //       c) scanner not fully stopped before connectGatt
        //       d) BluetoothDevice object obtained from a stale scan result
        fun gattStatusName(status: Int) = when (status) {
            0   -> "SUCCESS"
            8   -> "CONN_TIMEOUT"
            19  -> "TERMINATE_PEER"
            22  -> "TERMINATE_LOCAL"
            34  -> "FAIL_ESTABLISH"
            133 -> "GATT_ERROR(133) — likely stale cache or thread issue"
            else -> "UNKNOWN($status)"
        }
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner
    private val mainHandler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var statusChar:  BluetoothGattCharacteristic? = null
    private var configChar:  BluetoothGattCharacteristic? = null

    private var currentState     = BleState.DISCONNECTED
    private var lastBeaconStatus = BeaconStatus.IDLE
    private var connectRetries   = 0
    private var pendingDevice: BluetoothDevice? = null   // kept for retry

    private val measurementBuffer = mutableListOf<ByteArray>()
    private var isCollecting = false

    // ── Connect-timeout watchdog ──────────────────────────────────────────────
    // Android's connectGatt can hang silently at status 133 without ever
    // calling onConnectionStateChange.  We force a close + retry after a timeout.
    private val connectTimeoutRunnable = Runnable {
        onLog("⏱ Connection attempt timed out (no callback in ${CONNECT_TIMEOUT_MS}ms)")
        retryOrFail()
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    private val scanTimeoutRunnable = Runnable {
        onLog("⏱ Scan timed out — beacon not found")
        stopScan()
        setState(BleState.DISCONNECTED)
    }

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // ── FIX A: stop scan *before* calling connectGatt ─────────────────
            // Keeping the scanner running while connecting has been shown to
            // cause status=133 failures on many Android versions.  Stop it first,
            // wait one UI-thread cycle, then connect.
            stopScan()
            pendingDevice = result.device
            connectRetries = 0
            // Retrieve the device name with a fallback if it is null or blank
            val deviceName = result.device.name?.takeIf { it.isNotBlank() } ?: "N/A"
            onLog("📶 Found: ${deviceName}: ${result.device.address}  RSSI=${result.rssi} dBm")
            onLog("   adv flags: ${result.scanRecord?.advertiseFlags}  " +
                  "serviceUuids: ${result.scanRecord?.serviceUuids}")

            // ── FIX B: connectGatt MUST be called from the main thread ────────
            // Calling it from the scanner callback thread (which is NOT the main
            // thread) causes intermittent 133 errors on Android 8–13.
            mainHandler.post { connectToDevice(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED          -> "ALREADY_STARTED"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REG_FAILED"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR            -> "INTERNAL_ERROR"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED       -> "UNSUPPORTED"
                else -> "code=$errorCode"
            }
            onLog("❌ Scan failed: $reason")
            setState(BleState.ERROR)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        connectRetries = 0
        pendingDevice  = null
        setState(BleState.SCANNING)
        measurementBuffer.clear()
        isCollecting = false

        // ── FIX C: scan WITHOUT a service-UUID filter first ───────────────────
        // Many ESP32 firmwares advertise the 128-bit UUID only in the scan
        // response packet, not in the primary advertisement.  Android's
        // ScanFilter inspects both packets on some versions but only the primary
        // on others, so the filtered scan may silently miss the beacon.
        //
        // Strategy: use NO filter — match on device name or address in the
        // callback, or just take the first result if the beacon is the only
        // nearby BLE device.  Swap in the filtered version once you have
        // confirmed the beacon is visible.
        //
        // To re-enable filtered scanning, uncomment the block below and replace
        // the null in startScan() with listOf(filter).

        val filter = ScanFilter.Builder()
//            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .setDeviceName("CSI-Ranging")
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()

        onLog("🔍 Scanning …")
//        if (ActivityCompat.checkSelfPermission(
//                this,
//                Manifest.permission.BLUETOOTH_SCAN
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            // TODO: Consider calling
//            //    ActivityCompat#requestPermissions
//            // here to request the missing permissions, and then overriding
//            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
//            //                                          int[] grantResults)
//            // to handle the case where the user grants the permission. See the documentation
//            // for ActivityCompat#requestPermissions for more details.
//            return
//        }
        // Check with the active filtering
        scanner?.startScan(listOf(filter)  // null
            , settings, scanCallback)
            ?: onLog("❌ BluetoothLeScanner null — Bluetooth off?")

        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(device: BluetoothDevice) {
        // ── FIX D: refresh the BluetoothDevice from the adapter ──────────────
        // The device object from a ScanResult can carry stale state if the
        // adapter has been toggled or if there was a previous failed GATT
        // session.  Fetching it fresh from getRemoteDevice() avoids this.
        val freshDevice = bluetoothAdapter?.getRemoteDevice(device.address) ?: device

        setState(BleState.CONNECTING)
        onLog("⏳ Connecting to ${freshDevice.address} (attempt ${connectRetries + 1}/$MAX_CONNECT_RETRIES)")

        // Arm connect-timeout watchdog
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)

        // ── FIX E: always pass TRANSPORT_LE explicitly ────────────────────────
        // Without TRANSPORT_LE the stack may attempt BR/EDR first on dual-mode
        // phones, wasting time and occasionally producing status=133.
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            freshDevice.connectGatt(
                context,
                false,              // autoConnect=false - direct connection, faster
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } else {
            freshDevice.connectGatt(context, false, gattCallback)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun retryOrFail() {
        // Close any zombie GATT session before retrying
        gatt?.close()
        gatt = null

        if (connectRetries < MAX_CONNECT_RETRIES - 1) {
            connectRetries++
            onLog("🔄 Retrying connection (${connectRetries + 1}/$MAX_CONNECT_RETRIES) " +
                  "— pausing 1 s to let stack settle")
            // ── FIX F: short delay between retries ───────────────────────────
            // The Android BLE stack sometimes keeps an internal connection
            // attempt alive for a few hundred ms after close().  A 1-second
            // pause prevents the next connectGatt from racing with it.
            mainHandler.postDelayed({
                pendingDevice?.let { connectToDevice(it) }
                    ?: run { onLog("❌ No device to retry"); setState(BleState.ERROR) }
            }, 1_000)
        } else {
            onLog("❌ All $MAX_CONNECT_RETRIES attempts failed")
            setState(BleState.ERROR)
        }
    }

    // ── GATT callback ─────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        // Step 1 — connection established; request MTU before service discovery
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // Disarm watchdog — we got a callback
            mainHandler.removeCallbacks(connectTimeoutRunnable)

            val statusName = gattStatusName(status)
            val stateName  = when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING   -> "CONNECTING"
                else -> "state=$newState"
            }
            onLog("🔗 onConnectionStateChange: status=$statusName  newState=$stateName")

            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    onLog("✅ GATT connected — requesting MTU $REQUESTED_MTU")
                    // ── FIX G: requestMtu before discoverServices ─────────────
                    // Ensures the stack knows the PDU size before any reads/writes.
                    // discoverServices is called from onMtuChanged.
                    g.requestMtu(REQUESTED_MTU)
                }

                newState == BluetoothProfile.STATE_DISCONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    onLog("🔌 Clean disconnect")
                    cleanup()
                    setState(BleState.DISCONNECTED)
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    // status != SUCCESS means the connection was lost abnormally
                    onLog("⚠ Disconnected with error: $statusName")
                    cleanup()
                    // status=133: GATT_ERROR or HCI_ERR_LMP_RESPONSE_TIMEOUT; very common; on disconnect after connect attempt = typical
                    // status=8: GATT_CONN_TIMEOUT
                    // status=19: GATT_CONN_TERMINATE_PEER_USER (peer closed)
                    // "failed to establish" — retry automatically
                    if (status == 133 || status == 8 || status == 34) {
                        retryOrFail()
                    } else {
                        setState(BleState.ERROR)
                    }
                }

                else -> {
                    onLog("⚠ Unexpected GATT state: status=$statusName newState=$stateName")
                    cleanup()
                    retryOrFail()
                }
            }
        }

        // Step 2 — MTU result; now safe to discover services
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onLog("📐 MTU=$mtu bytes")
            } else {
                onLog("⚠ MTU negotiation failed (status=$status) — using default 23 B")
            }
            // Proceed with service discovery regardless
            onLog("🔎 Discovering services…")
            g.discoverServices()
        }

        // Step 3 — services available; resolve characteristics
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("❌ Service discovery failed: status=${gattStatusName(status)}")
                // ── FIX H: stale GATT cache can report 0 services or wrong UUIDs
                // If this fires, run:  adb shell pm clear com.lumais.blewfcsiram
                // or toggle Bluetooth off/on to clear the host-side cache.
                // On Android 8+ you can also call g.refresh() via reflection (see below).
                refreshGattCache(g)
                setState(BleState.ERROR)
                return
            }

            // Diagnostic dump — compare with nRF Connect output
            onLog("── Discovered ${g.services.size} service(s) ──")
            g.services.forEach { svc ->
                val match = if (svc.uuid == SERVICE_UUID.reverseEndianness()) " ← TARGET" else ""
                onLog("  SVC ${svc.uuid}$match")
                svc.characteristics.forEach { ch ->
                    val props = buildString {
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0)             append("READ ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)            append("WRITE ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("WRITE_NR ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)           append("NOTIFY ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)         append("INDICATE ")
                    }.trim()
                    onLog("    CHAR ${ch.uuid}  [$props]")
                }
            }

            val service = g.getService(SERVICE_UUID.reverseEndianness())
            if (service == null) {
                onLog("❌ Service $SERVICE_UUID not in list above — UUID mismatch or cache issue")
                onLog("   → Try toggling Bluetooth or clearing app storage to flush GATT cache")
                refreshGattCache(g)
                setState(BleState.ERROR)
                return
            }

            // ── Resolve all three characteristics ──────────────────
            commandChar = service.getCharacteristic(CHAR_COMMAND_UUID)
            statusChar  = service.getCharacteristic(CHAR_STATUS_UUID)
            configChar  = service.getCharacteristic(CHAR_CONFIG_UUID)

            onLog("CMD  (FF01): ${if (commandChar != null) "✅" else "❌ missing"}")
            onLog("STAT (FF02): ${if (statusChar  != null) "✅" else "❌ missing"}")
            onLog("CFG  (FF03): ${if (configChar  != null) "✅" else "⚠ absent (optional)"}")

            if (commandChar == null || statusChar == null) {
                onLog("❌ Required characteristics missing")
                setState(BleState.ERROR)
                return
            }

            // ── CCCD subscription ──────────────────────────────────
            // Enable local notification routing
            g.setCharacteristicNotification(statusChar, true)

            // Write CCCD on the peripheral (sequenced via onDescriptorWrite)
            // to actually start sending notifications over the air
            val cccd = statusChar!!.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                onLog("⚠ CCCD descriptor absent — polling only")
                // Still try to read the initial value
                g.readCharacteristic(statusChar)
                return
            }

            val enableBytes = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enableBytes)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enableBytes
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    onLog("🔔 Notifications enabled (FF02 CCCD written)")
                } else {
                    onLog("⚠ CCCD write failed: status=${gattStatusName(status)}")
                }
                // Initial status read — sequenced after CCCD write completes
                g.readCharacteristic(statusChar)
            }
        }

        // Step 5 — initial read result, so signal CONNECTED to UI
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == CHAR_STATUS_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    handleStatusUpdate(characteristic.value)
                }
                setState(BleState.CONNECTED)
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid == CHAR_STATUS_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) handleStatusUpdate(value)
                setState(BleState.CONNECTED)
            }
        }

        // Ongoing STATUS notifications
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
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
            if (characteristic.uuid == CHAR_STATUS_UUID) handleStatusUpdate(value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val name = when (characteristic.uuid) {
                CHAR_COMMAND_UUID -> "CMD(FF01)"
                CHAR_CONFIG_UUID  -> "CFG(FF03)"
                else -> characteristic.uuid.toString()
            }
            if (status == BluetoothGatt.GATT_SUCCESS) onLog("✅ Write OK → $name")
            else onLog("❌ Write failed → $name  status=${gattStatusName(status)}")
        }
    }

    // ── GATT cache refresh (reflection) ───────────────────────────────────────
    // Android caches GATT service tables across connections.  If the firmware
    // was updated or the bond was cleared on the peripheral side, the cache
    // can contain stale data causing onServicesDiscovered to return wrong UUIDs
    // or status 129/133.  BluetoothGatt.refresh() flushes the cache; it is a
    // hidden API accessible via reflection.
    private fun refreshGattCache(g: BluetoothGatt) {
        try {
            val refresh = g.javaClass.getMethod("refresh")
            val result  = refresh.invoke(g) as? Boolean
            onLog("🧹 GATT cache refresh: $result")
        } catch (e: Exception) {
            onLog("⚠ GATT cache refresh unavailable: ${e.message}")
        }
    }

    // ── Status handling ───────────────────────────────────────────────────────

    private fun handleStatusUpdate(value: ByteArray) {
        if (value.isEmpty()) return
        val raw = value[0].toInt() and 0xFF
        val status = when (raw) {
            0    -> BeaconStatus.IDLE
            1    -> BeaconStatus.RANGING
            2    -> BeaconStatus.ERROR
            else -> { onLog("⚠ Unknown status byte: 0x${raw.toString(16)}"); BeaconStatus.ERROR }
        }
        onLog("📊 Status: $status")

        if (lastBeaconStatus == BeaconStatus.RANGING && status != BeaconStatus.RANGING && isCollecting) {
            finalizeMeasurement()
        }
        if (status == BeaconStatus.RANGING) isCollecting = true

        lastBeaconStatus = status
        onBeaconStatus(status)
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendCommand(cmd: Byte) {
        val g    = gatt        ?: run { onLog("❌ Not connected"); return }
        val char = commandChar ?: run { onLog("❌ CMD char not ready"); return }
        writeChar(g, char, byteArrayOf(cmd))
    }

    // Note: that is not used yet intentionally
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendConfig(payload: ByteArray) {
        val g    = gatt       ?: run { onLog("❌ Not connected"); return }
        val char = configChar ?: run { onLog("⚠ CFG char not available"); return }
        writeChar(g, char, payload)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writeChar(g: BluetoothGatt, char: BluetoothGattCharacteristic, data: ByteArray) {
        val writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, data, writeType)
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            char.writeType = writeType
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
    }

    // ── Measurement data ──────────────────────────────────────────────────────

    fun addMeasurementChunk(data: ByteArray) {
        if (isCollecting) measurementBuffer.add(data.clone())
    }

    private fun finalizeMeasurement() {
        if (measurementBuffer.isEmpty()) return
        val total    = measurementBuffer.sumOf { it.size }
        val combined = ByteArray(total)
        var offset   = 0
        for (chunk in measurementBuffer) { chunk.copyInto(combined, offset); offset += chunk.size }
        measurementBuffer.clear()
        isCollecting = false
        onMeasurementData(combined)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun isConnected() = currentState == BleState.CONNECTED

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        gatt?.disconnect()
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun close() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        stopScan()
        gatt?.close()
        gatt = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun cleanup() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        commandChar = null
        statusChar  = null
        configChar  = null
        if (isCollecting) finalizeMeasurement()
        gatt?.close()
        gatt = null
    }

    private fun setState(state: BleState) {
        currentState = state
        onStateChanged(state)
    }
}
