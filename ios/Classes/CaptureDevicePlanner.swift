import Foundation

enum CaptureDevicePlanningPlatform {
    case android
    case ios
}

struct CaptureDevicePlanningState {
    let activeCaptureDeviceId: String?
    let activeRfidStatus: CaptureCapabilityStatus?
    let activeBarcodeStatus: CaptureCapabilityStatus?
    let activeRfidError: String?
    let activeBarcodeError: String?
    let barcodeOverrides: [String: String]
}

class CaptureDevicePlanner {
    init(platform: CaptureDevicePlanningPlatform) {
        self.platform = platform
    }

    func buildDevices(
        readers: [Reader],
        endpoints: [BarcodeScannerEndpoint],
        state: CaptureDevicePlanningState
    ) -> [CaptureDevice] {
        var assigned = Set<String>()
        var devices: [CaptureDevice] = []

        for reader in readers {
            let id = captureDeviceId(reader)
            let match = selectEndpoint(
                captureDeviceId: id,
                reader: reader,
                endpoints: endpoints,
                overrides: state.barcodeOverrides
            )
            if let endpoint = match.endpoint {
                assigned.insert(endpoint.endpointId)
            }
            devices.append(buildDevice(id: id, reader: reader, match: match, state: state))
        }

        for endpoint in endpoints where !assigned.contains(endpoint.endpointId) {
            devices.append(buildBarcodeOnlyDevice(endpoint, state: state))
        }

        return devices.sorted {
            if $0.active != $1.active {
                return $0.active
            }
            return $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
        }
    }

    private let platform: CaptureDevicePlanningPlatform

    private func buildDevice(
        id: String,
        reader: Reader,
        match: BarcodeMatch,
        state: CaptureDevicePlanningState
    ) -> CaptureDevice {
        let active = id == state.activeCaptureDeviceId
        let rfidStatus = active ? (state.activeRfidStatus ?? .disconnected) : .disconnected
        let barcodeStatus = active
            ? (state.activeBarcodeStatus ?? match.endpoint?.connectionStatus.toCaptureStatus() ?? .unavailable)
            : (match.endpoint?.connectionStatus.toCaptureStatus() ?? .unavailable)
        let rfid = CaptureRfidCapability(
            readerId: reader.id,
            hardwareIdentity: reader.hardwareIdentity ?? reader.name ?? "reader-\(reader.id)",
            displayName: reader.name ?? "RFID reader \(reader.id)",
            status: rfidStatus,
            model: reader.info?.modelVersion,
            serialNumber: reader.info?.serialNumber,
            firmwareVersion: reader.info?.firmwareVersion,
            error: active ? state.activeRfidError : nil
        )
        let barcode = match.endpoint?.toCaptureCapability(
            status: barcodeStatus,
            error: active ? state.activeBarcodeError : nil
        )
        let displayName: String
        if match.topology == .tc22RfidSled {
            displayName = "\(reader.name ?? "RFID sled") + terminal barcode"
        } else if let barcode = barcode {
            displayName = "\(reader.name ?? "RFID reader") + \(barcode.displayName)"
        } else {
            displayName = reader.name ?? "RFID reader \(reader.id)"
        }

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

    private func buildBarcodeOnlyDevice(
        _ endpoint: BarcodeScannerEndpoint,
        state: CaptureDevicePlanningState
    ) -> CaptureDevice {
        let id = "capture:barcode:\(endpoint.endpointId)"
        let active = id == state.activeCaptureDeviceId
        let status = active
            ? (state.activeBarcodeStatus ?? endpoint.connectionStatus.toCaptureStatus())
            : endpoint.connectionStatus.toCaptureStatus()
        let barcode = endpoint.toCaptureCapability(
            status: status,
            error: active ? state.activeBarcodeError : nil
        )
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
        endpoints: [BarcodeScannerEndpoint],
        overrides: [String: String]
    ) -> BarcodeMatch {
        if let override = overrides[captureDeviceId],
           let endpoint = endpoints.first(where: { $0.endpointId == override }) {
            return BarcodeMatch(
                endpoint: endpoint,
                confidence: .manual,
                reason: "Barcode Endpoint was manually selected for this Capture Device.",
                topology: topologyFor(reader: reader, endpoint: endpoint)
            )
        }

        if let serial = reader.info?.serialNumber?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
           !serial.isEmpty,
           let endpoint = endpoints.first(where: {
               $0.serialNumber?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == serial ||
                   $0.zebraScannerIdentifier?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == serial
           }) {
            return BarcodeMatch(
                endpoint: endpoint,
                confidence: .exact,
                reason: "RFID Reader and Barcode Endpoint share serial \(serial).",
                topology: topologyFor(reader: reader, endpoint: endpoint)
            )
        }

        if platform == .android,
           let endpoint = endpoints.first(where: { $0.source == .builtInTerminal && looksLikeSled(reader: reader) }) {
            return BarcodeMatch(
                endpoint: endpoint,
                confidence: .high,
                reason: "RFID sled paired with the terminal built-in Barcode Endpoint.",
                topology: .tc22RfidSled
            )
        }

        if let endpoint = endpoints.first(where: {
            $0.source == .externalBluetooth && nameTokensOverlap(reader: reader, endpoint: $0)
        }) {
            return BarcodeMatch(
                endpoint: endpoint,
                confidence: .medium,
                reason: "RFID Reader and Barcode Endpoint have overlapping Zebra model/name tokens.",
                topology: .bluetoothComboReader
            )
        }

        if let endpoint = endpoints.first(where: {
            $0.source == .rfidSled && nameTokensOverlap(reader: reader, endpoint: $0)
        }) {
            return BarcodeMatch(
                endpoint: endpoint,
                confidence: .medium,
                reason: "RFID Reader matched an RFID sled Barcode Endpoint by model/name tokens.",
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

    private func captureDeviceId(_ reader: Reader) -> String {
        "capture:rfid:\(reader.hardwareIdentity ?? reader.name ?? String(reader.id))"
    }

    private func topologyFor(reader: Reader, endpoint: BarcodeScannerEndpoint) -> CaptureDeviceTopology {
        if platform == .android && endpoint.source == .builtInTerminal && looksLikeSled(reader: reader) {
            return .tc22RfidSled
        }
        switch endpoint.source {
        case .builtInTerminal:
            return .externalRfidWithTerminalBarcode
        case .externalBluetooth, .rfidSled:
            return .bluetoothComboReader
        default:
            return .unknown
        }
    }

    private func looksLikeSled(reader: Reader) -> Bool {
        let text = "\(reader.name ?? "") \(reader.info?.modelVersion ?? "") \(reader.info?.scannerName ?? "")".uppercased()
        return text.contains("RFD") || text.contains("SLED")
    }

    private func nameTokensOverlap(reader: Reader, endpoint: BarcodeScannerEndpoint) -> Bool {
        let readerTokens = tokens("\(reader.name ?? "") \(reader.info?.modelVersion ?? "") \(reader.info?.scannerName ?? "")")
        let endpointTokens = tokens("\(endpoint.displayName) \(endpoint.model ?? "") \(endpoint.zebraScannerIdentifier ?? "")")
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
    if statuses.isEmpty {
        return .disconnected
    }
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
    if hasConnected && statuses.allSatisfy({ $0 == .connected || $0 == .unavailable }) {
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
