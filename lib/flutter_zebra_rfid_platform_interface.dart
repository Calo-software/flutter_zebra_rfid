import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'flutter_zebra_rfid_method_channel.dart';

abstract class FlutterZebraRfidPlatform extends PlatformInterface {
  /// Constructs a FlutterZebraRfidPlatform.
  FlutterZebraRfidPlatform() : super(token: _token);

  static final Object _token = Object();

  static FlutterZebraRfidPlatform _instance = MethodChannelFlutterZebraRfid();

  /// The default instance of [FlutterZebraRfidPlatform] to use.
  ///
  /// Defaults to [MethodChannelFlutterZebraRfid].
  static FlutterZebraRfidPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [FlutterZebraRfidPlatform] when
  /// they register themselves.
  static set instance(FlutterZebraRfidPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
