import Flutter
import UIKit

public class FlutterZebraRfidPlugin: NSObject, FlutterPlugin {
  public static func register(with registrar: FlutterPluginRegistrar) {
    let messenger : FlutterBinaryMessenger = registrar.messenger()
    let rfidInterfaceCallbacks : FlutterZebraRfidCallbacks = FlutterZebraRfidCallbacks(binaryMessenger: messenger)
    let rfidInterface : FlutterZebraRfid & NSObjectProtocol = FlutterZebraRfidSdk.init(callbacks: rfidInterfaceCallbacks)
    FlutterZebraRfidSetup.setUp(binaryMessenger: messenger, api: rfidInterface )
  }
}
