import 'package:rxdart/subjects.dart';

import 'flutter_zebra_rfid.g.dart';

class FlutterZebraRfidApi {
  /// Behaviour subject wrapping connection updates callback from the plugin
  BehaviorSubject<ReaderConnectionStatus> get onReaderConnectionStatusChanged =>
      _callbacks.connectionStatusChanged;

  /// Behaviour subject wrapping reader list updates callback from the plugin
  BehaviorSubject<List<String>> get onAvailableReadersChanged =>
      _callbacks.availableReadersChanged;

  /// Triggers reader list refresh for specified `connectionType`
  Future<void> updateAvailableReaders({
    required ReaderConnectionType connectionType,
  }) =>
      _api.updateAvailableReaders(connectionType);

  /// Connect a reader with `readerName`
  Future<void> connectReader({required String readerName}) =>
      _api.connectReader(readerName);

  /// Dicsonnect current reader
  Future<void> disconectCurrentReader() => _api.disconnectReader();

  /// Returns name of reader currently in use (or null if none in use)
  Future<String?> get currentReaderName => _api.currentReaderName();

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
  void onAvailableReadersChanged(List<String?> readers) =>
      availableReadersChanged.add(readers.map((e) => e as String).toList());

  final connectionStatusChanged = BehaviorSubject<ReaderConnectionStatus>()
    ..add(ReaderConnectionStatus.disconnected);

  final availableReadersChanged = BehaviorSubject<List<String>>()..add([]);
}
