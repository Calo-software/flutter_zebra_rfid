import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_rfid.dart';
import 'package:flutter_zebra_rfid_example/capture_dashboard.dart';
import 'package:flutter_zebra_rfid_example/scan_log_page.dart';

import 'package:flutter_zebra_rfid_example/main.dart';

void main() {
  test('API instances share native callback streams', () {
    final firstRfid = FlutterZebraRfidApi();
    final secondRfid = FlutterZebraRfidApi();
    final firstBarcode = FlutterZebraBarcodeApi();
    final secondBarcode = FlutterZebraBarcodeApi();
    final firstCapture = FlutterZebraCaptureApi();
    final secondCapture = FlutterZebraCaptureApi();

    expect(identical(firstRfid.onTagsRead, secondRfid.onTagsRead), isTrue);
    expect(
      identical(firstBarcode.onBarcodeRead, secondBarcode.onBarcodeRead),
      isTrue,
    );
    expect(
      identical(
        firstCapture.onAvailableCaptureDevicesChanged,
        secondCapture.onAvailableCaptureDevicesChanged,
      ),
      isTrue,
    );
  });

  testWidgets('Shows Capture Dashboard on launch', (tester) async {
    await tester.pumpWidget(const MyApp());

    expect(find.text('Zebra RFID + Barcode example'), findsOneWidget);
    expect(find.text('No active Capture Device'), findsOneWidget);
    expect(find.text('Refresh Capture Devices'), findsOneWidget);
    expect(find.text('Get Reader List'), findsNothing);
    expect(find.text('Scan Log'), findsOneWidget);
  });

  testWidgets('Shows combined RFID and barcode scan log', (tester) async {
    await tester.pumpWidget(_harness(ScanLogView(
      entries: [
        ScanLogEntry.rfid(
          RfidTag(id: 'E2801170', rssi: -45),
          at: DateTime(2026, 1, 1, 10, 30, 5),
        ),
        ScanLogEntry.barcode(
          Barcode(data: 'ABC123', scannerId: 1),
          at: DateTime(2026, 1, 1, 10, 30, 10),
        ),
      ],
      onClear: () {},
    )));

    expect(find.text('Scan Log'), findsOneWidget);
    expect(find.text('2 total'), findsOneWidget);
    expect(find.text('1 RFID'), findsOneWidget);
    expect(find.text('1 barcode'), findsOneWidget);
    expect(find.text('E2801170'), findsOneWidget);
    expect(find.text('ABC123'), findsOneWidget);
    expect(find.text('RFID'), findsOneWidget);
    expect(find.text('Barcode'), findsOneWidget);
  });

  testWidgets('Shows Capture Dashboard empty state', (tester) async {
    await tester.pumpWidget(_harness(_view(devices: [])));

    expect(find.text('No Capture Devices detected'), findsOneWidget);
  });

  testWidgets('Shows connected Capture Device evidence', (tester) async {
    final device = _device(status: CaptureDeviceStatus.connected);

    await tester.pumpWidget(_harness(_view(
      devices: [device],
      activeDevice: device,
      lastTag: RfidTag(id: 'E2801170', rssi: -45),
      lastBarcode: Barcode(data: 'ABC123', scannerId: 1),
    )));

    expect(find.text('RFD40 + RFD40 barcode'), findsWidgets);
    expect(find.text('Connected'), findsWidgets);
    expect(find.text('RFID E2801170'), findsOneWidget);
    expect(find.text('Barcode ABC123'), findsOneWidget);
  });

  testWidgets('Shows connected sled battery status', (tester) async {
    final device = _device(status: CaptureDeviceStatus.connected);

    await tester.pumpWidget(_harness(_view(
      devices: [device],
      activeDevice: device,
      sledBattery: BatteryData(
        level: 73,
        isCharging: false,
        cause: 'status event',
        source: BatteryDataSource.readerStatistics,
        healthPercentage: 91,
        cycleCount: 42,
      ),
      sledBatteryUpdatedAt: DateTime(2026, 7, 22, 10, 30),
    )));

    expect(find.text('Sled battery 73%'), findsOneWidget);
    expect(
      find.byTooltip(
        'Source: Zebra battery statistics\n'
        'Charging: no\n'
        'Health: 91%\n'
        'Charge cycles: 42\n'
        'Updated: 2026-07-22 10:30:00.000',
      ),
      findsOneWidget,
    );
  });

  testWidgets('Shows degraded per-capability failure', (tester) async {
    final device = _device(
      status: CaptureDeviceStatus.degraded,
      rfidStatus: CaptureCapabilityStatus.connected,
      barcodeStatus: CaptureCapabilityStatus.error,
      barcodeError: 'Scanner session failed',
    );

    await tester.pumpWidget(_harness(_view(
      devices: [device],
      activeDevice: device,
    )));

    expect(find.text('Degraded'), findsWidgets);
    expect(find.text('Barcode Error'), findsOneWidget);
  });

  testWidgets('Shows barcode override action when endpoints exist',
      (tester) async {
    final device = _device(status: CaptureDeviceStatus.disconnected);

    await tester.pumpWidget(_harness(_view(
      devices: [device],
      barcodeEndpoints: [
        BarcodeScannerEndpoint(
          endpointId: 'datawedge:INTERNAL',
          displayName: 'Internal imager',
          source: BarcodeScannerSource.builtInTerminal,
          mode: BarcodeScannerMode.dataWedge,
          connectionStatus: ScannerConnectionStatus.connected,
          active: false,
          preferred: false,
        ),
      ],
    )));

    expect(find.text('Barcode Override'), findsOneWidget);
    await tester.tap(find.text('Barcode Override'));
    await tester.pumpAndSettle();
    expect(find.text('Select Barcode Endpoint'), findsOneWidget);
    expect(find.text('Internal imager'), findsOneWidget);
  });
}

Widget _harness(Widget child) => MaterialApp(
      theme: ThemeData(useMaterial3: true),
      home: Scaffold(body: child),
    );

CaptureDashboardView _view({
  required List<CaptureDevice> devices,
  CaptureDevice? activeDevice,
  List<BarcodeScannerEndpoint> barcodeEndpoints = const [],
  RfidTag? lastTag,
  Barcode? lastBarcode,
  BatteryData? sledBattery,
  DateTime? sledBatteryUpdatedAt,
}) =>
    CaptureDashboardView(
      devices: devices,
      activeDevice: activeDevice,
      barcodeEndpoints: barcodeEndpoints,
      lastTag: lastTag,
      lastBarcode: lastBarcode,
      sledBattery: sledBattery,
      sledBatteryUpdatedAt: sledBatteryUpdatedAt,
      isLoading: false,
      onRefresh: () async {},
      onConnect: (_) async {},
      onDisconnect: (_) async {},
      onOverrideBarcode: (_, __) async {},
    );

CaptureDevice _device({
  required CaptureDeviceStatus status,
  CaptureCapabilityStatus rfidStatus = CaptureCapabilityStatus.connected,
  CaptureCapabilityStatus barcodeStatus = CaptureCapabilityStatus.connected,
  String? barcodeError,
}) =>
    CaptureDevice(
      id: 'capture:rfid:1',
      displayName: 'RFD40 + RFD40 barcode',
      topology: CaptureDeviceTopology.bluetoothComboReader,
      status: status,
      matchConfidence: CaptureMatchConfidence.exact,
      matchReason: 'RFID Reader and Barcode Endpoint share serial 123.',
      active: status != CaptureDeviceStatus.disconnected,
      rfid: CaptureRfidCapability(
        readerId: 1,
        displayName: 'RFD40',
        status: rfidStatus,
        serialNumber: '123',
      ),
      barcode: CaptureBarcodeCapability(
        endpointId: 'scanner-sdk:1',
        displayName: 'RFD40 barcode',
        source: CaptureBarcodeSource.externalBluetooth,
        mode: CaptureBarcodeMode.scannerSdk,
        status: barcodeStatus,
        preferred: true,
        scannerId: 1,
        serialNumber: '123',
        error: barcodeError,
      ),
      lastError: barcodeError,
    );
