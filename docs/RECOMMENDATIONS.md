# Recommendations (Actionable Improvements)

This document enumerates prioritized actionable improvements derived from the feature & gap analysis.

## Priority Legend
- High: Improves stability / developer experience directly
- Medium: Adds meaningful observability or configurability
- Low: Ergonomic or long-term architectural enhancement

## Short-Term (High Priority)
1. Connection Timeout & Retry
   - Add 10s timeout with single retry (exponential backoff seed 2s)
   - Emit `ReaderErrorCode.timeout` on expiry
   - Cancel timer on success / disconnect
2. Diagnostics Snapshot API
   - Expose: connectionState, connectAttempts, lastError, lastConnectStartTimestamp, lastConnectDurationMs, isLocating
   - Pigeon `Diagnostics` class + `diagnostics()` method
3. RF Parameter Exposure
   - Extend `ReaderConfig` with `rfModeTableIndex`, `receiveSensitivityIndex` (read-only initially; set rfMode if provided)
   - Provide pass-through in `getReaderConfig()`
4. Auto-Reconnect Policy
   - Trigger on unexpected disconnect (not user initiated)
   - Attempts: 3 (configurable constant), backoff: 2s, 4s, 8s
   - Emits intermediate status updates + errors on exhaustion
5. iOS Parity
   - Mirror state machine, timeout, diagnostics, RF params, auto-reconnect
6. Logging Layer
   - Introduce `LogLevel { verbose, debug, info, warn, error, none }`
   - Wrapper to gate logs; support host injection
7. Remove Unsafe Null Assertions
   - Replace `!!` with safe calls + guarded error emission

## Mid-Term (Medium Priority)
1. Memory Bank Selective Read API (EPC/TID/USER toggle)
2. Tag Throughput Metrics (tags/sec rolling window in diagnostics)
3. Config Persistence Verification (`restorePersistedConfig()`)
4. Structured Error Enrichment (vendor codes, stack traces optional)
5. Buffered Tag Stream (window & dedupe TTL)

## Long-Term (Low / Strategic)
1. Kotlin Coroutines + Flow migration
2. Sealed classes for state & events
3. Dependency Injection boundary (interface over Zebra SDK)
4. Structured logging sinks (JSON, remote, test harness)
5. Event replay for deterministic tests
6. Multi-reader orchestration support

## Risk Mitigations
| Risk | Mitigation |
|------|------------|
| Timeout too aggressive | Make configurable; default 10s |
| Reconnect loop thrashing | Exponential backoff + max attempts + cooldown |
| Backward incompatibilities | Additive Pigeon schema changes only |
| iOS drift from Android | Implement parity immediately after Android core changes |

## Metrics to Track Post-Implementation
- Connect Success Rate (%)
- Mean Connect Duration (ms)
- Timeout Count / Total Attempts
- Reconnect Attempts Before Success (distribution)
- Tags/sec (avg + p95) when inventory active

---
Generated on: 2025-09-29
