import 'package:rxdart/subjects.dart';

import 'flutter_zebra_capture.g.dart';
import 'flutter_zebra_rfid.g.dart' as rfid;

export 'flutter_zebra_capture.g.dart';

class FlutterZebraCaptureApi {
  BehaviorSubject<List<CaptureDevice>> get onAvailableCaptureDevicesChanged =>
      _callbacks.availableCaptureDevicesChanged;

  BehaviorSubject<CaptureDevice?> get onActiveCaptureDeviceChanged =>
      _callbacks.activeCaptureDeviceChanged;

  BehaviorSubject<CaptureDevice> get onCaptureDeviceStatusChanged =>
      _callbacks.captureDeviceStatusChanged;

  Future<void> refreshCaptureDevices() => _api.refreshCaptureDevices();

  Future<void> connectCaptureDevice({
    required String captureDeviceId,
    rfid.ReaderConfig? rfidConfig,
  }) =>
      _api.connectCaptureDevice(
        captureDeviceId,
        rfidConfig?.toCaptureReaderConfig(),
      );

  Future<void> disconnectCaptureDevice({
    required String captureDeviceId,
  }) =>
      _api.disconnectCaptureDevice(captureDeviceId);

  Future<void> setCaptureDeviceBarcodeOverride({
    required String captureDeviceId,
    required String barcodeEndpointId,
  }) =>
      _api.setCaptureDeviceBarcodeOverride(captureDeviceId, barcodeEndpointId);

  Future<CaptureDevice?> get activeCaptureDevice => _api.activeCaptureDevice();

  static final _sharedCallbacks = _FlutterZebraCaptureCallbacksImpl();

  final _api = FlutterZebraCapture();
  final _callbacks = _sharedCallbacks;
}

class _FlutterZebraCaptureCallbacksImpl
    implements FlutterZebraCaptureCallbacks {
  _FlutterZebraCaptureCallbacksImpl() {
    FlutterZebraCaptureCallbacks.setUp(this);
  }

  @override
  void onAvailableCaptureDevicesChanged(List<CaptureDevice?> devices) {
    availableCaptureDevicesChanged
        .add(devices.whereType<CaptureDevice>().toList());
  }

  @override
  void onActiveCaptureDeviceChanged(CaptureDevice? device) {
    activeCaptureDeviceChanged.add(device);
  }

  @override
  void onCaptureDeviceStatusChanged(CaptureDevice device) {
    captureDeviceStatusChanged.add(device);
  }

  final availableCaptureDevicesChanged = BehaviorSubject<List<CaptureDevice>>();
  final activeCaptureDeviceChanged = BehaviorSubject<CaptureDevice?>()
    ..add(null);
  final captureDeviceStatusChanged = BehaviorSubject<CaptureDevice>();
}

extension ReaderConfigCaptureX on rfid.ReaderConfig {
  CaptureReaderConfig toCaptureReaderConfig() => CaptureReaderConfig(
        transmitPowerIndex: transmitPowerIndex,
        tari: tari,
        beeperVolume: beeperVolume?.toCaptureReaderBeeperVolume(),
        enableDynamicPower: enableDynamicPower,
        enableLedBlink: enableLedBlink,
        batchMode: batchMode?.toCaptureReaderConfigBatchMode(),
        scanBatchMode: scanBatchMode?.toCaptureReaderConfigBatchMode(),
        rfModeTableIndex: rfModeTableIndex,
        receiveSensitivityIndex: receiveSensitivityIndex,
      );
}

extension _ReaderBeeperVolumeCaptureX on rfid.ReaderBeeperVolume {
  CaptureReaderBeeperVolume toCaptureReaderBeeperVolume() => switch (this) {
        rfid.ReaderBeeperVolume.quiet => CaptureReaderBeeperVolume.quiet,
        rfid.ReaderBeeperVolume.low => CaptureReaderBeeperVolume.low,
        rfid.ReaderBeeperVolume.medium => CaptureReaderBeeperVolume.medium,
        rfid.ReaderBeeperVolume.high => CaptureReaderBeeperVolume.high,
      };
}

extension _ReaderConfigBatchModeCaptureX on rfid.ReaderConfigBatchMode {
  CaptureReaderConfigBatchMode toCaptureReaderConfigBatchMode() =>
      switch (this) {
        rfid.ReaderConfigBatchMode.auto => CaptureReaderConfigBatchMode.auto,
        rfid.ReaderConfigBatchMode.enabled =>
          CaptureReaderConfigBatchMode.enabled,
        rfid.ReaderConfigBatchMode.disabled =>
          CaptureReaderConfigBatchMode.disabled,
      };
}

extension CaptureDeviceStatusLabelX on CaptureDeviceStatus {
  String get label => switch (this) {
        CaptureDeviceStatus.connecting => 'Connecting',
        CaptureDeviceStatus.connected => 'Connected',
        CaptureDeviceStatus.degraded => 'Degraded',
        CaptureDeviceStatus.disconnecting => 'Disconnecting',
        CaptureDeviceStatus.disconnected => 'Disconnected',
        CaptureDeviceStatus.error => 'Error',
      };
}

extension CaptureCapabilityStatusLabelX on CaptureCapabilityStatus {
  String get label => switch (this) {
        CaptureCapabilityStatus.unavailable => 'Unavailable',
        CaptureCapabilityStatus.disconnected => 'Disconnected',
        CaptureCapabilityStatus.connecting => 'Connecting',
        CaptureCapabilityStatus.connected => 'Connected',
        CaptureCapabilityStatus.error => 'Error',
      };
}

extension CaptureDeviceTopologyLabelX on CaptureDeviceTopology {
  String get label => switch (this) {
        CaptureDeviceTopology.bluetoothComboReader => 'Bluetooth combo reader',
        CaptureDeviceTopology.tc22RfidSled => 'TC22 RFID sled',
        CaptureDeviceTopology.externalRfidWithTerminalBarcode =>
          'External RFID + terminal barcode',
        CaptureDeviceTopology.rfidOnly => 'RFID only',
        CaptureDeviceTopology.barcodeOnly => 'Barcode only',
        CaptureDeviceTopology.unknown => 'Unknown topology',
        CaptureDeviceTopology.integratedMobileComputer =>
          'Integrated mobile computer',
      };
}

extension CaptureMatchConfidenceLabelX on CaptureMatchConfidence {
  String get label => switch (this) {
        CaptureMatchConfidence.exact => 'Exact match',
        CaptureMatchConfidence.high => 'High confidence',
        CaptureMatchConfidence.medium => 'Medium confidence',
        CaptureMatchConfidence.low => 'Low confidence',
        CaptureMatchConfidence.manual => 'Manual match',
      };
}

extension CaptureBarcodeSourceLabelX on CaptureBarcodeSource {
  String get label => switch (this) {
        CaptureBarcodeSource.builtInTerminal => 'Built-in terminal',
        CaptureBarcodeSource.rfidSled => 'RFID sled',
        CaptureBarcodeSource.externalBluetooth => 'External Bluetooth',
        CaptureBarcodeSource.externalUsb => 'External USB',
        CaptureBarcodeSource.unknown => 'Unknown',
      };
}
