# TC22 / RFD40 recovery investigation

Prepared 10 September 2026. Initial investigation and subsequent example-app hardware results are recorded below. Version 0.4.13 packages this candidate; Ledger remains unchanged.

## Incident evidence

Sentry issues: https://lime-digital.sentry.io/issues/7648649707/ and https://lime-digital.sentry.io/issues/7643670206/.

On Ledger 1.4.47+63, the same TC22 reported connection failures, recovery exhaustion at 06:53 NZST, a reboot at 06:55, and serial-worker crashes at 07:07:29, 07:07:34 and 07:08:32. At 08:52:34 it received tags again, then disconnected and crashed at 08:52:37. The operator reportedly separated the TC22 from the RFD40 and removed a battery; reinstallation is possible but unconfirmed. These combined actions do not establish which restored scanning.

Build 63's release commit pins plugin 30733b2e957206faa0277d80d5167c35666f2a7b. The current Ledger lockfile pins 81abc3c528bcb4eaa152b9dc3f305785d03a5276. Both identify as 0.4.12; their recovery paths are unchanged. This worktree starts at 81abc3c. The original plugin checkout has unrelated uncommitted work and was not edited.

## Confirmed defects and local changes

- Managed connect failures could publish a retryable state without disconnecting the failed SDK reader. Rediscovery can return the same object, bypassing replacement-reader cleanup. InvalidUsageException, OperationFailureException and unexpected failures now call the existing retirement helper on the I/O worker before publishing failure or requesting recovery. Region configuration recovery retains its separate existing path.
- The fifth scheduled RFID retry was rejected by the readiness budget guard. The final 15-second timer therefore expired without attempting connection. Scheduled retries now consume their reserved attempt: one initial connection plus five retries. Unscheduled requests still respect exhaustion; explicit connect resets the budget.
- Managed invalid-usage and operation failures now retain their diagnostic error code and details while remaining disconnected. They do not emit an extra recovery request. A successful connection clears the error.

Zebra documents disconnect cleanup even when isConnected is false after a connection attempt: https://techdocs.zebra.com/dcs/rfid/android/2-0-3-162/guide/connection/.

## Automated evidence

Two added regression tests failed against the original implementation, then passed after the fixes. They exercise the real coordinator retry timer and the native wrapper's managed failure callback ordering using a mocked SDK reader.

The full Android unit suite passed: 108 tests, zero failures/errors/skips.

Run from this worktree:

```sh
./android/gradlew -p android testDebugUnitTest
```

Gradle reports an existing compatibility warning: Android Gradle Plugin 8.6.0 was tested through compileSdk 35 while this project uses 36. Compilation and tests completed successfully.

## Limits

The production Already running exception has not been reproduced. Mocked SDK tests prove wrapper behaviour, not vendor serial-worker shutdown. The existing retirement helper logs and swallows disconnect errors; this change does not prove cleanup succeeded in that case or prevent a blocked disconnect. Do not label the fatal crash fixed, suppress its uncaught exception, or claim reinstallation is required.

No dependency pin was changed in Ledger. These changes need review and an isolated test build before fleet use.

## Physical validation when hardware is available

Use a TC22/RFD40, preferably the affected setup, with a known-good RFID tag and barcode. Record the APK/plugin commit, TC22 OS, sled firmware and exact battery removed. Capture Android logcat continuously and export Capture Device diagnostics after each failed cycle. Keep native connect, retirement, USB and lifecycle timestamps.

1. Establish baseline RFID tag reads and TC22 barcode reads.
2. Detach and reattach the TC22/RFD40 while Ledger is foregrounded. Verify automatic or explicit reconnect, a real RFID read, and barcode operation.
3. Repeat with sled battery removal and replacement; separately test background for 15–20 seconds then resume. Change one condition per cycle.
4. Leave the reader unavailable through retry exhaustion. Restore it and explicitly reconnect. Verify another retry sequence starts and reads a tag without clearing app data or reinstalling.
5. Repeat each scenario ten times, including detach during a pending connection. Observe whether SDK cleanup completes before the next connection and whether errors survive into diagnostics.

Pass requires a responsive app, successful post-recovery RFID and barcode reads, and no Already running crash. If cleanup throws/blocks or the crash recurs, preserve that log before trying another recovery action; it is the evidence needed for the next fix.

## Example harness prepared

The example now includes a Capture-screen recovery panel with cycle markers,
per-cycle scan report counts, bounded diagnostic snapshots and copy/log export.
Application lifecycle events are forwarded to the Capture Device API. See
`example/RECOVERY_TEST.md` and `example/tool/capture-recovery-log.sh` for physical
steps and continuous host log capture. The example uses this worktree directly.

Device-free verification: Flutter analysis clean; nine example tests passed;
Android test task successful (cached); debug APK built successfully. No device
installation or hardware acceptance yet. Native candidate files were preserved.

### TC22 first physical cycle (10 September)

Installed the debug example on TC22 serial 24090524700273. Host log capture:
`/tmp/zebra-recovery-tc22-20260910/logcat.txt`. User confirmed baseline RFID and
barcode values appeared, then reported detach/reattach recovery worked.
Cycle 1 marker: 11:04:12 device time; RFID disconnected at 11:04:23 while barcode
remained connected. Background/resume events occurred during this cycle.
At 11:04:46 discovery briefly failed; connection then hit a configuration error
before RFID reached verifying at 11:04:48. No fatal exception or Already running
entry was found in captured logs at this check. Post-recovery reads are user
confirmed; panel read markers were not captured for this cycle. This is one
successful user-observed cycle, not complete hardware acceptance or proof of
foreground-only automatic recovery.

User subsequently confirmed the separate RFD40 battery-removal/replacement test
worked, including the requested scans. Latest captured recovery at 11:32 device
time includes RFID disconnect, background/resume, transient discovery/connect
failures and return to RFID verifying with barcode connected at 11:32:32.
No fatal exception/Already running entry appeared in the recovery-log search.
Read success remains user-observed; no fresh panel scan markers in this latest
sequence. Battery test recorded as one user-confirmed success, with lifecycle
transitions present rather than a proven foreground-only recovery.

Background/resume: user confirmed both scans worked without the requested manual
Connect action. Logs show cycle marker 11:34:26, paused 11:34:37, resumed 11:35:18
(about 41 seconds), and both capabilities connected at 11:35:19. No fatal crash
entry found in the log search. Fresh post-resume scan success is user-confirmed;
initial reads on panel creation may be replayed stream values and are not proof
of fresh reads. Retry-exhaustion and repeated-cycle acceptance remain pending.

Retry exhaustion confirmed in the snapshot exported at 11:52:49–50 device time:
sequence 539 starts readiness attempt 5, sequence 545 records its discovery
failure, and sequence 546 records capture_device_recovery exhausted (generation
23, attempt 5, reader_discovery). This physically exercises the previously
skipped final scheduled discovery attempt. Reattachment and explicit recovery
from this exhausted state are the next pending step.

After confirmed exhaustion, user reattached the sled and reported automatic
reconnection and successful RFID/barcode reads without pressing Connect. Logs
show pause/resume ending at 11:53:39, followed by connection at 11:53:40 and RFID
verifying at 11:53:41 after a transient configuration failure. This is consistent
with the coordinator's resume path resetting the retry budget. It validates
user-observed automatic recovery after exhaustion with a lifecycle transition;
it does not exercise the explicit Connect path. No fatal exception or Already
running entry found in the checked capture. Four scenario types now have one
user-confirmed success each; repeated cycles and detach during pending connect
remain outstanding. The production crash has not been reproduced or proven fixed.
