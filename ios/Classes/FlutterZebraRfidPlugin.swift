import Flutter
import UIKit

@available(iOS 14.0, *)
public class FlutterZebraRfidPlugin: NSObject, FlutterPlugin {
    public static func register(with registrar: FlutterPluginRegistrar) {
        let messenger : FlutterBinaryMessenger = registrar.messenger()
        let rfidInterfaceCallbacks : FlutterZebraRfidCallbacks = FlutterZebraRfidCallbacks(binaryMessenger: messenger)
        let barcodeInterfaceCallbacks : FlutterZebraBarcodeCallbacks = FlutterZebraBarcodeCallbacks(binaryMessenger: messenger)
        
        let rfidInterface : FlutterZebraRfid & NSObjectProtocol = FlutterZebraRfidSdk.init(callbacks: rfidInterfaceCallbacks)
        let scannerInterface : FlutterZebraBarcode & NSObjectProtocol = FlutterZebraBarcodeSdk(callbacks: barcodeInterfaceCallbacks)
        
        FlutterZebraRfidSetup.setUp(binaryMessenger: messenger, api: rfidInterface)
        FlutterZebraBarcodeSetup.setUp(binaryMessenger: messenger, api: scannerInterface)
    }
}
