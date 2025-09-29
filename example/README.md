# flutter_zebra_rfid_example

Demonstrates usage of the `flutter_zebra_rfid` plugin, including:

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
1. Select connection type (All / USB / Bluetooth) using the dropdown.
2. Tap "Get Reader List" to populate available paired readers.
3. Tap a reader entry → choose Connect.
4. On success you will see connection + battery icons; tag reads will appear when you pull the physical trigger.
5. Press "Status" (via the reader popup) to manually request battery/device status.
6. Review Diagnostics & Errors panels (auto-updated on error; Refresh button for manual snapshot).

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
## 7. Refreshing Diagnostics Manually
Tap the Refresh button inside the Diagnostics panel to capture a current snapshot at any time.

---
## 8. Extending Tests (Optional)
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
## 9. Roadmap Alignment
This example currently surfaces features through step 6 of the internal roadmap (diagnostics). Upcoming additions (auto‑reconnect, RF parameter exposure in UI, iOS parity) will extend this guide.

---
## 10. Troubleshooting
| Symptom | Suggestion |
| ------- | ---------- |
| No readers found | Confirm device paired / transport mode, toggle connection type, ensure Bluetooth ON |
| Timeout always happens | Move reader closer, ensure it’s powered, verify battery | 
| Battery data blank | Tap Status; some devices delay initial battery event |
| Tags not appearing | Ensure trigger pressed; check antenna power configuration |

---
## 11. Related Docs
See `/docs/FEATURE_GAP_ANALYSIS.md`, `/docs/RECOMMENDATIONS.md`, and `/docs/ROADMAP.md` for deeper context.

---
Generated on: 2025-09-29
