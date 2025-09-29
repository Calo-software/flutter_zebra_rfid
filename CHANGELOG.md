## Unreleased

_No changes yet._

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
