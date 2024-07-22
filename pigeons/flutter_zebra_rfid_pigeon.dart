import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/flutter_zebra_rfid.g.dart',
    kotlinOut:
        'android/src/main/kotlin/nz/calo/flutter_zebra_rfid/FlutterZebraRfid.g.kt',
    swiftOut: 'ios/Runner/FlutterZebraRfid.g.swift',
    dartPackageName: 'flutter_zebra_rfid',
  ),
)
@HostApi()
abstract class FlutterZebraRfid {
  /// Returns list with names of available readers for specified `connectionType`.
  @async
  List<String> getAvailableReaders(ReaderConnectionType connectionType);

  /// Connects to a reader with `readerName` name.
  @async
  bool connectReader(String readerName);

  /// Disconnects a reader with `readerName` name.
  @async
  bool disconnectReader();
}

@FlutterApi()
abstract class FlutterZebraRfidCallbacks {
  void onReaderConnectionStatusChanged(ReaderConnectionStatus status);
}

enum ReaderConnectionType {
  bluetooth,
  usb,
}

enum ReaderConnectionStatus {
  connecting,
  connected,
  disconnecting,
  disconnected,
  error,
}
