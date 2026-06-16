import Flutter
import UIKit

@available(iOS 14.0, *)
public class FlutterZebraRfidPlugin: NSObject, FlutterPlugin {
    public static func register(with registrar: FlutterPluginRegistrar) {
        let messenger : FlutterBinaryMessenger = registrar.messenger()
        let rfidInterfaceCallbacks : FlutterZebraRfidCallbacks = FlutterZebraRfidCallbacks(binaryMessenger: messenger)
        let barcodeInterfaceCallbacks : FlutterZebraBarcodeCallbacks = FlutterZebraBarcodeCallbacks(binaryMessenger: messenger)
        let captureInterfaceCallbacks : FlutterZebraCaptureCallbacks = FlutterZebraCaptureCallbacks(binaryMessenger: messenger)
        
        let rfidSdk = FlutterZebraRfidSdk.init(callbacks: rfidInterfaceCallbacks)
        let scannerSdk = FlutterZebraBarcodeSdk(callbacks: barcodeInterfaceCallbacks)
        let rfidInterface : FlutterZebraRfid & NSObjectProtocol = rfidSdk
        let scannerInterface : FlutterZebraBarcode & NSObjectProtocol = scannerSdk
        let captureInterface : FlutterZebraCapture & NSObjectProtocol = FlutterZebraCaptureSdk(
            callbacks: captureInterfaceCallbacks,
            rfid: rfidSdk,
            barcode: scannerSdk
        )
        
        FlutterZebraRfidSetup.setUp(binaryMessenger: messenger, api: rfidInterface)
        FlutterZebraBarcodeSetup.setUp(binaryMessenger: messenger, api: scannerInterface)
        FlutterZebraCaptureSetup.setUp(binaryMessenger: messenger, api: captureInterface)
    }
}
