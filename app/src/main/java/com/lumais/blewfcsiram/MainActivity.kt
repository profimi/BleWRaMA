package com.lumais.blewfcsiram

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lumais.blewfcsiram.databinding.ActivityMainBinding
import com.lumais.blewfcsiram.ble.BleManager
import com.lumais.blewfcsiram.ble.BleState
import com.lumais.blewfcsiram.ble.BeaconStatus
import com.lumais.blewfcsiram.data.MeasurementRepository
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var measurementRepository: MeasurementRepository
    private val mainHandler = Handler(Looper.getMainLooper())

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            initializeBle()
        } else {
            showLog("❌ Bluetooth permissions denied.")
            updateUiForState(BleState.DISCONNECTED)
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startScanning()
        } else {
            showLog("❌ Bluetooth not enabled.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        measurementRepository = MeasurementRepository(this)
        bleManager = BleManager(this, ::onBleStateChanged, ::onBeaconStatusChanged, ::onMeasurementDataReceived, ::showLog)

        setupUI()
        checkPermissionsAndInit()
        refreshFileList()
    }

    private fun setupUI() {
        binding.btnScan.setOnClickListener {
            if (bleManager.isConnected()) {
                bleManager.disconnect()
            } else {
                checkPermissionsAndInit()
            }
        }

        binding.btnStartMeasurement.setOnClickListener {
            bleManager.sendCommand(BleManager.CMD_START)
            showLog("▶ Start measurement sent")
        }

        binding.btnStopMeasurement.setOnClickListener {
            bleManager.sendCommand(BleManager.CMD_STOP)
            showLog("■ Stop measurement sent")
        }

        binding.btnDeleteLastFile.setOnClickListener {
            val deleted = measurementRepository.deleteLastFile()
            if (deleted) {
                showLog("🗑 Last measurement file deleted")
                refreshFileList()
            } else {
                showLog("⚠ No files to delete")
            }
        }

        binding.btnDeleteAllFiles.setOnClickListener {
            val count = measurementRepository.deleteAllFiles()
            showLog("🗑 Deleted $count measurement file(s)")
            refreshFileList()
        }

        updateUiForState(BleState.DISCONNECTED)
        updateBeaconStatus(BeaconStatus.IDLE)
    }

    private fun checkPermissionsAndInit() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            initializeBle()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun initializeBle() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(intent)
        } else {
            startScanning()
        }
    }

    private fun startScanning() {
        updateUiForState(BleState.SCANNING)
        showLog("🔍 Scanning for ESP32-C6 beacon...")
        bleManager.startScan()
    }

    private fun onBleStateChanged(state: BleState) {
        mainHandler.post {
            updateUiForState(state)
            when (state) {
                BleState.CONNECTED -> showLog("✅ Connected to beacon")
                BleState.DISCONNECTED -> {
                    showLog("🔌 Disconnected")
                    updateBeaconStatus(BeaconStatus.IDLE)
                }
                BleState.SCANNING -> showLog("🔍 Scanning...")
                BleState.CONNECTING -> showLog("⏳ Connecting...")
                BleState.ERROR -> showLog("❌ BLE Error occurred")
            }
        }
    }

    private fun onBeaconStatusChanged(status: BeaconStatus) {
        mainHandler.post {
            updateBeaconStatus(status)
            when (status) {
                BeaconStatus.IDLE -> showLog("💤 Beacon: Idle")
                BeaconStatus.RANGING -> showLog("📡 Beacon: Ranging active")
                BeaconStatus.ERROR -> showLog("⚠ Beacon: Error state")
            }
        }
    }

    private fun onMeasurementDataReceived(data: ByteArray) {
        mainHandler.post {
            val filePath = measurementRepository.saveMeasurement(data)
            showLog("💾 Measurement saved: ${filePath.substringAfterLast('/')}")
            refreshFileList()
        }
    }

    private fun updateUiForState(state: BleState) {
        val connected = state == BleState.CONNECTED
        val scanning = state == BleState.SCANNING || state == BleState.CONNECTING

        binding.btnScan.text = when (state) {
            BleState.CONNECTED -> "Disconnect"
            BleState.SCANNING, BleState.CONNECTING -> "Scanning…"
            else -> "Connect"
        }
        binding.btnScan.isEnabled = state != BleState.SCANNING && state != BleState.CONNECTING

        binding.btnStartMeasurement.isEnabled = connected
        binding.btnStopMeasurement.isEnabled = connected

        binding.connectionStatusDot.setImageResource(
            when (state) {
                BleState.CONNECTED -> R.drawable.ic_dot_green
                BleState.SCANNING, BleState.CONNECTING -> R.drawable.ic_dot_yellow
                BleState.ERROR -> R.drawable.ic_dot_red
                else -> R.drawable.ic_dot_gray
            }
        )
        binding.tvConnectionStatus.text = when (state) {
            BleState.CONNECTED -> "Connected"
            BleState.SCANNING -> "Scanning"
            BleState.CONNECTING -> "Connecting"
            BleState.ERROR -> "Error"
            else -> "Disconnected"
        }

        binding.scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
    }

    private fun updateBeaconStatus(status: BeaconStatus) {
        binding.tvBeaconState.text = when (status) {
            BeaconStatus.IDLE -> "IDLE"
            BeaconStatus.RANGING -> "MEASURING"
            BeaconStatus.ERROR -> "ERROR"
        }
        binding.beaconStatusIndicator.setBackgroundResource(
            when (status) {
                BeaconStatus.IDLE -> R.drawable.bg_status_idle
                BeaconStatus.RANGING -> R.drawable.bg_status_measuring
                BeaconStatus.ERROR -> R.drawable.bg_status_error
            }
        )
    }

    private fun showLog(message: String) {
        mainHandler.post {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val current = binding.tvLog.text.toString()
            val newLine = "[$timestamp] $message"
            val updated = if (current.isEmpty()) newLine else "$newLine\n$current"
            // Keep last 30 lines
            val lines = updated.lines().take(30).joinToString("\n")
            binding.tvLog.text = lines
        }
    }

    private fun refreshFileList() {
        val files = measurementRepository.listFiles()
        if (files.isEmpty()) {
            binding.tvFileList.text = "No measurement files stored."
        } else {
            binding.tvFileList.text = files.joinToString("\n") { "📄 ${it.name}  (${it.length()} B)" }
        }
        binding.btnDeleteLastFile.isEnabled = files.isNotEmpty()
//        binding.btnDeleteAllFiles.isEnabled = files.isNotEmpty()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.close()
    }
}
