# Migration Guide (Upcoming Release)

This guide summarizes the changes in the upcoming release you need to review when upgrading from the previous published version (<= 0.0.1 prototype state).

## Summary
The release focuses on reliability, observability, and RF configuration introspection. It introduces a connection state machine, structured error codes, diagnostics snapshots, auto‑reconnect, safer inventory lifecycle, and a watchdog preventing runaway scans. Android implementation is complete; iOS parity is pending (will arrive shortly—treat current iOS side as transitional if consuming early).

## 1. Versioning & Semantic Expectations
- Previous version was a prototype; this release formalizes public API surfaces via Pigeon models.
- Any removal or rename of a Pigeon field will trigger a minor/major version bump. This release is additive (new fields) except where behavioral semantics changed.

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

## 4. Behavioral Changes
| Area | Previous | New Behavior | Impact |
|------|----------|--------------|--------|
| connect() | Could hang indefinitely on some failures | Times out (~10s) then emits `timeout` error and performs one retry attempt internally | Ensure UI surfaces timeout and does not trigger a second manual connect during internal retry window |
| concurrent connect calls | Second call might race or crash | Second call returns `alreadyConnecting` error immediately | Gate your connect button while state = CONNECTING |
| unexpected disconnect | Required manual reconnect | Triggers auto‑reconnect with capped exponential backoff | Remove custom reconnect loops to avoid duplication |
| inventory start/stop | Possible duplicate or out‑of‑order calls | Guarded via `inventoryActive` and debounced trigger events | Simplifies app logic; rely on safe methods |
| runaway scan (stuck trigger) | Possible indefinite tag stream | Inventory force‑stops after watchdog conditions | If long sessions required, plan for future configurability |
| trigger suppression needed | Not possible to mute trigger without disconnect | `setScanningEnabled(false)` gates trigger events | Simplifies UX on non‑inventory pages |

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
- None (no removed public Pigeon fields). All changes additive.
- Implicit “fire-and-forget” connect semantics are effectively deprecated—client code should observe state stream.

## 8. iOS Parity Status
Pending replication of: timeout, auto‑reconnect, watchdog, structured errors, diagnostics. Until complete, iOS will lack these features (calls may succeed but without new resilience semantics). Avoid relying on diagnostics for iOS in mixed deployments temporarily.

## 9. Testing & Validation Checklist
| Scenario | Expected |
|----------|----------|
| Disconnect device mid-inventory | Auto‑reconnect attempts begin; inventory not restarted automatically (you decide policy) |
| Trigger held beyond 30s | Inventory stops automatically; state indicates not active |
| No tags seen for >5s during inventory | Inventory stops (inactivity) |
| Bluetooth interference causing slow connect | Timeout fires → retry once → either success or error surfaced |
| Rapid double-tap Connect button | Second tap yields `alreadyConnecting` error (should be disabled in UI) |

## 10. Action Items for Upgraders
1. Update to new plugin version in `pubspec.yaml`.
2. Review error stream handling; replace generic exception logic.
3. Add optional diagnostics panel or logging on failure reports.
4. Remove manual reconnect loops.
5. QA the watchdog behavior with your operational tag density.

## 11. Future Configuration (Not Yet Implemented)
Planned tunables (subject to change):
- `connectTimeoutMs`
- `maxReconnectAttempts` / `reconnectBackoffStrategy`
- `inventoryMaxSessionMs` / `inventoryInactivityMs`
- Log level and external logger delegate

## 12. Support & Feedback
Open issues with: diagnostics snapshot, error code, reader model, reproduction steps. Include platform (Android/iOS), SDK device model, and plugin version.

---
Generated: 2025-09-29
