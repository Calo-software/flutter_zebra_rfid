import 'package:rxdart/subjects.dart';

import 'flutter_zebra_rfid.g.dart';

class FlutterZebraRfidApi {
  /// Behaviour subject wrapping connection updates callback from the plugin
  BehaviorSubject<ReaderConnectionStatus> get onReaderConnectionStatusChanged =>
      _callbacks.connectionStatusChanged;

  /// Behaviour subject wrapping reader list updates callback from the plugin
  BehaviorSubject<List<RfidReader>> get onAvailableReadersChanged =>
      _callbacks.availableReadersChanged;

  /// Triggers reader list refresh for specified `connectionType`
  Future<void> updateAvailableReaders({
    required ReaderConnectionType connectionType,
  }) =>
      _api.updateAvailableReaders(connectionType);

  /// Connect a reader with `readerName`
  Future<void> connectReader({required int readerId}) =>
      _api.connectReader(readerId);

  /// Dicsonnect current reader
  Future<void> disconectCurrentReader() => _api.disconnectReader();

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

  final connectionStatusChanged = BehaviorSubject<ReaderConnectionStatus>()
    ..add(ReaderConnectionStatus.disconnected);

  final availableReadersChanged = BehaviorSubject<List<RfidReader>>()..add([]);
}
