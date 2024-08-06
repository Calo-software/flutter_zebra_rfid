import 'package:rxdart/subjects.dart';

import 'flutter_zebra_rfid.g.dart';

class FlutterZebraRfidApi {
  /// Behavior subject wrapping connection updates callback from the plugin
  BehaviorSubject<ReaderConnectionStatus> get onReaderConnectionStatusChanged =>
      _callbacks.connectionStatusChanged;

  /// Behavior subject wrapping reader list updates callback from the plugin
  BehaviorSubject<List<RfidReader>> get onAvailableReadersChanged =>
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

  /// Disconnects current reader
  Future<void> disconectCurrentReader() => _api.disconnectReader();

  /// Triggers device status event
  Future<void> triggerDeviceStatus() => _api.triggerDeviceStatus();

  /// Returns reader currently in use (or null if none in use)
  Future<RfidReader?> get currentReader => _api.currentReader();

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
      connectionStatusChanged.add(status);

  @override
  void onAvailableReadersChanged(List<RfidReader?> readers) =>
      availableReadersChanged.add(readers.map((e) => e as RfidReader).toList());

  @override
  void onTagsRead(List<RfidTag?> tags) {
    tagsRead.add(
      tags.map((e) => e as RfidTag).toList(),
    );
  }

  @override
  void onBatteryDataReceived(BatteryData batteryData) =>
      batteryDataReceived.add(batteryData);

  final connectionStatusChanged = BehaviorSubject<ReaderConnectionStatus>()
    ..add(ReaderConnectionStatus.disconnected);

  final availableReadersChanged = BehaviorSubject<List<RfidReader>>();
  final tagsRead = BehaviorSubject<List<RfidTag>>();
  final batteryDataReceived = BehaviorSubject<BatteryData>();
}
