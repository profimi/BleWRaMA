# ESP32-C6 BLE Beacon — Android App

Kotlin Android application that connects to an ESP32-C6 Supermini BLE beacon, sends
start/stop measurement commands, receives status notifications, and persists
measurement data locally.

(c) Professor & Claude, 2026-05.

---

## Project Structure
BLE Client for WiFi-CSI Ranging Measurement: BleWfCsiRaM
```
BleWfCsiRaM/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/blebeacon/
│       │   ├── MainActivity.kt              ← UI + permission handling
│       │   ├── ble/
│       │   │   └── BleManager.kt            ← All BLE logic
│       │   └── data/
│       │       └── MeasurementRepository.kt ← Local file storage
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/
│           │   ├── colors.xml
│           │   ├── strings.xml
│           │   └── themes.xml
│           └── drawable/
│               ├── bg_status_idle.xml
│               ├── bg_status_measuring.xml
│               ├── bg_status_error.xml
│               ├── ic_dot_gray.xml
│               ├── ic_dot_green.xml
│               ├── ic_dot_yellow.xml
│               └── ic_dot_red.xml
├── build.gradle
└── settings.gradle
```

---

## BLE Interface

| Item | Value |
|---|---|
| Service UUID | `12345678-9ABC-DEF0-1234-56789ABCDEF0` |
| Command char (0xFF01) | Write / Write-No-Response, 1 byte: `0x01` = start, `0x02` = stop |
| Status char (0xFF02) | Read + Notify, 1 byte: `0` = idle, `1` = ranging, `2` = error |

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

> **Note:** The ESP32-C6 spec only defines a 1-byte Status characteristic.
> If your firmware sends additional measurement payload on a separate
> characteristic or as longer notifications, add its UUID to `BleManager` and
> call `addMeasurementChunk()` from the appropriate `onCharacteristicChanged`
> branch.

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

## Setup

1. Open the project root in **Android Studio Hedgehog or later**.
2. Let Gradle sync.
3. Build & run on a device with BLE (API 23+).
4. Grant Bluetooth and Location permissions when prompted.

### Minimum SDK
`minSdk = 23` (Android 6.0). Targets API 34.

### Permissions declared
- `BLUETOOTH` / `BLUETOOTH_ADMIN` — API ≤ 30
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` — API ≥ 31
- `ACCESS_FINE_LOCATION` — required by Android for BLE scanning

---

## Extending the app

- **Additional data characteristic:** Add `CHAR_DATA_UUID`, subscribe to it,
  and pipe chunks into `addMeasurementChunk()`.
- **CSV export:** Add a button in `MainActivity` that calls
  `measurementRepository.readFileAsText(file)` and shares via `Intent.ACTION_SEND`.
- **File viewer dialog:** Launch a `DialogFragment` with the hex dump when the
  user taps a file name in `tvFileList`.
