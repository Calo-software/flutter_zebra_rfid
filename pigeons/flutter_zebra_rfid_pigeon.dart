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
enum ReaderConnectionType {
  bluetooth,
  usb,
}

@HostApi()
abstract class FlutterZebraRfid {
  @async
  List<String> getAvailableReaders(ReaderConnectionType connectionType);
}
