package com.lumais.blewfcsiram

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.lumais.blewfcsiram.databinding.ActivityMainBinding
import com.lumais.blewfcsiram.ble.BleManager
import com.lumais.blewfcsiram.ble.BleState
import com.lumais.blewfcsiram.ble.BeaconStatus
import com.lumais.blewfcsiram.data.MeasurementRepository
import java.text.SimpleDateFormat
import java.util.*

@JvmInline
value class RangingMode private constructor(val value: Byte) {
    companion object {
        val FTM = RangingMode(0)
        val CSI = RangingMode(1)
    }
}
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var measurementRepository: MeasurementRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rangingMode = RangingMode.FTM
    private val rangingToggle by lazy { findViewById<MaterialButtonToggleGroup>(R.id.rangingModeToggle) }

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
        if (permissions.values.all { it }) initializeBle()
        else {
            showLog("❌ Bluetooth permissions denied.")
            updateUiForState(BleState.DISCONNECTED)
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startScanning()
        else showLog("❌ Bluetooth not enabled.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        measurementRepository = MeasurementRepository(this)
        bleManager = BleManager(
            context             = this,
            onStateChanged      = ::onBleStateChanged,
            onBeaconStatus      = ::onBeaconStatusChanged,
            onMeasurementData   = ::onMeasurementDataReceived,
            onLog               = ::showLog,
            // ── File-stream callbacks ──────────────────────────────────────────
            onFileReceived      = ::onDatFileReceived,
            onTransferComplete  = ::onTransferComplete,
        )

        setupUI()
        checkPermissionsAndInit()
        refreshFileList()
    }

    // ── UI wiring ─────────────────────────────────────────────────────────────

    private fun setupUI() {
        // Connection
        binding.btnScan.setOnClickListener {
            if (bleManager.isConnected()) bleManager.disconnect()
            else checkPermissionsAndInit()
        }

        rangingToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            rangingMode = when (checkedId) {
                R.id.btnFtm -> RangingMode.FTM
                R.id.btnCsi -> RangingMode.CSI
                else -> RangingMode.FTM
            }
        }

        // Measurement control
        binding.btnStartMeasurement.setOnClickListener {
            rangingToggle.isEnabled = false; rangingToggle.alpha = 0.5f
            bleManager.sendCommand(BleManager.CMD_START, rangingMode.value)
            showLog("▶ Start measurement sent")
        }
        binding.btnStopMeasurement.setOnClickListener {
            rangingToggle.isEnabled = true; rangingToggle.alpha = 1f
            bleManager.sendCommand(BleManager.CMD_STOP)
            showLog("■ Stop measurement sent")
        }

        // File management
        binding.btnDeleteLastFile.setOnClickListener {
            if (measurementRepository.deleteLastFile()) {
                showLog("🗑 Last file deleted")
                refreshFileList()
            } else showLog("⚠ No files to delete")
        }
        binding.btnDeleteAllFiles.setOnClickListener {
            val count = measurementRepository.deleteAllFiles()
            showLog("🗑 Deleted $count file(s)")
            refreshFileList()
        }

        // Opens PlotActivity, pre-loading the most recent stored file
        binding.btnPlot.setOnClickListener {
            // Plot only works on CSIB .bin files
            val binFiles = measurementRepository.listBinFiles()
            val intent = Intent(this, PlotActivity::class.java)
            binFiles.firstOrNull()?.let {
                intent.putExtra(PlotActivity.EXTRA_FILE_PATH, it.absolutePath)
            }
            startActivity(intent)
        }

        binding.btnExportCsv.setOnClickListener { exportLastBinAsCsv() }

        // Long-press event log to copy to clipboard
        binding.tvLog.setOnLongClickListener {
            val text = binding.tvLog.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            } else {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("BLE Event Log", text))
                // Android 13+ shows its own clipboard confirmation toast, suppress ours
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                    Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                showLog("📋 Log copied to clipboard")
            }
            true // consume the long-click
        }

        updateUiForState(BleState.DISCONNECTED)
        updateBeaconStatus(BeaconStatus.IDLE)
    }

    // ── File-stream callbacks (called on BleManager's GATT thread) ────────────

    /**
     * Called once per fully-reassembled .dat file delivered by the beacon
     * after a Stop command.  Saves the file and refreshes the list on the
     * main thread.
     */
    private fun onDatFileReceived(fileId: Int, data: ByteArray) {
        val file = measurementRepository.saveDatFile(fileId, data)
        mainHandler.post {
            showLog("💾 Received file $fileId → ${file.name}  (${file.length()} B)")
            refreshFileList()
        }
    }

    /**
     * Called when Stream_end is received — all files for this Stop command
     * have been delivered.
     */
    private fun onTransferComplete() {
        mainHandler.post {
            showLog("✅ File transfer complete")
            refreshFileList()
        }
    }

    // ── CSV export ────────────────────────────────────────────────────────────

    private fun exportLastBinAsCsv() {
        val binFile = measurementRepository.listBinFiles().firstOrNull() ?: run {
            showLog("⚠ No .bin measurement file to export")
            return
        }

        // Run on background thread — file I/O + parsing can be slow for large files
        Thread {
            val csvFile = measurementRepository.exportToCsv(binFile) { errMsg ->
                mainHandler.post { showLog("❌ CSV export: $errMsg") }
            }
            mainHandler.post {
                if (csvFile == null) return@post
                showLog("✅ CSV exported: ${csvFile.name}  (${csvFile.length()} B)")
                // Share the file via the system share sheet using FileProvider
                // so other apps (Files, Gmail, Drive, …) can receive it.
                try {
                    val uri = FileProvider.getUriForFile(
                        this, "${packageName}.fileprovider", csvFile
                    )
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT,
                                "Beacon measurement ${csvFile.nameWithoutExtension}")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Share CSV via…"
                    ))
                } catch (e: Exception) {
                    showLog("⚠ Share failed: ${e.message}")
                }
            }
        }.start()
    }

    // ── BLE state callbacks ───────────────────────────────────────────────────

    private fun checkPermissionsAndInit() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) initializeBle()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun initializeBle() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled)
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        else startScanning()
    }

    private fun startScanning() {
        updateUiForState(BleState.SCANNING)
        showLog("🔍 Scanning for ESP32-C6 beacon…")
        bleManager.startScan()
    }

    private fun onBleStateChanged(state: BleState) {
        mainHandler.post {
            updateUiForState(state)
            when (state) {
                BleState.CONNECTED    -> showLog("✅ Connected to beacon")
                BleState.DISCONNECTED -> { showLog("🔌 Disconnected"); updateBeaconStatus(BeaconStatus.IDLE) }
                BleState.SCANNING     -> showLog("🔍 Scanning…")
                BleState.CONNECTING   -> showLog("⏳ Connecting…")
                BleState.ERROR        -> showLog("❌ BLE Error")
            }
        }
    }

    private fun onBeaconStatusChanged(status: BeaconStatus) {
        mainHandler.post {
            updateBeaconStatus(status)
            when (status) {
                BeaconStatus.IDLE    -> showLog("💤 Beacon: Idle")
                BeaconStatus.RANGING -> showLog("📡 Beacon: Ranging active")
                BeaconStatus.ERROR   -> showLog("⚠ Beacon: Error state")
            }
        }
    }

    private fun onMeasurementDataReceived(data: ByteArray) {
        mainHandler.post {
            val path = measurementRepository.saveMeasurement(data)
            showLog("💾 Measurement saved: ${path.substringAfterLast('/')}")
            refreshFileList()
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun updateUiForState(state: BleState) {
        val connected = state == BleState.CONNECTED
        val scanning  = state == BleState.SCANNING || state == BleState.CONNECTING

        binding.btnScan.text = when (state) {
            BleState.CONNECTED -> "Disconnect"
            BleState.SCANNING, BleState.CONNECTING -> "Scanning…"
            else -> "Connect"
        }
        binding.btnScan.isEnabled = !scanning
        binding.btnStartMeasurement.isEnabled = connected
        binding.btnStopMeasurement.isEnabled  = connected

        binding.connectionStatusDot.setImageResource(
            when (state) {
                BleState.CONNECTED -> R.drawable.ic_dot_green
                BleState.SCANNING, BleState.CONNECTING -> R.drawable.ic_dot_yellow
                BleState.ERROR -> R.drawable.ic_dot_red
                else -> R.drawable.ic_dot_gray
            }
        )
        binding.tvConnectionStatus.text = when (state) {
            BleState.CONNECTED  -> "Connected"
            BleState.SCANNING   -> "Scanning"
            BleState.CONNECTING -> "Connecting"
            BleState.ERROR      -> "Error"
            else                -> "Disconnected"
        }

        binding.scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
    }

    private fun updateBeaconStatus(status: BeaconStatus) {
        binding.tvBeaconState.text = when (status) {
            BeaconStatus.IDLE    -> "IDLE"
            BeaconStatus.RANGING -> "MEASURING"
            BeaconStatus.ERROR   -> "ERROR"
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
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val updated = "[$ts] $message\n${binding.tvLog.text}"
            binding.tvLog.text = updated.lines().take(30).joinToString("\n")

            // val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            // val current = binding.tvLog.text.toString()
            // val newLine = "[$timestamp] $message"
            // val updated = if (current.isEmpty()) newLine else "$newLine\n$current"
            // // Keep last 30 lines
            // val lines = updated.lines().take(30).joinToString("\n")
            // binding.tvLog.text = lines
        }
    }

    /**
     * Rebuild the file list display and enable/disable action buttons.
     *
     * Shows .bin and .dat files in a unified list, with a type badge:
     *   📦 for .dat files transferred via the file-stream protocol
     *   📄 for .bin measurement blobs
     *
     * btnPlot and btnExportCsv are enabled only when at least one .bin file
     * exists (they require CSIB format parsing).
     */
    private fun refreshFileList() {
        val allFiles  = measurementRepository.listFiles()
        val binFiles  = measurementRepository.listBinFiles()
        val hasAny    = allFiles.isNotEmpty()
        val hasBin    = binFiles.isNotEmpty()

        binding.tvFileList.text = if (allFiles.isEmpty()) {
            "No measurement files stored."
        } else {
            allFiles.joinToString("\n") { f ->
                val icon = if (f.name.endsWith(".dat")) "📦" else "📄"
                "$icon ${f.name}  (${f.length()} B)"
            }
        }

        binding.btnDeleteLastFile.isEnabled = hasAny
        binding.btnDeleteAllFiles.isEnabled = hasAny
        binding.btnPlot.isEnabled           = hasBin
        binding.btnExportCsv.isEnabled      = hasBin
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.close()
    }
}
