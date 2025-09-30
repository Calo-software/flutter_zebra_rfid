## Unreleased

- No changes yet.

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
