# TC22 / RFD40 recovery example

This example uses `path: ../`, including the uncommitted recovery candidate in
this worktree. It installs as `nz.calo.flutter_zebra_rfid_example`, separately
from Ledger. The vendor serial-worker crash is not established as fixed.

## Build and connect

From `example/`:

```sh
flutter build apk --debug --dart-define=RECOVERY_BUILD=81abc3c-local-recovery-audit
adb devices -l
./tool/capture-recovery-log.sh SERIAL
```

Keep log capture running in its terminal. In another terminal:

```sh
adb -s SERIAL install -r build/app/outputs/flutter-apk/app-debug.apk
adb -s SERIAL shell am start -n nz.calo.flutter_zebra_rfid_example/.MainActivity
```

If detaching the sled also interrupts the debugging connection, arrange wireless
ADB before the test. Continuous host logging is necessary for fatal crashes;
in-app snapshots cannot survive process death. Do not clear app data/reinstall
between recovery attempts. `install -r` above is the initial candidate update.

## Test on the Capture screen

1. Refresh Capture Devices, connect the TC22 sled, grant requested permissions,
   and read a known RFID tag and barcode using the hardware triggers.
2. Expand **Recovery test**. Mark a cycle immediately before each disruption.
   Counters reset without disconnecting or clearing native diagnostics. They
   count reports, not unique tags. Stay on Capture for the whole cycle.
3. Detach and reattach the sled; confirm new RFID and barcode counts after
   recovery. Repeat separately with sled battery removal/replacement, then with
   15–20 seconds in the background and return to the app.
4. Leave the sled unavailable until diagnostics show retry exhaustion. Restore
   it and press the Capture Device's **Connect** button for an explicit retry.
5. Take **Snapshot diagnostics** after each cycle, especially before attempting
   another action after a failure. Copy exports or retrieve them from logcat.
   Snapshots contain UTC markers, scan counts, native diagnostics, and build
   label. A blocked snapshot reports a timeout; it does not cancel native work.
6. Repeat each scenario ten times, including detach during connection. Record
   the physical action, exact battery removed, OS and sled firmware alongside
   cycle numbers. Connected status alone is not proof: require real new reads.

The panel's last 200 event markers are held in memory; leaving Capture resets
that panel. Native diagnostics are not cleared. Host logs preserve markers and
exported snapshots. Do not use the separate RFID/Barcode screens during these
cycles: those expose lower-level connection controls.

The app forwards lifecycle changes to the managed Capture Device API using
Flutter's WidgetsBindingObserver. No timer in the panel triggers recovery.
See `../docs/investigations/2026-09-10-rfid-recovery.md` for the candidate's limits
and incident evidence.
