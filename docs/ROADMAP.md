# Roadmap

This roadmap captures prioritized implementation tasks and advanced enhancement ideas for `flutter_zebra_rfid`.

## 1. Short-Term (Stability & Reliability)
1. Connection timeout (10s default) and single retry with backoff
2. Diagnostics snapshot API (counters + last error + durations)
3. Expose RF parameters (rfModeTableIndex, receiveSensitivityIndex)
4. Auto-reconnect (bounded attempts + exponential backoff)
5. iOS parity for error taxonomy + state machine
6. Log level abstraction (debug/info/warn/error) with pluggable logger
7. Convenience `connectByName(String)` wrapper
8. Remove remaining unsafe `!!` usages
9. Basic integration tests + GitHub Actions CI (build + analyze)

## 2. Mid-Term (Observability & Configurability)
1. Extended diagnostics metrics (tags/sec rolling window, reconnect histogram)
2. Structured error enrichment (vendor codes, underlying exception types)
3. Config persistence verification & restore method
4. Memory bank selective read API (EPC/TID/USER granular access)
5. Tag burst buffering + coalescing window (reduce UI churn)

## 3. Long-Term (Architecture & Performance)
1. Kotlin coroutines + Flow-based event streams (backpressure-aware)
2. Sealed classes for connection/inventory states
3. Dependency injection boundary for Zebra SDK (test doubles & simulation)
4. Structured logging adapters (Logcat, JSON, remote sink)
5. Tag processing pipeline with pluggable filters (RSSI thresholds, dedupe TTL)
6. Performance profiling hooks (trace connect duration, inventory latency)

## 4. Advanced / Stretch Ideas
- Replayable event journal for deterministic testing
- Crash-safe recovery (persist last state; resume after app restart)
- Multi-reader orchestration layer (round-robin inventory coordination)
- Power management adaptations (dynamic power adjustments by battery level)
- Telemetry export (OpenTelemetry traces/spans for connect/inventory cycles)

## 5. Implementation Notes
- All Pigeon schema changes must be additive to preserve backward compatibility.
- Timeout + retry should precede auto-reconnect (foundation reuse).
- Diagnostics object should avoid heavy/expensive computations; snapshot must be O(1).
- Logging abstraction: use interface with default implementation; allow host app override.
- Gradually migrate from direct Handler usage to coroutines once baseline feature set stable.

---
Generated on: 2025-09-29
