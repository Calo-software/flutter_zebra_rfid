# flutter_zebra_rfid

Reliable Flutter plugin for Zebra RFID readers (Android + iOS). Focus areas: connection resilience, structured errors, diagnostics visibility, and safer inventory lifecycle.

> **Note:** Large portions of this repository (code, Gradle wiring, and documentation) were generated or refactored with help from large language models and then reviewed in this project.

> Status: Android reliability features are in place, and the current release adds Capture Device orchestration for combined RFID + barcode setups. iOS supports core external reader/scanner integrations, but Bluetooth pairing helpers and reader-region APIs currently return `unsupported`.

## Contents
1. Features
2. Getting Started
3. Android Setup Notes
4. iOS Setup Notes
5. Basic Usage
6. Error Codes
7. Diagnostics Snapshot
8. Auto‑Reconnect Behavior
9. Inventory Watchdog
10. RF Parameters
11. Scanning Suppression (Home Screen Quiet Mode)
12. Tag Locating
13. Bluetooth Pairing (Android)
14. Reader Region Configuration (Android)
15. Capture Device Orchestration
16. Barcode Scanner Topology
17. Migration Guide
18. User Handoff & Hardware Verification
19. Roadmap & Contributing

## 1. Features
- Indexed reader discovery + guarded connection state machine
- Connection timeout (10s) with single internal retry
- Structured `ReaderErrorCode` taxonomy
- Diagnostics snapshot (attempt counts, last error, durations, locating)
- Auto‑reconnect (bounded exponential backoff) on unexpected disconnect
- Safe inventory start/stop with trigger debounce & watchdog (max duration + inactivity)
- Multi-tag locate support with relative distance measurements
- RF parameter introspection (receiveSensitivityIndex, rfModeTableIndex placeholder)
- Bluetooth discovery and pairing helpers for Zebra readers on Android
- Reader regulatory region discovery and apply APIs on Android
- Hardware-aware barcode scanner endpoints for built-in terminal scanners, RFD sled scanners, and external Zebra scanners
- Capture Device orchestration for phone + Bluetooth combo reader and TC22 + RFID sled topologies
- Combined example-app Scan Log that color-codes RFID versus barcode scans
- Zebra Android SDK bundle refreshed to API3 `2.0.5.275`

## 2. Getting Started
Add dependency in your `pubspec.yaml` (version placeholder below):
```yaml
dependencies:
  flutter_zebra_rfid: ^0.4.4
```
Then run `flutter pub get`.

Import:
```dart
import 'package:flutter_zebra_rfid/flutter_zebra_rfid.dart';
```

## 3. Android Setup Notes

### Important: TC22/TC27 Built-in RFID Configuration
**If you're using a TC22 or TC27 with built-in RFID:** The internal RFID reader may be disabled in device settings, or your TC22 model may not include RFID hardware (not all TC22s have built-in RFID). The Zebra SDK accesses RFID directly via the RFID API - **DataWedge is for barcode scanning only, not RFID.**

📱 **See [TC22_QUICKSTART.md](TC22_QUICKSTART.md) for configuration steps**

Or read the detailed troubleshooting guide: [USB_TROUBLESHOOTING.md](USB_TROUBLESHOOTING.md)

**Quick check:**
1. Settings → Device Settings → RFID (enable if available)
2. Verify your TC22 model includes RFID (check device label)
3. Test with Zebra's "123RFID Mobile" app
4. Reboot device

Without RFID enabled in device settings, you'll only see external Bluetooth readers (like RFD40), not the built-in USB reader.

---

### General Android Requirements
- Minimum SDK: 30 (required by the bundled Zebra API3 2.0.5.275 SDK)
- USB permissions are automatically included via the plugin's manifest
- The bundled barcode library may overwrite `android:label`; ensure manifest patch:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools">
    <application
            android:name=".MainApplication"
            android:label="flutter_zebra_rfid_example"
            android:icon="@mipmap/ic_launcher"
            tools:replace="android:label">
            <!-- other components -->
    </application>
</manifest>
```

### Bundled Zebra SDK AARs
- Zebra `.aar` binaries live in `android/RFIDAPI3Library` and are published to a local Maven repository (`android/localMaven`) during the first Android build.
- The current Android bundle is based on Zebra API3 `2.0.5.275`.
- Android now uses Zebra's consolidated `rfidapi3lib-2.0.5.275.aar`; it includes the scanner-control classes used by the barcode endpoint implementation, so the older separate `BarcodeScannerLibrary.aar` is no longer bundled.
- No manual action is required when consuming the plugin via pub or as a path dependency; Gradle prints `[zebra] Published ...` logs on the first run.
- If the artifacts are ever deleted, rerun:
    ```bash
    cd android
    ./gradlew publishZebraAarsToLocalMaven
    ```
    (This task is also invoked automatically by `flutter build/run` when needed.)
- USB-only deployments: as of the next release, BLE permissions are requested only when Bluetooth discovery is required. If users deny BLE prompts, USB reader discovery still succeeds.
- The Android test configuration now includes Robolectric and Mockito-based unit test support for plugin-side behavior.

## 4. iOS Setup Notes
- Enable Background Modes: External accessory communication, Uses BLE accessories
- Add supported external accessory protocols for your Zebra device (see Zebra docs)
- Core RFID flows are supported on iOS.
- Bluetooth pairing/discovery helper APIs currently return `unsupported` on iOS.
- Reader region configuration currently returns `unsupported` on iOS.

## 5. Basic Usage
Pseudo-flow in your app:
```dart
final api = FlutterZebraRfid();
await api.updateAvailableReaders(connectionType: ReaderConnectionType.all);
final readers = await api.onAvailableReadersChanged.first;
if (readers.isEmpty) {
    // handle no readers
}
await api.connectReader(readerId: readers.first.id);

// Listen for status / tag events
api.onTagsRead.listen((tags) { /* update UI */ });
api.onTagsLocated.listen((tags) { /* handle tags with distance info */ });
api.onReaderConnectionError.listen((err) { /* switch on err.code */ });
api.onReaderConnectionStatusChanged.listen((s) { /* connection state updates */ });
api.onBatteryDataReceived.listen((battery) {
  print('${battery.percentage}% (${battery.sourceLabel})');
});

// Requests a fresh percentage. This is also requested automatically on connect.
await api.triggerDeviceStatus();

// Start inventory
await api.startInventory();

// Stop inventory (safe to call even if already stopped)
await api.stopInventory();

// Diagnostics snapshot
final diag = await api.diagnostics();
print('Connect attempts: ${diag.connectAttempts} last error: ${diag.lastErrorCode}');
```

Supported PP+ sleds report Zebra battery statistics, including health and cycle
count when available; other readers use Zebra's standard battery event.
`BatteryData.level` remains for compatibility, while new code should use
`BatteryData.percentage` and inspect `source`/`sourceLabel` when displaying the
value.

## 6. Error Codes
| Code | Meaning | Typical Action |
|------|---------|----------------|
| timeout | Connect exceeded allowed duration | Offer retry / surface network or BT hint |
| alreadyConnecting | A connect attempt is in-flight | Disable UI button until resolved |
| notConnected | Operation needs active reader | Prompt user to connect first |
| invalidReaderIndex | Provided index out of range | Refresh device list and reselect |
| noAvailableReaders | Discovery returned none | Prompt user to pair/enable device |
| sdkOperationFailure | Underlying Zebra SDK threw error | Log & allow retry |
| sdkInvalidUsage | Mis-ordered API usage (e.g., start before connect) | Correct call order |

## 7. Diagnostics Snapshot
Call `diagnostics()` for a lightweight point‑in‑time struct. Recommended usage:
- Show on a hidden developer panel
- Attach to bug reports
- Poll only on demand (avoid high-frequency loops)

Fields (current set): `connectionState`, `connectAttempts`, `lastErrorCode`, `lastErrorMessage`, `lastConnectStartTimestamp`, `lastConnectDurationMs`, `isLocating`, `scanningEnabled`, `scanningEnabledLastToggleMs`.

## 8. Auto‑Reconnect Behavior
Triggered only on unexpected disconnect (not manual user disconnect). Backoff schedule:
`1s → 2s → 4s → 8s → 15s` (max 5 attempts). If exhausted, an error is emitted and state returns to DISCONNECTED.

Client Guidance:
- Do NOT implement a parallel reconnect loop—let the plugin finish attempts first.
- If you need custom policy, open an issue; configuration hooks are planned.

## 9. Inventory Watchdog
Prevents runaway scanning when trigger release events are missed.
- Max session length: 30 seconds
- Inactivity cutoff: 5 seconds (no tags read)
When tripped, inventory automatically stops; you may restart if desired.

## 10. RF Parameters
- `receiveSensitivityIndex`: Read‑only snapshot of current device receive sensitivity (nullable if unsupported)
- `rfModeTableIndex`: Present but currently null on Android until underlying accessor validated

Use these for diagnostics rather than user‑facing configuration (configuration APIs may be added later).

## 11. Scanning Suppression (Home Screen Quiet Mode)
Sometimes you want hardware trigger pulls to be ignored (e.g., while the user is on a non‑inventory screen). Use the suppression API:

```dart
await api.setScanningEnabled(false); // disables trigger-started inventory; stops current inventory
// ... navigate to home screen ...
await api.setScanningEnabled(true); // re-enable when entering scanning workflow
```

Behavior:
- When disabled, trigger press/release events are ignored.
- If an inventory session is active when disabled, it is stopped immediately (with tag purge scheduled as normal).
- Diagnostics exposes `scanningEnabled` and the last toggle timestamp so you can confirm state remotely.

UI Hint: Show a small badge or icon when scanning is globally disabled to avoid confusion.

## 12. Tag Locating
The plugin supports Zebra's multi-tag locate feature, which provides relative distance measurements to help you find specific RFID tags. This is useful for scenarios like locating a specific item in a warehouse or retail environment.

### How it Works
Tag locating uses the RSSI (Received Signal Strength Indicator) and Zebra's MultiTagLocate API to calculate relative distances to specified tags. When locating is active:
- The reader continuously scans for the specified tags
- Distance measurements are provided as a percentage (0.0 to 1.0) where lower values indicate closer proximity
- Tags are reported via the `onTagsLocated` stream with `relativeDistance` values

### Usage Example
```dart
final api = FlutterZebraRfid();

// Listen for locate events
api.onTagsLocated.listen((tags) {
  for (final tag in tags) {
    print('Tag: ${tag.id}');
    print('RSSI: ${tag.rssi}');
    if (tag.relativeDistance != null) {
      final percentage = (tag.relativeDistance! * 100).toStringAsFixed(0);
      print('Distance: $percentage% (closer to 0% = nearer)');
    }
  }
});

// Start locating specific tags
final tagsToLocate = [
  RfidTag(id: 'E2801170...',  rssi: 0), // RSSI value is ignored for locate input
  RfidTag(id: 'E2801171...', rssi: 0),
];

await api.startLocating(tags: tagsToLocate);

// ... user moves around to find tags ...

// Stop locating when done
await api.stopLocating();
```

### Important Notes
- **Calibration RSSI**: The implementation currently uses a default calibration RSSI of -50 dBm. This value may need adjustment based on your tag types and environment for optimal accuracy.
- **Active during locate**: While locating, the reader continuously scans for the specified tags. Regular tag reads via `onTagsRead` may still occur depending on reader configuration.
- **Diagnostics**: The `isLocating` field in the diagnostics snapshot indicates whether a locate session is currently active.
- **Platform Support**: Currently implemented for Android. iOS support is pending.

### UI Recommendations
- Display distance as a visual indicator (progress bar, proximity meter, color gradient)
- Provide audio or haptic feedback as the user gets closer to the target tag
- Show RSSI values alongside distance for advanced users/debugging
- Allow users to easily switch between normal inventory and locate modes

## 13. Bluetooth Pairing (Android)
The plugin now exposes Android-side Bluetooth discovery and pairing helpers for Zebra readers.

Available APIs:
- `startBluetoothScan()` starts classic discovery and BLE scanning together.
- `stopBluetoothScan()` stops any active scan.
- `getBondedDevices()` returns already-paired devices.
- `pairBluetoothDevice(address: ...)` initiates pairing for a specific device.

Available streams:
- `onBluetoothDeviceDiscovered`
- `onBluetoothScanStatusChanged`
- `onBluetoothPairingResult`

Example:
```dart
final api = FlutterZebraRfidApi();

api.onBluetoothDeviceDiscovered.listen((device) {
  print('Found ${device.name ?? 'Unknown'} @ ${device.address}');
});

api.onBluetoothPairingResult.listen((result) {
  print('Pairing ${result.success ? 'succeeded' : 'failed'} for ${result.device.address}');
});

await api.startBluetoothScan();
final bondedDevices = await api.getBondedDevices();
if (bondedDevices.isNotEmpty) {
  await api.pairBluetoothDevice(address: bondedDevices.first.address);
}
```

Notes:
- Android requests Bluetooth permissions only when Bluetooth discovery or pairing is actually used.
- USB reader discovery does not depend on these BLE prompts.
- On iOS, these helper APIs currently return `unsupported`.

## 14. Reader Region Configuration (Android)
Some Zebra readers refuse RFID operations until a regulatory region is configured. The plugin now exposes helpers to inspect and apply supported regions on Android.

Available APIs:
- `supportedReaderRegions()` returns the supported region list for the current or last-selected reader.
- `setReaderRegion(regionCode: ...)` applies a selected region.

The Android implementation also attempts a limited recovery path when the Zebra SDK reports `RFID_READER_REGION_NOT_CONFIGURED`, which helps surface supported regions instead of failing silently.

Example:
```dart
final api = FlutterZebraRfidApi();

final regions = await api.supportedReaderRegions();
if (regions.isNotEmpty) {
  await api.setReaderRegion(regionCode: regions.first.code);
}
```

Notes:
- Region configuration is Android-only in this release.
- The example app includes a `Set Region` action for this workflow.
- iOS currently returns `unsupported` for these calls.

## 15. Capture Device Orchestration
Use the Capture Device API when your app should select one physical working setup and let the plugin manage the RFID and barcode capability paths behind it.

```dart
final capture = FlutterZebraDataCaptureApi();

capture.capture.onAvailableCaptureDevicesChanged.listen((devices) {
  // Show Capture Devices as the primary user-facing choices.
});

capture.capture.onActiveCaptureDeviceChanged.listen((device) {
  print('${device?.displayName}: ${device?.status}');
});

await capture.capture.refreshCaptureDevices();
final devices = await capture.capture.onAvailableCaptureDevicesChanged.first;
if (devices.isNotEmpty) {
  await capture.capture.connectCaptureDevice(
    captureDeviceId: devices.first.id,
  );
}
```

The first supported topologies are:
- Phone plus Bluetooth combo reader, where the external Zebra unit provides RFID and barcode capabilities.
- TC22 docked into an RFID sled, where the sled provides RFID and the TC22 built-in imager provides barcode through DataWedge.

Capture Device status can be `connected`, `degraded`, or `error`; per-capability status and errors identify whether RFID, barcode, or both need attention. If automatic barcode matching is uncertain, call `setCaptureDeviceBarcodeOverride(...)` with a discovered barcode endpoint.

The example app opens on a Capture tab for this workflow, while the RFID and Barcode tabs remain available for lower-level troubleshooting.

## 16. Barcode Scanner Topology
The plugin exposes barcode scanners as topology-aware endpoints so apps can handle mixed hardware, such as a TC22/TC27 built-in imager plus an RFD40/RFD90 sled barcode scanner.

```dart
final capture = FlutterZebraDataCaptureApi();

capture.barcode.onAvailableBarcodeScannersChanged.listen((endpoints) {
  // Show a picker when more than one endpoint is present.
});

capture.barcode.onBarcodeRead.listen((barcode) {
  print('${barcode.data} from ${barcode.source} / ${barcode.scannerName}');
});

await capture.barcode.refreshBarcodeScanners();
final endpoints = await capture.barcode.onAvailableBarcodeScannersChanged.first;
if (endpoints.length == 1) {
  await capture.barcode.setActiveBarcodeScanner(
    endpointId: endpoints.first.endpointId,
  );
}
```

Android uses two barcode paths:
- DataWedge for built-in Zebra terminal scanners. DataWedge is barcode-only and does not configure RFID.
- Zebra Scanner Control SDK for external Bluetooth/USB scanners and sled scanners where exposed by the SDK.

When multiple barcode endpoints are available, the plugin reports all of them and leaves active scanner selection explicit. Barcode events include endpoint/source metadata where available, making it visible whether a scan came from the terminal scanner or an attached sled.

The example app includes a barcode tab that lists endpoints by hardware source, allows selecting the active scanner, and shows recent scans with source metadata.

## 17. Migration Guide
See `docs/MIGRATION_vNEXT.md` for detailed behavioral diffs and required upgrade steps.

Treat `0.4.0` as a compatibility-review release:
- Existing RFID and barcode APIs remain available.
- User-facing connection flows should prefer Capture Device orchestration.
- Android apps should be fully rebuilt/reinstalled after upgrading because native Kotlin and generated Pigeon surfaces changed.
- Apps that previously connected RFID and barcode separately should retest setup order and scan routing.

## 18. User Handoff & Hardware Verification
Use `docs/PLUGIN_USER_HANDOFF.md` when passing this release to app teams or plugin consumers.

Use `docs/CAPTURE_DEVICE_MANUAL_TEST_MATRIX.md` for hardware verification across:
- Android phone plus Bluetooth combo reader
- iOS phone plus Bluetooth combo reader
- TC22 docked into RFID sled with TC22 built-in barcode scanner

The example app has four tabs:
- **Capture**: primary setup connection workflow.
- **RFID**: lower-level RFID troubleshooting.
- **Barcode**: lower-level barcode endpoint troubleshooting.
- **Scan Log**: combined RFID and barcode scan evidence, color-coded by type.

## 19. Roadmap & Contributing
Roadmap: `docs/ROADMAP.md`

Contributions welcome once core parity stabilizes. Please include:
1. Clear description & rationale
2. Test scenario or reproducible steps
3. Diagnostics snapshot (if a runtime issue)

## License
See `LICENSE` file.

## Disclaimer
This project is not affiliated with Zebra Technologies. All SDK binaries remain subject to original vendor licensing.
