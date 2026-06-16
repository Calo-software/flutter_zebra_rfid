# flutter_zebra_rfid_example

Demonstrates usage of the `flutter_zebra_rfid` plugin, including:

* Capture Device discovery & one-action connection for RFID + barcode setups
* Combined Scan Log for color-coded RFID and barcode evidence
* Reader discovery & connection
* Connection timeout & automatic single retry
* Structured connection errors (`ReaderErrorCode`)
* Diagnostics snapshot (attempt counters, last error, timings)
* Battery status & tag reads

---
## 1. Prerequisites

| Item | Requirement |
| ---- | ----------- |
| Flutter SDK | 3.22+ (stable) |
| Android | Reader paired via Bluetooth or cabled (USB) |
| iOS | (Parity in progress – core features tested on Android) |
| Permissions | Bluetooth scan / connect granted (Android 12+) |

Ensure the Zebra RFID SDK JARs are present (already bundled in the plugin's `android/RFIDAPI3Library`).

---
## 2. Running the Example

From the repository root:

```bash
flutter pub get
cd example
flutter run
```

If multiple devices/emulators are attached provide `-d <deviceId>`.

---
## 3. Workflow Overview
1. Use the Capture tab first. Tap "Refresh Capture Devices" to discover physical working setups.
2. Tap "Connect" on a Capture Device to connect RFID and activate/connect the matching barcode endpoint together.
3. Confirm RFID and barcode capability status side by side. If one side fails, the Capture Device becomes degraded and shows the failing capability.
4. Use "Barcode Override" when the plugin cannot confidently match the desired barcode endpoint.
5. Open Scan Log to confirm RFID and barcode scans remain separated by type.
6. Use the RFID and Barcode tabs for lower-level SDK troubleshooting.
7. Review Diagnostics & Errors panels on the RFID tab when investigating reader-specific failures.

---
## 4. Diagnostics Panel Fields
| Field | Meaning |
| ----- | ------- |
| State | Current connection status (mapped from internal state machine) |
| Attempts | Total connection attempts since app start (includes retries) |
| Last Error Code | Most recent `ReaderErrorCode` emitted |
| Last Error Msg | Human-readable message for last error |
| Last Connect Start | Epoch ms timestamp of last connect attempt start |
| Last Connect Duration ms | Duration of the last successful connect (ms) |
| Locating | Whether multi-tag locating is active |

---
## 5. Inducing & Observing Errors
| Scenario | How to Trigger | Expected Outcome |
| -------- | ------------- | ---------------- |
| Timeout | Power off reader or move out of range before connecting | After ~10s: Error panel shows `timeout`; second attempt auto-retries once |
| Invalid index | Rapidly connect before list loads (unlikely in UI) | `invalidReaderIndex` (guarded) |
| Duplicate connect | Tap connect twice quickly | `alreadyConnecting` emitted, no crash |
| SDK failure | Disconnect reader mid-connect (rare) | `sdkOperationFailure` if SDK reports vendor error |

Errors appear in the red panel. Diagnostics update automatically afterward.

---
## 6. Tag Reading
Trigger press (physical) starts inventory automatically; release stops it.

Each tag shows EPC ID with RSSI. (Burst buffering & memory banks reading roadmap items not yet implemented.)

---
## 7. Scan Log
The Scan Log tab records both RFID and barcode reads while you move between tabs.

- RFID reads are green.
- Barcode reads are blue.
- Use this tab during hardware verification to confirm RFID reads do not appear in the barcode stream after barcode activation.

---
## 8. Refreshing Diagnostics Manually
Tap the Refresh button inside the Diagnostics panel to capture a current snapshot at any time.

---
## 9. Extending Tests (Optional)
You can script repeated connect/disconnect cycles to validate counters:
```dart
for (var i = 0; i < 5; i++) {
	await api.connectReader(readerId: 0);
	await Future.delayed(const Duration(seconds: 2));
	final d = await api.diagnostics();
	print('Attempt ${i + 1}: ${d.connectAttempts} state=${d.connectionState}');
	await api.disconectCurrentReader();
}
```

---
## 10. Roadmap Alignment
This example now surfaces the primary Capture Device workflow plus lower-level RFID and Barcode debug flows.

---
## 11. Troubleshooting
| Symptom | Suggestion |
| ------- | ---------- |
| No readers found | Confirm device paired / transport mode, toggle connection type, ensure Bluetooth ON |
| Timeout always happens | Move reader closer, ensure it’s powered, verify battery | 
| Battery data blank | Tap Status; some devices delay initial battery event |
| Tags not appearing | Ensure trigger pressed; check antenna power configuration |
| RFID stops after barcode connects on TC22 sled | Reinstall latest build and confirm logs show Scanner SDK USB CDC is suppressed on Zebra terminal |

---
## 12. Related Docs
See `/docs/PLUGIN_USER_HANDOFF.md`, `/docs/CAPTURE_DEVICE_MANUAL_TEST_MATRIX.md`, `/docs/FEATURE_GAP_ANALYSIS.md`, `/docs/RECOMMENDATIONS.md`, and `/docs/ROADMAP.md` for deeper context.

---
Generated on: 2026-06-16
