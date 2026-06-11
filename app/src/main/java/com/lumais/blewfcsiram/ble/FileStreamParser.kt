package com.lumais.blewfcsiram.ble

import java.io.ByteArrayOutputStream

/**
 * Stateful parser for the file-transfer stream delivered on characteristic 0xFF04.
 *
 * Stream grammar (each BLE notification carries one+ item):
 *
 *   Manifest   = 0x0A  <file_count : 1B> => File_start
 *   File_start = 0x01  <file_id : 1B>  <file_size : 4B LE> => {ACK}, File_chunk
 *   File_chunk = 0x02  <chunk_seq : 2B LE>  <chunk_data : N bytes> => {ACK}, File_chunk | File_end
 *   File_end   = 0x03  (no payload) => {ACK}, File_start | Stream_end
 *   Stream_end = 0x0F  (no payload) => {ACK}
 *
 * The parser is fed raw notification bytes via [feed].  When a complete file
 * has been received [onFileComplete] is called with the file-id and the
 * reassembled payload; when the whole stream ends [onStreamEnd] is called.
 *
 * Thread-safety: all calls must come from the same thread (GATT callback thread
 * is fine; do not call from multiple threads concurrently).
 *
 * ── One item per notification ─────────────────────────────────────────────────
 * The firmware sends one BLE indication per item and waits for the stack ACK
 * (s_stream_ack semaphore) before sending the next one.  Therefore:
 *   • A File_chunk notification contains ONLY the 3-byte header + chunk data.
 *   • No two items ever share a single notification.
 * The parser exploits this: for File_chunk it consumes the entire notification
 * remainder as payload rather than trying to find the next item boundary inside
 * binary data, which would be ambiguous.
 *
 * Thread-safety: all [feed] calls must come from the same thread.
 */
class FileStreamParser(
    private val onLog: (String) -> Unit,
    /** Called when a complete file has been reassembled. */
    private val onFileComplete: (fileId: Int, data: ByteArray) -> Unit,
    /** Called when the Stream_end item (0x0F) is received. */
    private val onStreamEnd: () -> Unit,
) {
    // ── Item-type constants ───────────────────────────────────────────────────
    companion object {
        const val ITEM_MANIFEST    = 0x0A
        const val ITEM_FILE_START  = 0x01
        const val ITEM_FILE_CHUNK  = 0x02
        const val ITEM_FILE_END    = 0x03
        const val ITEM_STREAM_END  = 0x0F
    }

    // ── Parser state ──────────────────────────────────────────────────────────
    private var expectedFileCount = 0
    private var filesReceived     = 0

    // Current in-progress file
    private var currentFileId   = -1
    private var currentFileSize = 0         // declared size from File_start
    private var nextExpectedSeq = 0         // for gap detection
    private val currentBuffer   = ByteArrayOutputStream()

    // // Carry-over bytes from the previous notification that did not form a
    // // complete item header yet (max header is 7 bytes so this stays tiny).
    // Leftover bytes from a truncated item header in a previous notification.
    // With the one-item-per-notification contract this should never be needed,
    // but we keep it as a safety net for any framing edge cases.
    private val leftover = ByteArrayOutputStream()

    // ── Public API ────────────────────────────────────────────────────────────

    /** Feed one raw BLE notification payload into the parser. */
    fun feed(notification: ByteArray) {
        if (notification.isEmpty()) return

        // Prepend any leftover bytes from the previous notification
        val data: ByteArray = if (leftover.size() == 0) {
            notification
        } else {
            val combined = ByteArrayOutputStream(leftover.size() + notification.size)
            leftover.writeTo(combined)
            combined.write(notification)
            leftover.reset()
            combined.toByteArray()
        }

        var pos = 0
        while (pos < data.size) {
            val itemType = data[pos].toInt() and 0xFF

            when (itemType) {

                // ── Manifest: 0x0A <file_count:1B> ───────────────────────────
                ITEM_MANIFEST -> {
                    if (pos + 1 >= data.size) { saveLeftover(data, pos); return }
                    expectedFileCount = data[pos + 1].toInt() and 0xFF
                    onLog("📦 Stream manifest: $expectedFileCount file(s) incoming")
                    pos += 2
                }

                // ── File_start: 0x01 <file_id:1B> <file_size:4B LE> ──────────
                ITEM_FILE_START -> {
                    if (pos + 5 >= data.size) { saveLeftover(data, pos); return }
                    currentFileId   = data[pos + 1].toInt() and 0xFF
                    currentFileSize = readInt32LE(data, pos + 2)
                    nextExpectedSeq = 0
                    currentBuffer.reset()
                    onLog("📄 File_start: id=$currentFileId  size=$currentFileSize B")
                    pos += 6
                }

                // ── File_chunk: 0x02 <seq:2B LE> <data:N bytes> ──────────────
                // The chunk extends to the next item-type byte (or end of
                // notification), so we must detect where it ends.  Since chunk
                // data is variable-length and may itself contain any byte value,
                // we rely on the declared file_size to know when we are done.
                ITEM_FILE_CHUNK -> {
                    // Need at least type(1) + seq(2) + 1 data byte
                    //if (pos + 2 >= data.size) { saveLeftover(data, pos); return }
                    if (pos + 3 > data.size) { saveLeftover(data, pos); return }

                    val seq = readInt16LE(data, pos + 1)
                    // chunk data starts at pos+3; it runs until the next item
                    // boundary, which we find by scanning forward for a known
                    // item-type byte — BUT item-type bytes can collide with
                    // payload data.  The safe approach: consume all remaining
                    // bytes in this notification as chunk data, then rely on
                    // the next notification to start a fresh item.  This works
                    // because the peripheral frames one item per notification
                    // (or at most completes with a short tail item).
                    // For robustness we also stop early if we have received
                    // currentFileSize bytes already.
                    if (seq != nextExpectedSeq) {
                        onLog("⚠ Chunk seq gap: expected $nextExpectedSeq got $seq (file $currentFileId)")
                    }
                    nextExpectedSeq = seq + 1

                    // Consume the entire notification remainder as chunk
                    // payload.  With the one-item-per-notification contract there
                    // are no further item headers in this buffer, so greedy
                    // consumption is both correct and unambiguous.
                    val chunkStart = pos + 3
                    // Greedily consume the rest of the notification as chunk payload.
                    // If the remaining bytes in `data` start another item, that item
                    // will be caught on the next [feed] call via leftover — but since
                    // one notification = one chunk in practice, this is fine.
                    val remaining   = data.size - chunkStart
                    // val canAccept   = currentFileSize - currentBuffer.size()
                    // val chunkLen    = minOf(remaining, canAccept.coerceAtLeast(0))
                    val canAccept  = (currentFileSize - currentBuffer.size()).coerceAtLeast(0)
                    val chunkLen   = minOf(remaining, canAccept)

                    if (chunkLen > 0) currentBuffer.write(data, chunkStart, chunkLen)
                    // onLog("  chunk seq=$seq  len=$chunkLen  total=${currentBuffer.size()}/$currentFileSize")
                    onLog("  chunk seq=$seq  +${chunkLen}B  " +
                          "total=${currentBuffer.size()}/$currentFileSize")

                    // Auto-complete if we have received all declared bytes
                    if (currentBuffer.size() >= currentFileSize && currentFileSize > 0) {
                        onLog("✅ File $currentFileId complete by byte count " +
                              "(no File_end received yet)")
                        finaliseCurrentFile()
                    }

                    pos = data.size   // one item = one notification: nothing left to parse
                }

                // ── File_end: 0x03 (no payload) ──────────────────────────────
                ITEM_FILE_END -> {
                    // onLog("✅ File_end: id=$currentFileId  received=${currentBuffer.size()}/$currentFileSize B")
                    // if (currentFileId >= 0) {
                    //     onFileComplete(currentFileId, currentBuffer.toByteArray())
                    //     filesReceived++
                    // } else {
                    //     onLog("⚠ File_end with no active file — ignored")
                    // }
                    // currentFileId = -1
                    // currentBuffer.reset()

                    onLog("✅ File_end: id=$currentFileId  " +
                          "received=${currentBuffer.size()}/$currentFileSize B")
                    if (currentFileId >= 0) finaliseCurrentFile()
                    else onLog("⚠ File_end with no active file — ignored")

                    pos += 1
                }

                // ── Stream_end: 0x0F (no payload) ────────────────────────────
                ITEM_STREAM_END -> {
                    onLog("🏁 Stream_end: $filesReceived/$expectedFileCount file(s) received")
                    onStreamEnd()
                    reset()
                    pos += 1
                }

                else -> {
                    // onLog("⚠ Unknown stream item type: 0x${itemType.toString(16)}  at offset $pos — stream may be corrupt")
                    // // Attempt recovery: advance one byte and retry
                    // pos += 1

                    // With the one-item-per-notification guarantee an unknown type
                    // almost certainly means the wrong characteristic is being
                    // subscribed to, or the firmware sent a raw data chunk without
                    // the 0x02 header (e.g. the ESP32 ble_gatts_indicate bug where
                    // om_chunk is built but the wrong handle is indicated).
                    onLog("⚠ Unknown item type 0x${itemType.toString(16).uppercase()} " +
                          "at offset $pos — skipping entire notification " +
                          "(${data.size} B).  Raw: ${data.toHex()}")
                    // Skip the whole notification; do not try to recover byte-by-byte
                    // because the data is binary and any byte could look like a valid type.
                    return
                }
            }
        }
    }

    /** Reset all state (call when a new Stop command is issued or connection drops). */
    fun reset() {
        expectedFileCount = 0
        filesReceived     = 0
        currentFileId     = -1
        currentFileSize   = 0
        nextExpectedSeq   = 0
        currentBuffer.reset()
        leftover.reset()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun finaliseCurrentFile() {
        onFileComplete(currentFileId, currentBuffer.toByteArray())
        filesReceived++
        currentFileId = -1
        currentBuffer.reset()
    }

    private fun saveLeftover(data: ByteArray, fromPos: Int) {
        leftover.reset()
        leftover.write(data, fromPos, data.size - fromPos)
    }

    private fun readInt32LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8) or
        ((buf[offset + 2].toInt() and 0xFF) shl 16) or
        ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun readInt16LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun readInt16BE(buf: ByteArray, offset: Int): Int =
        ((buf[offset    ].toInt() and 0xFF) shl 8) or
         (buf[offset + 1].toInt() and 0xFF)

    /** Hex dump helper for diagnostic logging of unknown notifications. */
    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }
}
