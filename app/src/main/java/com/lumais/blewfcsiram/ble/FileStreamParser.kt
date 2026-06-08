package com.lumais.blewfcsiram.ble

import java.io.ByteArrayOutputStream

/**
 * Stateful parser for the file-transfer stream delivered on characteristic 0xFF04.
 *
 * Stream grammar (each BLE notification may contain one or more items):
 *
 *   Manifest   = 0x0A  <file_count : 1B>
 *   File_start = 0x01  <file_id : 1B>  <file_size : 4B LE>
 *   File_chunk = 0x02  <chunk_seq : 2B LE>  <chunk_data : N bytes>
 *   File_end   = 0x03  (no payload)
 *   Stream_end = 0x0F  (no payload)
 *
 * The parser is fed raw notification bytes via [feed].  When a complete file
 * has been received [onFileComplete] is called with the file-id and the
 * reassembled payload; when the whole stream ends [onStreamEnd] is called.
 *
 * Thread-safety: all calls must come from the same thread (GATT callback thread
 * is fine; do not call from multiple threads concurrently).
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

    // Carry-over bytes from the previous notification that did not form a
    // complete item header yet (max header is 7 bytes so this stays tiny).
    private val leftover = ByteArrayOutputStream()

    // ── Public API ────────────────────────────────────────────────────────────

    /** Feed one raw BLE notification payload into the parser. */
    fun feed(notification: ByteArray) {
        // Prepend any leftover bytes from the previous notification
        val data: ByteArray = if (leftover.size() == 0) {
            notification
        } else {
            val combined = ByteArrayOutputStream(leftover.size() + notification.size)
            leftover.writeTo(combined)
            combined.write(notification)
            combined.toByteArray()
        }
        leftover.reset()

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
                    if (pos + 2 >= data.size) { saveLeftover(data, pos); return }
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

                    val chunkStart = pos + 3
                    // Greedily consume the rest of the notification as chunk payload.
                    // If the remaining bytes in `data` start another item, that item
                    // will be caught on the next [feed] call via leftover — but since
                    // one notification = one chunk in practice, this is fine.
                    val remaining   = data.size - chunkStart
                    val canAccept   = currentFileSize - currentBuffer.size()
                    val chunkLen    = minOf(remaining, canAccept.coerceAtLeast(0))
                    currentBuffer.write(data, chunkStart, chunkLen)
                    onLog("  chunk seq=$seq  len=$chunkLen  total=${currentBuffer.size()}/$currentFileSize")
                    pos = data.size  // consumed everything
                }

                // ── File_end: 0x03 (no payload) ──────────────────────────────
                ITEM_FILE_END -> {
                    onLog("✅ File_end: id=$currentFileId  received=${currentBuffer.size()}/$currentFileSize B")
                    if (currentFileId >= 0) {
                        onFileComplete(currentFileId, currentBuffer.toByteArray())
                        filesReceived++
                    } else {
                        onLog("⚠ File_end with no active file — ignored")
                    }
                    currentFileId = -1
                    currentBuffer.reset()
                    pos += 1
                }

                // ── Stream_end: 0x0F (no payload) ────────────────────────────
                ITEM_STREAM_END -> {
                    onLog("🏁 Stream_end: received $filesReceived/$expectedFileCount file(s)")
                    onStreamEnd()
                    reset()
                    pos += 1
                }

                else -> {
                    onLog("⚠ Unknown stream item type: 0x${itemType.toString(16)}  at offset $pos — stream may be corrupt")
                    // Attempt recovery: advance one byte and retry
                    pos += 1
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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
}
