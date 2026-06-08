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

  /// Disconnects a current scanner.
  @async
  void disconnectScanner();

  /// Refreshes all barcode scanner endpoints, including DataWedge-managed
  /// terminal scanners and Scanner SDK external scanners where available.
  @async
  void refreshBarcodeScanners();

  /// Selects the endpoint that should be treated as the active barcode source.
  @async
  void setActiveBarcodeScanner(String endpointId);

  /// Clears the explicit active barcode source.
  @async
  void clearActiveBarcodeScanner();

  /// Reader currently in use
  BarcodeScanner? currentScanner();

  /// Barcode scanner endpoint currently selected, if any.
  BarcodeScannerEndpoint? activeBarcodeScanner();
}

@FlutterApi()
abstract class FlutterZebraBarcodeCallbacks {
  void onAvailableScannersChanged(List<BarcodeScanner> readers);
  void onAvailableBarcodeScannersChanged(List<BarcodeScannerEndpoint> endpoints);
  void onActiveBarcodeScannerChanged(BarcodeScannerEndpoint? endpoint);
  void onScannerConnectionStatusChanged(ScannerConnectionStatus status);
  void onBarcodeRead(Barcode barcode);
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

enum BarcodeScannerSource {
  builtInTerminal,
  rfidSled,
  externalBluetooth,
  externalUsb,
  unknown,
}

enum BarcodeScannerMode {
  auto,
  dataWedge,
  scannerSdk,
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
  final String? model;
  final String? serialNumber;
}

class Barcode {
  Barcode({
    required this.data,
    required this.scannerId,
    this.barcodeType,
    this.endpointId,
    this.source,
    this.scannerName,
  });

  final String data;
  final int scannerId;
  final int? barcodeType;
  final String? endpointId;
  final BarcodeScannerSource? source;
  final String? scannerName;
}

class BarcodeScannerEndpoint {
  BarcodeScannerEndpoint({
    required this.endpointId,
    required this.displayName,
    required this.source,
    required this.mode,
    required this.connectionStatus,
    required this.active,
    required this.preferred,
    this.zebraScannerIdentifier,
    this.scannerIndex,
    this.scannerId,
    this.model,
    this.serialNumber,
  });

  final String endpointId;
  final String displayName;
  final BarcodeScannerSource source;
  final BarcodeScannerMode mode;
  final ScannerConnectionStatus connectionStatus;
  final bool active;
  final bool preferred;
  final String? zebraScannerIdentifier;
  final int? scannerIndex;
  final int? scannerId;
  final String? model;
  final String? serialNumber;
}
