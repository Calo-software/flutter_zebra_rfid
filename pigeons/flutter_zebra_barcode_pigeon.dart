import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/flutter_zebra_barcode.g.dart',
    kotlinOut:
        'android/src/main/kotlin/nz/calo/flutter_zebra_rfid/FlutterZebraBarcode.g.kt',
    kotlinOptions: KotlinOptions(errorClassName: 'FlutterBarcodeError'),
    swiftOut: 'ios/Classes/FlutterZebraBarcode.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'FlutterBarcodeError'),
    dartPackageName: 'flutter_zebra_barcode',
  ),
)
@HostApi()
abstract class FlutterZebraBarcode {
  /// Returns list with names of available readers for specified `connectionType`.
  @async
  void updateAvailableScanners();

  /// Connects to a reader with `readerId` ID.
  @async
  void connectScanner(int scannerId);
/*
  /// Configures reader with `config`.
  @async
  void configureReader(ReaderConfig config, bool shouldPersist);

  /// Disconnects a reader with `readerName` name.
  @async
  void disconnectReader();

  /// Trigger device status
  @async
  void triggerDeviceStatus();
*/
  /// Reader currently in use
  BarcodeScanner? currentScanner();
}

@FlutterApi()
abstract class FlutterZebraBarcodeCallbacks {
  void onAvailableScannersChanged(List<BarcodeScanner> readers);
  void onScannerConnectionStatusChanged(ScannerConnectionStatus status);
  // void onBarcodesRead(List<RfidTag> tags);
  // void onBatteryDataReceived(BatteryData batteryData);
}

enum ScannerConnectionType {
  bluetooth,
  usb,
}

enum ScannerConnectionStatus {
  connecting,
  connected,
  disconnecting,
  disconnected,
  error,
}

class BarcodeScanner {
  BarcodeScanner({
    required this.name,
    required this.id,
    required this.model,
    required this.serialNumber,
  });
  final String? name;
  final int id;
  final String model;
  final String serialNumber;
}
/*
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
*/