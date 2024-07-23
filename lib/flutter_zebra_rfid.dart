import 'package:rxdart/subjects.dart';

import 'flutter_zebra_rfid.g.dart';

class FlutterZebraRfidApi {
  /// Behaviour subject wrapping connection updates callback from the plugin
  BehaviorSubject get onReaderConnectionStatusChanged =>
      _callbacks.connectionStatusChanged;

  BehaviorSubject get onAvailableReadersChanged =>
      _callbacks.availableReadersChanged;

  Future<void> updateAvailableReaders({
    required ReaderConnectionType connectionType,
  }) =>
      _api.updateAvailableReaders(connectionType);

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
