import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'flutter_zebra_rfid_platform_interface.dart';

/// An implementation of [FlutterZebraRfidPlatform] that uses method channels.
class MethodChannelFlutterZebraRfid extends FlutterZebraRfidPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('flutter_zebra_rfid');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
