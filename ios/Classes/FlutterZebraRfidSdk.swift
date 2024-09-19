import os

@available(iOS 14.0, *)
class FlutterZebraRfidSdk: NSObject, FlutterZebraRfid, srfidISdkApiDelegate {
    init(callbacks: FlutterZebraRfidCallbacksProtocol) {
        _rfidApi = srfidSdkFactory.createRfidSdkApiInstance()
        _callbacks = callbacks
        super.init()
        _rfidApi.srfidSetDelegate(self)
        
        subscribeToEvents()
        
        _rfidApi.srfidSetOperationalMode(Int32(SRFID_OPMODE_ALL))
        _rfidApi.srfidEnableAvailableReadersDetection(true)
        
        
        
    }
    // MARK:
    // srfidSdkApiDelegatejj
    func srfidEventReaderAppeared(_ availableReader: srfidReaderInfo!) {
        updateReaders()
    }
    
    func srfidEventReaderDisappeared(_ readerID: Int32) {
        updateReaders()
    }
    
    func srfidEventCommunicationSessionEstablished(_ activeReader: srfidReaderInfo!) {
        _srfidCurrentReader = activeReader
        _callbacks.onReaderConnectionStatusChanged(status: ReaderConnectionStatus.connected) {_ in}
    }
    
    func srfidEventCommunicationSessionTerminated(_ readerID: Int32) {
        _srfidCurrentReader = nil
        _callbacks.onReaderConnectionStatusChanged(status: ReaderConnectionStatus.disconnected) {_ in}

    }
    
    func srfidEventReadNotify(_ readerID: Int32, aTagData tagData: srfidTagData!) {
    }
    
    func srfidEventStatusNotify(_ readerID: Int32, aEvent event: SRFID_EVENT_STATUS, aNotification notificationData: Any!) {
    }
    
    func srfidEventProximityNotify(_ readerID: Int32, aProximityPercent proximityPercent: Int32) {
    }
    
    func srfidEventMultiProximityNotify(_ readerID: Int32, aTagData tagData: srfidTagData!) {
    }
    
    func srfidEventTriggerNotify(_ readerID: Int32, aTriggerEvent triggerEvent: SRFID_TRIGGEREVENT) {
    }
    
    func srfidEventBatteryNotity(_ readerID: Int32, aBatteryEvent batteryEvent: srfidBatteryEvent!) {
        _callbacks.onBatteryDataReceived(
            batteryData: BatteryData(
                level: Int64(batteryEvent.getPowerLevel()),
                isCharging: batteryEvent.getIsCharging(),
                cause: batteryEvent.getCause()
            ), completion: {_ in }
        )
    }
    
    func srfidEventWifiScan(_ readerID: Int32, wlanSCanObject wlanScanObject: srfidWlanScanList!) {
    }
    
    // MARK:
    // FlutterZebraRfid
    func updateAvailableReaders(connectionType: ReaderConnectionType, completion: @escaping (Result<Void, Error>) -> Void) {}
    
    /// Connects to a reader with `readerName` name.
    func connectReader(readerId: Int64, completion: @escaping (Result<Void, Error>) -> Void) {
        _logger.info("Connecting to reader: \(readerId)")
        _callbacks.onReaderConnectionStatusChanged(status: ReaderConnectionStatus.connecting) {_ in}
        let result = _rfidApi.srfidEstablishCommunicationSession(Int32(readerId))
        
        if (result != SRFID_RESULT_SUCCESS) {
            _logger.error("Failed to connect to reader: \(readerId)")
            _callbacks.onReaderConnectionStatusChanged(status: ReaderConnectionStatus.disconnected) {_ in}
        }
    }
    
    /// Disconnects a reader with `readerName` name.
    func disconnectReader(completion: @escaping (Result<Void, Error>) -> Void) {
        _logger.info("Disconnecting reader \(self._srfidCurrentReader?.getReaderName() ?? "unknown")")
        if let id = _srfidCurrentReader?.getReaderID() {
            _rfidApi.srfidTerminateCommunicationSession(id)
        }
        else {
            _logger.error("No active reader to disconnect")
            _callbacks.onReaderConnectionStatusChanged(status: ReaderConnectionStatus.disconnected) {_ in}
        }
    }

    func configureReader(config: ReaderConfig, shouldPersist: Bool, completion: @escaping (Result<Void, Error>) -> Void) {
        
        guard let reader = _currentReader else {
            _logger.error("No active reader to configure")
            return
        }
        
        // ANTENNA CONFIG
        var antennaConfig: srfidAntennaConfiguration? = srfidAntennaConfiguration()
        var statusMessage: NSString? = nil
        _rfidApi.srfidGetAntennaConfiguration(
            Int32(reader.id),
            aAntennaConfiguration: &antennaConfig, aStatusMessage: &statusMessage
        )
        
        antennaConfig?.setTari(0)
        // NOTE: RFModeIndex -> set to 0 in Android
        antennaConfig?.setLinkProfileIdx(0)
        if let info = reader.info {
            if let index = config.transmitPowerIndex {
                antennaConfig?.setPower(info.transmitPowerLevels[Int(index)] as! Int16)
            } else {
                // set max power
                if let level = info.transmitPowerLevels.last {
                    antennaConfig?.setPower(level as! Int16)
                }
            }
        }
        let result = _rfidApi.srfidSetAntennaConfiguration(
            Int32(reader.id),
            aAntennaConfiguration: antennaConfig, 
            aStatusMessage: &statusMessage
        )
        if (result != SRFID_RESULT_SUCCESS) {
            _logger.error("Cannot configure antenna: \(statusMessage)")
        }
        
        
    }
    
    func triggerDeviceStatus(completion: @escaping (Result<Void, Error>) -> Void) {}
    
    /// Name of reader currently in use
    func currentReaderName() throws -> String? {
        return try currentReader()?.name
    }
    
    func currentReader() throws -> Reader? {
        if let reader = _srfidCurrentReader {
            return _availableReaders.first(where: { $0.id == reader.getReaderID() })
        }
        return nil
    }

    // MARK:
    // Private
    private let _logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "generic")
    private let _rfidApi: srfidISdkApi
    private let _callbacks: FlutterZebraRfidCallbacksProtocol
    
    private var _srfidAvailableReaders: Array<srfidReaderInfo> = []
    private var _srfidCurrentReader: srfidReaderInfo? = nil
    
    private var _availableReaders: Array<Reader> = []
    private var _currentReader: Reader? = nil
    
    private func emitAvailableReaders() {
        _availableReaders = _srfidAvailableReaders.map {
            var capabilitiesInfo: srfidReaderCapabilitiesInfo? = srfidReaderCapabilitiesInfo()
            var statusMessage: NSString? = NSString()
            let result = _rfidApi.srfidGetReaderCapabilitiesInfo($0.getReaderID(), aReaderCapabilitiesInfo: &capabilitiesInfo, aStatusMessage: &statusMessage)
            
            var info: ReaderInfo? = nil
            if (result == SRFID_RESULT_SUCCESS) {
                var versionInfo: srfidReaderVersionInfo? = srfidReaderVersionInfo()
                
                let versionResult = _rfidApi.srfidGetReaderVersionInfo(
                    $0.getReaderID(),
                    aReaderVersionInfo: &versionInfo,
                    aStatusMessage: &statusMessage
                )
                
                var fwVersion: String? = nil
                if versionResult == SRFID_RESULT_SUCCESS {
                    fwVersion = versionInfo?.getDeviceVersion()
                }
                var transmitPowerLevels: Array<Int> = []
                if let min = capabilitiesInfo?.getMinPower(), let max = capabilitiesInfo?.getMaxPower(), let step = capabilitiesInfo?.getPowerStep() {
                    transmitPowerLevels =
                    Array(stride(
                        from: Int32.Stride(min),
                        to: Int32.Stride(max),
                        by: Int32.Stride(step)
                    ))
                }
                
                info = ReaderInfo(
                    transmitPowerLevels: transmitPowerLevels,
                    firmwareVersion: fwVersion,
                    modelVersion: capabilitiesInfo?.getModel(),
                    scannerName: capabilitiesInfo?.getScannerName(),
                    serialNumber: capabilitiesInfo?.getSerialNumber()
                )
            }
            
            return Reader(name: $0.getReaderName(), id: Int64($0.getReaderID()), info: info)
        }
        
        _callbacks.onAvailableReadersChanged(readers: _availableReaders) {_ in }
    }
    
    private func subscribeToEvents() {
        // Connection
        var mask = SRFID_EVENT_READER_APPEARANCE | SRFID_EVENT_READER_DISAPPEARANCE | SRFID_EVENT_SESSION_ESTABLISHMENT | SRFID_EVENT_SESSION_TERMINATION |
        SRFID_EVENT_MASK_BATTERY | SRFID_EVENT_MASK_TRIGGER |
        SRFID_EVENT_MASK_READ | SRFID_EVENT_MASK_STATUS | SRFID_EVENT_MASK_STATUS_OPERENDSUMMARY |
        SRFID_EVENT_MASK_TEMPERATURE | SRFID_EVENT_MASK_POWER | SRFID_EVENT_MASK_DATABASE |
        SRFID_EVENT_MASK_PROXIMITY | SRFID_EVENT_MASK_TRIGGER | SRFID_EVENT_MASK_MULTI_PROXIMITY
        
        _rfidApi.srfidSubsribe(forEvents: Int32(mask))
    }
    
    private func updateReaders() {
        var  availableReaders: NSMutableArray? = NSMutableArray()
        var  activeReaders: NSMutableArray? = NSMutableArray()
        _rfidApi.srfidGetAvailableReadersList(&availableReaders)
        _rfidApi.srfidGetActiveReadersList(&activeReaders)
        
        _srfidAvailableReaders.removeAll()
        if let array = availableReaders as? [srfidReaderInfo] {
            _srfidAvailableReaders.append(contentsOf: array)
        }
        if let array = activeReaders as? [srfidReaderInfo] {
            _srfidAvailableReaders.append(contentsOf: array)
        }
        emitAvailableReaders()
    }
}
