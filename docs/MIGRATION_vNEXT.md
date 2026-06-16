# Migration Guide: 0.4.0 Capture Device Release

This guide summarizes the changes to review when upgrading to `flutter_zebra_rfid` `0.4.0`.

## Summary
The release adds Capture Device orchestration above the existing RFID and barcode APIs. It lets apps present one physical capture setup to users while the plugin coordinates the RFID Reader and Barcode Endpoint behind the scenes.

The release also keeps the earlier reliability, observability, and RF configuration improvements: structured errors, diagnostics snapshots, auto-reconnect, safer inventory lifecycle, and watchdog behavior.

## 1. Versioning & Semantic Expectations
- `0.4.0` is a compatibility-review release.
- Existing RFID and barcode APIs remain available.
- New Capture Device and barcode endpoint APIs are additive.
- Default Android discovery/orchestration behavior changed, so user-facing connection flows should be retested.

## 2. New Concepts
| Concept | Description | Developer Action |
|--------|-------------|------------------|
| Connection State Machine | Explicit guarded transitions (DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR) avoid duplicate concurrent connects | Update UI/logic if you previously assumed immediate connect success. Listen for state stream before starting inventory. |
| Structured Error Codes (`ReaderErrorCode`) | Categorized failures (e.g. `timeout`, `alreadyConnecting`, `notConnected`, `sdkOperationFailure`) | Switch from generic try/catch logic to code‐based handling (e.g. show retry option on `timeout`). |
| Diagnostics Snapshot | O(1) snapshot with counts, durations, last error metadata | Use for support telemetry or debug panels. Avoid calling inside tight loops. |
| Auto‑Reconnect | Exponential backoff (1s → 2s → 4s → 8s → 15s, max 5 attempts) after unexpected disconnect | If app previously auto‑called `connect()` in error handler, remove to prevent conflict with built‑in policy. |
| Inventory Watchdog | Stops inventory after 30s max session or 5s inactivity (no tag reads) | If you relied on indefinite inventory, adjust by re‑starting inventory on demand or make watchdog configurable (future option). |
| RF Parameters Exposure | `rfModeTableIndex` (placeholder null on Android currently), `receiveSensitivityIndex` (read‑only) | Use for diagnostics; do not rely on rfMode being settable yet. |
| Scanning Suppression (setScanningEnabled) | Allows globally disabling trigger‑initiated inventory and stops any running session | Call `setScanningEnabled(false)` when on non‑scanning screens; re‑enable when entering scan workflows. Diagnostics now includes `scanningEnabled`. |
| Capture Device | User-facing physical capture setup with RFID and barcode capabilities | Prefer this for normal app connection flows instead of asking users to connect RFID and barcode separately. |
| Barcode Endpoint | A specific barcode path, such as DataWedge built-in terminal scanner or Scanner SDK external scanner | Use override APIs when automatic grouping is uncertain. |

## 3. API Additions (Pigeon Schema)
Added fields / classes (names may vary slightly pending final generation):
- `Diagnostics` class with:
  - `connectionState`
  - `connectAttempts`
  - `lastErrorCode`
  - `lastErrorMessage`
  - `lastConnectStartTimestamp`
  - `lastConnectDurationMs`
  - `isLocating`
  - `scanningEnabled` (new)
  - `scanningEnabledLastToggleMs` (new)
- `ReaderConfig` new fields:
  - `rfModeTableIndex` (nullable Integer/Long)
  - `receiveSensitivityIndex` (nullable Integer/Long)
- Host method: `diagnostics()` (returns `Diagnostics`)
- New `FlutterZebraDataCaptureApi` facade with `rfid`, `barcode`, and `capture` APIs.
- New Capture Device host methods:
  - `refreshCaptureDevices()`
  - `connectCaptureDevice(captureDeviceId, rfidConfig?)`
  - `disconnectCaptureDevice(captureDeviceId)`
  - `setCaptureDeviceBarcodeOverride(captureDeviceId, barcodeEndpointId)`
- New Capture Device streams:
  - `onAvailableCaptureDevicesChanged`
  - `onActiveCaptureDeviceChanged`
  - `onCaptureDeviceStatusChanged`
- New barcode endpoint APIs and streams:
  - `refreshBarcodeScanners()`
  - `setActiveBarcodeScanner(endpointId)`
  - `clearActiveBarcodeScanner()`
  - `activeBarcodeScanner`
  - `onAvailableBarcodeScannersChanged`
  - `onActiveBarcodeScannerChanged`
  - `onBarcodeRead`

## 4. Behavioral Changes
| Area | Previous | New Behavior | Impact |
|------|----------|--------------|--------|
| connect() | Could hang indefinitely on some failures | Times out (~10s) then emits `timeout` error and performs one retry attempt internally | Ensure UI surfaces timeout and does not trigger a second manual connect during internal retry window |
| concurrent connect calls | Second call might race or crash | Second call returns `alreadyConnecting` error immediately | Gate your connect button while state = CONNECTING |
| unexpected disconnect | Required manual reconnect | Triggers auto‑reconnect with capped exponential backoff | Remove custom reconnect loops to avoid duplication |
| inventory start/stop | Possible duplicate or out‑of‑order calls | Guarded via `inventoryActive` and debounced trigger events | Simplifies app logic; rely on safe methods |
| runaway scan (stuck trigger) | Possible indefinite tag stream | Inventory force‑stops after watchdog conditions | If long sessions required, plan for future configurability |
| trigger suppression needed | Not possible to mute trigger without disconnect | `setScanningEnabled(false)` gates trigger events | Simplifies UX on non‑inventory pages |
| user connects RFID + barcode | App had to connect each SDK path separately | Capture Device connect coordinates RFID and barcode capability activation | Move primary UI to Capture Device; keep direct pages for debug |
| TC22/RFD sled discovery | Barcode Scanner SDK could touch the sled over USB CDC | Scanner SDK USB CDC is suppressed on Zebra/TC terminals so RFID SDK owns sled USB | Retest TC22 sled flows after full reinstall |
| USB RFID discovery | Could be affected by Bluetooth permission flow | Local USB/serial transports are preferred before Bluetooth on Zebra terminals | Bluetooth permission prompts should not block TC22 USB RFID discovery |

## 5. Error Handling Migration
Instead of catching broad exceptions, subscribe to the plugin’s error/status stream and switch on `ReaderErrorCode`. Recommended mapping:
- `timeout`: Show retry or allow user to cancel
- `alreadyConnecting`: Disable UI action temporarily
- `notConnected`: Prompt to connect before inventory
- `sdkOperationFailure`: Show generic recoverable error
- `invalidReaderIndex` / `noAvailableReaders`: Prompt user to select a device

## 6. UI / UX Recommendations
- Show connection progress (spinner) while state = CONNECTING.
- On timeout, highlight with actionable button (“Retry”).
- Display diagnostics snapshot on a hidden debug panel (long‑press or developer menu).
- Auto-dismiss diagnostics panel when connection returns to CONNECTED (example app behavior).

## 7. Removal / Deprecations
- None. No public Pigeon fields were removed.
- Implicit "fire-and-forget" connect semantics remain discouraged. Client code should observe status streams.
- Manually connecting RFID and barcode separately is now an advanced/debug flow. Prefer Capture Device orchestration for normal operator workflows.

## 8. iOS Parity Status
iOS supports external Zebra reader/scanner surfaces reported by the existing SDK integrations. iOS Bluetooth pairing helpers are not included in this slice. Avoid building UX that expects the plugin to pair iOS Bluetooth devices.

## 9. Testing & Validation Checklist
| Scenario | Expected |
|----------|----------|
| Disconnect device mid-inventory | Auto‑reconnect attempts begin; inventory not restarted automatically (you decide policy) |
| Trigger held beyond 30s | Inventory stops automatically; state indicates not active |
| No tags seen for >5s during inventory | Inventory stops (inactivity) |
| Bluetooth interference causing slow connect | Timeout fires → retry once → either success or error surfaced |
| Rapid double-tap Connect button | Second tap yields `alreadyConnecting` error (should be disabled in UI) |
| Android phone plus Bluetooth combo reader | One Capture Device appears; RFID and barcode both scan after one connect action |
| TC22 docked into RFID sled | One Capture Device groups sled RFID with built-in terminal barcode |
| Barcode activation followed by RFID scan | RFID remains on RFID stream; barcode remains on barcode stream |
| Scan Log tab | RFID entries are green; barcode entries are blue |

## 10. Action Items for Upgraders
1. Update to `flutter_zebra_rfid: ^0.4.0`.
2. Full rebuild/reinstall Android apps after upgrade.
3. Move primary connection UI to Capture Device orchestration where possible.
4. Keep direct RFID and Barcode APIs for debug/escape hatches.
5. Review error stream handling; replace generic exception logic.
6. Remove manual reconnect loops.
7. QA watchdog behavior with your operational tag density.
8. Run `docs/CAPTURE_DEVICE_MANUAL_TEST_MATRIX.md`.

## 11. Future Configuration (Not Yet Implemented)
Planned tunables (subject to change):
- `connectTimeoutMs`
- `maxReconnectAttempts` / `reconnectBackoffStrategy`
- `inventoryMaxSessionMs` / `inventoryInactivityMs`
- Log level and external logger delegate

## 12. Support & Feedback
Open issues with: diagnostics snapshot, error code, reader model, reproduction steps. Include platform (Android/iOS), SDK device model, and plugin version.

---
Generated: 2026-06-16
