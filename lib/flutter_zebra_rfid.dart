
import 'flutter_zebra_rfid_platform_interface.dart';

class FlutterZebraRfid {
  Future<String?> getPlatformVersion() {
    return FlutterZebraRfidPlatform.instance.getPlatformVersion();
  }
}
