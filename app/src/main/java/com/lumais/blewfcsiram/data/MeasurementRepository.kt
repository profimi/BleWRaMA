package com.lumais.blewfcsiram.data

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Unified repository for all locally-stored measurement files:
 *
 *  • .bin  — raw CSIB binary blobs received over the legacy measurement path
 *  • .dat  — raw files transferred via the framed 0xFF04 file-stream protocol
 *  • .csv  — CSV exports derived from .bin files
 *
 * All files live in  <app-internal>/measurements/
 */
class MeasurementRepository(private val context: Context) {

    private val measurementDir: File by lazy {
        File(context.filesDir, "measurements").also { it.mkdirs() }
    }

    // ── .bin save (legacy CSIB path) ──────────────────────────────────────────
    /**
     * Save raw BLE measurement bytes to a timestamped .bin file.
     * The file begins with a 4-line text header followed immediately by the
     * raw CSIB binary payload (header + records as received from the beacon).
     * Returns the absolute path of the saved file.
     */
    fun saveMeasurement(data: ByteArray): String {
        val timestamp = nowStamp("yyyyMMdd_HHmmss_SSS")
        val file = File(measurementDir, "measurement_$timestamp.bin")
        file.outputStream().use { out ->
            out.write(
                ("# ESP32-C6 Beacon Measurement\n" +
                 "# Timestamp: $timestamp\n" +
                 "# Bytes: ${data.size}\n" +
                 "# ---\n").toByteArray(Charsets.UTF_8)
            )
            out.write(data)
        }
        return file.absolutePath
    }

    // ── .dat save (framed 0xFF04 file-stream path) ────────────────────────────
    /**
     * Save a raw file received via the BLE file-stream protocol.
     *
     * Naming: <file_id>_<DD-hh-mm>.dat  (day-hour-minute of reception)
     * e.g.   2_19-14-35.dat
     *
     * Returns the saved [File].
     */
    fun saveDatFile(fileId: Int, data: ByteArray): File {
        val stamp = nowStamp("dd-HH-mm")
        val file  = File(measurementDir, "${fileId}_$stamp.dat")
        file.writeBytes(data)
        return file
    }

    // ── CSV export ────────────────────────────────────────────────────────────

    /**
     * Parse [binFile] as a CSIB binary measurement and write a CSV file
     * alongside it (same base name, .csv extension) in the measurements dir.
     *
     * CSV structure:
     *   - Lines starting with '#' are header comments describing every column.
     *   - The first non-comment line is the column header row.
     *   - One data row per CSIB record.
     *
     * Returns the CSV [File] on success, null on failure.
     * [onError] receives a human-readable reason string on failure.
     */
    fun exportToCsv(binFile: File, onError: (String) -> Unit = {}): File? {
        return try {
            // ── 1. Read the raw bytes, skipping the prepended text header ──
            val allBytes = binFile.readBytes()
            val payloadStart = findPayloadStart(allBytes)
            if (payloadStart < 0) {
                onError("Cannot locate binary payload in ${binFile.name}")
                return null
            }
            val payload = allBytes.copyOfRange(payloadStart, allBytes.size)

            // ── 2. Parse CSIB structure ────────────────────────────────────
            val HEADER_SIZE = 32
            val RECORD_SIZE = 24
            val MAGIC       = 0x43534942 // "CSIB" little-endian

            if (payload.size < HEADER_SIZE) {
                onError("Payload too small for CSIB header (${payload.size} B)")
                return null
            }

            val buf         = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val magic       = buf.int.toUInt().toInt()
            val version     = buf.short.toInt()
            val recordSize  = buf.short.toInt()
            val recordCount = buf.int
            val sessionUs   = buf.long
            buf.position(HEADER_SIZE)   // skip reserved bytes

            if (magic != MAGIC) {
                onError("Bad magic: 0x${magic.toString(16).uppercase()} (expected CSIB=0x43534942)")
                return null
            }
            if (recordSize != RECORD_SIZE) {
                onError("Unexpected record size: $recordSize (expected $RECORD_SIZE)")
                return null
            }

            val count   = minOf(recordCount, (payload.size - HEADER_SIZE) / RECORD_SIZE)
            val csvFile = File(measurementDir, binFile.nameWithoutExtension + ".csv")

            csvFile.bufferedWriter(Charsets.UTF_8).use { w ->
                // Comment header block
                w.write("# ESP32-C6 Beacon Measurement Export\n")
                w.write("# Source file : ${binFile.name}\n")
                w.write("# Export time : ${nowStamp("yyyy-MM-dd HH:mm:ss")}\n")
                w.write("# CSIB version: $version\n")
                w.write("# Record count: $count\n")
                w.write("# Session start (esp_timer µs): $sessionUs\n")
                w.write("# ---\n")
                w.write("# Columns:\n")
                w.write("#   seq             - Measurement sequence number\n")
                w.write("#   timestamp_ms    - ms since session start\n")
                w.write("#   dist_raw_m      - Raw ToF distance (metres)\n")
                w.write("#   dist_filtered_m - Kalman-filtered distance (metres)\n")
                w.write("#   variance        - Kalman P covariance\n")
                w.write("#   rssi_dbm        - RSSI in dBm (rssi_raw − 128)\n")
                w.write("#   rssi_raw        - Raw RSSI byte (bias +128)\n")
                w.write("#   outlier         - 1 = rejected by 3σ gate, 0 = accepted\n")
                w.write("#   valid           - 1 = raw measurement valid, 0 = invalid\n")
                w.write("# ---\n")

                // Column header
                w.write("seq,timestamp_ms,dist_raw_m,dist_filtered_m,variance," +
                        "rssi_dbm,rssi_raw,outlier,valid\n")

                // Data rows
                repeat(count) {
                    val seq             = buf.int.toLong() and 0xFFFFFFFFL
                    val timestampMs     = buf.int.toLong() and 0xFFFFFFFFL
                    val distRaw         = buf.float
                    val distFiltered    = buf.float
                    val variance        = buf.float
                    val rssiRaw         = buf.get().toInt() and 0xFF
                    val outlierRejected = buf.get().toInt() and 0xFF
                    val valid           = buf.get().toInt() and 0xFF
                    buf.get() // _pad

                    val rssiDbm = rssiRaw - 128

                    w.write("$seq,$timestampMs,")
                    w.write("${formatFloat(distRaw)},")
                    w.write("${formatFloat(distFiltered)},")
                    w.write("${formatFloat(variance)},")
                    w.write("$rssiDbm,$rssiRaw,")
                    w.write("$outlierRejected,$valid\n")
                }
            }
            csvFile
        } catch (e: Exception) {
            onError("Export failed: ${e.message}")
            null
        }
    }

    // ── File listing ──────────────────────────────────────────────────────────
    /**
     * List all measurement files (.bin and .dat), newest-first.
     * .csv and other derived files are excluded.
     */
    fun listFiles(): List<File> =
        measurementDir
            .listFiles { f ->
                (f.name.startsWith("measurement_") && f.name.endsWith(".bin")) ||
                f.name.endsWith(".dat")
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Return only .bin files (newest-first) — used by the CSV export and plot
     * features which understand the CSIB format.
     */
    fun listBinFiles(): List<File> =
        measurementDir
            .listFiles { f -> f.name.startsWith("measurement_") && f.name.endsWith(".bin") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Delete the most recently modified file (bin or dat).
     * Returns true if a file was deleted.
     */
    fun deleteLastFile(): Boolean {
        val last = listFiles().firstOrNull() ?: return false
        return last.delete()
    }

    /**
     * Delete all measurement files (bin and dat) plus any derived csv files.
     * Returns the total number of files deleted.
     */
    fun deleteAllFiles(): Int {
        val targets = measurementDir.listFiles { f ->
            f.name.endsWith(".bin") || f.name.endsWith(".dat") || f.name.endsWith(".csv")
        } ?: return 0
        return targets.count { it.delete() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun nowStamp(pattern: String): String {
        // Uses thread-safe DateTimeFormatter and forces invariant English digits
        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.US)
        return LocalDateTime.now().format(formatter)
    }

    /** Format a float to 6 significant decimal places, using "NaN" for non-finite values. */
    private fun formatFloat(v: Float): String =
        if (!v.isFinite()) "NaN" else "%.6f".format(v)

    /**
     * Locate where the binary CSIB payload starts within [bytes].
     * The file begins with up to 4 lines of '#' comment text; the payload
     * follows immediately after.  We search for the CSIB magic bytes
     * (0x42 0x49 0x53 0x43 in little-endian = "BISC" ... actually
     * 0x43534942 LE = bytes 42 49 53 43) starting after any text header.
     *
     * Strategy: scan for the 4-byte magic sequence after skipping '#' lines.
     */
    private fun findPayloadStart(bytes: ByteArray): Int {
        // Fast path: skip lines beginning with '#'
        var pos = 0
        while (pos < bytes.size && bytes[pos] == '#'.code.toByte()) {
            while (pos < bytes.size && bytes[pos] != '\n'.code.toByte()) pos++
            pos++ // skip '\n'
        }
        // At this point pos should be right at the CSIB magic.
        // Verify: little-endian 0x43534942 = bytes C S I B = 0x43 0x53 0x49 0x42
        if (pos + 4 <= bytes.size &&
            bytes[pos]     == 0x43.toByte() &&
            bytes[pos + 1] == 0x53.toByte() &&
            bytes[pos + 2] == 0x49.toByte() &&
            bytes[pos + 3] == 0x42.toByte()) return pos

        // Fallback: linear search for magic anywhere in file
        for (i in 0..bytes.size - 4) {
            if (bytes[i]     == 0x43.toByte() &&
                bytes[i + 1] == 0x53.toByte() &&
                bytes[i + 2] == 0x49.toByte() &&
                bytes[i + 3] == 0x42.toByte()) return i
        }
        return -1
    }

    /**
     * Read the contents of a file as text (for display/debug).
     */
    fun readFileAsText(file: File): String {
        return try {
            val bytes = file.readBytes()
            // Print header as text, then hex dump of payload bytes
            val headerEnd = bytes.indexOfFirst { it == '#'.code.toByte() }.let {
                var pos = 0
                var newlines = 0
                while (pos < bytes.size && newlines < 4) {
                    if (bytes[pos] == '\n'.code.toByte()) newlines++
                    pos++
                }
                pos
            }
            val headerText = String(bytes, 0, headerEnd.coerceAtMost(bytes.size))
            val payload = bytes.drop(headerEnd)
            val hex = payload.joinToString(" ") { "%02X".format(it) }
            "$headerText\nPayload hex:\n$hex"
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }
    }
}
