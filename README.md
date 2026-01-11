# flutter_zebra_rfid

Reliable Flutter plugin for Zebra RFID readers (Android + iOS). Focus areas: connection resilience, structured errors, diagnostics visibility, and safer inventory lifecycle.

> **Note:** Large portions of this repository (code, Gradle wiring, and documentation) were generated or refactored with help from large language models and then reviewed in this project.

> Status: Android reliability features complete (timeout, retry, auto‑reconnect, diagnostics, watchdog). iOS parity for these features is upcoming.

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
12. Migration Guide
13. Roadmap & Contributing

## 1. Features
- Indexed reader discovery + guarded connection state machine
- Connection timeout (10s) with single internal retry
- Structured `ReaderErrorCode` taxonomy
- Diagnostics snapshot (attempt counts, last error, durations, locating)
- Auto‑reconnect (bounded exponential backoff) on unexpected disconnect
- Safe inventory start/stop with trigger debounce & watchdog (max duration + inactivity)
- Tag locating support
- RF parameter introspection (receiveSensitivityIndex, rfModeTableIndex placeholder)

## 2. Getting Started
Add dependency in your `pubspec.yaml` (version placeholder below):
```yaml
dependencies:
    flutter_zebra_rfid: ^0.2.0
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
- Minimum SDK: 28 (required by Zebra API3 FINDIT AAR)
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
- All Zebra `.aar` binaries live in `android/RFIDAPI3Library` and are published to a local Maven repository (`android/localMaven`) during the first Android build.
- No manual action is required when consuming the plugin via pub or as a path dependency; Gradle prints `[zebra] Published ...` logs on the first run.
- If the artifacts are ever deleted, rerun:
    ```bash
    cd android
    ./gradlew publishZebraAarsToLocalMaven
    ```
    (This task is also invoked automatically by `flutter build/run` when needed.)
- USB-only deployments: as of the next release, BLE permissions are requested only when Bluetooth discovery is required. If users deny BLE prompts, USB reader discovery still succeeds.

## 4. iOS Setup Notes
- Enable Background Modes: External accessory communication, Uses BLE accessories
- Add supported external accessory protocols for your Zebra device (see Zebra docs)
- Parity features (timeout, auto‑reconnect, diagnostics watchdog) pending—check CHANGELOG.

## 5. Basic Usage
Pseudo-flow in your app:
```dart
final api = FlutterZebraRfid();
final readers = await api.getAvailableReaders();
if (readers.isEmpty) {
    // handle no readers
}
await api.connect(index: 0); // triggers async connect sequence

// Listen for status / tag events (actual stream names may differ in implementation)
api.onTags.listen((tags) { /* update UI */ });
api.onErrors.listen((err) { /* switch on err.code */ });
api.onStatus.listen((s) { /* connection state updates */ });

// Start inventory
await api.startInventory();

// Stop inventory (safe to call even if already stopped)
await api.stopInventory();

// Diagnostics snapshot
final diag = await api.diagnostics();
print('Connect attempts: ${diag.connectAttempts} last error: ${diag.lastErrorCode}');
```

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

## 12. Migration Guide
See `docs/MIGRATION_vNEXT.md` for detailed behavioral diffs and required upgrade steps (timeouts, watchdog, auto‑reconnect implications).

## 13. Roadmap & Contributing
Roadmap: `docs/ROADMAP.md`

Contributions welcome once core parity stabilizes. Please include:
1. Clear description & rationale
2. Test scenario or reproducible steps
3. Diagnostics snapshot (if a runtime issue)

## License
See `LICENSE` file.

## Disclaimer
This project is not affiliated with Zebra Technologies. All SDK binaries remain subject to original vendor licensing.

