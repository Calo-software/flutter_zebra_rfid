# Plugin User Handoff: Capture Device Release

This handoff is for teams upgrading to `flutter_zebra_rfid` `0.4.0` and testing Zebra setups that combine RFID and barcode scanning.

## What changed

The plugin now has a **Capture Device** layer above the lower-level RFID and barcode APIs. A Capture Device represents the physical setup an operator uses, even when Zebra exposes RFID and barcode through separate SDK paths.

Supported setups in this release:

- Android or iOS phone plus Bluetooth combo reader: external Zebra unit provides RFID and barcode.
- TC22 docked into an RFID sled: RFID comes from the sled, barcode comes from the TC22 built-in scanner through DataWedge.

Existing RFID and barcode APIs are still available as advanced/debug surfaces.

## Recommended app flow

Use `FlutterZebraDataCaptureApi` and make Capture Devices the primary connection UI:

```dart
final api = FlutterZebraDataCaptureApi();

api.capture.onAvailableCaptureDevicesChanged.listen((devices) {
  // Show these as the user's available physical capture setups.
});

api.capture.onActiveCaptureDeviceChanged.listen((device) {
  // Show connected/degraded/error status.
});

await api.capture.refreshCaptureDevices();
final devices = await api.capture.onAvailableCaptureDevicesChanged.first;

if (devices.isNotEmpty) {
  await api.capture.connectCaptureDevice(
    captureDeviceId: devices.first.id,
  );
}
```

Keep the lower-level APIs for troubleshooting:

- `FlutterZebraRfidApi` for direct reader discovery, connection, diagnostics, region setup, and tag streams.
- `FlutterZebraBarcodeApi` for direct barcode endpoint discovery, activation, Scanner SDK connection, and barcode streams.

## Important behavior notes

- Capture Device status can be `disconnected`, `connecting`, `connected`, `degraded`, `disconnecting`, or `error`.
- Per-capability RFID and barcode statuses are exposed so partial failures are visible.
- On TC22-style Zebra terminals, RFID discovery prefers local USB/serial before Bluetooth.
- On TC22-style Zebra terminals, Scanner SDK USB CDC is suppressed so it does not take the RFID sled USB path away from the RFID SDK.
- Bluetooth Scanner SDK discovery remains enabled for phone-plus-Bluetooth-reader setups.
- DataWedge is used for terminal barcode input. RFID inventory is handled through the RFID SDK, not DataWedge.
- If automatic barcode grouping is uncertain, use `setCaptureDeviceBarcodeOverride(...)`.

## Upgrade impact

Treat `0.4.0` as a compatibility-review release:

- Existing RFID and barcode APIs remain available.
- User-facing connection flows should move to Capture Device orchestration where possible.
- Apps that manually connect RFID and barcode separately should be tested carefully because the plugin now coordinates those paths.
- Android apps require a full rebuild/reinstall after upgrading.
- If a TC22 was used with an earlier test build that disabled DataWedge RFID input, reset the app's DataWedge profile or DataWedge app data once before retesting.

## Example app

The example app now has four tabs:

- **Capture**: primary workflow for discovering and connecting one physical setup.
- **RFID**: lower-level RFID reader debug page.
- **Barcode**: lower-level barcode endpoint debug page.
- **Scan Log**: combined RFID and barcode scan evidence, color-coded by type.

Use the Scan Log tab during hardware verification:

- RFID reads are green.
- Barcode reads are blue.
- RFID reads should not move into the barcode stream after barcode activation.

## Manual verification matrix

Run the matrix in `docs/CAPTURE_DEVICE_MANUAL_TEST_MATRIX.md`.

Minimum checks before rollout:

- Android phone plus Bluetooth combo reader.
- iOS phone plus Bluetooth combo reader. Do not expect iOS Bluetooth pairing helpers in this slice.
- TC22 docked into RFID sled with TC22 built-in barcode scanner.

For each setup:

- Refresh Capture Devices.
- Connect through one Capture Device action.
- Scan RFID and barcode.
- Confirm Scan Log type/color separation.
- Confirm Capture Device status remains `connected` or clearly `degraded` with a capability-specific error.

## Support artifacts to collect

For issues, ask users to provide:

- Device model and Android/iOS version.
- Zebra reader/sled model.
- Plugin version.
- Which example-app tab was used to connect.
- Screenshot of the Capture Dashboard.
- Screenshot or export of Scan Log behavior.
- Relevant Android logcat lines containing `FlutterZebraRfidPlugin`, `FlutterZebraBarcode`, `RFIDSerialIOMgr`, or `DataWedge`.

## Related docs

- `CONTEXT.md`
- `docs/adr/0001-capture-device-orchestration.md`
- `docs/MIGRATION_vNEXT.md`
- `docs/CAPTURE_DEVICE_MANUAL_TEST_MATRIX.md`
