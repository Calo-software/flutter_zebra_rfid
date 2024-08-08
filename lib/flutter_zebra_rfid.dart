import 'package:flutter_zebra_rfid/shared_types.dart';
import 'package:rxdart/subjects.dart';

import 'flutter_zebra_rfid.g.dart';

export 'flutter_zebra_rfid.g.dart';

class FlutterZebraRfidApi {
  /// Behavior subject wrapping connection updates callback from the plugin
  BehaviorSubject<ConnectionStatus> get onReaderConnectionStatusChanged =>
      _callbacks.connectionStatusChanged;

  /// Behavior subject wrapping reader list updates callback from the plugin
  BehaviorSubject<List<Reader>> get onAvailableReadersChanged =>
      _callbacks.availableReadersChanged;

  /// Behavior subject wrapping tags read callback from the plugin
  BehaviorSubject<List<RfidTag>> get onTagsRead => _callbacks.tagsRead;

  /// Behavior subject wrapping battery data updated
  BehaviorSubject<BatteryData> get onBatteryDataReceived =>
      _callbacks.batteryDataReceived;

  /// Triggers reader list refresh for specified `connectionType`
  Future<void> updateAvailableReaders({
    required ReaderConnectionType connectionType,
  }) =>
      _api.updateAvailableReaders(connectionType);

  /// Connects a reader with `readerName`
  Future<void> connectReader({required int readerId}) =>
      _api.connectReader(readerId);

  /// Configures the connected reader, if `shouldPersist` is true then the
  /// configuration is stored in the reader
  Future<void> configureReader({
    required ReaderConfig config,
    required bool shouldPersist,
  }) =>
      _api.configureReader(config, shouldPersist);

  /// Disconnects current reader
  Future<void> disconectCurrentReader() => _api.disconnectReader();

  /// Triggers device status event
  Future<void> triggerDeviceStatus() => _api.triggerDeviceStatus();

  /// Returns reader currently in use (or null if none in use)
  Future<Reader?> get currentReader => _api.currentReader();

  final _api = FlutterZebraRfid();
  final _callbacks = _FlutterZebraRfidCallbacksImpl();
}

class _FlutterZebraRfidCallbacksImpl implements FlutterZebraRfidCallbacks {
  _FlutterZebraRfidCallbacksImpl() {
    FlutterZebraRfidCallbacks.setUp(this);
  }

  /// Implements connection updates callback from the plugin
  @override
  void onReaderConnectionStatusChanged(ReaderConnectionStatus status) =>
      connectionStatusChanged.add(status.connectionStatus);

  @override
  void onAvailableReadersChanged(List<Reader?> readers) =>
      availableReadersChanged.add(readers.map((e) => e as Reader).toList());

  @override
  void onTagsRead(List<RfidTag?> tags) {
    tagsRead.add(
      tags.map((e) => e as RfidTag).toList(),
    );
  }

  @override
  void onBatteryDataReceived(BatteryData batteryData) =>
      batteryDataReceived.add(batteryData);

  final connectionStatusChanged = BehaviorSubject<ConnectionStatus>()
    ..add(ConnectionStatus.disconnected);

  final availableReadersChanged = BehaviorSubject<List<Reader>>();
  final tagsRead = BehaviorSubject<List<RfidTag>>();
  final batteryDataReceived = BehaviorSubject<BatteryData>();
}

extension ReaderInfoX on ReaderInfo {
  String get asString => '''
TransmitPowerLevels: ${transmitPowerLevels.first} - ${transmitPowerLevels.last}
FirmwareVersion: $firmwareVersion
ModelVersion: $modelVersion
ScannerName: $scannerName
SerialNumber: $serialNumber
''';
}

extension ReaderConnectionStatusX on ReaderConnectionStatus {
  ConnectionStatus get connectionStatus => switch (this) {
        ReaderConnectionStatus.connecting => ConnectionStatus.connecting,
        ReaderConnectionStatus.connected => ConnectionStatus.connected,
        ReaderConnectionStatus.disconnecting => ConnectionStatus.disconnecting,
        ReaderConnectionStatus.disconnected => ConnectionStatus.disconnected,
        ReaderConnectionStatus.error => ConnectionStatus.error,
      };
}
