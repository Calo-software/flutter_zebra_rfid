import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/flutter_zebra_rfid.g.dart',
    kotlinOut:
        'android/src/main/kotlin/nz/calo/flutter_zebra_rfid/FlutterZebraRfid.g.kt',
    kotlinOptions: KotlinOptions(errorClassName: 'FlutterRfidError'),
    swiftOut: 'ios/Classes/FlutterZebraRfid.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'FlutterRfidError'),
    cppHeaderOut: 'windows/flutter_zebra_rfid.g.h',
    cppSourceOut: 'windows/flutter_zebra_rfid.g.cpp',
    cppOptions: CppOptions(
      namespace: 'flutter_zebra_rfid',
      headerIncludePath: 'flutter_zebra_rfid.g.h',
    ),
    dartPackageName: 'flutter_zebra_rfid',
  ),
)
@HostApi()
abstract class FlutterZebraRfid {
  /// Returns list with names of available readers for specified `connectionType`.
  @async
  void updateAvailableReaders(ReaderConnectionType connectionType);

  /// Starts Bluetooth classic device discovery for pairing.
  @async
  void startBluetoothScan();

  /// Stops Bluetooth classic device discovery.
  @async
  void stopBluetoothScan();

  /// Returns currently bonded Bluetooth devices.
  @async
  List<BluetoothDevice> getBondedDevices();

  /// Starts pairing with the Bluetooth device at `address`.
  @async
  void pairBluetoothDevice(String address);

  /// Connects to a reader with `readerId` ID.
  @async
  void connectReader(int readerId);

  /// Configures reader with `config`.
  @async
  void configureReader(ReaderConfig config, bool shouldPersist);

  /// Disconnects a current reader.
  @async
  void disconnectReader();

  /// Trigger device status
  @async
  void triggerDeviceStatus();

  /// Start locating the specified `tags`.
  /// If `disableBeep` is true, the reader will not beep for tags not in the locate list.
  @async
  void startLocating({required List<RfidTag> tags, bool? disableBeep});

  /// Stop locating tags.
  @async
  void stopLocating();

  /// Reset the locate state (clears session, allows new locate operations).
  @async
  void resetLocateState();

  /// Reader currently in use
  Reader? currentReader();

  /// Reader config
  @async
  ReaderConfig readerConfig();

  /// Supported regulatory regions for the current or last-selected reader.
  @async
  List<ReaderRegion> supportedReaderRegions();

  /// Applies a regulatory region to the current reader.
  @async
  void setReaderRegion(String regionCode);

  /// Runtime diagnostics snapshot (counters / last error / state)
  @async
  Diagnostics diagnostics();

  /// Enable or disable hardware-trigger initiated scanning/inventory.
  /// When disabled, trigger pulls are ignored and any active inventory is stopped.
  @async
  void setScanningEnabled(bool enabled);
}

@FlutterApi()
abstract class FlutterZebraRfidCallbacks {
  void onAvailableReadersChanged(List<Reader> readers);
  void onReaderConnectionStatusChanged(ReaderConnectionStatus status);
  void onTagsRead(List<RfidTag> tags);
  void onBatteryDataReceived(BatteryData batteryData);
  void onTagsLocated(List<RfidTag> tags);
  void onBluetoothDeviceDiscovered(BluetoothDevice device);
  void onBluetoothScanStatusChanged(BluetoothScanStatus status);
  void onBluetoothPairingResult(BluetoothDevice device, bool success);
  // Fired when a connection-related error occurs. Status callback will also emit `ReaderConnectionStatus.error`.
  void onReaderConnectionError(ReaderError error);
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

enum BluetoothScanStatus {
  scanning,
  finished,
  error,
}

// Categorised error codes for connection / configuration failures.
enum ReaderErrorCode {
  unknown,
  noAvailableReaders,
  invalidReaderIndex,
  readerDeviceNull,
  alreadyConnecting,
  notConnected,
  sdkInvalidUsage,
  sdkOperationFailure,
  timeout,
}

class ReaderError {
  ReaderError({
    required this.code,
    required this.message,
    this.details,
  });
  final ReaderErrorCode code;
  final String message;
  final String? details;
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

class BluetoothDevice {
  BluetoothDevice({
    required this.name,
    required this.address,
    required this.isPaired,
  });

  final String? name;
  final String address;
  final bool isPaired;
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
    this.tari,
    this.beeperVolume,
    this.enableDynamicPower,
    this.enableLedBlink,
    this.batchMode,
    this.scanBatchMode,
    this.rfModeTableIndex,
    this.receiveSensitivityIndex,
  });
  final int? transmitPowerIndex;
  final int? tari;
  final ReaderBeeperVolume? beeperVolume;
  final bool? enableDynamicPower;
  final bool? enableLedBlink;
  final ReaderConfigBatchMode? batchMode;
  final ReaderConfigBatchMode? scanBatchMode;
  // Additional RF parameters (read-only for now on Android; setting may be added later)
  final int? rfModeTableIndex;
  final int? receiveSensitivityIndex;
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
  final String? firmwareVersion;
  final String? modelVersion;
  final String? scannerName;
  final String? serialNumber;
}

class ReaderRegion {
  ReaderRegion({
    required this.code,
    this.name,
    this.standardName,
  });

  final String code;
  final String? name;
  final String? standardName;
}

class RfidTag {
  RfidTag({
    required this.id,
    required this.rssi,
    this.relativeDistance,
  });

  final String id;
  final int rssi;
  final double? relativeDistance;
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

class Diagnostics {
  Diagnostics({
    required this.connectionState,
    required this.connectAttempts,
    required this.lastErrorCode,
    required this.lastErrorMessage,
    required this.lastConnectStartMs,
    required this.lastConnectDurationMs,
    required this.isLocating,
    this.scanningEnabled,
    this.scanningEnabledLastToggleMs,
    this.inventoryActive,
    this.lastInventoryStartMs,
    this.lastInventoryStopMs,
    this.pendingPurgeActive,
    this.lastInventoryStopReason,
    this.lastInventoryStartReason,
  });

  final ReaderConnectionStatus connectionState;
  final int connectAttempts; // total attempts since process start
  final ReaderErrorCode? lastErrorCode;
  final String? lastErrorMessage;
  final int? lastConnectStartMs;
  final int? lastConnectDurationMs;
  final bool isLocating;
  // Whether trigger-driven scanning is currently enabled (may be null for older platform versions).
  final bool? scanningEnabled;
  // Epoch ms of last toggle (null if never toggled or unsupported).
  final int? scanningEnabledLastToggleMs;
  // Whether the plugin currently believes inventory is active.
  final bool? inventoryActive;
  // Epoch ms of the last successful inventory start.
  final int? lastInventoryStartMs;
  // Epoch ms of the last inventory stop or forced recovery cleanup.
  final int? lastInventoryStopMs;
  // Whether a delayed purge is currently scheduled.
  final bool? pendingPurgeActive;
  // Reason associated with the latest inventory stop or forced cleanup.
  final String? lastInventoryStopReason;
  // Reason associated with the latest successful inventory start.
  final String? lastInventoryStartReason;
}
