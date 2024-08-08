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

  /// Behavior subject wrapping tags read callback from the plugin
  // BehaviorSubject<List<RfidTag>> get onTagsRead => _callbacks.tagsRead;

  /// Behavior subject wrapping battery data updated
  // BehaviorSubject<BatteryData> get onBatteryDataReceived =>
  //     _callbacks.batteryDataReceived;

  /// Triggers reader list refresh
  Future<void> updateAvailableScanners() => _api.updateAvailableScanners();

  /// Connects a reader with `readerName`
  Future<void> connectScanner({required int scannerId}) =>
      _api.connectScanner(scannerId);

  /// Configures the connected reader, if `shouldPersist` is true then the
  /// configuration is stored in the reader
  // Future<void> configureReader({
  //   required ReaderConfig config,
  //   required bool shouldPersist,
  // }) =>
  //     _api.configureReader(config, shouldPersist);

  /// Disconnects current reader
  // Future<void> disconectCurrentReader() => _api.disconnectReader();

  /// Triggers device status event
  // Future<void> triggerDeviceStatus() => _api.triggerDeviceStatus();

  /// Returns reader currently in use (or null if none in use)
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

  // @override
  // void onTagsRead(List<RfidTag?> tags) {
  //   tagsRead.add(
  //     tags.map((e) => e as RfidTag).toList(),
  //   );
  // }

  // @override
  // void onBatteryDataReceived(BatteryData batteryData) =>
  //     batteryDataReceived.add(batteryData);

  final connectionStatusChanged = BehaviorSubject<ConnectionStatus>()
    ..add(ConnectionStatus.disconnected);

  final availableScannersChanged = BehaviorSubject<List<BarcodeScanner>>();
  // final tagsRead = BehaviorSubject<List<RfidTag>>();
  // final batteryDataReceived = BehaviorSubject<BatteryData>();
}

// extension ReaderInfoX on ReaderInfo {
//   String get asString => '''
// TransmitPowerLevels: ${transmitPowerLevels.first} - ${transmitPowerLevels.last}
// FirmwareVersion: $firmwareVersion
// ModelVersion: $modelVersion
// ScannerName: $scannerName
// SerialNumber: $serialNumber
// ''';
// }

extension ScannerConnectionStatusX on ScannerConnectionStatus {
  ConnectionStatus get connectionStatus => switch (this) {
        ScannerConnectionStatus.connecting => ConnectionStatus.connecting,
        ScannerConnectionStatus.connected => ConnectionStatus.connected,
        ScannerConnectionStatus.disconnecting => ConnectionStatus.disconnecting,
        ScannerConnectionStatus.disconnected => ConnectionStatus.disconnected,
        ScannerConnectionStatus.error => ConnectionStatus.error,
      };
}
