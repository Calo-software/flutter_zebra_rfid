import os

@available(iOS 14.0, *)
class FlutterZebraCaptureSdk: NSObject, FlutterZebraCapture {
    func captureDiagnostics() throws -> [CaptureDiagnosticEvent] {
        []
    }

    func clearCaptureDiagnostics() throws {
        // Android supplies full hardware diagnostics. iOS intentionally
        // exposes the compatible, empty implementation for this pilot.
    }

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

    func configureCaptureDevice(
        captureDeviceId: String,
        rfidConfig: CaptureReaderConfig,
        shouldPersist: Bool,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        guard _activeCaptureDeviceId == captureDeviceId else {
            completion(.failure(FlutterCaptureError(
                code: "captureDeviceNotActive",
                message: "Capture Device is not active",
                details: captureDeviceId
            )))
            return
        }
        _rfid.configureReader(
            config: rfidConfig.toReaderConfig(),
            shouldPersist: shouldPersist,
            completion: completion
        )
    }

    func setCaptureDeviceForeground(
        foreground: Bool,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        completion(.success(()))
    }

    func activeCaptureDevice() throws -> CaptureDevice? {
        buildDevices().first(where: { $0.id == _activeCaptureDeviceId })
    }

    private let _callbacks: FlutterZebraCaptureCallbacksProtocol
    private let _rfid: FlutterZebraRfidSdk
    private let _barcode: FlutterZebraBarcodeSdk
    private let _planner = CaptureDevicePlanner(platform: .ios)
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
        _planner.buildDevices(
            readers: _rfid.availableReadersSnapshot(),
            endpoints: _barcode.barcodeEndpointsSnapshot(),
            state: CaptureDevicePlanningState(
                activeCaptureDeviceId: _activeCaptureDeviceId,
                activeRfidStatus: _activeRfidStatus,
                activeBarcodeStatus: _activeBarcodeStatus,
                activeRfidError: _activeRfidError,
                activeBarcodeError: _activeBarcodeError,
                barcodeOverrides: _barcodeOverrides
            )
        )
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
