import 'package:flutter_zebra_rfid/shared_types.dart';
import 'package:rxdart/subjects.dart';

import 'flutter_zebra_barcode.dart';
import 'flutter_zebra_rfid.g.dart';

export 'flutter_zebra_rfid.g.dart';
export 'flutter_zebra_barcode.dart' hide wrapResponse;

class FlutterZebraDataCaptureApi {
  FlutterZebraDataCaptureApi({
    FlutterZebraRfidApi? rfid,
    FlutterZebraBarcodeApi? barcode,
  })  : rfid = rfid ?? FlutterZebraRfidApi(),
        barcode = barcode ?? FlutterZebraBarcodeApi();

  final FlutterZebraRfidApi rfid;
  final FlutterZebraBarcodeApi barcode;
}

class FlutterZebraRfidApi {
  /// Behavior subject wrapping connection updates callback from the plugin
  BehaviorSubject<ConnectionStatus> get onReaderConnectionStatusChanged =>
      _callbacks.connectionStatusChanged;

  /// Behavior subject wrapping discovered Bluetooth devices during pairing scan.
  BehaviorSubject<BluetoothDevice> get onBluetoothDeviceDiscovered =>
      _callbacks.bluetoothDeviceDiscovered;

  /// Behavior subject wrapping Bluetooth scan status updates.
  BehaviorSubject<BluetoothScanStatus> get onBluetoothScanStatusChanged =>
      _callbacks.bluetoothScanStatusChanged;

  /// Behavior subject wrapping Bluetooth pairing result events.
  BehaviorSubject<BluetoothPairingResult> get onBluetoothPairingResult =>
      _callbacks.bluetoothPairingResult;

  /// Behavior subject wrapping reader list updates callback from the plugin
  BehaviorSubject<List<Reader>> get onAvailableReadersChanged =>
      _callbacks.availableReadersChanged;

  /// Behavior subject wrapping tags read callback from the plugin
  BehaviorSubject<List<RfidTag>> get onTagsRead => _callbacks.tagsRead;

  /// Behavior subject wrapping battery data updated
  BehaviorSubject<BatteryData> get onBatteryDataReceived =>
      _callbacks.batteryDataReceived;

  /// Behavior subject wrapping tags locate callback from the plugin
  BehaviorSubject<List<RfidTag>> get onTagsLocated => _callbacks.tagsLocated;

  /// Stream of connection-related errors with structured codes.
  BehaviorSubject<ReaderError> get onReaderConnectionError =>
      _callbacks.connectionErrors;

  /// Triggers reader list refresh for specified `connectionType`
  Future<void> updateAvailableReaders({
    required ReaderConnectionType connectionType,
  }) =>
      _api.updateAvailableReaders(connectionType);

  /// Starts Bluetooth classic discovery for pairing a new reader.
  Future<void> startBluetoothScan() => _api.startBluetoothScan();

  /// Stops Bluetooth discovery if currently running.
  Future<void> stopBluetoothScan() => _api.stopBluetoothScan();

  /// Returns the list of currently bonded Bluetooth devices.
  Future<List<BluetoothDevice>> getBondedDevices() async =>
      (await _api.getBondedDevices()).whereType<BluetoothDevice>().toList();

  /// Starts pairing with the Bluetooth device at `address`.
  Future<void> pairBluetoothDevice({required String address}) =>
      _api.pairBluetoothDevice(address);

  /// Connects a reader with `readerName`
  Future<void> connectReader({required int readerId}) =>
      _api.connectReader(readerId);

  /// Connects directly to a network reader by IP address or host name.
  Future<void> connectReaderByIp({required String host, int? port}) =>
      _api.connectReaderByIp(host, port);

  /// Configures the connected reader, if `shouldPersist` is true then the
  /// configuration is stored in the reader
  Future<void> configureReader({
    required ReaderConfig config,
    required bool shouldPersist,
  }) =>
      _api.configureReader(config, shouldPersist);

  /// Configures Wi-Fi on the connected reader.
  ///
  /// On Windows this currently supports USB-connected readers and WPA/WPA2
  /// personal or open networks.
  Future<void> configureWifi({required WifiConfig config}) =>
      _api.configureWifi(config);

  /// Returns Wi-Fi status for the connected reader.
  Future<WifiStatus> wifiStatus() => _api.wifiStatus();

  /// Disconnects current reader
  Future<void> disconectCurrentReader() => _api.disconnectReader();

  /// Triggers device status event
  Future<void> triggerDeviceStatus() => _api.triggerDeviceStatus();

  /// Start locating the specified `tags`.
  /// If `disableBeep` is true, the reader will not beep for tags not in the locate list.
  Future<void> startLocating(
          {required List<RfidTag> tags, bool? disableBeep}) =>
      _api.startLocating(tags: tags, disableBeep: disableBeep);

  /// Stop locating.
  Future<void> stopLocating() => _api.stopLocating();

  /// Reset the locate state (clears session, allows new locate operations).
  Future<void> resetLocateState() => _api.resetLocateState();

  /// Returns reader currently in use (or null if none in use).
  Future<Reader?> get currentReader => _api.currentReader();

  /// Returns current reader config.
  Future<ReaderConfig> get readerConfig => _api.readerConfig();

  /// Returns supported regulatory regions for the current or last-selected reader.
  Future<List<ReaderRegion>> supportedReaderRegions() async =>
      (await _api.supportedReaderRegions()).whereType<ReaderRegion>().toList();

  /// Applies a regulatory region to the current reader.
  Future<void> setReaderRegion({required String regionCode}) =>
      _api.setReaderRegion(regionCode);

  /// Returns a diagnostics snapshot (counters, last error, timing, state).
  Future<Diagnostics> diagnostics() => _api.diagnostics();

  /// Enable or disable hardware-trigger initiated scanning/inventory.
  Future<void> setScanningEnabled({required bool enabled}) =>
      _api.setScanningEnabled(enabled);

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
      connectionStatusChanged.add(status.connectionStatus);

  @override
  void onBluetoothDeviceDiscovered(BluetoothDevice device) {
    bluetoothDeviceDiscovered.add(device);
  }

  @override
  void onBluetoothScanStatusChanged(BluetoothScanStatus status) {
    bluetoothScanStatusChanged.add(status);
  }

  @override
  void onBluetoothPairingResult(BluetoothDevice device, bool success) {
    bluetoothPairingResult.add(
      BluetoothPairingResult(device: device, success: success),
    );
  }

  @override
  void onAvailableReadersChanged(List<Reader?> readers) =>
      availableReadersChanged.add(readers.map((e) => e as Reader).toList());

  @override
  void onTagsRead(List<RfidTag?> tags) {
    tagsRead.add(
      tags.map((e) => e as RfidTag).toList(),
    );
  }

  @override
  void onBatteryDataReceived(BatteryData batteryData) =>
      batteryDataReceived.add(batteryData);

  @override
  void onTagsLocated(List<RfidTag?> tags) {
    tagsLocated.add(
      tags.map((e) => e as RfidTag).toList(),
    );
  }

  @override
  void onReaderConnectionError(ReaderError error) {
    connectionErrors.add(error);
  }

  final connectionStatusChanged = BehaviorSubject<ConnectionStatus>()
    ..add(ConnectionStatus.disconnected);

  final bluetoothDeviceDiscovered = BehaviorSubject<BluetoothDevice>();
  final bluetoothScanStatusChanged = BehaviorSubject<BluetoothScanStatus>();
  final bluetoothPairingResult = BehaviorSubject<BluetoothPairingResult>();
  final availableReadersChanged = BehaviorSubject<List<Reader>>();
  final tagsRead = BehaviorSubject<List<RfidTag>>();
  final batteryDataReceived = BehaviorSubject<BatteryData>();
  final tagsLocated = BehaviorSubject<List<RfidTag>>();
  final connectionErrors = BehaviorSubject<ReaderError>();
}

class BluetoothPairingResult {
  BluetoothPairingResult({required this.device, required this.success});

  final BluetoothDevice device;
  final bool success;
}

extension ReaderInfoX on ReaderInfo {
  String get asString => '''
TransmitPowerLevels: ${transmitPowerLevels.first} - ${transmitPowerLevels.last}
FirmwareVersion: $firmwareVersion
ModelVersion: $modelVersion
ScannerName: $scannerName
SerialNumber: $serialNumber
''';
}

extension ReaderConnectionStatusX on ReaderConnectionStatus {
  ConnectionStatus get connectionStatus => switch (this) {
        ReaderConnectionStatus.connecting => ConnectionStatus.connecting,
        ReaderConnectionStatus.connected => ConnectionStatus.connected,
        ReaderConnectionStatus.disconnecting => ConnectionStatus.disconnecting,
        ReaderConnectionStatus.disconnected => ConnectionStatus.disconnected,
        ReaderConnectionStatus.error => ConnectionStatus.error,
      };
}
