import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/flutter_zebra_rfid.g.dart',
    kotlinOut:
        'android/src/main/kotlin/nz/calo/flutter_zebra_rfid/FlutterZebraRfid.g.kt',
    swiftOut: 'ios/Classes/FlutterZebraRfid.g.swift',
    dartPackageName: 'flutter_zebra_rfid',
  ),
)
@HostApi()
abstract class FlutterZebraRfid {
  /// Returns list with names of available readers for specified `connectionType`.
  @async
  void updateAvailableReaders(ReaderConnectionType connectionType);

  /// Connects to a reader with `readerName` name.
  @async
  void connectReader(int readerId);

  /// Disconnects a reader with `readerName` name.
  @async
  void disconnectReader();

  /// Trigger device status
  @async
  void triggerDeviceStatus();

  /// Reader currently in use
  RfidReader? currentReader();
}

@FlutterApi()
abstract class FlutterZebraRfidCallbacks {
  void onAvailableReadersChanged(List<RfidReader> readers);
  void onReaderConnectionStatusChanged(ReaderConnectionStatus status);
  void onTagsRead(List<RfidTag> tags);
  void onBatteryDataReceived(BatteryData batteryData);
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

class RfidReader {
  RfidReader({required this.name, required this.id});
  final String? name;
  final int id;
}

class RfidTag {
  RfidTag({
    required this.id,
    required this.rssi,
  });

  final String id;
  final int rssi;
}

class BatteryData {
  BatteryData({
    required this.level,
    required this.isCharging,
    required this.cause,
  });

  final int level;
  final bool isCharging;
  final String cause;
}
