import os

@available(iOS 14.0, *)
class FlutterZebraCaptureSdk: NSObject, FlutterZebraCapture {
    init(
        callbacks: FlutterZebraCaptureCallbacksProtocol,
        rfid: FlutterZebraRfidSdk,
        barcode: FlutterZebraBarcodeSdk
    ) {
        _callbacks = callbacks
        _rfid = rfid
        _barcode = barcode
        super.init()
    }

    func refreshCaptureDevices(completion: @escaping (Result<Void, Error>) -> Void) {
        _rfid.updateAvailableReaders(connectionType: .all) { _ in }
        _barcode.refreshBarcodeScanners { _ in }
        emitDevices()
        completion(.success(()))
    }

    func connectCaptureDevice(
        captureDeviceId: String,
        rfidConfig: CaptureReaderConfig?,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        guard let device = buildDevices().first(where: { $0.id == captureDeviceId }) else {
            completion(.failure(FlutterCaptureError(
                code: "captureDeviceUnavailable",
                message: "Capture Device is not available",
                details: captureDeviceId
            )))
            return
        }

        _activeCaptureDeviceId = captureDeviceId
        _activeRfidStatus = device.rfid == nil ? .unavailable : .connecting
        _activeBarcodeStatus = device.barcode == nil ? .unavailable : .connecting
        _activeRfidError = nil
        _activeBarcodeError = nil
        emitDevices()

        if let rfid = device.rfid {
            _rfid.connectReader(readerId: rfid.readerId) { result in
                switch result {
                case .success:
                    self._activeRfidStatus = .connected
                    if let rfidConfig {
                        self._rfid.configureReader(
                            config: rfidConfig.toReaderConfig(),
                            shouldPersist: false
                        ) { configResult in
                            if case .failure(let error) = configResult {
                                self._activeRfidStatus = .error
                                self._activeRfidError = "Connected, but RFID configuration failed: \(error.localizedDescription)"
                            }
                            self.emitDevices()
                        }
                    }
                case .failure(let error):
                    self._activeRfidStatus = .error
                    self._activeRfidError = error.localizedDescription
                }
                self.emitDevices()
            }
        }

        if let barcode = device.barcode {
            _barcode.setActiveBarcodeScanner(endpointId: barcode.endpointId) { result in
                switch result {
                case .success:
                    if let scannerId = barcode.scannerId {
                        self._barcode.connectScanner(scannerId: scannerId) { connectResult in
                            switch connectResult {
                            case .success:
                                self._activeBarcodeStatus = .connected
                                self._activeBarcodeError = nil
                            case .failure(let error):
                                self._activeBarcodeStatus = .error
                                self._activeBarcodeError = error.localizedDescription
                            }
                            self.emitDevices()
                        }
                    } else {
                        self._activeBarcodeStatus = .connected
                        self._activeBarcodeError = nil
                    }
                case .failure(let error):
                    self._activeBarcodeStatus = .error
                    self._activeBarcodeError = error.localizedDescription
                }
                self.emitDevices()
            }
        }

        completion(.success(()))
    }

    func disconnectCaptureDevice(
        captureDeviceId: String,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        _activeRfidStatus = .disconnected
        _activeBarcodeStatus = .disconnected
        _activeRfidError = nil
        _activeBarcodeError = nil
        _rfid.disconnectReader { _ in }
        _barcode.disconnectScanner { _ in }
        if _activeCaptureDeviceId == captureDeviceId {
            _activeCaptureDeviceId = nil
        }
        emitDevices()
        completion(.success(()))
    }

    func setCaptureDeviceBarcodeOverride(
        captureDeviceId: String,
        barcodeEndpointId: String,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        guard _barcode.barcodeEndpointsSnapshot().contains(where: { $0.endpointId == barcodeEndpointId }) else {
            completion(.failure(FlutterCaptureError(
                code: "barcodeEndpointUnavailable",
                message: "Barcode Endpoint is not available",
                details: barcodeEndpointId
            )))
            return
        }
        _barcodeOverrides[captureDeviceId] = barcodeEndpointId
        emitDevices()
        completion(.success(()))
    }

    func activeCaptureDevice() throws -> CaptureDevice? {
        buildDevices().first(where: { $0.id == _activeCaptureDeviceId })
    }

    private let _callbacks: FlutterZebraCaptureCallbacksProtocol
    private let _rfid: FlutterZebraRfidSdk
    private let _barcode: FlutterZebraBarcodeSdk
    private var _activeCaptureDeviceId: String? = nil
    private var _activeRfidStatus: CaptureCapabilityStatus? = nil
    private var _activeBarcodeStatus: CaptureCapabilityStatus? = nil
    private var _activeRfidError: String? = nil
    private var _activeBarcodeError: String? = nil
    private var _barcodeOverrides: [String: String] = [:]

    private func emitDevices() {
        let devices = buildDevices()
        _callbacks.onAvailableCaptureDevicesChanged(devices: devices) { _ in }
        let active = devices.first(where: { $0.id == _activeCaptureDeviceId })
        _callbacks.onActiveCaptureDeviceChanged(device: active) { _ in }
        if let active {
            _callbacks.onCaptureDeviceStatusChanged(device: active) { _ in }
        }
    }

    private func buildDevices() -> [CaptureDevice] {
        let readers = _rfid.availableReadersSnapshot()
        let endpoints = _barcode.barcodeEndpointsSnapshot()
        var assigned = Set<String>()
        var devices: [CaptureDevice] = []

        for reader in readers {
            let id = "capture:rfid:\(reader.id)"
            let match = selectEndpoint(captureDeviceId: id, reader: reader, endpoints: endpoints)
            if let endpoint = match.endpoint {
                assigned.insert(endpoint.endpointId)
            }
            devices.append(buildDevice(id: id, reader: reader, match: match))
        }

        for endpoint in endpoints where !assigned.contains(endpoint.endpointId) {
            devices.append(buildBarcodeOnlyDevice(endpoint))
        }

        return devices.sorted {
            if $0.active != $1.active {
                return $0.active
            }
            return $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
        }
    }

    private func buildDevice(id: String, reader: Reader, match: BarcodeMatch) -> CaptureDevice {
        let active = id == _activeCaptureDeviceId
        let rfidStatus = active ? (_activeRfidStatus ?? .disconnected) : .disconnected
        let barcodeStatus = active ? (_activeBarcodeStatus ?? match.endpoint?.connectionStatus.toCaptureStatus() ?? .unavailable) : (match.endpoint?.connectionStatus.toCaptureStatus() ?? .unavailable)
        let rfid = CaptureRfidCapability(
            readerId: reader.id,
            displayName: reader.name ?? "RFID reader \(reader.id)",
            status: rfidStatus,
            model: reader.info?.modelVersion,
            serialNumber: reader.info?.serialNumber,
            error: active ? _activeRfidError : nil
        )
        let barcode = match.endpoint?.toCaptureCapability(
            status: barcodeStatus,
            error: active ? _activeBarcodeError : nil
        )
        let displayName = barcode == nil
            ? (reader.name ?? "RFID reader \(reader.id)")
            : "\(reader.name ?? "RFID reader") + \(barcode!.displayName)"
        return CaptureDevice(
            id: id,
            displayName: displayName,
            topology: match.topology,
            status: deriveStatus(rfidStatus: rfid.status, barcodeStatus: barcode?.status),
            matchConfidence: match.confidence,
            matchReason: match.reason,
            active: active,
            rfid: rfid,
            barcode: barcode,
            lastError: rfid.error ?? barcode?.error
        )
    }

    private func buildBarcodeOnlyDevice(_ endpoint: BarcodeScannerEndpoint) -> CaptureDevice {
        let id = "capture:barcode:\(endpoint.endpointId)"
        let active = id == _activeCaptureDeviceId
        let status = active ? (_activeBarcodeStatus ?? endpoint.connectionStatus.toCaptureStatus()) : endpoint.connectionStatus.toCaptureStatus()
        let barcode = endpoint.toCaptureCapability(status: status, error: active ? _activeBarcodeError : nil)
        return CaptureDevice(
            id: id,
            displayName: endpoint.displayName,
            topology: .barcodeOnly,
            status: deriveStatus(rfidStatus: nil, barcodeStatus: barcode.status),
            matchConfidence: .high,
            matchReason: "Barcode Endpoint reported without a matching RFID Reader.",
            active: active,
            rfid: nil,
            barcode: barcode,
            lastError: barcode.error
        )
    }

    private func selectEndpoint(
        captureDeviceId: String,
        reader: Reader,
        endpoints: [BarcodeScannerEndpoint]
    ) -> BarcodeMatch {
        if let override = _barcodeOverrides[captureDeviceId],
           let endpoint = endpoints.first(where: { $0.endpointId == override }) {
            return BarcodeMatch(
                endpoint: endpoint,
                confidence: .manual,
                reason: "Barcode Endpoint was manually selected for this Capture Device.",
                topology: .bluetoothComboReader
            )
        }

        if let serial = reader.info?.serialNumber?.uppercased(),
           let endpoint = endpoints.first(where: { $0.serialNumber?.uppercased() == serial }) {
            return BarcodeMatch(
                endpoint: endpoint,
                confidence: .exact,
                reason: "RFID Reader and Barcode Endpoint share serial \(serial).",
                topology: .bluetoothComboReader
            )
        }

        if let endpoint = endpoints.first(where: { nameTokensOverlap(reader: reader, endpoint: $0) }) {
            return BarcodeMatch(
                endpoint: endpoint,
                confidence: .medium,
                reason: "RFID Reader and Barcode Endpoint have overlapping Zebra model/name tokens.",
                topology: .bluetoothComboReader
            )
        }

        return BarcodeMatch(
            endpoint: nil,
            confidence: .low,
            reason: "No Barcode Endpoint could be confidently matched; select one manually if barcode capture is required.",
            topology: .rfidOnly
        )
    }

    private func nameTokensOverlap(reader: Reader, endpoint: BarcodeScannerEndpoint) -> Bool {
        let readerTokens = tokens("\(reader.name ?? "") \(reader.info?.modelVersion ?? "") \(reader.info?.scannerName ?? "")")
        let endpointTokens = tokens("\(endpoint.displayName) \(endpoint.model ?? "")")
        return !readerTokens.intersection(endpointTokens).filter { $0.count >= 3 }.isEmpty
    }

    private func tokens(_ value: String) -> Set<String> {
        Set(value.uppercased().split { !$0.isLetter && !$0.isNumber }.map(String.init).filter { $0.count >= 3 })
    }

    private struct BarcodeMatch {
        let endpoint: BarcodeScannerEndpoint?
        let confidence: CaptureMatchConfidence
        let reason: String
        let topology: CaptureDeviceTopology
    }
}

private func deriveStatus(
    rfidStatus: CaptureCapabilityStatus?,
    barcodeStatus: CaptureCapabilityStatus?
) -> CaptureDeviceStatus {
    let statuses = [rfidStatus, barcodeStatus].compactMap { $0 }
    if statuses.contains(.connecting) {
        return .connecting
    }
    let hasConnected = statuses.contains(.connected)
    let hasError = statuses.contains(.error)
    if hasConnected && hasError {
        return .degraded
    }
    if hasError {
        return .error
    }
    if hasConnected {
        return .connected
    }
    return .disconnected
}

private extension ScannerConnectionStatus {
    func toCaptureStatus() -> CaptureCapabilityStatus {
        switch self {
        case .connecting:
            return .connecting
        case .connected:
            return .connected
        case .disconnecting, .disconnected:
            return .disconnected
        case .error:
            return .error
        }
    }
}

private extension BarcodeScannerEndpoint {
    func toCaptureCapability(status: CaptureCapabilityStatus, error: String?) -> CaptureBarcodeCapability {
        CaptureBarcodeCapability(
            endpointId: endpointId,
            displayName: displayName,
            source: source.toCaptureSource(),
            mode: mode.toCaptureMode(),
            status: status,
            preferred: preferred,
            scannerId: scannerId,
            model: model,
            serialNumber: serialNumber,
            error: error
        )
    }
}

private extension BarcodeScannerSource {
    func toCaptureSource() -> CaptureBarcodeSource {
        switch self {
        case .builtInTerminal:
            return .builtInTerminal
        case .rfidSled:
            return .rfidSled
        case .externalBluetooth:
            return .externalBluetooth
        case .externalUsb:
            return .externalUsb
        case .unknown:
            return .unknown
        }
    }
}

private extension BarcodeScannerMode {
    func toCaptureMode() -> CaptureBarcodeMode {
        switch self {
        case .auto:
            return .auto
        case .dataWedge:
            return .dataWedge
        case .scannerSdk:
            return .scannerSdk
        }
    }
}

private extension CaptureReaderConfig {
    func toReaderConfig() -> ReaderConfig {
        ReaderConfig(
            transmitPowerIndex: transmitPowerIndex,
            tari: tari,
            beeperVolume: beeperVolume?.toReaderBeeperVolume(),
            enableDynamicPower: enableDynamicPower,
            enableLedBlink: enableLedBlink,
            batchMode: batchMode?.toReaderConfigBatchMode(),
            scanBatchMode: scanBatchMode?.toReaderConfigBatchMode(),
            rfModeTableIndex: rfModeTableIndex,
            receiveSensitivityIndex: receiveSensitivityIndex
        )
    }
}

private extension CaptureReaderBeeperVolume {
    func toReaderBeeperVolume() -> ReaderBeeperVolume {
        switch self {
        case .quiet:
            return .quiet
        case .low:
            return .low
        case .medium:
            return .medium
        case .high:
            return .high
        }
    }
}

private extension CaptureReaderConfigBatchMode {
    func toReaderConfigBatchMode() -> ReaderConfigBatchMode {
        switch self {
        case .auto:
            return .auto
        case .enabled:
            return .enabled
        case .disabled:
            return .disabled
        }
    }
}
