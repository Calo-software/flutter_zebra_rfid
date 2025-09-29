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
import ReaderErrorCode
import ReaderError
import Diagnostics
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.ArrayMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
    // Flag to allow suppressing trigger-driven scanning
    @Volatile private var scanningEnabled: Boolean = true
    private var scanningEnabledLastToggleMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    // --- Inventory / trigger guarding ---
    @Volatile private var inventoryActive: Boolean = false
    private var lastTriggerPressTimestamp: Long = 0L
    private var lastInventoryStartTimestamp: Long = 0L
    private var lastInventoryStopTimestamp: Long = 0L
    private var pendingPurgeRunnable: Runnable? = null
    private val INVENTORY_RELEASE_DEBOUNCE_MS = 120L
    private val PURGE_TAGS_DELAY_MS = 300L
    // Watchdog configuration
    private val INVENTORY_MAX_SESSION_MS = 30_000L // hard ceiling
    private val INVENTORY_INACTIVITY_TIMEOUT_MS = 5_000L // stop if no tag reads in this window
    private var lastTagReadTimestamp: Long = 0L
    private var inventoryWatchdogRunnable: Runnable? = null

    // --- Connection timing / retry ---
    private var connectTimeoutRunnable: Runnable? = null
    private var connectAttempt: Int = 0
    private var pendingConnectFuture: Future<*>? = null
    private var lastConnectStartTimestamp: Long = 0L

    private val CONNECT_TIMEOUT_MS = 10_000L
    private val RETRY_BACKOFF_MS = 2_000L
    private val MAX_CONNECT_ATTEMPTS = 2 // initial + 1 retry

    // Internal state machine to prevent race conditions
    private enum class InternalConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }
    private var internalState: InternalConnectionState = InternalConnectionState.DISCONNECTED
    private var totalConnectAttemptsCounter: Int = 0
    private var lastErrorCode: ReaderErrorCode? = null
    private var lastErrorMessage: String? = null
    private var lastConnectDurationMs: Long? = null
    // --- Auto-reconnect ---
    private var autoReconnectEnabled: Boolean = true
    private var reconnectAttempt: Int = 0
    private var pendingReconnectRunnable: Runnable? = null
    private var lastDisconnectTimestamp: Long = 0L
    private var unexpectedDisconnectCount: Int = 0
    private val MAX_RECONNECT_ATTEMPTS = 5
    private val INITIAL_RECONNECT_DELAY_MS = 1_000L
    private val MAX_RECONNECT_DELAY_MS = 15_000L

    private fun scheduleAutoReconnect(reason: String) {
        if (!autoReconnectEnabled) {
            Log.d(TAG, "AutoReconnect disabled; not scheduling (reason=$reason)")
            return
        }
        if (readerDevice == null) {
            Log.d(TAG, "No readerDevice bound; cannot auto-reconnect")
            return
        }
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.d(TAG, "Max auto-reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS); giving up")
            return
        }
        reconnectAttempt += 1
        val delay = computeReconnectDelay(reconnectAttempt)
        Log.d(TAG, "Scheduling auto-reconnect attempt #$reconnectAttempt in ${delay}ms (reason=$reason)")
        cancelPendingReconnect()
        val readerId = availableRFIDReaderList?.indexOf(readerDevice!!)?.toLong() ?: return
        val runnable = Runnable {
            synchronized(this) {
                if (internalState == InternalConnectionState.CONNECTED || internalState == InternalConnectionState.CONNECTING) {
                    Log.d(TAG, "Skipping auto-reconnect attempt #$reconnectAttempt; state=$internalState")
                    return@Runnable
                }
                Log.d(TAG, "Auto-reconnect attempt #$reconnectAttempt starting...")
                // Reset connect attempt counters for a clean sequence
                connectAttempt = 1
                totalConnectAttemptsCounter += 1
                beginAsyncConnect(readerId, isRetry = reconnectAttempt > 1)
            }
        }
        pendingReconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun computeReconnectDelay(attempt: Int): Long {
        // Exponential backoff with cap: base * 2^(attempt-1)
        val base = INITIAL_RECONNECT_DELAY_MS
        val factor = 1L shl (attempt - 1).coerceAtMost(10) // avoid overflow
        val raw = base * factor
        return raw.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    @Synchronized
    private fun cancelPendingReconnect() {
        pendingReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingReconnectRunnable = null
    }

    private fun updateConnectionState(newState: InternalConnectionState, externalStatus: ReaderConnectionStatus? = null, logMsg: String? = null, error: Throwable? = null) {
        if (internalState == newState) return
        if (logMsg != null) Log.d(TAG, logMsg + (error?.let { " | error=${it.message}" } ?: ""))
        internalState = newState
        externalStatus?.let { status ->
            mainHandler.post {
                callbacks.onReaderConnectionStatusChanged(status) {}
            }
        }
    }

    private fun emitError(code: ReaderErrorCode, message: String, details: String? = null, throwable: Throwable? = null) {
        Log.e(TAG, "[ReaderError][$code] $message ${details ?: ""} ${throwable?.message ?: ""}")
        val err = ReaderError(code, message, details ?: throwable?.message)
        lastErrorCode = code
        lastErrorMessage = message
        mainHandler.post { callbacks.onReaderConnectionError(err) {} }
    }

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

    @Synchronized
    fun connectReader(readerId: Long): ReaderInfo? {
        // Validate list
        val list = availableRFIDReaderList
        if (list == null) {
            emitError(ReaderErrorCode.NO_AVAILABLE_READERS, "No available readers list loaded")
            updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "No available readers list loaded")
            return null
        }

        if (readerId < 0 || readerId >= list.size) {
            emitError(ReaderErrorCode.INVALID_READER_INDEX, "Reader index $readerId out of range (size=${list.size})")
            updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Reader index $readerId out of range (size=${list.size})")
            return null
        }

        // If already connected to this reader
        reader?.let { existing ->
            if (existing.isConnected && currentReader()?.id == readerId) {
                Log.d(TAG, "Reader $readerId already connected (idempotent connect)")
                return readerInfo
            }
        }

        if (internalState == InternalConnectionState.CONNECTING) {
            emitError(ReaderErrorCode.ALREADY_CONNECTING, "Connect already in progress; ignoring duplicate request")
            Log.d(TAG, "Connect already in progress; ignoring duplicate request")
            return null
        }

        readerDevice = list[readerId.toInt()]
        val targetReader = readerDevice?.rfidReader
        if (targetReader == null) {
            emitError(ReaderErrorCode.READER_DEVICE_NULL, "Selected ReaderDevice has null rfidReader")
            updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Selected ReaderDevice has null rfidReader")
            return null
        }
        reader = targetReader

        if (targetReader.isConnected) {
            updateConnectionState(InternalConnectionState.CONNECTED, ReaderConnectionStatus.CONNECTED, "Reader already physically connected")
            return readerInfo
        }

        // New attempt sequence
        connectAttempt = 1
        totalConnectAttemptsCounter += 1
        beginAsyncConnect(readerId)
        return null // async result
    }

    @Synchronized
    private fun beginAsyncConnect(readerId: Long, isRetry: Boolean = false) {
        val targetReader = reader ?: return
        updateConnectionState(InternalConnectionState.CONNECTING, ReaderConnectionStatus.CONNECTING, (if (isRetry) "Retrying" else "Starting") + " connection attempt #$connectAttempt to readerId=$readerId (${readerDevice?.name})")
        lastConnectStartTimestamp = System.currentTimeMillis()
        scheduleConnectTimeout(readerId, connectAttempt)
        // Launch blocking connect off main thread
        pendingConnectFuture = ioExecutor.submit {
            try {
                targetReader.connect()
                // If timed out already, skip success path
                synchronized(this) {
                    if (internalState != InternalConnectionState.CONNECTING) return@submit
                }
                setupReader()
                val capabilities = targetReader.ReaderCapabilities
                val levels = capabilities.transmitPowerLevelValues
                val info = ReaderInfo(
                    levels.asList(),
                    capabilities.firwareVersion,
                    capabilities.modelName,
                    capabilities.scannerName,
                    capabilities.serialNumber,
                )
                synchronized(this) {
                    readerInfo = info
                    clearConnectTimeout()
                    lastConnectDurationMs = System.currentTimeMillis() - lastConnectStartTimestamp
                    updateConnectionState(InternalConnectionState.CONNECTED, ReaderConnectionStatus.CONNECTED, "Reader connected (attempt #$connectAttempt)")
                }
                triggerDeviceStatus()
            } catch (e: InvalidUsageException) {
                synchronized(this) {
                    clearConnectTimeout()
                    emitError(ReaderErrorCode.SDK_INVALID_USAGE, "Invalid usage while connecting", e.message, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Invalid usage while connecting", e)
                }
            } catch (e: OperationFailureException) {
                synchronized(this) {
                    clearConnectTimeout()
                    emitError(ReaderErrorCode.SDK_OPERATION_FAILURE, "Operation failed while connecting", e.vendorMessage, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Operation failed while connecting", e)
                }
            } catch (e: Throwable) {
                synchronized(this) {
                    clearConnectTimeout()
                    emitError(ReaderErrorCode.UNKNOWN, "Unexpected error while connecting", e.message, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Unexpected error while connecting", e)
                }
            }
        }
    }

    @Synchronized
    private fun scheduleConnectTimeout(readerId: Long, attempt: Int) {
        clearConnectTimeout()
        val runnable = Runnable {
            synchronized(this) {
                if (internalState != InternalConnectionState.CONNECTING) return@synchronized
                emitError(ReaderErrorCode.TIMEOUT, "Connection attempt #$attempt timed out after ${CONNECT_TIMEOUT_MS}ms")
                updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Connect timeout (attempt #$attempt)")
                // Cancel any in-flight future (best effort)
                pendingConnectFuture?.cancel(true)
                clearConnectTimeout()
                if (attempt < MAX_CONNECT_ATTEMPTS) {
                    connectAttempt += 1
                    totalConnectAttemptsCounter += 1
                    mainHandler.postDelayed({ beginAsyncConnect(readerId, isRetry = true) }, RETRY_BACKOFF_MS)
                }
            }
        }
        connectTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, CONNECT_TIMEOUT_MS)
    }

    @Synchronized
    private fun clearConnectTimeout() {
        connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }

    // Diagnostics snapshot
    @Synchronized
    fun diagnostics(): Diagnostics {
        val externalStatus = when (internalState) {
            InternalConnectionState.CONNECTING -> ReaderConnectionStatus.CONNECTING
            InternalConnectionState.CONNECTED -> ReaderConnectionStatus.CONNECTED
            InternalConnectionState.DISCONNECTING -> ReaderConnectionStatus.DISCONNECTING
            InternalConnectionState.DISCONNECTED -> ReaderConnectionStatus.DISCONNECTED
            InternalConnectionState.ERROR -> ReaderConnectionStatus.ERROR
        }
        return Diagnostics(
            externalStatus,
            totalConnectAttemptsCounter.toLong(),
            lastErrorCode,
            lastErrorMessage,
            if (lastConnectStartTimestamp == 0L) null else lastConnectStartTimestamp,
            lastConnectDurationMs,
            isLocating,
            scanningEnabled,
            if (scanningEnabledLastToggleMs == 0L) null else scanningEnabledLastToggleMs
        )
    }

    fun setScanningEnabled(enabled: Boolean) {
        if (scanningEnabled == enabled) return
        scanningEnabled = enabled
        scanningEnabledLastToggleMs = System.currentTimeMillis()
        if (!enabled) {
            // Stop any active inventory immediately
            if (inventoryActive) {
                safeStopInventory("scanning disabled")
            }
        }
        Log.d(TAG, "Scanning enabled set to $scanningEnabled")
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
        // NOTE: SDK does not expose a direct getter for current RF mode index; we leave it unchanged.
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
        // Cancel any pending connect timeout / future if user is disconnecting
        synchronized(this) {
            clearConnectTimeout()
            pendingConnectFuture?.cancel(true)
        }
        if (reader == null) {
            Log.d(TAG, "No connected RFID Reader (disconnect noop)")
            return
        }
        if (internalState == InternalConnectionState.DISCONNECTING || internalState == InternalConnectionState.DISCONNECTED) {
            Log.d(TAG, "Disconnect already in progress or completed")
            return
        }
        updateConnectionState(InternalConnectionState.DISCONNECTING, ReaderConnectionStatus.DISCONNECTING, "Disconnecting reader")
        try {
            if (reader?.isConnected == true) {
                reader?.disconnect()
            }
            updateConnectionState(InternalConnectionState.DISCONNECTED, ReaderConnectionStatus.DISCONNECTED, "Reader disconnected")
            // User initiated disconnect -> cancel any auto reconnect sequence
            cancelPendingReconnect()
            reconnectAttempt = 0
        } catch (e: Throwable) {
            emitError(ReaderErrorCode.UNKNOWN, "Error during disconnect", e.message, e)
            updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Error during disconnect", e)
        }
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
        if (!reader!!.isConnected) {
            Log.d(TAG, "Reader not connected, connecting...")
            reader!!.connect()
        }
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
        } else {
            throw Error("Not connected to any Reader")
        }
    }

    fun getReaderConfig(): ReaderConfig {
        if (reader == null) {
            Log.d(TAG, "Not connected to any Reader!")
            throw Error("Not connected to any Reader")
        }

        try {
            Log.d(TAG, "Reader Config:")

            val antennaRfConfig = reader!!.Config.Antennas.getAntennaRfConfig(1)
            val transmitPowerIndex = antennaRfConfig.transmitPowerIndex
            Log.d(TAG, "Transmit Power Index: $transmitPowerIndex")

            val receiveSensitivityIndex = antennaRfConfig.receiveSensitivityIndex
            // rfModeTableIndex getter not available; returning null for now.
            val rfModeIndex: Int? = null
            Log.d(TAG, "Receive Sensitivity Index: $receiveSensitivityIndex")
            Log.d(TAG, "RF Mode Table Index: $rfModeIndex")

            val tari = antennaRfConfig.tari
            Log.d(TAG, "Tari: $tari")

            var beeperVolume: ReaderBeeperVolume? = null

            when (reader!!.Config.beeperVolume) {
                BEEPER_VOLUME.HIGH_BEEP -> beeperVolume = ReaderBeeperVolume.HIGH
                BEEPER_VOLUME.MEDIUM_BEEP -> beeperVolume = ReaderBeeperVolume.MEDIUM
                BEEPER_VOLUME.LOW_BEEP -> beeperVolume = ReaderBeeperVolume.LOW
                BEEPER_VOLUME.QUIET_BEEP -> beeperVolume = ReaderBeeperVolume.QUIET
            }
            Log.d(TAG, "Beeper volume: $beeperVolume")

            var batchMode: ReaderConfigBatchMode? = null
            when (reader!!.Config.batchModeConfig) {
                BATCH_MODE.AUTO -> batchMode = ReaderConfigBatchMode.AUTO
                BATCH_MODE.ENABLE -> batchMode = ReaderConfigBatchMode.ENABLED
                BATCH_MODE.DISABLE -> batchMode = ReaderConfigBatchMode.DISABLED
            }
            Log.d(TAG, "Batch mode: $batchMode")

            var scanBatchMode: ReaderConfigBatchMode? = null
            when (reader!!.Config.scanBatchModeConfig) {
                SCAN_BATCH_MODE.AUTO -> scanBatchMode = ReaderConfigBatchMode.AUTO
                SCAN_BATCH_MODE.ENABLE -> scanBatchMode = ReaderConfigBatchMode.ENABLED
                SCAN_BATCH_MODE.DISABLE -> scanBatchMode = ReaderConfigBatchMode.DISABLED
            }
            Log.d(TAG, "Scan batch mode: $scanBatchMode")

            return ReaderConfig(
                transmitPowerIndex.toLong(),
                tari.toLong(),
                beeperVolume,
                reader!!.Config.dpoState == DYNAMIC_POWER_OPTIMIZATION.ENABLE,
                // NOTE: SDK doesn't provide this LED blink read API reliably; leaving null
                null,
                batchMode,
                scanBatchMode,
                rfModeIndex?.toLong(),
                receiveSensitivityIndex.toLong()
            )
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
                    if (!scanningEnabled) {
                        Log.d(TAG, "Trigger event ignored (scanning disabled)")
                        return
                    }
                    if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.handheldEvent === HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                        Log.d(TAG, "Handheld trigger pressed")
                        lastTriggerPressTimestamp = System.currentTimeMillis()
                        safeStartInventory("trigger pressed")
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
                        val elapsed = System.currentTimeMillis() - lastTriggerPressTimestamp
                        if (elapsed < INVENTORY_RELEASE_DEBOUNCE_MS) {
                            Log.d(TAG, "Trigger release within ${INVENTORY_RELEASE_DEBOUNCE_MS}ms debounce window ($elapsed ms) -> ignoring stop")
                        } else {
                            safeStopInventory("trigger released")
                        }
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
        // Legacy direct call retained (now guarded via safeStartInventory). Prefer safeStartInventory.
        if (!isReaderConnected()) return
        if (inventoryActive) {
            Log.d(TAG, "performInventory() called but inventory already active; ignoring")
            return
        }
        try {
            reader!!.Actions.Inventory.perform()
            inventoryActive = true
            lastInventoryStartTimestamp = System.currentTimeMillis()
            lastTagReadTimestamp = lastInventoryStartTimestamp
            scheduleInventoryWatchdog()
            Log.d(TAG, "Inventory started (performInventory)")
        } catch (e: InvalidUsageException) {
            inventoryActive = false
            Log.d(TAG, "InvalidUsageException starting inventory: ${e.message}")
        } catch (e: OperationFailureException) {
            inventoryActive = false
            Log.d(TAG, "OperationFailureException starting inventory: ${e.message}")
        } catch (t: Throwable) {
            inventoryActive = false
            Log.d(TAG, "Unexpected error starting inventory: ${t.message}")
        }
    }

    @Synchronized
    fun stopInventory() {
        // Legacy direct call retained (now guarded via safeStopInventory). Prefer safeStopInventory.
        if (!isReaderConnected()) return
        if (!inventoryActive) {
            Log.d(TAG, "stopInventory() called but inventory not active; ignoring")
            return
        }
        try {
            reader!!.Actions.Inventory.stop()
            inventoryActive = false
            lastInventoryStopTimestamp = System.currentTimeMillis()
            cancelInventoryWatchdog()
            Log.d(TAG, "Inventory stopped (stopInventory)")
        } catch (e: InvalidUsageException) {
            Log.d(TAG, "InvalidUsageException stopping inventory: ${e.message}")
        } catch (e: OperationFailureException) {
            Log.d(TAG, "OperationFailureException stopping inventory: ${e.message}")
        } catch (t: Throwable) {
            Log.d(TAG, "Unexpected error stopping inventory: ${t.message}")
        }
    }

    // Guarded start that cancels pending purge and debounces duplicate starts
    @Synchronized
    private fun safeStartInventory(reason: String) {
        if (!isReaderConnected()) {
            Log.d(TAG, "safeStartInventory($reason) aborted: reader not connected")
            return
        }
        cancelScheduledPurge()
        if (inventoryActive) {
            Log.d(TAG, "safeStartInventory($reason) ignored: inventory already active")
            return
        }
        Log.d(TAG, "safeStartInventory($reason) -> starting inventory")
        performInventory()
    }

    // Guarded stop that defers purge to allow late tag reads to flush through
    @Synchronized
    private fun safeStopInventory(reason: String) {
        if (!isReaderConnected()) {
            Log.d(TAG, "safeStopInventory($reason) aborted: reader not connected")
            return
        }
        if (!inventoryActive) {
            Log.d(TAG, "safeStopInventory($reason) ignored: inventory not active")
            return
        }
        Log.d(TAG, "safeStopInventory($reason) -> stopping inventory")
        stopInventory()
        schedulePurgeTags()
    }

    @Synchronized
    private fun schedulePurgeTags() {
        cancelScheduledPurge()
        val runnable = Runnable {
            try {
                if (!isReaderConnected()) return@Runnable
                Log.d(TAG, "Purging tags after delay (${PURGE_TAGS_DELAY_MS}ms)")
                reader?.Actions?.purgeTags()
            } catch (t: Throwable) {
                Log.d(TAG, "Error purging tags: ${t.message}")
            }
        }
        pendingPurgeRunnable = runnable
        mainHandler.postDelayed(runnable, PURGE_TAGS_DELAY_MS)
    }

    @Synchronized
    private fun cancelScheduledPurge() {
        pendingPurgeRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingPurgeRunnable = null
    }

    private fun isReaderConnected(): Boolean {
        val connected = reader?.isConnected == true && internalState == InternalConnectionState.CONNECTED
        if (!connected) Log.d(TAG, "READER NOT CONNECTED (state=$internalState)")
        return connected
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
                if (readTags.isNotEmpty()) {
                    lastTagReadTimestamp = System.currentTimeMillis()
                }
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

    // --- Inventory Watchdog ---
    @Synchronized
    private fun scheduleInventoryWatchdog() {
        cancelInventoryWatchdog()
        val runnable = object : Runnable {
            override fun run() {
                if (!inventoryActive) return
                val now = System.currentTimeMillis()
                val elapsedSession = now - lastInventoryStartTimestamp
                val idleElapsed = now - lastTagReadTimestamp
                if (elapsedSession >= INVENTORY_MAX_SESSION_MS) {
                    Log.d(TAG, "Watchdog: Max inventory session duration exceeded (${elapsedSession}ms) -> stopping")
                    safeStopInventory("watchdog max duration")
                    return
                }
                if (idleElapsed >= INVENTORY_INACTIVITY_TIMEOUT_MS) {
                    Log.d(TAG, "Watchdog: Inactivity timeout (${idleElapsed}ms without tag) -> stopping")
                    safeStopInventory("watchdog inactivity")
                    return
                }
                // Reschedule for next check (run every second)
                mainHandler.postDelayed(this, 1_000L)
            }
        }
        inventoryWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, 1_000L)
    }

    @Synchronized
    private fun cancelInventoryWatchdog() {
        inventoryWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        inventoryWatchdogRunnable = null
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
//            getAvailableReaderList(currentConnectionType!!)
        }
    }

    override fun RFIDReaderDisappeared(device: ReaderDevice?) {
        Log.d(TAG, "Reader ${device?.name} disappeared")
        if (applicationContext != null && currentConnectionType != null) {
//            getAvailableReaderList(currentConnectionType!!)
        }
        // If the device that disappeared is our current reader and we were connected -> schedule auto reconnect.
        if (device != null && readerDevice != null && device == readerDevice) {
            val wasConnected = internalState == InternalConnectionState.CONNECTED
            if (wasConnected) {
                Log.d(TAG, "Unexpected disconnect (device disappeared); initiating auto-reconnect sequence")
                lastDisconnectTimestamp = System.currentTimeMillis()
                unexpectedDisconnectCount += 1
                updateConnectionState(InternalConnectionState.DISCONNECTED, ReaderConnectionStatus.DISCONNECTED, "Reader disappeared")
                scheduleAutoReconnect("device disappeared")
            }
        }
    }
}