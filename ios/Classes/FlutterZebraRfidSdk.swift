
class FlutterZebraRfidSdk: NSObject, FlutterZebraRfid, srfidISdkApiDelegate {
    init(callbacks: FlutterZebraRfidCallbacksProtocol) {
        _api = srfidSdkFactory.createRfidSdkApiInstance()
        _callbacks = callbacks
        super.init()
        _api.srfidSetDelegate(self)
    }
    // MARK:
    // srfidSdkApiDelegatejj
    func srfidEventReaderAppeared(_ availableReader: srfidReaderInfo!) {
        _availableReaders.removeAll(where: { $0.getReaderID() == availableReader.getReaderID()})
        _availableReaders.append(availableReader)
        emitAvailableReaders()
    }
    
    func srfidEventReaderDisappeared(_ readerID: Int32) {
        _availableReaders.removeAll(where: { $0.getReaderID() == readerID})
        emitAvailableReaders()
    }
    
    func srfidEventCommunicationSessionEstablished(_ activeReader: srfidReaderInfo!) {
    }
    
    func srfidEventCommunicationSessionTerminated(_ readerID: Int32) {
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
    }
    
    func srfidEventWifiScan(_ readerID: Int32, wlanSCanObject wlanScanObject: srfidWlanScanList!) {
    }
    
    // MARK:
    // FlutterZebraRfid
    func updateAvailableReaders(connectionType: ReaderConnectionType, completion: @escaping (Result<Void, Error>) -> Void) {}
    
    /// Connects to a reader with `readerName` name.
    func connectReader(readerId: Int64, completion: @escaping (Result<Void, Error>) -> Void) {
    }
    
    /// Disconnects a reader with `readerName` name.
    func disconnectReader(completion: @escaping (Result<Void, Error>) -> Void) {}

    func configureReader(config: ReaderConfig, shouldPersist: Bool, completion: @escaping (Result<Void, Error>) -> Void) {}
    
    func triggerDeviceStatus(completion: @escaping (Result<Void, Error>) -> Void) {}
    
    /// Name of reader currently in use
    func currentReaderName() throws -> String? {
        return nil
    }
    
    func currentReader() throws -> Reader? {
        return nil
    }

    // MARK:
    // Private
    let _api: srfidISdkApi
    let _callbacks: FlutterZebraRfidCallbacksProtocol
    
    var _availableReaders: Array<srfidReaderInfo> = []
    
    func emitAvailableReaders() {
        let readers = _availableReaders.map { Reader(name: $0.getReaderName(), id: Int64($0.getReaderID())) }
        _callbacks.onAvailableReadersChanged(readers: readers) { result in
        }
    }
}
