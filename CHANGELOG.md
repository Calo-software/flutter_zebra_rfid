## 0.4.10 - 2026-07-28

### Fixed
- Skip DataWedge initialization and health commands on Samsung Scanner SDK Capture Devices.
- Expose RFID Reader firmware through the Capture Device capability for support diagnostics.

## 0.4.9 - 2026-07-28

### Fixed
- Wait for DataWedge health and scanner enumeration before completing initial Capture Device discovery.
- Adopt a late TC22 internal-imager endpoint and recover barcode capture without reconnecting healthy RFID.
- Suppress Scanner SDK initialization on Zebra TC-series terminals while preserving it for Samsung Bluetooth RFD40+ devices.
- Require a usable DataWedge scanner state before reporting the barcode capability connected.

## 0.4.8 - 2026-07-28

### Added
- Add a privacy-filtered, bounded Capture Device diagnostic buffer and export/clear API for remote support.
- Record Capture Device discovery, capability, recovery, DataWedge, and supported Scanner SDK lifecycle events without scanned values.
- Include the connected Zebra RFID Reader power state and SDK failure details in diagnostics on Android so support can distinguish standby, active, Bluetooth-off, unsupported commands, and unavailable sessions.

### Changed
- Persist at most 500 recent diagnostic events, 15 minutes, or 256 KB in app-private Android storage.
- Keep compatible empty Capture Device diagnostic exports on iOS.

## 0.4.7 - 2026-07-27

### Added
- Add stable RFID hardware identity and foreground lifecycle control to the Capture Device API.

### Changed
- Make Capture Device orchestration the connection and recovery owner for its RFID Reader and Barcode Endpoint.
- Restore RFID configuration and trigger ownership before activating barcode capture.

### Fixed
- Reconcile non-cancellable late RFID connection completions instead of ignoring successful native sessions.
- Prevent a timeout, duplicate readiness request, or page lifecycle event from starting a competing Zebra connection.
- Terminate invalidated RFID sessions before retrying and rebind tag events once during recovery.
- Prevent low-level RFID and barcode connection calls from competing with an active Capture Device.

## 0.4.5 - 2026-07-22

### Added
- Expose an explicit `BatteryData.percentage` getter while retaining `level` for source compatibility.
- Identify battery feedback as a Zebra reader event or Zebra PP+ battery statistics.
- Surface PP+ battery health and charge-cycle counts when supported by the connected sled.

### Changed
- Report Zebra battery statistics for supported RFD40/RFD90 sleds and request fresh battery status automatically after iOS connection.
- Demonstrate percentage, source, charging, health, cycle count, freshness, and estimation state in the example app.

### Fixed
- Remove the Android voltage-curve fallback that could present a guessed percentage as reader battery status.
- Clear stale battery feedback from the example UI when the RFID Reader disconnects.
- Serialize Android DataWedge commands and correlate their results so profile setup, scanner enumeration, and recovery cannot overlap.
- Configure Barcode, RFID-disable, and Intent plug-ins in one DataWedge profile update instead of racing three broadcasts.
- Rely on DataWedge app-profile association instead of issuing an invalid `SWITCH_TO_PROFILE` command for an already-associated foreground app.
- Wait for DataWedge profile transitions and repair the scanner according to its reported state (`ENABLE_PLUGIN` when disabled, `RESUME_PLUGIN` only when suspended).
- Serialize RFID device-status refreshes behind reader setup and retry transient SDK lock contention without failing an established reader connection.
- Make barcode refresh check DataWedge health and automatically recover a selected scanner in `IDLE` or `DISABLED` state.
- Add `recoverActiveBarcodeScanner()` for an explicit operator-triggered recovery that verifies the scanner returns to `WAITING` or `SCANNING`.

## 0.4.4 - 2026-07-07

### Fixed
- Stop Android RFID inventory on every normal trigger release, including very fast trigger taps that were previously ignored by the release debounce and could leave inventory active until watchdog recovery.
- Configure the Android Zebra stop trigger as a handheld release trigger with timeout so native reader state matches the press-to-scan, release-to-stop workflow.
- Treat Android powered-off or unreachable reader connect failures as `DISCONNECTED` instead of terminal `ERROR`.

## 0.4.3 - 2026-06-23

### Changed
- Refreshed the bundled Android Zebra RFID SDK to API3 `2.0.5.275`.
- Consolidated Android RFID and scanner-control classes onto Zebra's `rfidapi3lib-2.0.5.275.aar` to avoid duplicate classes from the ZIOTC and legacy barcode AARs.
- Replaced the iOS static Zebra SDK libraries with Zebra RFID and Scanner XCFrameworks from iOS SDK `1.1.94`.
- Raised Android `minSdk` from 28 to 30 to match Zebra's API3 `2.0.5.275` sample applications.

### Migration Notes
- No public Dart API migration is required.
- Android consumers must perform a clean rebuild and reinstall after upgrading because the native Zebra SDK binaries changed.
- Apps requiring Android API 28 or 29 must remain on an earlier plugin release with the older Zebra SDK bundle.

## 0.4.1 - 2026-06-17

### Fixed
- Treat Android Zebra RFID sled battery removal and Bluetooth disconnection events as normal `DISCONNECTED` state transitions instead of surfacing transient SDK reconnect failures as diagnostics.
- Prevent Android auto-reconnect failures from leaving stale diagnostics after the reader has already settled to `DISCONNECTED`.
- Avoid self-triggered Android Bluetooth connect retries by clearing the connect timeout once the blocking SDK `connect()` call succeeds, before slower reader configuration continues.

### Changed
- Refactored Capture Device planning into shared Android and iOS planner modules. Public Capture, RFID, and Barcode API shapes are unchanged.

### Migration Notes
- No public Dart API migration is required for existing consumers.
- Android consumers should still perform a full rebuild/reinstall after upgrading because this release changes native Kotlin behavior.
- Apps that show custom diagnostics from `onReaderConnectionError` should expect fewer error callbacks during physical sled removal; use connection status `DISCONNECTED` as the user-facing state for that case.

## 0.4.0 - 2026-06-16

### Added
- Capture Device orchestration API above the existing RFID and barcode APIs. Apps can now discover and connect one physical working setup while the plugin coordinates RFID Reader and Barcode Endpoint activation.
- Barcode endpoint APIs for built-in terminal DataWedge scanners, RFID sled scanners, and external Zebra Scanner SDK scanners.
- Combined example-app Capture Dashboard and Scan Log tabs. The Scan Log color-codes RFID and barcode reads and keeps recording while users move between tabs.
- User-facing context, ADR, migration, handoff, and manual hardware verification docs for Capture Device workflows.

### Changed
- Android `ReaderConnectionType.all` now prefers local Zebra terminal transports before Bluetooth, and suppresses Bluetooth fallback when a local USB/serial RFID reader is found.
- Android Scanner SDK discovery suppresses USB CDC on Zebra/TC-series terminals so the RFID sled USB path remains owned by the RFID SDK. Bluetooth Scanner SDK discovery remains enabled.
- The example app now opens on the Capture Dashboard; lower-level RFID and Barcode pages are advanced debug tabs.
- Dart wrapper callback streams are shared across API instances so app pages do not replace each other's native callback handlers.

### Fixed
- Prevent Android Bluetooth Scanner SDK startup from crashing when `BLUETOOTH_CONNECT` is missing.
- Prevent barcode DataWedge receivers from treating RFID-looking DataWedge payloads as barcode scans.
- Prevent example RFID page stream listeners from calling `setState()` after disposal.
- Improve RFID and Barcode debug page layouts so connection/status cards do not crowd scan evidence.

### Migration Notes
- This is a compatibility-review release. Existing RFID APIs remain available, but apps should consider moving user-facing connection flows to the Capture Device API.
- Apps that previously connected RFID and barcode independently may see different default discovery/selection behavior on Zebra terminals.
- Perform a full rebuild/reinstall after upgrading Android apps because native Kotlin and generated Pigeon surfaces changed.

### Fixed
- USB reader discovery no longer fails when users deny Bluetooth permissions. Bluetooth permissions are only required when discovering wireless readers; USB enumeration now proceeds even if BLE access is withheld.

## 0.3.0 - 2025-09-30

### Changed
- Android minSdk raised from 26 to 28 to satisfy Zebra API3 (FINDIT 2.0.5.214) AAR manifest requirement. Projects needing API <28 must either:
	- Remove `API3_FINDIT` (and any dependent AARs) from `android/RFIDAPI3Library`, or
	- Use an earlier Zebra SDK bundle whose AARs declare a lower minSdk, or
	- (Not recommended) apply `tools:overrideLibrary` which risks runtime crashes on older devices.
- Build toolchain updated: Gradle 8.8, Android Gradle Plugin 8.6.0, Kotlin 2.1.0, compileSdk 36 (required by `integration_test` and forward compatibility).
- Zebra SDK `.aar` dependencies are now auto-published to a local Maven repo (`android/localMaven`) during the first build; Gradle logs `[zebra] Published ...` when publication occurs. This avoids broken AAR packaging caused by direct local `.aar` dependencies.

### Notes
- Consumers do not need manual steps beyond the new minSdk 28 requirement. To regenerate the local Maven cache manually, run `./gradlew publishZebraAarsToLocalMaven` inside the plugin's `android/` directory.
- This is a build-time change only; runtime RFID logic is unchanged from 0.2.x.

## 0.2.0 - 2025-09-29

### Added
- Scanning suppression API: `setScanningEnabled(bool enabled)` to globally gate hardware trigger initiated inventory and stop active sessions when disabled.
- Diagnostics enrichment: `scanningEnabled`, `scanningEnabledLastToggleMs` fields.

### Documentation
- README section on Scanning Suppression / Quiet Mode.
- Migration guide updated with suppression concept row.

## 0.1.0 - 2025-09-29

### Added
- Connection timeout (10s) with single retry and structured `timeout` error.
- Structured error taxonomy (`ReaderErrorCode`) for programmatic handling.
- Diagnostics snapshot API (connectionState, attempts, last error, durations, locating flag).
- Auto-reconnect with capped exponential backoff (1s → 2s → 4s → 8s → 15s, max 5 attempts).
- RF parameter exposure: `rfModeTableIndex` (placeholder / null on Android currently), `receiveSensitivityIndex` (read-only).
- Inventory safeguards: guarded start/stop, debounce on trigger release, delayed purge to avoid premature clears.
- Inventory watchdog: stops runaway sessions after max duration (30s) or inactivity (5s no tags).
- Migration guide (`docs/MIGRATION_vNEXT.md`).

### Changed
- Connection model now enforces state machine preventing duplicate concurrent connects.
- Error reporting unified via error stream instead of relying on thrown exceptions.

### Fixed
- Build issues related to rfMode accessor mismatch (now returns nullable placeholder on Android until fully supported).
- Potential runaway scanning when trigger release not detected.

### Pending (iOS Parity)
- iOS implementation will receive the same timeout, auto-reconnect, diagnostics, and watchdog logic in a subsequent update.

### Notes
- All Pigeon schema changes are additive; no breaking field removals.
- Remove custom manual reconnect loops to avoid conflict with built-in policy.

## 0.0.1

Initial prototype release.
