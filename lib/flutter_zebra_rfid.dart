import 'package:rxdart/subjects.dart';

import 'flutter_zebra_rfid.g.dart';

class FlutterZebraRfidApi {
  /// Behaviour subject wrapping connection updates callback from the plugin
  BehaviorSubject get onReaderConnectionStatusChanged =>
      _callbacks.connectionStatusChanged;

  Future<List<String>> getAvailableReaders({
    required ReaderConnectionType connectionType,
  }) async {
    final readers = await _api.getAvailableReaders(connectionType);
    return readers.map((item) => item as String).toList();
  }

  final _api = FlutterZebraRfid();
  final _callbacks = _FlutterZebraRfidCallbacksImpl();
}

class _FlutterZebraRfidCallbacksImpl implements FlutterZebraRfidCallbacks {
  /// Implements connection updates callback from the plugin
  @override
  void onReaderConnectionStatusChanged(ConnectionStatus status) =>
      connectionStatusChanged.add(status);

  final connectionStatusChanged = BehaviorSubject<ConnectionStatus>()
    ..add(ConnectionStatus.disconnected);
}
