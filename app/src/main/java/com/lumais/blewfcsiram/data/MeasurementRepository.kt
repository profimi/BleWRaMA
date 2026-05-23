package com.lumais.blewfcsiram.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeasurementRepository(private val context: Context) {

    private val measurementDir: File by lazy {
        File(context.filesDir, "measurements").also { it.mkdirs() }
    }

    /**
     * Save raw measurement data to a timestamped file.
     * The file format is binary (.bin) with a human-readable header line prepended.
     * Returns the absolute path of the saved file.
     */
    fun saveMeasurement(data: ByteArray): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val file = File(measurementDir, "measurement_$timestamp.bin")

        file.outputStream().use { out ->
            // Write a simple text header
            val header = "# ESP32-C6 Beacon Measurement\n" +
                    "# Timestamp: $timestamp\n" +
                    "# Bytes: ${data.size}\n" +
                    "# ---\n"
            out.write(header.toByteArray())
            out.write(data)
        }

        return file.absolutePath
    }

    /**
     * List all measurement files, newest first.
     */
    fun listFiles(): List<File> {
        return measurementDir
            .listFiles { f -> f.name.startsWith("measurement_") && f.name.endsWith(".bin") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Delete the most recently created measurement file.
     * Returns true if a file was deleted.
     */
    fun deleteLastFile(): Boolean {
        val last = listFiles().firstOrNull() ?: return false
        return last.delete()
    }

    /**
     * Delete all measurement files.
     * Returns the number of files deleted.
     */
    fun deleteAllFiles(): Int {
        return listFiles().count { it.delete() }
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
