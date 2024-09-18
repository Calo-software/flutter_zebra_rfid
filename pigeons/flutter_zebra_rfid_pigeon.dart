import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/flutter_zebra_rfid.g.dart',
    kotlinOut:
        'android/src/main/kotlin/nz/calo/flutter_zebra_rfid/FlutterZebraRfid.g.kt',
    kotlinOptions: KotlinOptions(errorClassName: 'FlutterRfidError'),
    swiftOut: 'ios/Classes/FlutterZebraRfid.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'FlutterRfidError'),
    dartPackageName: 'flutter_zebra_rfid',
  ),
)
@HostApi()
abstract class FlutterZebraRfid {
  /// Returns list with names of available readers for specified `connectionType`.
  @async
  void updateAvailableReaders(ReaderConnectionType connectionType);

  /// Connects to a reader with `readerId` ID.
  @async
  void connectReader(int readerId);

  /// Configures reader with `config`.
  @async
  void configureReader(ReaderConfig config, bool shouldPersist);

  /// Disconnects a reader with `readerName` name.
  @async
  void disconnectReader();

  /// Trigger device status
  @async
  void triggerDeviceStatus();

  /// Reader currently in use
  Reader? currentReader();
}

@FlutterApi()
abstract class FlutterZebraRfidCallbacks {
  void onAvailableReadersChanged(List<Reader> readers);
  void onReaderConnectionStatusChanged(ReaderConnectionStatus status);
  void onTagsRead(List<RfidTag> tags);
  void onBatteryDataReceived(BatteryData batteryData);
}

enum ReaderConnectionType {
  bluetooth,
  usb,
  all,
}

enum ReaderConnectionStatus {
  connecting,
  connected,
  disconnecting,
  disconnected,
  error,
}

class Reader {
  Reader({
    required this.name,
    required this.id,
    this.info,
  });
  final String? name;
  final int id;
  final ReaderInfo? info;
}

enum ReaderConfigBatchMode {
  auto,
  enabled,
  disabled,
}

enum ReaderBeeperVolume {
  quiet,
  low,
  medium,
  high,
}

class ReaderConfig {
  ReaderConfig({
    this.transmitPowerIndex,
    this.beeperVolume,
    this.enableDynamicPower,
    this.enableLedBlink,
    this.batchMode,
    this.scanBatchMode,
  });
  final int? transmitPowerIndex;
  final ReaderBeeperVolume? beeperVolume;
  final bool? enableDynamicPower;
  final bool? enableLedBlink;
  final ReaderConfigBatchMode? batchMode;
  final ReaderConfigBatchMode? scanBatchMode;
}

class ReaderInfo {
  ReaderInfo({
    required this.transmitPowerLevels,
    required this.firmwareVersion,
    required this.modelVersion,
    required this.scannerName,
    required this.serialNumber,
  });

  final List transmitPowerLevels;
  final String firmwareVersion;
  final String modelVersion;
  final String scannerName;
  final String serialNumber;
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
