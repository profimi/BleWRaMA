package com.lumais.blewfcsiram.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ── Header (32 bytes) ────────────────────────────────────────────────────────
data class CsibHeader(
    val magic: UInt,            // 0x43534942 = "CSIB"
    val version: UShort,        // 1
    val recordSize: UShort,     // 24
    val recordCount: UInt,
    val sessionStartUs: Long,   // esp_timer µs
)

// ── Record (24 bytes) ────────────────────────────────────────────────────────
data class CsibRecord(
    val seq: UInt,
    val timestampMs: UInt,      // ms since session start
    val distRawM: Float,        // raw ToF distance (m)
    val distFilteredM: Float,   // Kalman-filtered distance (m)
    val variance: Float,        // Kalman P covariance
    val rssiRaw: UByte,         // RSSI + 128 bias; dBm = rssiRaw − 128
    val outlierRejected: Boolean,
    val valid: Boolean,
) {
    val rssiDbm: Int get() = rssiRaw.toInt() - 128
    /** X-axis value: time in units of 100 ms */
    val timeHundredMs: Float get() = timestampMs.toFloat() / 100f
}

// ── Aggregated slot (one 100 ms bucket) ─────────────────────────────────────
data class TimeSlot(
    val timeHundredMs: Float,
    val distRaw: Float,
    val distFiltered: Float,
    val variance: Float,
    /** valid-and-not-rejected / total measurements in this 100 ms window */
    val validRatio: Float,
)

sealed class ParseResult {
    data class Success(val header: CsibHeader, val records: List<CsibRecord>) : ParseResult()
    data class Error(val message: String) : ParseResult()
}

object CsibParser {

    private const val MAGIC = 0x43534942u
    private const val HEADER_SIZE = 32
    private const val RECORD_SIZE = 24

    fun parse(file: File): ParseResult {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < HEADER_SIZE) return ParseResult.Error("File too small for header")

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            // ── Header ────────────────────────────────────────────────────
            val magic = buf.int.toUInt()
            if (magic != MAGIC) return ParseResult.Error(
                "Bad magic: expected 0x${MAGIC.toString(16).uppercase()}, " +
                        "got 0x${magic.toString(16).uppercase()}"
            )
            val version     = buf.short.toUShort()
            val recordSize  = buf.short.toUShort()
            val recordCount = buf.int.toUInt()
            val sessionStart= buf.long
            // skip 12 reserved bytes
            buf.position(HEADER_SIZE)

            val header = CsibHeader(magic, version, recordSize, recordCount, sessionStart)

            // Validate record size
            if (recordSize.toInt() != RECORD_SIZE)
                return ParseResult.Error("Unexpected record size: ${recordSize.toInt()} (expected $RECORD_SIZE)")

            // ── Records ───────────────────────────────────────────────────
            val available = (bytes.size - HEADER_SIZE) / RECORD_SIZE
            val count = minOf(recordCount.toInt(), available)
            val records = ArrayList<CsibRecord>(count)

            repeat(count) {
                val seq             = buf.int.toUInt()
                val timestampMs     = buf.int.toUInt()
                val distRaw         = buf.float
                val distFiltered    = buf.float
                val variance        = buf.float
                val rssiRaw         = buf.get().toUByte()
                val outlierRejected = buf.get().toInt() != 0
                val valid           = buf.get().toInt() != 0
                buf.get() // _pad

                records += CsibRecord(
                    seq, timestampMs, distRaw, distFiltered, variance,
                    rssiRaw, outlierRejected, valid
                )
            }

            ParseResult.Success(header, records)
        } catch (e: Exception) {
            ParseResult.Error("Parse exception: ${e.message}")
        }
    }

    /**
     * Aggregate records into 100 ms time buckets for plotting.
     * Each bucket averages distance/variance and counts valid-ratio.
     */
    fun aggregate(records: List<CsibRecord>): List<TimeSlot> {
        if (records.isEmpty()) return emptyList()

        // Group by 100 ms bucket (floor of timeHundredMs)
        val buckets = LinkedHashMap<Int, MutableList<CsibRecord>>()
        for (r in records) {
            val bucket = (r.timestampMs.toLong() / 100).toInt()
            buckets.getOrPut(bucket) { mutableListOf() }.add(r)
        }

        return buckets.map { (bucket, recs) ->
            val timHms = bucket.toFloat()   // x-axis unit = 100 ms

            val validRecs = recs.filter { it.valid }
            val notRejected = validRecs.filter { !it.outlierRejected }

            val distRaw      = if (validRecs.isNotEmpty()) validRecs.map { it.distRawM }.average().toFloat() else Float.NaN
            val distFiltered = if (notRejected.isNotEmpty()) notRejected.map { it.distFilteredM }.average().toFloat() else Float.NaN
            val variance     = if (notRejected.isNotEmpty()) notRejected.map { it.variance }.average().toFloat() else Float.NaN
            val validRatio   = if (recs.isNotEmpty()) notRejected.size.toFloat() / recs.size.toFloat() else 0f

            TimeSlot(timHms, distRaw, distFiltered, variance, validRatio)
        }.sortedBy { it.timeHundredMs }
    }
}
