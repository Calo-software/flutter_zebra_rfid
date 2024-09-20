import os

@available(iOS 14.0, *)
class FlutterZebraBarcodeSdk: NSObject, FlutterZebraBarcode, ISbtSdkApiDelegate {
    init(callbacks: FlutterZebraBarcodeCallbacksProtocol) {
        _barcodeApi = SbtSdkFactory.createSbtSdkApiInstance()
         _callbacks = callbacks
         super.init()

        _barcodeApi.sbtSetDelegate(self)
        _barcodeApi.sbtSetOperationalMode(Int32(SBT_OPMODE_ALL))
        
         subscribeToEvents()
    }

    // MARK: FlutterZebraBarcode protocol
    func updateAvailableScanners(completion: @escaping (Result<Void, any Error>) -> Void) {
    }
    
    func connectScanner(scannerId: Int64, completion: @escaping (Result<Void, any Error>) -> Void) {
        
        let result = _barcodeApi.sbtEstablishCommunicationSession(Int32(scannerId))
        if (result != SBT_RESULT_SUCCESS) {
            _logger.error("Cannot connect to scanner (\(scannerId))")
            completion(.failure(FlutterBarcodeError(
                code: "0",
                message: "Cannot connect to the scanner",
                details: nil))
            )
            return
        }
        
        completion(.success(()))
    }
    
    func disconnectScanner(completion: @escaping (Result<Void, any Error>) -> Void) {
        guard let scanner = _currentSbtScanner else {
            _logger.error("No connected scanner")
            completion(.failure(FlutterBarcodeError(
                code: "0",
                message: "No connected scanners",
                details: nil
            )))
            return
        }
        let scannerId = scanner.getScannerID()
        
        let result = _barcodeApi.sbtTerminateCommunicationSession(Int32(scannerId))
        if (result != SBT_RESULT_SUCCESS) {
            _logger.error("Cannot disconnect from scanner (\(scannerId))")
            completion(.failure(FlutterBarcodeError(
                code: "0",
                message: "Cannot disconnect from the scanner",
                details: nil))
            )
            return
        }
        completion(.success(()))
    }
    
    func currentScanner() throws -> BarcodeScanner? {
        guard let scanner = _currentSbtScanner else {
            _logger.error("No connect scanner")
            return nil
        }
        return BarcodeScanner(
            id: _currentSbtScanner?.getScannerID(),
            name: _currentSbtScanner?.getScannerName(),
            model: _currentSbtScanner?.getScannerModel(),
            serialNumber: _currentSbtScanner?.serialNo
        )
    }
    
    // MARK: ISbtSdkApiDelegate
    func sbtEventScannerAppeared(_ availableScanner: SbtScannerInfo!) {
        _logger.debug("Scanner appeared: \(availableScanner.getScannerName())")
        updateScanners()
    }
    
    func sbtEventScannerDisappeared(_ scannerID: Int32) {
        _logger.debug("Scanner disappeared: \(scannerID)")
        updateScanners()
    }
    
    func sbtEventCommunicationSessionEstablished(_ activeScanner: SbtScannerInfo!) {
        _logger.debug("Scanner connected: \(activeScanner.getScannerName())")
        _currentSbtScanner = activeScanner
        _callbacks.onScannerConnectionStatusChanged(status: .disconnected) {_ in}
    }
    
    func sbtEventCommunicationSessionTerminated(_ scannerID: Int32) {
        _logger.debug("Scanner disconnected: \(scannerID)")
        _currentSbtScanner = nil
        _callbacks.onScannerConnectionStatusChanged(status: .disconnected) {_ in}
    }
    
    func sbtEventBarcode(_ barcodeData: String!, barcodeType: Int32, fromScanner scannerID: Int32) {
        _logger.debug("Barcode scanned: \(barcodeData)")

    }
    
    func sbtEventBarcodeData(_ barcodeData: Data!, barcodeType: Int32, fromScanner scannerID: Int32) {
        _logger.debug("Barcode data scanned: \(barcodeData)")
    }
    
    func sbtEventFirmwareUpdate(_ fwUpdateEventObj: FirmwareUpdateEvent!) {
        _logger.debug("Firmware update event")
    }
    
    func sbtEventImage(_ imageData: Data!, fromScanner scannerID: Int32) {
        _logger.debug("Image event: \(scannerID)")
    }
    
    func sbtEventVideo(_ videoFrame: Data!, fromScanner scannerID: Int32) {
        _logger.debug("Video event: \(scannerID)")
    }
    

    // MARK: PRIVATE
    private let _logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "generic")
    private let _callbacks: FlutterZebraBarcodeCallbacksProtocol
    private let _barcodeApi: ISbtSdkApi

    private var _currentSbtScanner: SbtScannerInfo? = nil
    private var _availableScannerList: NSMutableArray? = []
    
    private func subscribeToEvents() {
        let mask = SBT_EVENT_SCANNER_APPEARANCE |
        SBT_EVENT_SCANNER_DISAPPEARANCE | SBT_EVENT_SESSION_ESTABLISHMENT |
        SBT_EVENT_SESSION_TERMINATION | SBT_EVENT_BARCODE | SBT_EVENT_IMAGE |
        SBT_EVENT_VIDEO
        
        _barcodeApi.sbtSubsribe(forEvents: Int32(mask))
        _barcodeApi.sbtEnableBluetoothScannerDiscovery(true)
        _barcodeApi.sbtEnableAvailableScannersDetection(true)
    }
    
    private func updateScanners() {
        _availableScannerList?.removeAllObjects()
        
        var list: NSMutableArray? = nil
        let availableResult = _barcodeApi.sbtGetAvailableScannersList(&list)
        if (availableResult == SBT_RESULT_SUCCESS) {
            _availableScannerList?.addObjects(from: list as! [Any])
        }

        let activeResult = _barcodeApi.sbtGetActiveScannersList(&list)
        if (activeResult == SBT_RESULT_SUCCESS) {
            _availableScannerList?.addObjects(from: list as! [Any])
        }
        
        if let availableList = _availableScannerList {
            _callbacks.onAvailableScannersChanged(readers: availableList.map {
                let scanner = $0 as! SbtScannerInfo
                return BarcodeScanner(
                    name: scanner.getScannerName(),
                    id: Int64(scanner.getScannerID()),
                    model: scanner.getScannerModel(),
                    serialNumber: scanner.serialNo
                )
            }) {_ in}

        } else {
            _logger.debug("No scanners detected!")
        }
    }
}
