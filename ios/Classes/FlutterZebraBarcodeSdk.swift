import os

@available(iOS 14.0, *)
class FlutterZebraBarcodeSdk: NSObject, FlutterZebraBarcode, ISbtSdkApiDelegate {
    init(callbacks: FlutterZebraBarcodeCallbacksProtocol) {
        _logger.debug("Starting Flutter Barcode SDK")
        _barcodeApi = SbtSdkFactory.createSbtSdkApiInstance()
         _callbacks = callbacks
         super.init()

        subscribeToEvents()
        
        _barcodeApi.sbtSetDelegate(self)
        _barcodeApi.sbtSetOperationalMode(Int32(SBT_OPMODE_ALL))
        
        
        updateScanners()
    }

    // MARK: FlutterZebraBarcode protocol
    func updateAvailableScanners(completion: @escaping (Result<Void, any Error>) -> Void) {
        updateScanners()
        completion(.success(()))
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

    func refreshBarcodeScanners(completion: @escaping (Result<Void, any Error>) -> Void) {
        updateScanners()
        completion(.success(()))
    }

    func setActiveBarcodeScanner(endpointId: String, completion: @escaping (Result<Void, any Error>) -> Void) {
        guard let scanner = scannerForEndpoint(endpointId) else {
            completion(.failure(FlutterBarcodeError(
                code: "scannerUnavailable",
                message: "Barcode scanner endpoint is not available",
                details: endpointId
            )))
            return
        }
        _activeEndpointId = endpointId
        _callbacks.onActiveBarcodeScannerChanged(endpoint: endpoint(for: scanner, active: true)) {_ in}
        completion(.success(()))
    }

    func clearActiveBarcodeScanner(completion: @escaping (Result<Void, any Error>) -> Void) {
        _activeEndpointId = nil
        _callbacks.onActiveBarcodeScannerChanged(endpoint: nil) {_ in}
        updateScanners()
        completion(.success(()))
    }
    
    func currentScanner() throws -> BarcodeScanner? {
        guard let scanner = _currentSbtScanner else {
            _logger.error("No connect scanner")
            return nil
        }
        return BarcodeScanner(
            name: scanner.getScannerName(),
            id: Int64(scanner.getScannerID()),
            model: scanner.getScannerModel(),
            serialNumber: scanner.serialNo
        )
    }

    func activeBarcodeScanner() throws -> BarcodeScannerEndpoint? {
        guard let endpointId = _activeEndpointId else {
            return nil
        }
        return scannerForEndpoint(endpointId).map { endpoint(for: $0, active: true) }
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
        _activeEndpointId = endpointId(for: activeScanner)
        _callbacks.onScannerConnectionStatusChanged(status: .connected) {_ in}
        _callbacks.onActiveBarcodeScannerChanged(endpoint: endpoint(for: activeScanner, active: true)) {_ in}
        updateScanners()
    }
    
    func sbtEventCommunicationSessionTerminated(_ scannerID: Int32) {
        _logger.debug("Scanner disconnected: \(scannerID)")
        _currentSbtScanner = nil
        _callbacks.onScannerConnectionStatusChanged(status: .disconnected) {_ in}
        updateScanners()
    }
    
    func sbtEventBarcode(_ barcodeData: String!, barcodeType: Int32, fromScanner scannerID: Int32) {
        // NOTE: this doesn't seem to return anything valid
        _logger.debug("Barcode scanned: \(barcodeData ?? "unknown")")
    }
    
    func sbtEventBarcodeData(_ barcodeData: Data!, barcodeType: Int32, fromScanner scannerID: Int32) {
        _logger.debug("Barcode data scanned: \(barcodeData)")
        if let data = barcodeData {
            let barcode = String(decoding: data, as: UTF8.self)
            _callbacks.onBarcodeRead(
                barcode: Barcode(data: barcode,
                                 scannerId: Int64(scannerID),
                                 barcodeType: Int64(barcodeType),
                                 endpointId: _activeEndpointId,
                                 source: .externalBluetooth,
                                 scannerName: _currentSbtScanner?.getScannerName()
                                )
            ) {_ in}
        }
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
    private var _activeEndpointId: String? = nil
    
    private func subscribeToEvents() {
        let mask = Int32(SBT_EVENT_SCANNER_APPEARANCE) |
        Int32(SBT_EVENT_SCANNER_DISAPPEARANCE) | Int32(SBT_EVENT_SESSION_ESTABLISHMENT) |
              Int32(SBT_EVENT_SESSION_TERMINATION) | Int32(SBT_EVENT_BARCODE) | Int32(SBT_EVENT_IMAGE) |
              Int32(SBT_EVENT_VIDEO)
        
        _barcodeApi.sbtSubsribe(forEvents: Int32(mask))
        _barcodeApi.sbtEnableBluetoothScannerDiscovery(true)
        _barcodeApi.sbtEnableAvailableScannersDetection(true)
    }
    
    private func updateScanners() {
        _availableScannerList?.removeAllObjects()
        
        var list: NSMutableArray? = NSMutableArray()
        let availableResult = _barcodeApi.sbtGetAvailableScannersList(&list)
        if (availableResult == SBT_RESULT_SUCCESS) {
            _availableScannerList?.addObjects(from: list as! [Any])
        }

        let activeResult = _barcodeApi.sbtGetActiveScannersList(&list)
        if (activeResult == SBT_RESULT_SUCCESS) {
            _availableScannerList?.addObjects(from: list as! [Any])
        }
        _logger.debug("Found \(self._availableScannerList!.count) scanners")

        if let availableList = _availableScannerList {
            let scanners = availableList.map {
                let scanner = $0 as! SbtScannerInfo
                return BarcodeScanner(
                    name: scanner.getScannerName(),
                    id: Int64(scanner.getScannerID()),
                    model: scanner.getScannerModel(),
                    serialNumber: scanner.serialNo ?? String()
                )
            }
            _callbacks.onAvailableScannersChanged(readers: scanners) {_ in}

            let endpoints = availableList.map {
                endpoint(for: $0 as! SbtScannerInfo, active: endpointId(for: $0 as! SbtScannerInfo) == _activeEndpointId)
            }
            if (_activeEndpointId == nil && endpoints.count == 1) {
                _activeEndpointId = endpoints.first?.endpointId
            }
            _callbacks.onAvailableBarcodeScannersChanged(endpoints: endpoints.map {
                var endpoint = $0
                endpoint.active = endpoint.endpointId == _activeEndpointId
                return endpoint
            }) {_ in}
            _callbacks.onActiveBarcodeScannerChanged(endpoint: endpoints.first(where: { $0.endpointId == _activeEndpointId })) {_ in}

        } else {
            _logger.debug("No scanners detected!")
        }
    }

    private func endpointId(for scanner: SbtScannerInfo) -> String {
        "scanner-sdk:\(scanner.getScannerID())"
    }

    private func scannerForEndpoint(_ endpointId: String) -> SbtScannerInfo? {
        guard let availableList = _availableScannerList else {
            return nil
        }
        return availableList.compactMap { $0 as? SbtScannerInfo }.first {
            self.endpointId(for: $0) == endpointId
        }
    }

    private func endpoint(for scanner: SbtScannerInfo, active: Bool) -> BarcodeScannerEndpoint {
        BarcodeScannerEndpoint(
            endpointId: endpointId(for: scanner),
            displayName: scanner.getScannerName() ?? "Scanner \(scanner.getScannerID())",
            source: inferSource(scanner),
            mode: .scannerSdk,
            connectionStatus: _currentSbtScanner?.getScannerID() == scanner.getScannerID() ? .connected : .disconnected,
            active: active,
            preferred: active,
            zebraScannerIdentifier: nil,
            scannerIndex: nil,
            scannerId: Int64(scanner.getScannerID()),
            model: scanner.getScannerModel(),
            serialNumber: scanner.serialNo
        )
    }

    private func inferSource(_ scanner: SbtScannerInfo) -> BarcodeScannerSource {
        let text = "\(scanner.getScannerName() ?? "") \(scanner.getScannerModel() ?? "")".uppercased()
        if text.contains("RFD") {
            return .rfidSled
        }
        if text.contains("USB") {
            return .externalUsb
        }
        return .externalBluetooth
    }
}
