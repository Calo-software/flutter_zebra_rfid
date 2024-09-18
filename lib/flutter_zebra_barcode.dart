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

  /// Behavior subject wrapping barcode read callback from the plugin
  BehaviorSubject<Barcode> get onBarcodeRead => _callbacks.barcodeRead;

  /// Triggers reader list refresh
  Future<void> updateAvailableScanners() => _api.updateAvailableScanners();

  /// Connects a reader with `readerName`
  Future<void> connectScanner({required int scannerId}) =>
      _api.connectScanner(scannerId);

  /// Returns scanner in use (or null if none in use)
  Future<BarcodeScanner?> get currentScanner => _api.currentScanner();

  final _api = FlutterZebraBarcode();
  final _callbacks = _FlutterZebraBarcodeCallbacksImpl();
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
  void onBarcodeRead(Barcode? barcode) {
    if (barcode != null) barcodeRead.add(barcode);
  }

  final connectionStatusChanged = BehaviorSubject<ConnectionStatus>()
    ..add(ConnectionStatus.disconnected);

  final availableScannersChanged = BehaviorSubject<List<BarcodeScanner>>();
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
