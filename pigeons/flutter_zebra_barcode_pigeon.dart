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

/*
  /// Configures reader with `config`.
  @async
  void configureReader(ReaderConfig config, bool shouldPersist);

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
  });

  final String data;
  final int scannerId;
  final int? barcodeType;
}
