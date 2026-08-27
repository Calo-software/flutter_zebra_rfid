import os
import ZebraRfidSdkFramework

@available(iOS 14.0, *)
class FlutterZebraRfidSdk: NSObject, FlutterZebraRfid, srfidISdkApiDelegate {
    init(callbacks: FlutterZebraRfidCallbacksProtocol) {
        _logger.debug("Starting Flutter RFID SDK")
        _rfidApi = srfidSdkFactory.createRfidSdkApiInstance()
        _callbacks = callbacks
        super.init()

        _rfidApi.srfidSetDelegate(self)
        
        _rfidApi.srfidSetOperationalMode(Int32(SRFID_OPMODE_ALL))
//        _rfidApi.srfidEnableDebugLog()
        
        subscribeToEvents()
    }
    
    // MARK: srfidSdkApiDelegate`
    func srfidEventReaderAppeared(_ availableReader: srfidReaderInfo!) {
        updateReaders()
    }
    
    func srfidEventReaderDisappeared(_ readerID: Int32) {
        updateReaders()
    }
    
    func srfidEventCommunicationSessionEstablished(_ activeReader: srfidReaderInfo!) {
        _srfidCurrentReader = activeReader
        _currentReader = _availableReaders.first(where: { $0.id == activeReader.getReaderID()})
        let asciiResult = _rfidApi.srfidEstablishAsciiConnection(activeReader.getReaderID())

        _callbacks.onReaderConnectionStatusChanged(status: ReaderConnectionStatus.connected) {_ in}
        _rfidApi.srfidRequestBatteryStatus(activeReader.getReaderID())
    }
    
    func srfidEventCommunicationSessionTerminated(_ readerID: Int32) {
        _srfidCurrentReader = nil
        _currentReader = nil
        _callbacks.onReaderConnectionStatusChanged(status: ReaderConnectionStatus.disconnected) {_ in}
    }
    
    func srfidEventReadNotify(_ readerID: Int32, aTagData tagData: srfidTagData!) {
        _logger.debug("Read event (reader \(readerID)): \(tagData)")
        _callbacks.onTagsRead(tags: [RfidTag(
            id: tagData.getTagId(),
            rssi: Int64(tagData.getPeakRSSI())
        )]) {_ in}
    }
    
    func srfidEventStatusNotify(_ readerID: Int32, aEvent event: SRFID_EVENT_STATUS, aNotification notificationData: Any!) {
        _logger.debug("Status event (reader \(readerID)): \(event.rawValue), data: \(notificationData.debugDescription)")
    }
    
    func srfidEventProximityNotify(_ readerID: Int32, aProximityPercent proximityPercent: Int32) {
        _logger.debug("Proximity event (reader \(readerID)): \(proximityPercent)")
    }
    
    func srfidEventMultiProximityNotify(_ readerID: Int32, aTagData tagData: srfidTagData!) {
        _logger.debug("Multi proximity event (reader \(readerID)): \(tagData)")
    }
    func srfidEventTriggerNotify(_ readerID: Int32, aTriggerEvent triggerEvent: SRFID_TRIGGEREVENT) {
        _logger.debug("Trigger event (reader \(readerID)): \(triggerEvent.rawValue)")

        if !_scanningEnabled {
            _logger.debug("Trigger event ignored because scanning is disabled")
            return
        }
        
        switch (triggerEvent) {
        case SRFID_TRIGGEREVENT_PRESSED:
            do {
                try performInventory()
            } catch let error {
                _logger.debug("Perform inventory failed: \(error)")
            }
            break
        case SRFID_TRIGGEREVENT_RELEASED:
            do {
                try stopInventory()
            } catch let error {
                _logger.debug("Stop inventory failed: \(error)")
            }
            break
        default:
            break
        }
    }
    
    func srfidEventBatteryNotity(_ readerID: Int32, aBatteryEvent batteryEvent: srfidBatteryEvent!) {
        _logger.debug("Battery event (reader \(readerID)): \(batteryEvent)")
        _callbacks.onBatteryDataReceived(
            batteryData: BatteryData(
                level: Int64(batteryEvent.getPowerLevel()),
                isCharging: batteryEvent.getIsCharging(),
                cause: batteryEvent.getCause(),
                source: BatteryDataSource.readerEvent,
                isPercentageEstimated: false
            ), completion: {_ in }
        )
    }
    
    func srfidEventWifiScan(_ readerID: Int32, wlanSCanObject wlanScanObject: srfidWlanScanList!) {
        _logger.debug("Wifi scan event (reader \(readerID)): \(wlanScanObject)")
    }

    func srfidEventIOTSatusNotity(_ readerID: Int32, aIOTStatusEvent iotStatusEvent: srfidIOTStatusEvent!) {
        _logger.debug("IoT status event (reader \(readerID)): \(iotStatusEvent)")
    }

    func srfidEventConnectedInterfaceNotity(_ readerID: Int32, aConnectedInterfaceEvent connectedInterfaceEvent: sfidConnectedInterfaceEvent!) {
        _logger.debug("Connected interface event (reader \(readerID)): \(connectedInterfaceEvent)")
    }
    
    // MARK: FlutterZebraRfid protocol
    func updateAvailableReaders(connectionType: ReaderConnectionType, completion: @escaping (Result<Void, Error>) -> Void) {
        updateReaders()
        completion(.success(()))
    }

    func startBluetoothScan(completion: @escaping (Result<Void, Error>) -> Void) {
        completion(.failure(FlutterRfidError(
            code: "unsupported",
            message: "Bluetooth pairing scan is not supported on iOS in this plugin",
            details: nil
        )))
    }

    func stopBluetoothScan(completion: @escaping (Result<Void, Error>) -> Void) {
        completion(.failure(FlutterRfidError(
            code: "unsupported",
            message: "Bluetooth pairing scan is not supported on iOS in this plugin",
            details: nil
        )))
    }

    func getBondedDevices(completion: @escaping (Result<[BluetoothDevice], Error>) -> Void) {
        completion(.success([]))
    }

    func pairBluetoothDevice(address: String, completion: @escaping (Result<Void, Error>) -> Void) {
        completion(.failure(FlutterRfidError(
            code: "unsupported",
            message: "Bluetooth pairing is not supported on iOS in this plugin",
            details: address
        )))
    }

    /// Connects to a reader with `readerName` name.
    func connectReader(readerId: Int64, completion: @escaping (Result<Void, Error>) -> Void) {
        _logger.info("Connecting to reader: \(readerId)")
        _callbacks.onReaderConnectionStatusChanged(status: ReaderConnectionStatus.connecting) {_ in}
        let result = _rfidApi.srfidEstablishCommunicationSession(Int32(readerId))
        
        if (result != SRFID_RESULT_SUCCESS) {
            var log: NSString? = nil
            _rfidApi.srfidRetrieveDebugLog(&log)
            _logger.error("Failed to connect to reader: \(readerId) - \(Int(result.rawValue)): \(log)")
            _callbacks.onReaderConnectionStatusChanged(status: ReaderConnectionStatus.disconnected) {_ in}
            completion(.failure(FlutterRfidError(code: "0", message: "Failed to connect to reader", details: nil)))
            return
        }
        
        completion(.success(()))
    }
    
    /// Disconnects a reader with `readerName` name.
    func disconnectReader(completion: @escaping (Result<Void, Error>) -> Void) {
        _logger.info("Disconnecting reader \(self._srfidCurrentReader?.getReaderName() ?? "unknown")")
        
        let exception = FlutterRfidError(code: "0", message: "Failed to disconnect reader", details: nil)
        
        if let id = _srfidCurrentReader?.getReaderID() {
            let result = _rfidApi.srfidTerminateCommunicationSession(id)
            if (result != SRFID_RESULT_SUCCESS) {
                completion(.failure(exception))
                return
            }
        } else {
            _logger.error("No active reader to disconnect")
            _callbacks.onReaderConnectionStatusChanged(status: ReaderConnectionStatus.disconnected) {_ in}
            completion(.failure(exception))
            return
        }
        completion(.success(()))

    }

    func configureReader(config: ReaderConfig, shouldPersist: Bool, completion: @escaping (Result<Void, Error>) -> Void) {
        
        guard let reader = _currentReader else {
            _logger.error("No active reader to configure")
            completion(.failure(FlutterRfidError(
                code: "1",
                message: "No active reader to configure",
                details: nil
            )))
            return
        }
        
        let readerId = Int32(reader.id)
        
        // ANTENNA CONFIG
        var antennaConfig: srfidAntennaConfiguration? = srfidAntennaConfiguration()
        var statusMessage: NSString? = nil
        _rfidApi.srfidGetAntennaConfiguration(
            readerId,
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
                if let power = info.transmitPowerLevels.last {
                    antennaConfig?.setPower(power as! Int16)
                }
            }
        }
        let antennaResult = _rfidApi.srfidSetAntennaConfiguration(
            readerId,
            aAntennaConfiguration: antennaConfig,
            aStatusMessage: &statusMessage
        )
        if (antennaResult != SRFID_RESULT_SUCCESS) {
            _logger.error("Cannot configure antenna: \(statusMessage)")
        }
        
        // SINGULATION CONFIG
        var singulationConfig: srfidSingulationConfig? = srfidSingulationConfig()
        _rfidApi.srfidGetSingulationConfiguration(
            readerId,
            aSingulationConfig: &singulationConfig,
            aStatusMessage: &statusMessage
        )
        
        singulationConfig?.setSlFlag(SRFID_SLFLAG_ALL)
        if let session = config.inventorySession {
            switch session {
            case .s0: singulationConfig?.setSession(SRFID_SESSION_S0)
            case .s1: singulationConfig?.setSession(SRFID_SESSION_S1)
            case .s2: singulationConfig?.setSession(SRFID_SESSION_S2)
            case .s3: singulationConfig?.setSession(SRFID_SESSION_S3)
            }
        }
        if let population = config.estimatedTagPopulation {
            guard population > 0 && population <= Int64(Int32.max) else {
                completion(.failure(FlutterRfidError(
                    code: "invalid_tag_population",
                    message: "Estimated tag population must be between 1 and \(Int32.max)",
                    details: nil
                )))
                return
            }
            singulationConfig?.setTagPopulation(Int32(population))
        }
        singulationConfig?.setInventoryState(SRFID_INVENTORYSTATE_A)
        let singulationResult = _rfidApi.srfidSetSingulationConfiguration(readerId, aSingulationConfig: singulationConfig, aStatusMessage: &statusMessage)
        if (singulationResult != SRFID_RESULT_SUCCESS) {
            completion(.failure(FlutterRfidError(
                code: "singulation_rejected",
                message: "Cannot configure singulation",
                details: statusMessage
            )))
            return
        }
        if config.inventorySession != nil || config.estimatedTagPopulation != nil {
            var applied: srfidSingulationConfig? = srfidSingulationConfig()
            let readResult = _rfidApi.srfidGetSingulationConfiguration(
                readerId,
                aSingulationConfig: &applied,
                aStatusMessage: &statusMessage
            )
            let sessionMatches = config.inventorySession == nil || applied?.getSession() == singulationConfig?.getSession()
            let populationMatches = config.estimatedTagPopulation == nil || Int64(applied?.getTagPopulation() ?? -1) == config.estimatedTagPopulation
            if readResult != SRFID_RESULT_SUCCESS || !sessionMatches || !populationMatches {
                completion(.failure(FlutterRfidError(
                    code: "singulation_verification_failed",
                    message: "RFID Reader did not apply the requested inventory settings",
                    details: statusMessage
                )))
                return
            }
        }

        if let enabled = config.uniqueTagReporting {
            let requested = srfidUniqueTagsReport()
            requested.setUniqueTagsReportEnabled(enabled)
            let setResult = _rfidApi.srfidSetUniqueTagReportConfiguration(
                readerId,
                aUtrConfiguration: requested,
                aStatusMessage: &statusMessage
            )
            var applied: srfidUniqueTagsReport? = srfidUniqueTagsReport()
            let readResult = _rfidApi.srfidGetUniqueTagReportConfiguration(
                readerId,
                aUtrConfiguration: &applied,
                aStatusMessage: &statusMessage
            )
            if setResult != SRFID_RESULT_SUCCESS || readResult != SRFID_RESULT_SUCCESS || applied?.getEnabled() != enabled {
                completion(.failure(FlutterRfidError(
                    code: "unique_tag_reporting_verification_failed",
                    message: "RFID Reader did not apply unique tag reporting",
                    details: statusMessage
                )))
                return
            }
        }
        
        // TRIGGER CONFIGURATION
        var startTriggerConfig: srfidStartTriggerConfig? = srfidStartTriggerConfig()
        
        let getStartTriggerResult = _rfidApi.srfidGetStartTriggerConfiguration(
            readerId,
            aStartTriggeConfig: &startTriggerConfig,
            aStatusMessage: &statusMessage
        )
        if (getStartTriggerResult != SRFID_RESULT_SUCCESS) {
            _logger.error("Cannot get start trigger config: \(statusMessage)")
        }
        startTriggerConfig?.setTriggerType(SRFID_TRIGGERTYPE_PRESS)
        startTriggerConfig?.setStartDelay(0)
        startTriggerConfig?.setStartOnHandheldTrigger(true)
        
        let setStartTriggerResult = _rfidApi.srfidSetStartTriggerConfiguration(
            readerId,
            aStartTriggeConfig: startTriggerConfig,
            aStatusMessage: &statusMessage
        )
        if (setStartTriggerResult != SRFID_RESULT_SUCCESS) {
            _logger.error("Cannot set start trigger config: \(statusMessage)")
        }
        
        var stopTriggerConfig: srfidStopTriggerConfig? = srfidStopTriggerConfig()
        
        let getStopTriggerResult = _rfidApi.srfidGetStopTriggerConfiguration(
            readerId,
            aStopTriggeConfig: &stopTriggerConfig,
            aStatusMessage: &statusMessage
        )
        if (getStopTriggerResult != SRFID_RESULT_SUCCESS) {
            _logger.error("Cannot get stop trigger config: \(statusMessage)")
        }
        stopTriggerConfig?.setTriggerType(SRFID_TRIGGERTYPE_RELEASE)
        stopTriggerConfig?.setStopOnHandheldTrigger(true)
        stopTriggerConfig?.setStopOnTimeout(true)
        stopTriggerConfig?.setStopTimout(25*1000)
        stopTriggerConfig?.setStopOnTagCount(false)
        stopTriggerConfig?.setStopOnInventoryCount(false)
        stopTriggerConfig?.setStopOnAccessCount(false)

        
        let setStopTriggerResult = _rfidApi.srfidSetStopTriggerConfiguration(
            readerId,
            aStopTriggeConfig: stopTriggerConfig,
            aStatusMessage: &statusMessage
        )
        if (setStopTriggerResult != SRFID_RESULT_SUCCESS) {
            _logger.error("Cannot set stop trigger config: \(statusMessage)")
        }

        // TAG REPORT CONFIG
        var tagReportConfig: srfidTagReportConfig? = srfidTagReportConfig()
        let getTagReportConfig = _rfidApi.srfidGetTagReportConfiguration(
            readerId,
            aTagReportConfig: &tagReportConfig,
            aStatusMessage: &statusMessage
        )
        
        if (getTagReportConfig != SRFID_RESULT_SUCCESS) {
            _logger.error("Cannot get tag report config: \(statusMessage)")
        }
        tagReportConfig?.setIncPC(true)
        tagReportConfig?.setIncRSSI(true)
        tagReportConfig?.setIncTagSeenCount(true)
        tagReportConfig?.setIncLastSeenTime(true)
        tagReportConfig?.setIncFirstSeenTime(true)
        
        let setTagReportConfig = _rfidApi.srfidSetTagReportConfiguration(
            readerId,
            aTagReportConfig: tagReportConfig,
            aStatusMessage: &statusMessage
        )
        if (setTagReportConfig != SRFID_RESULT_SUCCESS) {
            _logger.error("Cannot set tag report config: \(statusMessage)")
        }
        
        // PRE-FILTERS - clear all
        let prefiltersConfigResult = _rfidApi.srfidSetPreFilters(
            readerId,
            aPreFilters: [],
            aStatusMessage: &statusMessage
        )
        if (prefiltersConfigResult != SRFID_RESULT_SUCCESS) {
            _logger.error("Cannot clear pre-filters: \(statusMessage)")
        }
        
        // BEEPER CONFIG
        if let volume = config.beeperVolume {
            var beeperConfig: SRFID_BEEPERCONFIG
            switch (volume) {
            case .high: beeperConfig = SRFID_BEEPERCONFIG_HIGH
                break
            case .medium: beeperConfig = SRFID_BEEPERCONFIG_MEDIUM
                break
            case .low: beeperConfig = SRFID_BEEPERCONFIG_LOW
                break
            case .quiet: beeperConfig = SRFID_BEEPERCONFIG_QUIET
                break
            }
            let result = _rfidApi.srfidSetBeeperConfig(
                readerId,
                aBeeperConfig: beeperConfig, 
                aStatusMessage: &statusMessage
            )
            if (result != SRFID_RESULT_SUCCESS) {
                _logger.error("Cannot set beeper volume: \(statusMessage)")
            }
        }
        
        if let enabled = config.enableDynamicPower {
            let dpoConfig = srfidDynamicPowerConfig()
            dpoConfig.setDynamicPowerOptimizationEnabled(enabled)
            let result = _rfidApi.srfidSetDpoConfiguration(
                readerId,
                aDpoConfiguration: dpoConfig,
                aStatusMessage: &statusMessage
            )
            if (result != SRFID_RESULT_SUCCESS) {
                _logger.error("Cannot set dynamic power config: \(statusMessage)")
            }
        }
        
        if let enabled = config.enableLedBlink {
            // TODO: doesn't seem to be supported
        }
        
        if let batchMode = config.batchMode {
            var batchModeConfig: SRFID_BATCHMODECONFIG
            switch (batchMode) {
            case .auto: batchModeConfig = SRFID_BATCHMODECONFIG_AUTO
                break
            case .enabled: batchModeConfig = SRFID_BATCHMODECONFIG_ENABLE
                break
            case .disabled: batchModeConfig = SRFID_BATCHMODECONFIG_DISABLE
                break
            }
            let result = _rfidApi.srfidSetBatchModeConfig(
                readerId,
                aBatchModeConfig: batchModeConfig,
                aStatusMessage: &statusMessage
            )
            if (result != SRFID_RESULT_SUCCESS) {
                _logger.error("Cannot set batch mode: \(statusMessage)")
            }

        }
        
        if let scanBatchMode = config.scanBatchMode {
            // TODO: doesn't seem to be supported
        }
                
        if (shouldPersist) {
            let result = _rfidApi.srfidSaveReaderConfiguration(
                readerId,
                aSaveCustomDefaults: true,
                aStatusMessage: &statusMessage
            )
            if (result != SRFID_RESULT_SUCCESS) {
                _logger.error("Cannot set config persistence: \(statusMessage)")
            }

        }
        
        completion(.success(()))
    }
    
    func triggerDeviceStatus(completion: @escaping (Result<Void, Error>) -> Void) {
        let exception = FlutterRfidError(
            code: "0",
            message: "No connected reader",
            details: nil
        )
        guard let readerId = _srfidCurrentReader?.getReaderID() else {
            completion(.failure(exception))
            return
        }
        let batteryResult = _rfidApi.srfidRequestBatteryStatus(Int32(readerId))
        let statusResult = _rfidApi.srfidRequestDeviceStatus(Int32(readerId),
                                          aBattery: false,
                                          aTemperature: true,
                                          aPower: true
        )
        if (batteryResult != SRFID_RESULT_SUCCESS && statusResult != SRFID_RESULT_SUCCESS) {
            completion(.failure(exception))
            return
        }
        completion(.success(()))
    }
    
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

    func availableReadersSnapshot() -> [Reader] {
        return _availableReaders
    }

    func startLocating(tags: [RfidTag], disableBeep: Bool?, completion: @escaping (Result<Void, Error>) -> Void) {
        _isLocating = true
        completion(.success(()))
    }

    func stopLocating(completion: @escaping (Result<Void, Error>) -> Void) {
        _isLocating = false
        completion(.success(()))
    }

    func resetLocateState(completion: @escaping (Result<Void, Error>) -> Void) {
        _isLocating = false
        completion(.success(()))
    }

    func readerConfig(completion: @escaping (Result<ReaderConfig, Error>) -> Void) {
        guard let readerId = _srfidCurrentReader?.getReaderID() else {
            completion(.failure(FlutterRfidError(
                code: "0",
                message: "No connected reader",
                details: nil
            )))
            return
        }
        var statusMessage: NSString? = nil
        var singulation: srfidSingulationConfig? = srfidSingulationConfig()
        let singulationResult = _rfidApi.srfidGetSingulationConfiguration(
            readerId,
            aSingulationConfig: &singulation,
            aStatusMessage: &statusMessage
        )
        var uniqueTags: srfidUniqueTagsReport? = srfidUniqueTagsReport()
        let uniqueResult = _rfidApi.srfidGetUniqueTagReportConfiguration(
            readerId,
            aUtrConfiguration: &uniqueTags,
            aStatusMessage: &statusMessage
        )
        var session: ReaderInventorySession? = nil
        switch singulation?.getSession() {
        case SRFID_SESSION_S0: session = .s0
        case SRFID_SESSION_S1: session = .s1
        case SRFID_SESSION_S2: session = .s2
        case SRFID_SESSION_S3: session = .s3
        default: session = nil
        }
        completion(.success(ReaderConfig(
            inventorySession: singulationResult == SRFID_RESULT_SUCCESS ? session : nil,
            estimatedTagPopulation: singulationResult == SRFID_RESULT_SUCCESS ? Int64(singulation?.getTagPopulation() ?? 0) : nil,
            uniqueTagReporting: uniqueResult == SRFID_RESULT_SUCCESS ? uniqueTags?.getEnabled() : nil
        )))
    }

    func supportedReaderRegions(completion: @escaping (Result<[ReaderRegion], Error>) -> Void) {
        completion(.failure(FlutterRfidError(
            code: "unsupported",
            message: "Reader region configuration is not supported on iOS in this plugin",
            details: nil
        )))
    }

    func setReaderRegion(regionCode: String, completion: @escaping (Result<Void, Error>) -> Void) {
        completion(.failure(FlutterRfidError(
            code: "unsupported",
            message: "Reader region configuration is not supported on iOS in this plugin",
            details: regionCode
        )))
    }

    func diagnostics(completion: @escaping (Result<Diagnostics, Error>) -> Void) {
        let connectionState: ReaderConnectionStatus = _srfidCurrentReader == nil ? .disconnected : .connected
        completion(.success(Diagnostics(
            connectionState: connectionState,
            connectAttempts: 0,
            lastErrorCode: nil,
            lastErrorMessage: nil,
            lastConnectStartMs: nil,
            lastConnectDurationMs: nil,
            isLocating: _isLocating,
            scanningEnabled: _scanningEnabled,
            scanningEnabledLastToggleMs: _scanningEnabledLastToggleMs,
            inventoryActive: nil,
            lastInventoryStartMs: nil,
            lastInventoryStopMs: nil,
            pendingPurgeActive: nil,
            lastInventoryStopReason: nil,
            lastInventoryStartReason: nil,
      readerPowerState: nil,
      readerPowerStateError: nil
        )))
    }

    func setScanningEnabled(enabled: Bool, completion: @escaping (Result<Void, Error>) -> Void) {
        _scanningEnabled = enabled
        _scanningEnabledLastToggleMs = Int64(Date().timeIntervalSince1970 * 1000)
        if !enabled {
            do {
                try stopInventory()
            } catch {
                _logger.debug("Stop inventory while disabling scanning failed: \(error)")
            }
        }
        completion(.success(()))
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
    private var _isLocating: Bool = false
    private var _scanningEnabled: Bool = true
    private var _scanningEnabledLastToggleMs: Int64? = nil
    
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
            
            let hardwareIdentity = $0.getReaderName() ?? String($0.getReaderID())
            return Reader(
                name: $0.getReaderName(),
                id: Int64($0.getReaderID()),
                info: info,
                hardwareIdentity: hardwareIdentity
            )
        }
        
        _callbacks.onAvailableReadersChanged(readers: _availableReaders) {_ in }
    }
    
    private func subscribeToEvents() {
        // Connection
        let mask = SRFID_EVENT_READER_APPEARANCE | SRFID_EVENT_READER_DISAPPEARANCE | SRFID_EVENT_SESSION_ESTABLISHMENT | SRFID_EVENT_SESSION_TERMINATION
        
        _rfidApi.srfidSubsribe(forEvents: Int32(mask))
        _rfidApi.srfidSubsribe(forEvents: Int32(SRFID_EVENT_MASK_READ | SRFID_EVENT_MASK_STATUS | SRFID_EVENT_MASK_STATUS_OPERENDSUMMARY))
        _rfidApi.srfidSubsribe(forEvents: Int32(SRFID_EVENT_MASK_TEMPERATURE | SRFID_EVENT_MASK_POWER | SRFID_EVENT_MASK_DATABASE))
        _rfidApi.srfidSubsribe(forEvents: Int32(SRFID_EVENT_MASK_PROXIMITY))
        _rfidApi.srfidSubsribe(forEvents: Int32(SRFID_EVENT_MASK_TRIGGER))
        _rfidApi.srfidSubsribe(forEvents: Int32(SRFID_EVENT_MASK_BATTERY))
        _rfidApi.srfidSubsribe(forEvents: Int32(SRFID_EVENT_MASK_MULTI_PROXIMITY))
        
        _rfidApi.srfidEnableAvailableReadersDetection(true)
        _rfidApi.srfidSubsribe(forEvents: Int32(SRFID_EVENT_MASK_WLAN_SCAN))
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
        _logger.debug("Found \(self._srfidAvailableReaders.count) readers")
        emitAvailableReaders()
    }
    
    private func performInventory() throws {
        guard let readerId = _srfidCurrentReader?.getReaderID() else {
            throw FlutterRfidError(code: "0", message: "No reader connected", details: nil)
        }
        
        var statusMessage: NSString? = nil
        let reportConfig = srfidReportConfig()
        reportConfig.setIncPC(true)
        reportConfig.setIncRSSI(true)
        reportConfig.setIncLastSeenTime(true)
        reportConfig.setIncFirstSeenTime(true)
        
        let accessConfig = srfidAccessConfig()
        accessConfig.setDoSelect(false)
        var antennaConfig: srfidAntennaConfiguration? = srfidAntennaConfiguration()
        _rfidApi.srfidGetAntennaConfiguration(
            readerId,
            aAntennaConfiguration: &antennaConfig,
            aStatusMessage: &statusMessage
        )
        
        if let power = antennaConfig?.getPower() {
            accessConfig.setPower(power)
        }
        
        let result = _rfidApi.srfidStartInventory(
            Int32(readerId),
            aMemoryBank: SRFID_MEMORYBANK_ALL,
            aReportConfig: reportConfig,
            aAccessConfig: accessConfig,
            aStatusMessage: &statusMessage
        )
        
        if (result != SRFID_RESULT_SUCCESS) {
            _logger.error("Failed to start inventory: \(statusMessage)")
            throw FlutterRfidError(
                code: "0",
                message: "Failed to start inventory: \(String(describing: statusMessage))",
                details: nil
            )
        }
    }
    
    private func stopInventory() throws {
        guard let readerId = _srfidCurrentReader?.getReaderID() else {
            throw FlutterRfidError(code: "0", message: "No reader connected", details: nil)
        }
        
        var statusMessage: NSString? = nil

        let result = _rfidApi.srfidStopInventory(
            Int32(readerId),
            aStatusMessage: &statusMessage
        )
        
        if (result != SRFID_RESULT_SUCCESS) {
            _logger.error("Failed to stop inventory: \(statusMessage)")
            throw FlutterRfidError(
                code: "0",
                message: "Failed to stop inventory: \(String(describing: statusMessage))",
                details: nil
            )
        }
    }
 }
