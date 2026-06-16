# ESP32-C6 BLE Beacon — Android App

Kotlin Android application that connects to an ESP32-C3/6 Supermini WiFi-CSI/FTM ranging beacon over Bluetooth Low Energy (BLE), sends
start/stop measurement commands, receives and persists streamed result files, visualises them, and exports them as CSV.

(c) Professor & Claude, 2026-05.

---

## How It Works

### Scanning
`BleManager.startScan()` filters by the 128-bit service UUID.  
Scan auto-stops after **15 seconds** if no device is found.

### Connection & Service Discovery
On `STATE_CONNECTED` the app calls `discoverServices()`, then:
1. Resolves `CHAR_COMMAND_UUID` and `CHAR_STATUS_UUID` from the service.
2. Enables notifications on the Status characteristic (writes CCCD).
3. Reads the current Status value.

### Commands
`sendCommand(CMD_START, RANGING_MODE)` / `sendCommand(CMD_STOP)` write 1-2 bytes to the
Command characteristic.  
Write type is automatically selected:
- `WRITE_TYPE_NO_RESPONSE` if the characteristic advertises `PROPERTY_WRITE_NO_RESPONSE`
- `WRITE_TYPE_DEFAULT` otherwise

### Measurement data collection
The app buffers every Status notification chunk received while the beacon
reports `RANGING (1)`.  
When the status transitions away from `RANGING` (to `IDLE` or `ERROR`),
`finalizeMeasurement()` concatenates the buffer and hands it to
`MeasurementRepository`.

### File storage
`MeasurementRepository` saves files to `<app-internal-storage>/measurements/`.
Each file is named `measurement_YYYYMMDD_HHmmss_SSS.bin` and contains:
```
# ESP32-C6 Beacon Measurement
# Timestamp: 20260512_143022_412
# Bytes: 42
# ---
<raw bytes>
```

Files are never accessible to other apps (no `READ_EXTERNAL_STORAGE` needed).

---

## Table of Contents

1. [Project Structure](#1-project-structure)
2. [Build & Setup](#2-build--setup)
3. [Android Permissions](#3-android-permissions)
4. [BLE Interface Specification](#4-ble-interface-specification)
5. [File-Stream Protocol (0xFF04)](#5-file-stream-protocol-0xff04)
6. [CSIB Binary File Format](#6-csib-binary-file-format)
7. [Local File Storage](#7-local-file-storage)
8. [Connection & Scanning Logic](#8-connection--scanning-logic)
9. [CCCD Subscription Sequencing](#9-cccd-subscription-sequencing)
10. [UI Reference](#10-ui-reference)
11. [Measurement Plot](#11-measurement-plot)
12. [CSV Export](#12-csv-export)
13. [Known Firmware Issues & Workarounds](#13-known-firmware-issues--workarounds)
14. [Diagnostics & Logcat](#14-diagnostics--logcat)
15. [Architecture & Threading](#15-architecture--threading)

---

## 1. Project Structure
BLE Client for WiFi-CSI Ranging Measurement: BleWRaMA
```
BleWRaMA/
├── app/
│   ├── build.gradle                         ← Android app build configuration
│   └── src/main/
│       ├── AndroidManifest.xml              ← Android app settings
│       ├── java/com/lumais/blewrama/
│       │   ├── MainActivity.kt              ← GUI + permission handling
│       │   ├── ChartMarkerView.kt           ← Pop-up tooltip shown when tapping a data point
│       │   ├── PlotActivity.kt              ← Plotting layout and visual interaction handlers
│       │   ├── ble/
│       │   │   └── BleManager.kt            ← All BLE logic
│       │   │   └── FileStreamParser.kt      ← File parser for the receiving data stream
│       │   └── data/
│       │       └── MeasurementRepository.kt ← Local file storage
│       │       └── CsibParser.kt            ← File format parser to convert binary data to CSV
│       └── res/                             ← GUI resources
│           ├── layout/
│           │   ├── activity_main.xml        ← Main app interface layout
│           │   ├── activity_plot.xml        ← Plotting view layout
│           │   └── marker_view.xml          ← Pop-up tooltip layout
│           ├── values/
│           │   ├── colors.xml
│           │   ├── strings.xml
│           │   └── themes.xml
│           └── drawable/
│               ├── bg_marker.xml
│               ├── bg_spinner.xml
│               ├── bg_status_idle.xml
│               ├── bg_status_measuring.xml
│               ├── bg_status_error.xml
│               ├── ic_dot_gray.xml
│               ├── ic_dot_green.xml
│               ├── ic_dot_yellow.xml
│               └── ic_dot_red.xml
├── build.gradle                             ← Common build configuration
└── settings.gradle                          ← Common build dependency settings
```

---

## 2. Build & Setup

### Requirements

| Item | Value |
|---|---|
| Android Studio | Hedgehog (2023.1.1) or later |
| Kotlin | 1.9.x |
| AGP | 8.2.x |
| `compileSdk` | 34 |
| `minSdk` | 23 (Android 6.0) |
| `targetSdk` | 34 |

### Dependencies (`app/build.gradle`)

```groovy
implementation 'androidx.core:core-ktx:1.12.0'
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.11.0'
implementation 'androidx.cardview:cardview:1.0.0'
implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
implementation 'com.github.PhilJay:MPAndroidChart:v3.1.0'   // via JitPack
```

JitPack must be declared in `settings.gradle` `dependencyResolutionManagement`:

```groovy
repositories {
    google()
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```

### Build

```bash
git clone <repo-url>
cd BleBeaconApp
./gradlew assembleDebug
# or open in Android Studio and press Run
```

---

## 3. Android Permissions

| Permission | `maxSdkVersion` | Purpose |
|---|---|---|
| `BLUETOOTH` | 30 | Legacy BLE API (Android ≤ 11) |
| `BLUETOOTH_ADMIN` | 30 | Enable adapter (Android ≤ 11) |
| `BLUETOOTH_SCAN` | — | BLE scan, flagged `neverForLocation` (Android 12+) |
| `BLUETOOTH_CONNECT` | — | GATT connect (Android 12+) |
| `ACCESS_FINE_LOCATION` | — | Required by Android for BLE scanning on all versions |
| `ACCESS_COARSE_LOCATION` | — | Coarse fallback |

All permissions are requested at runtime via
`ActivityResultContracts.RequestMultiplePermissions`. Bluetooth enable uses
`ACTION_REQUEST_ENABLE`.

---

## 4. BLE Interface Specification

### Service & Characteristics

| Role | Short UUID | Full UUID |
|---|---|---|
| **Service** | — | `12345678-9ABC-DEF0-1234-56789ABCDEF0` |
| Command | 0xFF01 | `0000FF01-0000-1000-8000-00805F9B34FB` |
| Status | 0xFF02 | `0000FF02-0000-1000-8000-00805F9B34FB` |
| Config | 0xFF03 | `0000FF03-0000-1000-8000-00805F9B34FB` |
| Data | 0xFF04 | `0000FF04-0000-1000-8000-00805F9B34FB` |
| CCCD descriptor | — | `00002902-0000-1000-8000-00805F9B34FB` |

> **UUID byte order:** Android's `UUID.fromString()` and `ParcelUuid` use the
> canonical string form for all comparisons. The little-endian wire bytes
> (`F0 DE BC 9A …`) are handled transparently by the BLE stack.

### Command Characteristic — 0xFF01

**Properties:** Write, Write-No-Response  
**Length:** 1 byte

| Value | Command | Effect |
|---|---|---|
| `0x01` | **Start** | Begin ranging session |
| `0x02` | **Stop** | End session; beacon sends result files on 0xFF04 |

Write type is selected automatically: `WRITE_TYPE_NO_RESPONSE` if
`PROPERTY_WRITE_NO_RESPONSE` is set, otherwise `WRITE_TYPE_DEFAULT`.

Sending **Stop** also calls `fileStreamParser.reset()` to clear any stale
parser state before the new transfer begins.

### Status Characteristic — 0xFF02

**Properties:** Read, Notify  
**CCCD written by Android:** `{0x01, 0x00}` — `ENABLE_NOTIFICATION_VALUE`  
**Length:** 1 byte

| Value | State | UI label |
|---|---|---|
| `0x00` | IDLE | "IDLE" (dark pill) |
| `0x01` | RANGING | "MEASURING" (green pill) |
| `0x02` | ERROR | "ERROR" (red pill) |

The initial value is read after the CCCD write completes. Subsequent state
changes arrive as notifications and update the UI status pill in real time.

### Config Characteristic — 0xFF03

**Properties:** Write  
**Length:** application-defined

Available for sending sensor configuration parameters via
`BleManager.sendConfig(payload: ByteArray)`. Not currently driven by the UI
but fully plumbed through the GATT write path.

### Data Characteristic — 0xFF04

**Properties:** Read, **Indicate**  
**CCCD written by Android:** `{0x02, 0x00}` — `ENABLE_INDICATION_VALUE`

Carries the framed file-stream protocol (Section 5). Each indication
delivers exactly one protocol item.

> **Critical — Indicate vs Notify:** writing `{0x01, 0x00}` (Notify) to an
> Indicate-only characteristic is rejected by NimBLE with
> `BLE_ATT_ERR_REQ_NOT_SUPPORTED` (GATT error 6), causing complete silence on
> this characteristic. `BleManager` inspects `PROPERTY_INDICATE` on every
> characteristic before selecting the CCCD value (see Section 9).

Android delivers both Notify and Indicate payloads through the same
`onCharacteristicChanged` callback — no receive-side distinction is needed.

---

## 5. File-Stream Protocol (0xFF04)

After a Stop command the ESP32 sends up to 3 result files encoded in a
self-describing framed stream. The firmware sends **one indication per item**
and waits for the GATT ACK (`xSemaphoreTake(s_stream_ack)`) before sending
the next, so indication boundaries always coincide with item boundaries.

### 5.1 Item Types

| Type byte | Name | Payload |
|---|---|---|
| `0x0A` | **Manifest** | `<file_count : 1 B>` |
| `0x01` | **File_start** | `<file_id : 1 B>` `<file_size : 4 B LE>` |
| `0x02` | **File_chunk** | `<chunk_seq : 2 B LE>` `<chunk_data : N B>` |
| `0x03` | **File_end** | *(no payload)* |
| `0x0F` | **Stream_end** | *(no payload)* |

**Minimum indication sizes:**

| Item | Bytes |
|---|---|
| Manifest | 2 (type + count) |
| File_start | 6 (type + id + 4-byte size) |
| File_chunk | 3 (header) + 1…(MTU−3) data |
| File_end | 1 |
| Stream_end | 1 |

### 5.2 Stream Sequence

```
Manifest(n_files)
  File_start(id=0, size=S0)
    File_chunk(seq=0, data…)
    File_chunk(seq=1, data…)
    …
  File_end
  File_start(id=1, size=S1)
    File_chunk(seq=0, data…)
    …
  File_end
  …
Stream_end
```

### 5.3 Endianness

| Field | Encoding |
|---|---|
| `file_size` in File_start | **Little-endian** (ESP32 native struct, RISC-V LE) |
| `chunk_seq` in File_chunk | **Little-endian** (explicit shift encoding in firmware) |
| All CSIB binary fields | **Little-endian** |

> Historical note: an earlier firmware revision encoded `chunk_seq` in
> big-endian. The current codebase uses `readInt16LE`. Verify against your
> firmware's actual shift pattern if chunks appear out of order.

### 5.4 Parser Behaviour (`FileStreamParser.kt`)

`FileStreamParser` is a stateful decoder fed one indication at a time via
`feed(ByteArray)`.

**Chunk consumption:** Because each indication holds exactly one item,
`File_chunk` consumes the entire indication remainder as payload — no attempt
is made to find the next item boundary inside binary data (which would be
ambiguous). The number of bytes accepted is capped at
`currentFileSize − bytesReceived` to prevent overrun.

**Auto-complete:** If accumulated bytes reach `currentFileSize` before
`File_end` is received, the file is finalised immediately.

**Sequence gap detection:** `nextExpectedSeq` is tracked per file; a gap
logs a warning but does not abort the transfer.

**Unknown item type:** The entire notification is skipped with a hex dump
logged. Byte-by-byte recovery is not attempted because binary chunk data
can contain any byte value, making any such recovery ambiguous.

**Leftover buffer:** Bytes that form an incomplete item header at the end of
one notification are saved and prepended to the next `feed()` call. With
the strict one-item-per-indication contract this path should never be
reached, but it is retained as a safety net.

---

## 6. CSIB Binary File Format

Compact binary measurement format produced by the ESP32 ranging subsystem.
Parsed by `CsibParser.kt`.

### 6.1 File Header — 32 bytes at offset 0

| Offset | Size | Type | Value / Notes |
|---|---|---|---|
| 0 | 4 | `uint32` | Magic = `0x43534942` ("CSIB" in little-endian) |
| 4 | 2 | `uint16` | Version = 1 |
| 6 | 2 | `uint16` | Record size = 24 |
| 8 | 4 | `uint32` | Record count (updated on flush) |
| 12 | 8 | `int64` | Session start timestamp (esp_timer µs) |
| 20 | 12 | — | Reserved, zero |

### 6.2 Record Layout — 24 bytes, repeating from offset 32

| Offset | Size | Type | Field | Notes |
|---|---|---|---|---|
| 0 | 4 | `uint32` | `seq` | Measurement sequence number |
| 4 | 4 | `uint32` | `timestamp_ms` | Milliseconds since session start |
| 8 | 4 | `float32` | `dist_raw_m` | Raw ToF distance (metres) |
| 12 | 4 | `float32` | `dist_filtered_m` | Kalman-filtered distance (metres) |
| 16 | 4 | `float32` | `variance` | Kalman filter P covariance |
| 20 | 1 | `uint8` | `rssi` | RSSI with +128 bias; dBm = `rssi − 128` |
| 21 | 1 | `uint8` | `outlier_rejected` | 1 = rejected by 3σ gate |
| 22 | 1 | `uint8` | `valid` | 1 = raw measurement is valid |
| 23 | 1 | `uint8` | `_pad` | Unused, always 0 |

### 6.3 Encoding Rules

- **Byte order:** little-endian throughout
- **Struct packing:** `#pragma pack(1)` — no inter-field padding
- **float32:** IEEE 754 single precision
- **RSSI:** `rssi_dbm = rssi_raw − 128`
  (example: raw `0xA8` = 168 → −40 dBm)
- Records begin at byte offset **32** and repeat contiguously

### 6.4 Payload Location in `.bin` Files

`.bin` files saved by `MeasurementRepository` prepend a 4-line UTF-8 text
comment header (`# …\n` lines) before the raw CSIB bytes.
`CsibParser` and `exportToCsv()` both locate the payload by scanning
forward to the four-byte magic sequence `0x43 0x53 0x49 0x42`.

---

## 7. Local File Storage

All files are stored in `<app-internal>/measurements/` —
inaccessible to other apps without explicit sharing via `FileProvider`.
No `WRITE_EXTERNAL_STORAGE` permission is required.

### File Types

| Ext | Naming pattern | Content | Created by |
|---|---|---|---|
| `.bin` | `measurement_YYYYMMDD_HHmmss_SSS.bin` | 4-line text header + raw CSIB binary | Legacy measurement path |
| `.dat` | `<file_id>_<DD-HH-mm>.dat` | Raw bytes as received on 0xFF04 | File-stream transfer |
| `.csv` | `<same-base-as-.bin>.csv` | Commented CSV export of a `.bin` file | Export button |

**Example names:**
```
measurement_20260519_143201_042.bin
0_19-14-32.dat
1_19-14-32.dat
measurement_20260519_143201_042.csv
```

### `MeasurementRepository` Public API

| Method | Returns | Description |
|---|---|---|
| `saveMeasurement(data)` | `String` (path) | Writes 4-line header + raw CSIB as `.bin` |
| `saveDatFile(fileId, data)` | `File` | Writes raw bytes as `<id>_<DD-HH-mm>.dat` |
| `exportToCsv(binFile, onError)` | `File?` | Parses CSIB `.bin`, writes commented `.csv` |
| `listFiles()` | `List<File>` | All `.bin` + `.dat`, newest first |
| `listBinFiles()` | `List<File>` | Only `.bin` files (for CSIB-aware features) |
| `deleteLastFile()` | `Boolean` | Deletes the newest file (bin or dat) |
| `deleteAllFiles()` | `Int` | Deletes all `.bin`, `.dat`, `.csv`; returns count |

### CSV Format

```csv
# ESP32-C6 Beacon Measurement Export
# Source file : measurement_20260519_143201_042.bin
# Export time : 2026-05-19 14:32:05
# CSIB version: 1
# Record count: 1200
# Session start (esp_timer µs): 4823910234
# ---
# Columns:
#   seq             - Measurement sequence number
#   timestamp_ms    - ms since session start
#   dist_raw_m      - Raw ToF distance (metres)
#   dist_filtered_m - Kalman-filtered distance (metres)
#   variance        - Kalman P covariance
#   rssi_dbm        - RSSI in dBm (rssi_raw − 128)
#   rssi_raw        - Raw RSSI byte (bias +128)
#   outlier         - 1 = rejected by 3σ gate, 0 = accepted
#   valid           - 1 = raw measurement valid, 0 = invalid
# ---
seq,timestamp_ms,dist_raw_m,dist_filtered_m,variance,rssi_dbm,rssi_raw,outlier,valid
0,0,1.234567,1.230012,0.000423,-40,88,0,1
1,100,1.241230,1.231445,0.000418,-41,87,0,1
```

Floats: 6 decimal places; non-finite values written as `NaN`.

---

## 8. Connection & Scanning Logic

Implemented in `BleManager.kt`.

### 8.1 Scan

- **Filter:** `null` (no UUID filter). UUID-filtered scans silently miss
  devices that advertise the 128-bit service UUID only in the scan-response
  packet — a common ESP32 firmware pattern. The app logs the full
  advertisement record (`advertiseFlags`, `serviceUuids`) on each result.
- **Mode:** `SCAN_MODE_LOW_LATENCY`, `MATCH_MODE_AGGRESSIVE`,
  `CALLBACK_TYPE_ALL_MATCHES`
- **Timeout:** 20 seconds; on expiry state returns to `DISCONNECTED`
- **Thread safety:** `onScanResult` fires on a Binder thread.
  `connectToDevice()` is posted to the main thread via `mainHandler.post{}`
  because calling `connectGatt` from a non-main thread causes intermittent
  status=133 failures on Android 8–13.
- **Scanner stop before connect:** `stopScan()` is called inside
  `onScanResult` *before* posting `connectToDevice()`. Keeping the scanner
  running while connecting has been shown to cause status=133 on many
  Android versions.

### 8.2 GATT Connection Sequence

```
startScan()
  └─ onScanResult
       stopScan()
       mainHandler.post { connectToDevice() }
         connectGatt(autoConnect=false, TRANSPORT_LE)
           └─ onConnectionStateChange(CONNECTED, SUCCESS)
                requestMtu(185)
                  └─ onMtuChanged
                       discoverServices()
                         └─ onServicesDiscovered
                              resolve characteristics
                              enqueue CCCDs (FF02, FF04)
                              drainCccdQueue()
                                └─ onDescriptorWrite (FF02)
                                     drainCccdQueue()
                                       └─ onDescriptorWrite (FF04)
                                            readCharacteristic(statusChar)
                                              └─ onCharacteristicRead
                                                   setState(CONNECTED)  ← UI unlocked
```

Key implementation decisions:

| Decision | Reason |
|---|---|
| `autoConnect=false` | Direct connection; faster than passive background scan |
| `TRANSPORT_LE` explicit | Prevents BR/EDR fallback on dual-mode phones |
| MTU before `discoverServices` | Stack knows PDU size before any attribute read/write |
| Fresh `BluetoothDevice` via `getRemoteDevice(address)` | Avoids stale internal state from previous failed sessions |

### 8.3 Retry & Watchdog

- **Connect-timeout watchdog:** 8 seconds. If `onConnectionStateChange` is
  never called (observed on some OEM builds) the watchdog calls `retryOrFail()`.
- **Auto-retry:** up to 3 attempts with a 1-second cooldown between each.
  The delay lets the Android BLE stack fully release the previous connection
  attempt before the next `connectGatt`.
- **Retried status codes:** 133 (`GATT_ERROR`), 8 (`CONN_TIMEOUT`),
  34 (`FAIL_ESTABLISH`). All others set `ERROR` state without retrying.
- **GATT cache flush:** On service-discovery failure `BluetoothGatt.refresh()`
  is invoked via reflection. This clears stale cached service tables that
  can appear after a firmware update or bond clear on the peripheral.

---

## 9. CCCD Subscription Sequencing

Android's GATT stack is strictly single-operation: only one GATT procedure
can be in flight at a time. Attempting to write two descriptors back-to-back
silently cancels the first.

`BleManager` solves this with a `cccdQueue: ArrayDeque<CccdEntry>`, where
each `CccdEntry` holds the characteristic reference, its CCCD descriptor,
and its **property flags**.

**Drain sequence:**

1. `onServicesDiscovered` enqueues entries for FF02 then FF04, then calls
   `drainCccdQueue(g)`.
2. `drainCccdQueue` removes the first entry and writes its CCCD.
3. Each `onDescriptorWrite` callback calls `drainCccdQueue` again if the
   queue is non-empty, otherwise issues the initial status read.

**CCCD value selection — critical for 0xFF04:**

| Characteristic | Property | CCCD bytes written |
|---|---|---|
| 0xFF02 Status | `PROPERTY_NOTIFY` | `{0x01, 0x00}` — `ENABLE_NOTIFICATION_VALUE` |
| 0xFF04 Data | `PROPERTY_INDICATE` | `{0x02, 0x00}` — `ENABLE_INDICATION_VALUE` |

Writing `{0x01, 0x00}` (Notify) to an Indicate-only characteristic is
rejected by NimBLE with `BLE_ATT_ERR_REQ_NOT_SUPPORTED` (GATT status 6),
silencing all indications. `drainCccdQueue` inspects `PROPERTY_INDICATE`
on the stored `properties` field to select the correct value:

```kotlin
val enableValue = when {
    entry.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE    // {0x02, 0x00}
    else ->
        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE  // {0x01, 0x00}
}
```

A characteristic advertising both NOTIFY and INDICATE uses INDICATE
(the stricter mode).

---

## 10. UI Reference

### Main Screen (`MainActivity`)

| Control | Enabled when | Action |
|---|---|---|
| **Connect** button | Always | Starts BLE scan |
| **Disconnect** button | Connected | Closes GATT link |
| Connection dot (gray/yellow/green/red) | Always | Reflects `BleState` |
| **▶ Start** | Connected | Sends CMD `0x01` |
| **■ Stop** | Connected | Sends CMD `0x02`; triggers file transfer |
| Beacon state pill | Always | Shows IDLE / MEASURING / ERROR |
| **🗑 Last** | Files exist | Deletes newest `.bin` or `.dat` |
| **🗑 All** | Files exist | Deletes all `.bin`, `.dat`, `.csv` |
| **📈 Plot** | `.bin` file exists | Opens `PlotActivity` with newest `.bin` |
| **⬆ Export Last as CSV** | `.bin` file exists | Parses newest `.bin` → CSV, opens share sheet |
| File list | Always | Lists all `.bin` (📄) and `.dat` (📦) files, newest first |
| Event log | Always | Last 30 timestamped lines, newest at top |
| **Long-press log** | Log non-empty | Copies full log to clipboard |

### Connection Status Dot

| Colour | `BleState` |
|---|---|
| Gray | `DISCONNECTED` |
| Yellow | `SCANNING` or `CONNECTING` |
| Green | `CONNECTED` |
| Red | `ERROR` |

### Beacon State Pill

| Colour | `BeaconStatus` | Label |
|---|---|---|
| Dark slate | `IDLE` | IDLE |
| Green | `RANGING` | MEASURING |
| Red | `ERROR` | ERROR |

### File List Icons

| Icon | Type | Created by |
|---|---|---|
| 📄 | `.bin` | Legacy measurement path |
| 📦 | `.dat` | 0xFF04 file-stream transfer |

Plot and Export CSV are disabled when no `.bin` file exists — both features
require CSIB format parsing. Delete and file count reflect all file types.

---

## 11. Measurement Plot

`PlotActivity` renders CSIB measurement data using **MPAndroidChart v3.1.0**.

### Data Loading

A `.bin` file is either:
- **Pre-loaded** via `EXTRA_FILE_PATH` intent extra (passed by MainActivity
  with the most recent `.bin`)
- **Loaded from the internal store** via the file spinner + Load button
- **Loaded from any file manager app** via the "Open file from storage…"
  button (`GetContent` contract, copied to a temp file for random-access
  parsing)

### Parsing & Aggregation (`CsibParser.kt`)

1. Validates magic `0x43534942`, version, and record size
2. Reads all records into `List<CsibRecord>`
3. `aggregate()` groups records into **100 ms time buckets** by
   `floor(timestamp_ms / 100)`, then per bucket:
   - `distRaw`: average of valid (`valid=1`) records
   - `distFiltered`: average of non-rejected (`outlier_rejected=0`) records
   - `variance`: average of non-rejected records
   - `validRatio`: `count(non-rejected) / count(all)` in the bucket

### Chart Configuration

| Series | Colour | Y axis | Source |
|---|---|---|---|
| Raw dist (m) | Blue `#4A9EFF` | Left | `dist_raw_m` (valid records) |
| Filtered dist (m) | Green `#2ECC71` | Left, filled | `dist_filtered_m` (non-rejected) |
| Variance | Red `#E74C3C` | Left, dashed | `variance` (non-rejected) |
| Valid % | Yellow `#F1C40F` | Right (0–100%) | `validRatio × 100` per bucket |

- **X axis:** bucket index in units of 100 ms; formatted as integers
- **Left Y axis:** shared by distance (m) and variance
- **Right Y axis:** valid ratio formatted as `%`
- Circle markers drawn only when fewer than 80 data points (readability)

### Interaction

- **Pinch / drag:** zoom and pan
- **Tap a point:** shows `ChartMarkerView` popup — `t = N ms`, `y = value`
- **Checkboxes:** toggle each series on/off; chart redraws instantly without
  re-parsing

---

## 12. CSV Export

Triggered by **⬆ Export Last as CSV** in `MainActivity`.

### Steps

1. `listBinFiles().firstOrNull()` — picks the most recent `.bin`
2. File I/O + CSIB parse runs on a background `Thread` to avoid blocking the UI
3. `MeasurementRepository.exportToCsv()`:
   a. Reads all bytes; scans past `#` comment lines to locate CSIB magic
   b. Validates magic, version, record size
   c. Writes `<same-base>.csv` into the measurements directory with a
      13-line comment header followed by the column header row and one data
      row per record
4. On success, `FileProvider.getUriForFile()` produces a content URI and
   `Intent.ACTION_SEND` (type `text/csv`) opens the system share sheet

The `.csv` file is not shown in the main file list (only `.bin` and `.dat`
appear) but is accessible via the share sheet or ADB.

---

## 13. Known Firmware Issues & Workarounds

### `BLE_HS_ENOMEM` during chunk streaming

**Symptom:** `Indicate custom failed: BLE_HS_ENOMEM` in ESP32 log; chunks
dropped; received `.dat` file shorter than declared `file_size`.

**Cause:** The NimBLE mbuf pool is exhausted because another subsystem
(e.g. a concurrent telemetry task) holds mbufs while the streaming loop
tries to allocate one for the next chunk indication.

**Firmware fix — retry with back-off on `ENOMEM`:**

```c
int rc;
uint8_t retries = 0;
do {
    struct os_mbuf *om = ble_hs_mbuf_from_flat(tx_buf, read_bytes + 3);
    if (om == NULL) {
        vTaskDelay(pdMS_TO_TICKS(10));
        continue;
    }
    rc = ble_gatts_indicate_custom(conn, s_stream_handle, om);
    if (rc == BLE_HS_ENOMEM) {
        os_mbuf_free_chain(om);   // stack did NOT take ownership on error
        ESP_LOGW(TAG, "ENOMEM retry %u", ++retries);
        vTaskDelay(pdMS_TO_TICKS(20));
    } else if (rc != 0) {
        os_mbuf_free_chain(om);
        ESP_LOGE(TAG, "indicate_custom failed: %d", rc);
        break;
    }
} while (rc == BLE_HS_ENOMEM && retries < 5);

if (rc == 0) {
    xSemaphoreTake(s_stream_ack, portMAX_DELAY);
    seq++;
}
```

Also consider increasing `MYNEWT_VAL(BLE_MSYS_1_BLOCK_COUNT)` in
`sdkconfig` / `idf_component.yml` if pool contention is persistent.

### `File_start` field ordering

If the Android log shows `id=<size_value>  size=0`, the firmware is writing
`file_size` into the `file_id` byte. Verify the serialisation:

```c
tx_buf[0] = ITEM_FILE_START;             // 0x01
tx_buf[1] = (uint8_t)file_id;            // 1 byte
tx_buf[2] = (file_size >>  0) & 0xFF;    // LE byte 0 (LSB)
tx_buf[3] = (file_size >>  8) & 0xFF;    // LE byte 1
tx_buf[4] = (file_size >> 16) & 0xFF;    // LE byte 2
tx_buf[5] = (file_size >> 24) & 0xFF;    // LE byte 3 (MSB)
```

<!--
### One item per indication

The parser assumes one item per indication (matching the firmware's
semaphore-gated send loop). If two items share a single PDU (e.g. `File_end`
immediately followed by `File_start`), the second item is lost because
`File_chunk` consumes the entire indication remainder as payload, and
non-chunk items advance `pos` normally — but if `File_chunk` appears first
it preempts everything that follows in that buffer. Ensure each
`ble_gatts_indicate_custom` call carries exactly one protocol item.

-->

---

## 14. Diagnostics & Logcat

### Logcat filters

```bash
# Raw HCI + GATT events alongside app log
adb logcat -s BluetoothGatt:D BtGatt.GattService:D bt_btif:D *:S

# App process only
adb logcat --pid=$(adb shell pidof com.lumais.blewrama)
```

### In-app event log

The log panel (bottom of main screen) records:

- Every service and characteristic UUID found on connect (compare with nRF Connect)
- CCCD write result per characteristic, including the bytes written
- MTU negotiation result
- GATT status codes with human-readable names
- File-stream item receipt with byte counts and chunk sequence numbers
- Chunk sequence-number gaps

**Long-press the log** to copy all lines to the clipboard. On Android 13+
the system shows its own clipboard confirmation; the app suppresses its own
toast to avoid duplication.

### GATT Status Code Reference

| Code | Symbolic name | Common cause |
|---|---|---|
| 0 | `SUCCESS` | — |
| 6 | `ATT_ERR_REQ_NOT_SUPPORTED` | Wrong CCCD value (Notify sent to Indicate char) |
| 8 | `CONN_TIMEOUT` | Peripheral did not respond to connection request |
| 19 | `TERMINATE_PEER` | Peripheral closed the link cleanly |
| 22 | `TERMINATE_LOCAL` | Android closed the link |
| 34 | `FAIL_ESTABLISH` | Link-layer failure (range or interference) |
| 133 | `GATT_ERROR` | Stale GATT cache, wrong thread, scanner not stopped before connect |

### Stale GATT Cache

If `onServicesDiscovered` returns wrong UUIDs or status 133 after a
firmware update, clear the host-side cache:

```bash
adb shell pm clear com.lumais.blewrama
```

Or toggle Bluetooth off/on on the phone. The app calls
`BluetoothGatt.refresh()` (hidden API, accessed via reflection) automatically
on service-discovery failure.

---

## 15. Architecture & Threading

### Component Diagram

```
MainActivity
├── BleManager              Scan, GATT lifecycle, CCCD queue, command writes
│   └── FileStreamParser    0xFF04 indication decoder; file reassembly state machine
└── MeasurementRepository   .bin save, .dat save, CSIB parse, CSV export

PlotActivity
├── CsibParser              Binary parse + 100 ms aggregation
└── MPAndroidChart          LineChart rendering (4 series, dual Y axes)

ChartMarkerView             Tap-to-inspect popup for the chart
```

### Threading Model

| Thread | What runs on it |
|---|---|
| **Main (UI)** | All view updates; `connectGatt`; command writes; `fileStreamParser.reset()` |
| **GATT callback (Binder)** | All `onXxx` GATT callbacks; `FileStreamParser.feed()` calls |
| **Worker (`Thread{}`)** | CSV export (file I/O + CSIB parse in `exportLastBinAsCsv`) |

Every `BleManager` callback (`onStateChanged`, `onBeaconStatus`,
`onFileReceived`, `onTransferComplete`) is invoked on the GATT Binder thread.
`MainActivity` wraps each handler body in `mainHandler.post {}` before
touching any view or calling repository methods that write files.

`showLog()` is safe to call from any thread — it always posts to
`mainHandler` internally.

---

## Extending the app

<!--
- **Additional data characteristic:** Add `CHAR_DATA_UUID`, subscribe to it,
  and pipe chunks into `addMeasurementChunk()`.
- **CSV export:** Add a button in `MainActivity` that calls
  `measurementRepository.readFileAsText(file)` and shares via `Intent.ACTION_SEND`.
-->
- **File viewer dialog:** Launch a `DialogFragment` with the hex dump when the
  user taps a file name in `tvFileList`.
