package nz.calo.flutter_zebra_rfid.rfid

import BatteryData
import FlutterZebraRfidCallbacks
import Reader
import ReaderBeeperVolume
import ReaderConfig
import ReaderConfigBatchMode
import ReaderConnectionStatus
import ReaderConnectionType
import ReaderInfo
import RfidTag
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.ArrayMap
import android.util.Log
import com.zebra.rfid.api3.ACCESS_OPERATION_STATUS
import com.zebra.rfid.api3.Antennas
import com.zebra.rfid.api3.BATCH_MODE
import com.zebra.rfid.api3.BEEPER_VOLUME
import com.zebra.rfid.api3.DYNAMIC_POWER_OPTIMIZATION
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE
import com.zebra.rfid.api3.INVENTORY_STATE
import com.zebra.rfid.api3.InvalidUsageException
import com.zebra.rfid.api3.MEMORY_BANK
import com.zebra.rfid.api3.OperationFailureException
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.ReaderDevice
import com.zebra.rfid.api3.Readers
import com.zebra.rfid.api3.Readers.RFIDReaderEventHandler
import com.zebra.rfid.api3.RfidEventsListener
import com.zebra.rfid.api3.RfidReadEvents
import com.zebra.rfid.api3.RfidStatusEvents
import com.zebra.rfid.api3.SCAN_BATCH_MODE
import com.zebra.rfid.api3.SESSION
import com.zebra.rfid.api3.SL_FLAG
import com.zebra.rfid.api3.START_TRIGGER_TYPE
import com.zebra.rfid.api3.STATUS_EVENT_TYPE
import com.zebra.rfid.api3.STOP_TRIGGER_TYPE
import com.zebra.rfid.api3.TagAccess
import com.zebra.rfid.api3.TriggerInfo


fun readerConnectionTypeToTransport(type: ReaderConnectionType): ENUM_TRANSPORT {
    return when (type) {
        ReaderConnectionType.BLUETOOTH -> ENUM_TRANSPORT.BLUETOOTH
        ReaderConnectionType.USB -> ENUM_TRANSPORT.SERVICE_USB
        ReaderConnectionType.ALL -> ENUM_TRANSPORT.ALL
    }
}

class RFIDReaderInterface(
    private var callbacks: FlutterZebraRfidCallbacks,
    private var applicationContext: Context
) : RfidEventsListener, RFIDReaderEventHandler {

    private val TAG: String = "FlutterZebraRfidPlugin"

    private var readers: Readers? = null
    private var availableRFIDReaderList: ArrayList<ReaderDevice>? = null
    private var readerDevice: ReaderDevice? = null
    private var reader: RFIDReader? = null
    private var readerInfo: ReaderInfo? = null
    private var currentConnectionType: ReaderConnectionType? = null
    private var isLocating: Boolean = false

    init {
        Log.d(TAG, "Initializing RFID SDK...")
        Readers.attach(this)
    }

    fun getAvailableReaderList(
        connectionType: ReaderConnectionType
    ) {

        if (readers == null) {
            readers = Readers(applicationContext, readerConnectionTypeToTransport(connectionType))
        }

        if (connectionType != currentConnectionType) {
            readers!!.setTransport(readerConnectionTypeToTransport(connectionType))
        }

        currentConnectionType = connectionType
        availableRFIDReaderList = readers!!.GetAvailableRFIDReaderList()
        Log.d(TAG, "Available readers: $availableRFIDReaderList")
        val readers = availableRFIDReaderList!!.mapIndexed { index, reader ->
            Reader(reader.name, index.toLong())
        }
        callbacks.onAvailableReadersChanged(readers) {}
    }

    fun connectReader(readerId: Long): ReaderInfo? {
        try {
            if (availableRFIDReaderList != null) {
                if (availableRFIDReaderList!!.size <= readerId) throw Error("Reader not available to connect")

                readerDevice = availableRFIDReaderList!![readerId.toInt()]
                reader = readerDevice!!.rfidReader
                if (!reader!!.isConnected) {
                    callbacks.onReaderConnectionStatusChanged(ReaderConnectionStatus.CONNECTING) {}
                    Log.d(TAG, "RFID Reader Connecting...")
                    reader!!.connect()
                    setupReader()
                    Log.d(TAG, "RFID Reader Connected!")
                    val capabilities = reader!!.ReaderCapabilities
                    val levels = capabilities.transmitPowerLevelValues
                    readerInfo = ReaderInfo(
                        levels.asList(),
                        capabilities.firwareVersion,
                        capabilities.modelName,
                        capabilities.scannerName,
                        capabilities.serialNumber,
                    )
                    callbacks.onReaderConnectionStatusChanged(ReaderConnectionStatus.CONNECTED) {}

                    triggerDeviceStatus()
                } else {
                    callbacks.onReaderConnectionStatusChanged(ReaderConnectionStatus.CONNECTED) {}
                }
                return null
            }
        } catch (e: Exception) {
            Log.d(TAG, "RFID Reader connection error: ${e.toString()}")
            callbacks.onReaderConnectionStatusChanged(ReaderConnectionStatus.ERROR) {}
        }
        return null
    }

    fun configureReader(config: ReaderConfig, shouldPersist: Boolean) {
        if (reader == null) {
            Log.d(TAG, "No connected to any Reader!")
            throw Error("Not connected to any Reader")
        }

        // Transmit power
        var powerIndex = config.transmitPowerIndex?.toInt()
        val maxIndex = reader!!.ReaderCapabilities.transmitPowerLevelValues.size - 1
        // set to max by default
        if (powerIndex == null || powerIndex > maxIndex) powerIndex = maxIndex
        val antennaRfConfig = reader!!.Config.Antennas.getAntennaRfConfig(1)
        antennaRfConfig.setrfModeTableIndex(0)
        antennaRfConfig.tari = 0
        antennaRfConfig.transmitPowerIndex = powerIndex
        reader!!.Config.Antennas.setAntennaRfConfig(1, antennaRfConfig)

        // Beeper volume
        val beeperVolume = config.beeperVolume
        if (beeperVolume != null) {
            when (beeperVolume) {
                ReaderBeeperVolume.QUIET -> reader!!.Config.beeperVolume = BEEPER_VOLUME.QUIET_BEEP
                ReaderBeeperVolume.LOW -> reader!!.Config.beeperVolume = BEEPER_VOLUME.LOW_BEEP
                ReaderBeeperVolume.MEDIUM -> reader!!.Config.beeperVolume =
                    BEEPER_VOLUME.MEDIUM_BEEP

                ReaderBeeperVolume.HIGH -> reader!!.Config.beeperVolume = BEEPER_VOLUME.HIGH_BEEP
            }
        }

        // Dynamic power
        val enableDynamicPower = config.enableDynamicPower
        if (enableDynamicPower != null) {
            reader!!.Config.dpoState =
                if (enableDynamicPower) DYNAMIC_POWER_OPTIMIZATION.ENABLE else DYNAMIC_POWER_OPTIMIZATION.DISABLE
        }

        // LED blink
        val enableLedBlink = config.enableLedBlink
        if (enableLedBlink != null) {
            reader!!.Config.setLedBlinkEnable(enableLedBlink)
        }

        val batchMode = config.batchMode
        if (batchMode != null) {
            val mode = when (batchMode) {
                ReaderConfigBatchMode.AUTO -> BATCH_MODE.AUTO
                ReaderConfigBatchMode.ENABLED -> BATCH_MODE.ENABLE
                ReaderConfigBatchMode.DISABLED -> BATCH_MODE.DISABLE
            }
            reader!!.Config.setBatchMode(mode)
        }

        val scanBatchMode = config.scanBatchMode
        if (scanBatchMode != null) {
            val mode = when (scanBatchMode) {
                ReaderConfigBatchMode.AUTO -> SCAN_BATCH_MODE.AUTO
                ReaderConfigBatchMode.ENABLED -> SCAN_BATCH_MODE.ENABLE
                ReaderConfigBatchMode.DISABLED -> SCAN_BATCH_MODE.DISABLE
            }
            reader!!.Config.setScanBatchMode(mode)
        }

        if (shouldPersist) reader!!.Config.saveConfig()

    }

    fun disconnectCurrentReader() {
        callbacks.onReaderConnectionStatusChanged(ReaderConnectionStatus.DISCONNECTING) {}

        if (reader == null) {
            Log.d(TAG, "No connected RFID Reader!")
            return
        }

        if (reader!!.isConnected) {
            reader!!.disconnect()
        }
        callbacks.onReaderConnectionStatusChanged(ReaderConnectionStatus.DISCONNECTED) {}
    }

    fun currentReader(): Reader? {
        if (readerDevice != null) {
            return Reader(
                readerDevice!!.name,
                availableRFIDReaderList!!.indexOf(readerDevice!!).toLong(),
                readerInfo
            )
        }
        return null
    }

    fun triggerDeviceStatus() {
        if (readerDevice != null) {
            return reader!!.Config.getDeviceStatus(true, true, true)
        }
    }

    fun startLocating(tags: List<RfidTag>) {
        if (isLocating) return
        Log.d(TAG, "Start locating tags: $tags")

        isLocating = true
        val multiTagLocateTagMap = ArrayMap<String, String>()
        multiTagLocateTagMap.clear();
        tags.forEach {
            // NOTE: which calibration rssi to use?
            // As TAGS RSSI value varies from a reference distance based on tag types
            // and the environment this value helps to calibrate for accurate distance measurements
            multiTagLocateTagMap[it.id] = "-50"
        }
        reader!!.Actions.MultiTagLocate.purgeItemList()
        reader!!.Actions.MultiTagLocate.importItemList(multiTagLocateTagMap)
        reader!!.Actions.MultiTagLocate.perform()
    }

    fun stopLocating() {
        Log.d(TAG, "Stop locating tags")

        reader!!.Actions.MultiTagLocate.stop()
        reader!!.Actions.MultiTagLocate.purgeItemList()
        isLocating = false
    }

    private fun setupReader() {
        if (reader!!.isConnected) {
            Log.d(TAG, "Configuring...")
            val triggerInfo = TriggerInfo()
            triggerInfo.StartTrigger.triggerType = START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
            triggerInfo.StopTrigger.triggerType = STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE
            try {
                // receive events from reader
                reader!!.Events.addEventsListener(this)
                // HH event
                reader!!.Events.setHandheldEvent(true)
                // tag event with tag data
                reader!!.Events.setTagReadEvent(true)
                // application will collect tag using getReadTags API
                reader!!.Events.setAttachTagDataWithReadEvent(false)

                reader!!.Events.setBatteryEvent(true)
                reader!!.Events.setInventoryStartEvent(true)
                reader!!.Events.setInventoryStopEvent(true)
                reader!!.Events.setReaderDisconnectEvent(true)
                reader!!.Events.setAntennaEvent(true)
                reader!!.Events.setTemperatureAlarmEvent(true)
                reader!!.Events.setPowerEvent(true)

                // set start and stop triggers
                reader!!.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
                reader!!.Config.startTrigger = triggerInfo.StartTrigger
                reader!!.Config.stopTrigger = triggerInfo.StopTrigger


                // set antenna configurations
                val config: Antennas.AntennaRfConfig =
                    reader!!.Config.Antennas.getAntennaRfConfig(1)

                config.setrfModeTableIndex(0)
                config.setTari(0)
                reader!!.Config.Antennas.setAntennaRfConfig(1, config)

                val s1_singulationControl: Antennas.SingulationControl =
                    reader!!.Config.Antennas.getSingulationControl(1)
                s1_singulationControl.setSession(SESSION.SESSION_S0)
                s1_singulationControl.Action.setInventoryState(INVENTORY_STATE.INVENTORY_STATE_A)
                s1_singulationControl.Action.setSLFlag(SL_FLAG.SL_ALL)
                reader!!.Config.Antennas.setSingulationControl(1, s1_singulationControl)

                // delete any prefilters
                reader!!.Actions.PreFilters.deleteAll()

            } catch (e: Throwable) {
                Log.d(TAG, "Error configuring reader: $e")
                throw Error("Error configuring reader")
            }
        }
    }

    fun getReaderConfig() {
        if (reader == null) {
            Log.d(TAG, "Not connected to any Reader!")
            throw Error("Not connected to any Reader")
        }

        try {
            val antennaRfConfig = reader!!.Config.Antennas.getAntennaRfConfig(1)
            val transmitPowerIndex = antennaRfConfig.transmitPowerIndex
            val receiveSensitivityIndex = antennaRfConfig.receiveSensitivityIndex
            val rfModeIndex = antennaRfConfig // Corrected property name
            val tari = antennaRfConfig.tari

            Log.d(TAG, "Reader Config:")
            Log.d(TAG, "Transmit Power Index: $transmitPowerIndex")
            Log.d(TAG, "Receive Sensitivity Index: $receiveSensitivityIndex")
            Log.d(TAG, "RF Mode Table Index: $rfModeIndex")
            Log.d(TAG, "Tari: $tari")
        } catch (e: Exception) {
            Log.d(TAG, "Error getting reader config: $e")
            throw Error("Error getting reader config")
        }
    }

    // Status Event Notification
    override fun eventStatusNotify(rfidStatusEvents: RfidStatusEvents) {
        Log.d(TAG, "Status Notification: " + rfidStatusEvents.StatusEventData.statusEventType)
        when (rfidStatusEvents.StatusEventData.statusEventType) {
            STATUS_EVENT_TYPE.BATTERY_EVENT -> {
                val data = rfidStatusEvents.StatusEventData.BatteryData
                val batteryData = BatteryData(data.level.toLong(), data.charging, data.cause)
                Log.d(
                    TAG,
                    "Battery data - level: ${batteryData.level}, isCharging: ${batteryData.isCharging}, cause: ${batteryData.cause}"
                )
                Handler(Looper.getMainLooper()).post {
                    callbacks.onBatteryDataReceived(batteryData) {}
                }
            }

            STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
                Log.d(TAG, "Handheld trigger event detected")
                try {
                    if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.handheldEvent === HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                        Log.d(TAG, "Handheld trigger pressed")
                        performInventory();
                        // Read all memory banks
                        val memoryBanksToRead = arrayOf(
                            MEMORY_BANK.MEMORY_BANK_EPC,
                            MEMORY_BANK.MEMORY_BANK_TID,
                            MEMORY_BANK.MEMORY_BANK_USER
                        )
                        for (bank in memoryBanksToRead) {
                            val ta = TagAccess()
                            val sequence = ta.Sequence(ta)
                            Log.d(TAG, "Reading memory bank: $bank")
                        }
                    } else {
                        Log.d(TAG, "Handheld trigger released")
                        stopInventory()
                    }
                } catch (e: Throwable) {
                    Log.d(TAG, "Error handling handheld trigger event: $e")
                }
            }

            else -> {
                Log.d(
                    TAG,
                    "Unhandled status event type: ${rfidStatusEvents.StatusEventData.statusEventType}"
                )
            }
        }
    }

    @Synchronized
    fun performInventory() {
        // check reader connection
        if (!isReaderConnected()) return
        try {
            Log.d(TAG, "Perform inventory")
            reader!!.Actions.Inventory.perform()
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun stopInventory() {
        // check reader connection
        if (!isReaderConnected()) return
        try {
            Log.d(TAG, "Stop inventory")
            reader!!.Actions.Inventory.stop()
            reader!!.Actions.purgeTags()
            Log.d(TAG, "Inventory stopped")
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            e.printStackTrace()
        }
    }

    private fun isReaderConnected(): Boolean {
        return if (reader!!.isConnected) true else {
            Log.d(TAG, "READER NOT CONNECTED")
            false
        }
    }

    // Read Event Notification
    override fun eventReadNotify(e: RfidReadEvents) {
        Log.d(TAG, "Read Event Notification")

        // Each access belong to a tag.
        // Therefore, as we are performing an access sequence on 3 Memory Banks, each tag could be reported 3 times
        // Each tag data represents a memory bank
        val readTags = reader?.Actions?.getReadTags(100)
        if (readTags != null) {
            try {
                Log.d(TAG, "Tags read: $readTags")
                Handler(Looper.getMainLooper()).post {
                    callbacks.onTagsRead(readTags.map {
                        RfidTag(
                            it.tagID,
                            it.peakRSSI.toLong()
                        )
                    }) {}
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error $e")
            }

//            val readTagsList = readTags.toList()
//            val tagReadGroup = readTagsList.groupBy { it.tagID }.toMutableMap()

//            var epc = ""
//            var tid = ""
//            var usr = ""
//            for (tagKey in tagReadGroup.keys) {
//                val tagValueList = tagReadGroup[tagKey]
//
//                for (tagData in tagValueList!!) {
//                    if (tagData.opCode == ACCESS_OPERATION_CODE.ACCESS_OPERATION_READ) {
//                        when (tagData.memoryBank.ordinal) {
//                            MEMORY_BANK.MEMORY_BANK_EPC.ordinal -> epc =
//                                getMemBankData(tagData.memoryBankData, tagData.opStatus)
//
//                            MEMORY_BANK.MEMORY_BANK_TID.ordinal -> tid =
//                                getMemBankData(tagData.memoryBankData, tagData.opStatus)
//
//                            MEMORY_BANK.MEMORY_BANK_USER.ordinal -> usr =
//                                getMemBankData(tagData.memoryBankData, tagData.opStatus)
//                        }
//                    }
//                }
//                var myTag = "EPC ${epc}\nTID ${tid}\nUSER ${usr}\n"
//            }
        }

        if (isLocating) {
            val locateTags = reader!!.Actions.getMultiTagLocateTagInfo(100)
            if (locateTags != null) {
                try {
                    Log.d(TAG, "Locate tags read: $locateTags")
                    Handler(Looper.getMainLooper()).post {
                        callbacks.onTagsLocated(locateTags.map {
                            RfidTag(
                                it.tagID,
                                it.peakRSSI.toLong(),
                                if (it.isContainsMultiTagLocateInfo) (it.MultiTagLocateInfo.relativeDistance / 100.0) else null
                            )
                        }) {}
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error $e")
                }
            }
        }
    }

    fun getMemBankData(memoryBankData: String?, opStatus: ACCESS_OPERATION_STATUS): String {
        return if (opStatus != ACCESS_OPERATION_STATUS.ACCESS_SUCCESS) {
            opStatus.toString()
        } else
            memoryBankData!!
    }


    fun onDestroy() {
        try {
            if (reader != null) {
                reader!!.Events?.removeEventsListener(this)
                reader!!.disconnect()
                reader!!.Dispose()
                readers?.Dispose()
                Readers.deattach(this)
            }
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun RFIDReaderAppeared(device: ReaderDevice?) {
        Log.d(TAG, "Reader ${device?.name} appeared")
        if (applicationContext != null && currentConnectionType != null) {
            getAvailableReaderList(currentConnectionType!!)
        }
    }

    override fun RFIDReaderDisappeared(device: ReaderDevice?) {
        Log.d(TAG, "Reader ${device?.name} disappeared")
        if (applicationContext != null && currentConnectionType != null) {
            getAvailableReaderList(currentConnectionType!!)
        }
    }
}