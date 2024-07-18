import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_rfid.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_rfid_platform_interface.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_rfid_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockFlutterZebraRfidPlatform
    with MockPlatformInterfaceMixin
    implements FlutterZebraRfidPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final FlutterZebraRfidPlatform initialPlatform = FlutterZebraRfidPlatform.instance;

  test('$MethodChannelFlutterZebraRfid is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelFlutterZebraRfid>());
  });

  test('getPlatformVersion', () async {
    FlutterZebraRfid flutterZebraRfidPlugin = FlutterZebraRfid();
    MockFlutterZebraRfidPlatform fakePlatform = MockFlutterZebraRfidPlatform();
    FlutterZebraRfidPlatform.instance = fakePlatform;

    expect(await flutterZebraRfidPlugin.getPlatformVersion(), '42');
  });
}
