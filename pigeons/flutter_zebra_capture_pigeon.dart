import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/flutter_zebra_capture.g.dart',
    kotlinOut:
        'android/src/main/kotlin/nz/calo/flutter_zebra_rfid/FlutterZebraCapture.g.kt',
    kotlinOptions: KotlinOptions(errorClassName: 'FlutterCaptureError'),
    swiftOut: 'ios/Classes/FlutterZebraCapture.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'FlutterCaptureError'),
    dartPackageName: 'flutter_zebra_capture',
  ),
)
@HostApi()
abstract class FlutterZebraCapture {
  @async
  void refreshCaptureDevices();

  @async
  void connectCaptureDevice(
      String captureDeviceId, CaptureReaderConfig? rfidConfig);

  @async
  void disconnectCaptureDevice(String captureDeviceId);

  @async
  void setCaptureDeviceBarcodeOverride(
    String captureDeviceId,
    String barcodeEndpointId,
  );

  @async
  void configureCaptureDevice(
    String captureDeviceId,
    CaptureReaderConfig rfidConfig,
    bool shouldPersist,
  );

  @async
  void setCaptureDeviceForeground(bool foreground);

  List<CaptureDiagnosticEvent> captureDiagnostics();

  void clearCaptureDiagnostics();

  CaptureDevice? activeCaptureDevice();
}

@FlutterApi()
abstract class FlutterZebraCaptureCallbacks {
  void onAvailableCaptureDevicesChanged(List<CaptureDevice> devices);
  void onActiveCaptureDeviceChanged(CaptureDevice? device);
  void onCaptureDeviceStatusChanged(CaptureDevice device);
}

enum CaptureDeviceStatus {
  connecting,
  connected,
  degraded,
  disconnecting,
  disconnected,
  error,
}

enum CaptureCapabilityStatus {
  unavailable,
  disconnected,
  connecting,
  verifying,
  connected,
  error,
}

enum CaptureCapabilityType {
  rfid,
  barcode,
}

enum CaptureDeviceTopology {
  bluetoothComboReader,
  tc22RfidSled,
  externalRfidWithTerminalBarcode,
  rfidOnly,
  barcodeOnly,
  unknown,
}

enum CaptureMatchConfidence {
  exact,
  high,
  medium,
  low,
  manual,
}

enum CaptureBarcodeSource {
  builtInTerminal,
  rfidSled,
  externalBluetooth,
  externalUsb,
  unknown,
}

enum CaptureBarcodeMode {
  auto,
  dataWedge,
  scannerSdk,
}

enum CaptureReaderConfigBatchMode {
  auto,
  enabled,
  disabled,
}

enum CaptureReaderBeeperVolume {
  quiet,
  low,
  medium,
  high,
}

enum CaptureReaderInventorySession {
  s0,
  s1,
  s2,
  s3,
}

class CaptureReaderConfig {
  CaptureReaderConfig({
    this.transmitPowerIndex,
    this.tari,
    this.beeperVolume,
    this.enableDynamicPower,
    this.enableLedBlink,
    this.batchMode,
    this.scanBatchMode,
    this.rfModeTableIndex,
    this.receiveSensitivityIndex,
    this.inventorySession,
    this.estimatedTagPopulation,
    this.uniqueTagReporting,
  });

  final int? transmitPowerIndex;
  final int? tari;
  final CaptureReaderBeeperVolume? beeperVolume;
  final bool? enableDynamicPower;
  final bool? enableLedBlink;
  final CaptureReaderConfigBatchMode? batchMode;
  final CaptureReaderConfigBatchMode? scanBatchMode;
  final int? rfModeTableIndex;
  final int? receiveSensitivityIndex;
  final CaptureReaderInventorySession? inventorySession;
  final int? estimatedTagPopulation;
  final bool? uniqueTagReporting;
}

class CaptureRfidCapability {
  CaptureRfidCapability({
    required this.readerId,
    required this.hardwareIdentity,
    required this.displayName,
    required this.status,
    this.model,
    this.serialNumber,
    this.firmwareVersion,
    this.error,
  });

  final int readerId;
  final String hardwareIdentity;
  final String displayName;
  final CaptureCapabilityStatus status;
  final String? model;
  final String? serialNumber;
  final String? firmwareVersion;
  final String? error;
}

class CaptureBarcodeCapability {
  CaptureBarcodeCapability({
    required this.endpointId,
    required this.displayName,
    required this.source,
    required this.mode,
    required this.status,
    required this.preferred,
    this.scannerId,
    this.model,
    this.serialNumber,
    this.error,
  });

  final String endpointId;
  final String displayName;
  final CaptureBarcodeSource source;
  final CaptureBarcodeMode mode;
  final CaptureCapabilityStatus status;
  final bool preferred;
  final int? scannerId;
  final String? model;
  final String? serialNumber;
  final String? error;
}

class CaptureDevice {
  CaptureDevice({
    required this.id,
    required this.displayName,
    required this.topology,
    required this.status,
    required this.matchConfidence,
    required this.matchReason,
    required this.active,
    this.rfid,
    this.barcode,
    this.lastError,
  });

  final String id;
  final String displayName;
  final CaptureDeviceTopology topology;
  final CaptureDeviceStatus status;
  final CaptureMatchConfidence matchConfidence;
  final String matchReason;
  final bool active;
  final CaptureRfidCapability? rfid;
  final CaptureBarcodeCapability? barcode;
  final String? lastError;
}

class CaptureDiagnosticEvent {
  CaptureDiagnosticEvent({
    required this.timestampMs,
    required this.sequence,
    required this.category,
    required this.operation,
    required this.outcome,
    required this.detailsJson,
  });

  final int timestampMs;
  final int sequence;
  final String category;
  final String operation;
  final String outcome;
  final String detailsJson;
}
