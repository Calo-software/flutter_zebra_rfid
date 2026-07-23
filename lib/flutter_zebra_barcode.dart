import 'package:flutter_zebra_rfid/shared_types.dart';
import 'package:rxdart/subjects.dart';

import 'flutter_zebra_barcode.g.dart';

export 'flutter_zebra_barcode.g.dart';

class FlutterZebraBarcodeApi {
  /// Behavior subject wrapping connection updates callback from the plugin
  BehaviorSubject<ConnectionStatus> get onReaderConnectionStatusChanged =>
      _callbacks.connectionStatusChanged;

  /// Behavior subject wrapping scanner list updates callback from the plugin
  BehaviorSubject<List<BarcodeScanner>> get onAvailableScannersChanged =>
      _callbacks.availableScannersChanged;

  /// Behavior subject wrapping topology-aware barcode endpoint updates.
  BehaviorSubject<List<BarcodeScannerEndpoint>>
      get onAvailableBarcodeScannersChanged =>
          _callbacks.availableBarcodeScannersChanged;

  /// Behavior subject wrapping the selected barcode endpoint.
  BehaviorSubject<BarcodeScannerEndpoint?> get onActiveBarcodeScannerChanged =>
      _callbacks.activeBarcodeScannerChanged;

  /// Behavior subject wrapping barcode read callback from the plugin
  BehaviorSubject<Barcode> get onBarcodeRead => _callbacks.barcodeRead;

  /// Triggers reader list refresh
  Future<void> updateAvailableScanners() => _api.updateAvailableScanners();

  /// Refreshes all barcode scanner endpoints across DataWedge and Scanner SDK.
  Future<void> refreshBarcodeScanners() => _api.refreshBarcodeScanners();

  /// Connects a reader with `readerName`
  Future<void> connectScanner({required int scannerId}) =>
      _api.connectScanner(scannerId);

  /// Disconnects the current Scanner SDK scanner, if connected.
  Future<void> disconnectScanner() => _api.disconnectScanner();

  /// Selects the active barcode scanner endpoint.
  Future<void> setActiveBarcodeScanner({required String endpointId}) =>
      _api.setActiveBarcodeScanner(endpointId);

  /// Re-applies and verifies the active DataWedge endpoint configuration.
  ///
  /// This is safe to expose as an operator recovery action. It completes only
  /// after DataWedge reports that the scanner is ready, or throws when native
  /// recovery fails.
  Future<void> recoverActiveBarcodeScanner() async {
    final endpoint = await activeBarcodeScanner;
    if (endpoint == null) {
      throw StateError('No active barcode scanner endpoint');
    }
    if (endpoint.mode != BarcodeScannerMode.dataWedge) {
      throw StateError('The active barcode scanner is not a DataWedge endpoint');
    }
    await setActiveBarcodeScanner(endpointId: endpoint.endpointId);
  }

  /// Clears the selected active barcode scanner endpoint.
  Future<void> clearActiveBarcodeScanner() => _api.clearActiveBarcodeScanner();

  /// Returns scanner in use (or null if none in use)
  Future<BarcodeScanner?> get currentScanner => _api.currentScanner();

  /// Returns the selected barcode scanner endpoint, if any.
  Future<BarcodeScannerEndpoint?> get activeBarcodeScanner =>
      _api.activeBarcodeScanner();

  static final _sharedCallbacks = _FlutterZebraBarcodeCallbacksImpl();

  final _api = FlutterZebraBarcode();
  final _callbacks = _sharedCallbacks;
}

class _FlutterZebraBarcodeCallbacksImpl
    implements FlutterZebraBarcodeCallbacks {
  _FlutterZebraBarcodeCallbacksImpl() {
    FlutterZebraBarcodeCallbacks.setUp(this);
  }

  /// Implements connection updates callback from the plugin
  @override
  void onScannerConnectionStatusChanged(ScannerConnectionStatus status) =>
      connectionStatusChanged.add(status.connectionStatus);

  @override
  void onAvailableScannersChanged(List<BarcodeScanner?> scanners) =>
      availableScannersChanged
          .add(scanners.map((e) => e as BarcodeScanner).toList());

  @override
  void onAvailableBarcodeScannersChanged(
    List<BarcodeScannerEndpoint?> endpoints,
  ) =>
      availableBarcodeScannersChanged
          .add(endpoints.map((e) => e as BarcodeScannerEndpoint).toList());

  @override
  void onActiveBarcodeScannerChanged(BarcodeScannerEndpoint? endpoint) =>
      activeBarcodeScannerChanged.add(endpoint);

  @override
  void onBarcodeRead(Barcode? barcode) {
    if (barcode != null) barcodeRead.add(barcode);
  }

  final connectionStatusChanged = BehaviorSubject<ConnectionStatus>()
    ..add(ConnectionStatus.disconnected);

  final availableScannersChanged = BehaviorSubject<List<BarcodeScanner>>();
  final availableBarcodeScannersChanged =
      BehaviorSubject<List<BarcodeScannerEndpoint>>();
  final activeBarcodeScannerChanged = BehaviorSubject<BarcodeScannerEndpoint?>()
    ..add(null);
  final barcodeRead = BehaviorSubject<Barcode>();
}

extension ScannerConnectionStatusX on ScannerConnectionStatus {
  ConnectionStatus get connectionStatus => switch (this) {
        ScannerConnectionStatus.connecting => ConnectionStatus.connecting,
        ScannerConnectionStatus.connected => ConnectionStatus.connected,
        ScannerConnectionStatus.disconnecting => ConnectionStatus.disconnecting,
        ScannerConnectionStatus.disconnected => ConnectionStatus.disconnected,
        ScannerConnectionStatus.error => ConnectionStatus.error,
      };
}

extension BarcodeScannerSourceX on BarcodeScannerSource {
  String get label => switch (this) {
        BarcodeScannerSource.builtInTerminal => 'Built-in terminal',
        BarcodeScannerSource.rfidSled => 'RFID sled',
        BarcodeScannerSource.externalBluetooth => 'External Bluetooth',
        BarcodeScannerSource.externalUsb => 'External USB',
        BarcodeScannerSource.unknown => 'Unknown',
      };
}

extension BarcodeScannerModeX on BarcodeScannerMode {
  String get label => switch (this) {
        BarcodeScannerMode.auto => 'Auto',
        BarcodeScannerMode.dataWedge => 'DataWedge',
        BarcodeScannerMode.scannerSdk => 'Scanner SDK',
      };
}
