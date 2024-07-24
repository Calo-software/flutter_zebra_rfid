package nz.calo.flutter_zebra_rfid.rfid

import ReaderConnectionType
import FlutterZebraRfidCallbacks
import android.content.Context
import android.util.Log
import com.zebra.rfid.api3.*
import com.zebra.rfid.api3.Readers.RFIDReaderEventHandler
import java.util.*


fun readerConnectionTypeToTransport(type: ReaderConnectionType): ENUM_TRANSPORT {
    return when (type) {
        ReaderConnectionType.BLUETOOTH -> ENUM_TRANSPORT.BLUETOOTH
        ReaderConnectionType.USB -> ENUM_TRANSPORT.SERVICE_USB
    }
}

class RFIDReaderInterface(private var listener: IRFIDReaderListener, private var callbacks: FlutterZebraRfidCallbacks) : RfidEventsListener, RFIDReaderEventHandler {

    private val TAG: String = "FlutterZebraRfidPlugin"

    private var readers: Readers? = null
    private var availableRFIDReaderList: ArrayList<ReaderDevice>? = null
    private var readerDevice: ReaderDevice? = null
    private var reader: RFIDReader? = null
    private var currentConnectionType: ReaderConnectionType? = null
    private var applicationContext: Context? = null

    fun getAvailableReaderList(
        context: Context,
        connectionType: ReaderConnectionType
    ) {
        applicationContext = context
        if (readers == null) {
            readers = Readers(context, readerConnectionTypeToTransport(connectionType))
        }

        if (connectionType != currentConnectionType) {
            readers!!.setTransport(readerConnectionTypeToTransport(connectionType))
        }

        currentConnectionType = connectionType
        availableRFIDReaderList = readers!!.GetAvailableRFIDReaderList()
        Log.d(TAG, "Available readers: $availableRFIDReaderList")
        val readers = availableRFIDReaderList!!.map { it.name }
        callbacks.onAvailableReadersChanged(readers) {}
    }

    fun connectReader(readerName: String) {
        try {
            if (availableRFIDReaderList != null) {
                val readerDevices = availableRFIDReaderList!!.filter { it.name == readerName }
                if (readerDevices.isEmpty()) throw Error("Reader not available to connect")

                readerDevice = readerDevices.first()
                reader = readerDevice!!.rfidReader
                if (!reader!!.isConnected) {
                    callbacks.onReaderConnectionStatusChanged(ReaderConnectionStatus.CONNECTING) {}
                    Log.d(TAG, "RFID Reader Connecting...")
                    reader!!.connect()
                    configureReader(/*scanConnectionMode*/)
                    Log.d(TAG, "RFID Reader Connected!")
                    callbacks.onReaderConnectionStatusChanged(ReaderConnectionStatus.CONNECTED) {}
                } else {
                    callbacks.onReaderConnectionStatusChanged(ReaderConnectionStatus.CONNECTED) {}
                }
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "RFID Reader connection error: ${e.toString()}")
            callbacks.onReaderConnectionStatusChanged(ReaderConnectionStatus.ERROR) {}
        }
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

    fun currentReaderName(): String? {
        return readerDevice?.name
    }

//    fun connect(context: Context /*, scanConnectionMode : ScanConnectionEnum */): Boolean {
//        // Init
//        readers = Readers(context, ENUM_TRANSPORT.ALL)
//
//        try {
//            if (readers != null) {
//                availableRFIDReaderList = readers.GetAvailableRFIDReaderList()
//                if (availableRFIDReaderList != null && availableRFIDReaderList!!.size != 0) {
//                    // get first reader from list
//                    readerDevice = availableRFIDReaderList!![0]
//                    reader = readerDevice!!.rfidReader
//                    if (!reader!!.isConnected) {
//                        Log.d(TAG, "RFID Reader Connecting...")
//                        reader!!.connect()
//                        configureReader(/*scanConnectionMode*/)
//                        Log.d(TAG, "RFID Reader Connected!")
//                        return true
//                    }
//                }
//            }
//        } catch (e: InvalidUsageException) {
//            e.printStackTrace()
//        } catch (e: OperationFailureException) {
//            e.printStackTrace()
//        } catch (e: OperationFailureException) {
//            e.printStackTrace()
//        } catch (e: InvalidUsageException) {
//            e.printStackTrace()
//        }
//        Log.d(TAG, "RFID Reader connection error!")
//        return false
//    }

    private fun configureReader(/*scanConnectionMode : ScanConnectionEnum*/) {
        if (reader!!.isConnected) {
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

                // set start and stop triggers
                reader!!.Config.startTrigger = triggerInfo.StartTrigger
                reader!!.Config.stopTrigger = triggerInfo.StopTrigger
                reader!!.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)

                // Terminal scan, use trigger for scanning!
                //if(scanConnectionMode == ScanConnectionEnum.TerminalScan)
//                reader.Config.setKeylayoutType(ENUM_KEYLAYOUT_TYPE.UPPER_TRIGGER_FOR_SCAN)
                //else
                //   reader.Config.setKeylayoutType(ENUM_KEYLAYOUT_TYPE.UPPER_TRIGGER_FOR_SLED_SCAN)


            } catch (e: InvalidUsageException) {
                e.printStackTrace()
            } catch (e: OperationFailureException) {
                e.printStackTrace()
            }
        }
    }

    // Status Event Notification
    override fun eventStatusNotify(rfidStatusEvents: RfidStatusEvents) {
        Log.d(TAG, "Status Notification: " + rfidStatusEvents.StatusEventData.statusEventType)
        if (rfidStatusEvents.StatusEventData.statusEventType === STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
            if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.handheldEvent === HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                try {
                    // Read all memory banks
                    val memoryBanksToRead = arrayOf(
                        MEMORY_BANK.MEMORY_BANK_EPC,
                        MEMORY_BANK.MEMORY_BANK_TID,
                        MEMORY_BANK.MEMORY_BANK_USER
                    );
                    for (bank in memoryBanksToRead) {
                        val ta = TagAccess()
                        val sequence = ta.Sequence(ta)
                        val op = sequence.Operation()
                        op.accessOperationCode = ACCESS_OPERATION_CODE.ACCESS_OPERATION_READ
                        op.ReadAccessParams.memoryBank =
                            bank ?: throw IllegalArgumentException("bank must not be null")
                        reader!!.Actions.TagAccess.OperationSequence.add(op)
                    }

                    reader!!.Actions.TagAccess.OperationSequence.performSequence()

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.handheldEvent === HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                try {
                    reader!!.Actions.TagAccess.OperationSequence.stopSequence()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Read Event Notification
    override fun eventReadNotify(e: RfidReadEvents) {
        // Each access belong to a tag.
        // Therefore, as we are performing an access sequence on 3 Memory Banks, each tag could be reported 3 times
        // Each tag data represents a memory bank
        val readTags = reader?.Actions?.getReadTags(100)
        if (readTags != null) {
            val readTagsList = readTags.toList()
            val tagReadGroup = readTagsList.groupBy { it.tagID }.toMutableMap()

            var epc = ""
            var tid = ""
            var usr = ""
            for (tagKey in tagReadGroup.keys) {
                val tagValueList = tagReadGroup[tagKey]

                for (tagData in tagValueList!!) {
                    if (tagData.opCode == ACCESS_OPERATION_CODE.ACCESS_OPERATION_READ) {
                        when (tagData.memoryBank.ordinal) {
                            MEMORY_BANK.MEMORY_BANK_EPC.ordinal -> epc =
                                getMemBankData(tagData.memoryBankData, tagData.opStatus)

                            MEMORY_BANK.MEMORY_BANK_TID.ordinal -> tid =
                                getMemBankData(tagData.memoryBankData, tagData.opStatus)

                            MEMORY_BANK.MEMORY_BANK_USER.ordinal -> usr =
                                getMemBankData(tagData.memoryBankData, tagData.opStatus)
                        }
                    }
                }
                var myTag = "EPC ${epc}\nTID ${tid}\nUSER ${usr}\n"
                listener.newTagRead(myTag)
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
            getAvailableReaderList(applicationContext!!, currentConnectionType!!)
        }
    }

    override fun RFIDReaderDisappeared(device: ReaderDevice?) {
        Log.d(TAG, "Reader ${device?.name} disappeared")
        if (applicationContext != null && currentConnectionType != null) {
            getAvailableReaderList(applicationContext!!, currentConnectionType!!)
        }
    }


}