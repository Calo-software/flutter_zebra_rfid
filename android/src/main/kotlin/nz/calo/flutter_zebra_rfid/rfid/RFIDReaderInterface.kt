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
import ReaderRegion
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
import com.zebra.rfid.api3.RFIDResults
import com.zebra.rfid.api3.RegionInfo
import com.zebra.rfid.api3.RegulatoryConfig
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

internal fun readerConnectionTypeToDiscoveryTransports(type: ReaderConnectionType): List<ENUM_TRANSPORT> {
    return when (type) {
        ReaderConnectionType.BLUETOOTH -> listOf(ENUM_TRANSPORT.BLUETOOTH)
        ReaderConnectionType.USB -> listOf(ENUM_TRANSPORT.SERVICE_SERIAL, ENUM_TRANSPORT.SERVICE_USB)
        ReaderConnectionType.ALL -> listOf(
            ENUM_TRANSPORT.BLUETOOTH,
            ENUM_TRANSPORT.SERVICE_SERIAL,
            ENUM_TRANSPORT.SERVICE_USB,
        )
    }
}

private class ReaderRegionConfigurationException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class SupportedRegionCandidate(
    val regionCode: String,
    val standardName: String?,
    val hoppingConfigurable: Boolean,
    val channelSelectable: Boolean,
    val lbtConfigurable: Boolean,
    val supportedChannels: Array<String>,
)

internal fun supportedRegionCandidates(regionInfos: List<RegionInfo>): List<SupportedRegionCandidate> {
    return regionInfos.mapNotNull { regionInfo ->
        val regionCode = regionInfo.regionCode?.trim().orEmpty()
        if (regionCode.isEmpty()) return@mapNotNull null

        val standardName = regionInfo.standardName?.trim()?.takeIf { it.isNotEmpty() }
        val channels = regionInfo.supportedChannels
            ?.filter { it.isNotBlank() }
            ?.toTypedArray()
            ?: emptyArray()

        SupportedRegionCandidate(
            regionCode = regionCode,
            standardName = standardName,
            hoppingConfigurable = regionInfo.isHoppingConfigurable(),
            channelSelectable = regionInfo.isChannelSelectable(),
            lbtConfigurable = regionInfo.isLBTConfigurable(),
            supportedChannels = channels,
        )
    }.distinctBy { "${it.regionCode}|${it.standardName ?: ""}" }
}

internal fun buildRegulatoryConfigForSingleSupportedRegion(regionInfos: List<RegionInfo>): RegulatoryConfig? {
    val candidates = supportedRegionCandidates(regionInfos)
    if (candidates.size != 1) {
        return null
    }

    val candidate = candidates.single()
    return RegulatoryConfig().apply {
        setRegion(candidate.regionCode)
        candidate.standardName?.let(::setStandardName)
        setIsHoppingOn(candidate.hoppingConfigurable)
        setChannelSelectable(candidate.channelSelectable)
        setLBTConfigurable(candidate.lbtConfigurable)
        if (candidate.supportedChannels.isNotEmpty()) {
            setEnabledChannels(candidate.supportedChannels)
        }
    }
}

internal fun describeSupportedRegions(regionInfos: List<RegionInfo>): String {
    val candidates = supportedRegionCandidates(regionInfos)
    if (candidates.isEmpty()) {
        return "none reported"
    }

    return candidates.joinToString(", ") { candidate ->
        candidate.standardName?.let { "${candidate.regionCode} ($it)" } ?: candidate.regionCode
    }
}

internal fun toReaderRegions(regionInfos: List<RegionInfo>): List<ReaderRegion> {
    return supportedRegionCandidates(regionInfos).map { candidate ->
        ReaderRegion(
            code = candidate.regionCode,
            name = null,
            standardName = candidate.standardName,
        )
    }
}

class RFIDReaderInterface(
    private var callbacks: FlutterZebraRfidCallbacks,
    private var applicationContext: Context
) : RfidEventsListener, RFIDReaderEventHandler {

    private val TAG: String = "FlutterZebraRfidPlugin"
    private val DEBUG = false // Enable verbose logging for troubleshooting

    private var readers: Readers? = null
    private var availableRFIDReaderList: ArrayList<ReaderDevice>? = null
    private var readerDevice: ReaderDevice? = null
    private var reader: RFIDReader? = null
    private var readerInfo: ReaderInfo? = null
    private var currentConnectionType: ReaderConnectionType? = null
    private var isLocating: Boolean = false
    // Locate session management
    @Volatile private var locateSessionActive: Boolean = false
    @Volatile private var locateTargetTags: List<RfidTag>? = null
    @Volatile private var locateDisableBeep: Boolean = false
    @Volatile private var locatePendingStart: Boolean = false
    private var locatePurgeCompleteRunnable: Runnable? = null
    private var locateOriginalBeeperVolume: BEEPER_VOLUME? = null
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
    private var lastInventoryStartReason: String? = null
    private var lastInventoryStopReason: String? = null
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

    // Battery fallback derivation state
    private var lastBatteryLevel: Int? = null
    private var lastBatteryCharging: Boolean = false

    private fun estimateBatteryPercentFromVoltageMv(voltageMv: Int): Int {
        val v = voltageMv / 1000.0
        return when {
            v >= 4.15 -> 100
            v >= 4.05 -> 90
            v >= 3.98 -> 80
            v >= 3.92 -> 70
            v >= 3.88 -> 60
            v >= 3.83 -> 50
            v >= 3.78 -> 40
            v >= 3.73 -> 30
            v >= 3.67 -> 20
            v >= 3.60 -> 15
            v >= 3.55 -> 10
            v >= 3.50 -> 7
            v >= 3.45 -> 5
            else -> 3
        }
    }

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
        Log.i(TAG, "========== READER DISCOVERY STARTED ==========")
        Log.i(TAG, "Requested connection type: $connectionType")

        val transports = readerConnectionTypeToDiscoveryTransports(connectionType)
        Log.i(TAG, "Using SDK transports: ${transports.joinToString()}")

        try {
            val mergedDevices = arrayListOf<ReaderDevice>()
            val seenKeys = linkedSetOf<String>()
            var primaryReaders: Readers? = null
            val discoveryFailures = mutableListOf<String>()

            transports.forEach { transport ->
                try {
                    Log.d(TAG, "Creating Readers instance with transport: $transport")
                    val transportReaders = Readers(applicationContext, transport)
                    if (primaryReaders == null) {
                        primaryReaders = transportReaders
                    }

                    Log.d(TAG, "Calling GetAvailableRFIDReaderList() for transport=$transport")
                    val discoveredDevices = transportReaders.GetAvailableRFIDReaderList() ?: arrayListOf()
                    Log.i(TAG, "Transport $transport discovered ${discoveredDevices.size} reader(s)")

                    discoveredDevices.forEach { device ->
                        val deviceKey = buildReaderDiscoveryKey(device)
                        if (seenKeys.add(deviceKey)) {
                            mergedDevices.add(device)
                        } else {
                            Log.d(TAG, "Skipping duplicate reader from transport=$transport key=$deviceKey")
                        }
                    }
                } catch (transportError: Exception) {
                    val failure = "$transport: ${transportError.javaClass.simpleName}: ${transportError.message}"
                    discoveryFailures.add(failure)
                    Log.e(TAG, "Discovery failed for transport=$transport", transportError)
                }
            }

            readers = primaryReaders
            currentConnectionType = connectionType
            availableRFIDReaderList = mergedDevices
            
            val readerCount = availableRFIDReaderList?.size ?: 0
            Log.i(TAG, "Discovery complete. Found $readerCount reader(s)")
            if (discoveryFailures.isNotEmpty()) {
                Log.w(TAG, "One or more transport discovery attempts failed: ${discoveryFailures.joinToString(" | ")}")
            }
            
            // Log detailed info about each discovered reader
            availableRFIDReaderList?.forEachIndexed { index, device ->
                Log.i(TAG, "--- Reader #$index ---")
                Log.i(TAG, "  Name: ${device.name}")
                Log.i(TAG, "  RFIDReader: ${device.rfidReader}")
                
                if (DEBUG) {
                    try {
                        Log.d(TAG, "  Address: ${device.address}")
                        Log.d(TAG, "  Password: ${device.password}")
                        Log.d(TAG, "  ToString: $device")
                    } catch (e: Exception) {
                        Log.d(TAG, "  Could not read all properties: ${e.message}")
                    }
                }
            }
            
            if (readerCount == 0) {
                Log.w(TAG, "WARNING: No readers found!")
                Log.w(TAG, "  - Connection type requested: $connectionType")
                Log.w(TAG, "  - Transports used: ${transports.joinToString()}")
                Log.w(TAG, "  - If using TC22 built-in RFID, verify:")
                Log.w(TAG, "    1. Device actually has RFID hardware (not all TC22s do)")
                Log.w(TAG, "    2. RFID works in Zebra's 123RFID Mobile app")
                Log.w(TAG, "    3. Check Settings → RFID is enabled")
            }
            
            val readers = availableRFIDReaderList!!.mapIndexed { index, reader ->
                Reader(reader.name, index.toLong())
            }
            callbacks.onAvailableReadersChanged(readers) {}

            // Replay connection state so that a freshly-attached Dart side (e.g. after
            // a hot reload) immediately learns about an existing live connection.
            if (internalState == InternalConnectionState.CONNECTED) {
                Log.i(TAG, "Replaying CONNECTED status to freshly-attached Dart listeners (reader.isConnected=${reader?.isConnected})")
                callbacks.onReaderConnectionStatusChanged(ReaderConnectionStatus.CONNECTED) {}
            }

        } catch (e: Exception) {
            Log.e(TAG, "ERROR during reader discovery: ${e.message}", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            e.printStackTrace()
            // Return empty list on error
            callbacks.onAvailableReadersChanged(emptyList()) {}
        }
        
        Log.i(TAG, "========== READER DISCOVERY ENDED ==========")
    }

    private fun buildReaderDiscoveryKey(device: ReaderDevice): String {
        val address = runCatching { device.address }.getOrNull()?.trim().orEmpty()
        val name = device.name?.trim().orEmpty()
        return listOf(name, address)
            .filter { it.isNotEmpty() }
            .joinToString("|")
            .ifEmpty { device.toString() }
    }

    @Synchronized
    fun connectReader(readerId: Long): ReaderInfo? {
        Log.i(TAG, "========== CONNECT READER STARTED ==========")
        Log.i(TAG, "Requested reader ID: $readerId")
        
        // Validate list
        val list = availableRFIDReaderList
        if (list == null) {
            Log.e(TAG, "ERROR: No available readers list loaded")
            emitError(ReaderErrorCode.NO_AVAILABLE_READERS, "No available readers list loaded")
            updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "No available readers list loaded")
            return null
        }
        
        Log.d(TAG, "Available readers list size: ${list.size}")

        if (readerId < 0 || readerId >= list.size) {
            Log.e(TAG, "ERROR: Reader index $readerId out of range (size=${list.size})")
            emitError(ReaderErrorCode.INVALID_READER_INDEX, "Reader index $readerId out of range (size=${list.size})")
            updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Reader index $readerId out of range (size=${list.size})")
            return null
        }

        // If already connected to this reader
        reader?.let { existing ->
            if (existing.isConnected && currentReader()?.id == readerId) {
                return rearmConnectedReaderSession(
                    existing,
                    "Reader $readerId already connected (idempotent connect)",
                )
            }
        }

        if (internalState == InternalConnectionState.CONNECTING) {
            emitError(ReaderErrorCode.ALREADY_CONNECTING, "Connect already in progress; ignoring duplicate request")
            Log.d(TAG, "Connect already in progress; ignoring duplicate request")
            return null
        }

        readerDevice = list[readerId.toInt()]
        Log.i(TAG, "Selected reader device: ${readerDevice?.name}")
        
        val targetReader = readerDevice?.rfidReader
        if (targetReader == null) {
            Log.e(TAG, "ERROR: Selected ReaderDevice has null rfidReader")
            Log.e(TAG, "  ReaderDevice name: ${readerDevice?.name}")
            Log.e(TAG, "  ReaderDevice: $readerDevice")
            emitError(ReaderErrorCode.READER_DEVICE_NULL, "Selected ReaderDevice has null rfidReader")
            updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Selected ReaderDevice has null rfidReader")
            return null
        }
        
        reader = targetReader
        Log.d(TAG, "RFIDReader object obtained: $targetReader")

        if (targetReader.isConnected) {
            return rearmConnectedReaderSession(
                targetReader,
                "Reader already physically connected",
            )
        }
        
        Log.i(TAG, "Reader not connected, starting connection sequence...")

        // New attempt sequence
        connectAttempt = 1
        totalConnectAttemptsCounter += 1
        Log.d(TAG, "Connect attempt: $connectAttempt, Total attempts: $totalConnectAttemptsCounter")
        beginAsyncConnect(readerId)
        return null // async result
    }

    @Synchronized
    private fun beginAsyncConnect(readerId: Long, isRetry: Boolean = false) {
        val targetReader = reader ?: return
        
        val attemptType = if (isRetry) "Retrying" else "Starting"
        Log.i(TAG, "$attemptType connection attempt #$connectAttempt to readerId=$readerId")
        Log.i(TAG, "  Reader name: ${readerDevice?.name}")
        Log.i(TAG, "  Reader object: $targetReader")
        
        updateConnectionState(InternalConnectionState.CONNECTING, ReaderConnectionStatus.CONNECTING, "$attemptType connection attempt #$connectAttempt to readerId=$readerId (${readerDevice?.name})")
        lastConnectStartTimestamp = System.currentTimeMillis()
        scheduleConnectTimeout(readerId, connectAttempt)
        
        Log.d(TAG, "Launching blocking connect() call on background thread...")
        // Launch blocking connect off main thread
        pendingConnectFuture = ioExecutor.submit {
            try {
                Log.d(TAG, "Calling targetReader.connect()...")
                connectWithRegionRecovery(targetReader)
                Log.i(TAG, "targetReader.connect() completed successfully!")
                // If timed out already, skip success path
                synchronized(this) {
                    if (internalState != InternalConnectionState.CONNECTING) {
                        Log.w(TAG, "Connection succeeded but state changed to $internalState, ignoring")
                        return@submit
                    }
                }
                
                Log.d(TAG, "Setting up reader configuration...")
                setupReader()
                
                Log.d(TAG, "Reading reader capabilities...")
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
                    Log.e(TAG, "InvalidUsageException during connect: ${e.message}", e)
                    Log.e(TAG, "  Info: ${e.info}")
                    emitError(ReaderErrorCode.SDK_INVALID_USAGE, "Invalid usage while connecting", e.message, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Invalid usage while connecting", e)
                }
            } catch (e: ReaderRegionConfigurationException) {
                synchronized(this) {
                    clearConnectTimeout()
                    Log.e(TAG, "Reader region configuration required during connect: ${e.message}", e)
                    emitError(ReaderErrorCode.SDK_OPERATION_FAILURE, "Reader region is not configured", e.message, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Reader region is not configured", e)
                }
            } catch (e: OperationFailureException) {
                synchronized(this) {
                    clearConnectTimeout()
                    Log.e(TAG, "OperationFailureException during connect: ${e.message}", e)
                    Log.e(TAG, "  Vendor message: ${e.vendorMessage}")
                    Log.e(TAG, "  Status description: ${e.statusDescription}")
                    Log.e(TAG, "  Results: ${e.results}")
                    emitError(ReaderErrorCode.SDK_OPERATION_FAILURE, "Operation failed while connecting", e.vendorMessage, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Operation failed while connecting", e)
                }
            } catch (e: Throwable) {
                synchronized(this) {
                    clearConnectTimeout()
                    Log.e(TAG, "Unexpected error during connect: ${e.message}", e)
                    Log.e(TAG, "  Exception type: ${e.javaClass.name}")
                    e.printStackTrace()
                    emitError(ReaderErrorCode.UNKNOWN, "Unexpected error while connecting", e.message, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Unexpected error while connecting", e)
                }
            }
        }
    }

    @Throws(InvalidUsageException::class, OperationFailureException::class, ReaderRegionConfigurationException::class)
    private fun connectWithRegionRecovery(targetReader: RFIDReader) {
        try {
            targetReader.connect()
        } catch (e: OperationFailureException) {
            if (e.results != RFIDResults.RFID_READER_REGION_NOT_CONFIGURED) {
                throw e
            }

            Log.w(TAG, "Reader reported RFID_READER_REGION_NOT_CONFIGURED during connect; attempting recovery")
            val supportedRegions = getSupportedRegions(targetReader)
            val regionConfig = buildRegulatoryConfigForSingleSupportedRegion(supportedRegions)
                ?: throw ReaderRegionConfigurationException(
                    "Reader requires regulatory region setup. Supported regions: ${describeSupportedRegions(supportedRegions)}. Configure the region in Zebra 123RFID Mobile and retry.",
                    e,
                )

            val configuredRegion = supportedRegionCandidates(supportedRegions).single()

            try {
                targetReader.Config.setRegulatoryConfig(regionConfig)
                targetReader.PostConnectReaderUpdate()
                val regionLabel = configuredRegion.standardName?.let { "${configuredRegion.regionCode} ($it)" }
                    ?: configuredRegion.regionCode
                Log.i(TAG, "Applied regulatory region $regionLabel after connect reported region not configured")
            } catch (configError: InvalidUsageException) {
                throw ReaderRegionConfigurationException(
                    "Reader requires regulatory region setup, but the SDK rejected automatic configuration: ${configError.info ?: configError.message}",
                    configError,
                )
            } catch (configError: OperationFailureException) {
                val details = configError.vendorMessage ?: configError.statusDescription ?: configError.message
                throw ReaderRegionConfigurationException(
                    "Reader requires regulatory region setup, but the SDK failed to apply the region: $details",
                    configError,
                )
            }
        }
    }

    private fun getSupportedRegions(targetReader: RFIDReader): List<RegionInfo> {
        val supportedRegions = targetReader.ReaderCapabilities.SupportedRegions ?: return emptyList()
        return (0 until supportedRegions.length()).mapNotNull { index ->
            try {
                supportedRegions.getRegionInfo(index)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun buildRegulatoryConfigForRegionCode(
        regionCode: String,
        regionInfos: List<RegionInfo>,
    ): RegulatoryConfig? {
        val candidate = supportedRegionCandidates(regionInfos).firstOrNull {
            it.regionCode.equals(regionCode, ignoreCase = true)
        } ?: return null

        return RegulatoryConfig().apply {
            setRegion(candidate.regionCode)
            candidate.standardName?.let(::setStandardName)
            setIsHoppingOn(candidate.hoppingConfigurable)
            setChannelSelectable(candidate.channelSelectable)
            setLBTConfigurable(candidate.lbtConfigurable)
            if (candidate.supportedChannels.isNotEmpty()) {
                setEnabledChannels(candidate.supportedChannels)
            }
        }
    }

    private fun refreshReaderInfo(targetReader: RFIDReader): ReaderInfo {
        val capabilities = targetReader.ReaderCapabilities
        val levels = capabilities.transmitPowerLevelValues
        return ReaderInfo(
            levels.asList(),
            capabilities.firwareVersion,
            capabilities.modelName,
            capabilities.scannerName,
            capabilities.serialNumber,
        )
    }

    @Synchronized
    private fun rearmConnectedReaderSession(targetReader: RFIDReader, logMessage: String): ReaderInfo? {
        Log.i(TAG, logMessage)
        // Dispatch hardware I/O off the main thread — SDK commands like setStartTrigger
        // timeout when called on the main (Pigeon message handler) thread.
        ioExecutor.submit {
            try {
                setupReader()
                val info = refreshReaderInfo(targetReader)
                synchronized(this) {
                    readerInfo = info
                    clearConnectTimeout()
                    if (lastConnectStartTimestamp != 0L) {
                        lastConnectDurationMs = System.currentTimeMillis() - lastConnectStartTimestamp
                    }
                    updateConnectionState(
                        InternalConnectionState.CONNECTED,
                        ReaderConnectionStatus.CONNECTED,
                        logMessage,
                    )
                }
                triggerDeviceStatus()
            } catch (e: OperationFailureException) {
                synchronized(this) {
                    Log.e(TAG, "OperationFailureException re-arming reader session: ${e.message}", e)
                    Log.e(TAG, "  Vendor message: ${e.vendorMessage}")
                    Log.e(TAG, "  Status description: ${e.statusDescription}")
                    emitError(ReaderErrorCode.SDK_OPERATION_FAILURE, "Operation failed re-arming reader session", e.vendorMessage, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Operation failed re-arming reader session", e)
                }
            } catch (e: InvalidUsageException) {
                synchronized(this) {
                    Log.e(TAG, "InvalidUsageException re-arming reader session: ${e.message}", e)
                    emitError(ReaderErrorCode.SDK_INVALID_USAGE, "Invalid usage re-arming reader session", e.message, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Invalid usage re-arming reader session", e)
                }
            } catch (e: Throwable) {
                synchronized(this) {
                    Log.e(TAG, "Unexpected error re-arming reader session: ${e.message}", e)
                    emitError(ReaderErrorCode.UNKNOWN, "Unexpected error re-arming reader session", e.message, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Unexpected error re-arming reader session", e)
                }
            }
        }
        return null
    }

    @Synchronized
    fun supportedReaderRegions(): List<ReaderRegion> {
        val targetReader = reader ?: readerDevice?.rfidReader
            ?: throw IllegalStateException("No reader selected. Discover and connect to a reader first.")
        return toReaderRegions(getSupportedRegions(targetReader))
    }

    @Synchronized
    fun setReaderRegion(regionCode: String) {
        val normalizedRegionCode = regionCode.trim().uppercase()
        require(normalizedRegionCode.isNotEmpty()) { "Region code must not be empty" }

        val targetReader = reader ?: readerDevice?.rfidReader
            ?: throw IllegalStateException("No reader selected. Discover and connect to a reader first.")
        reader = targetReader

        val supportedRegions = getSupportedRegions(targetReader)
        val regulatoryConfig = buildRegulatoryConfigForRegionCode(normalizedRegionCode, supportedRegions)
            ?: throw IllegalArgumentException(
                "Region $normalizedRegionCode is not supported by this reader. Supported regions: ${describeSupportedRegions(supportedRegions)}"
            )

        try {
            targetReader.Config.setRegulatoryConfig(regulatoryConfig)
            targetReader.PostConnectReaderUpdate()
            setupReader()
            readerInfo = refreshReaderInfo(targetReader)
            clearConnectTimeout()
            reconnectAttempt = 0
            cancelPendingReconnect()
            lastErrorCode = null
            lastErrorMessage = null
            updateConnectionState(
                InternalConnectionState.CONNECTED,
                ReaderConnectionStatus.CONNECTED,
                "Applied reader region $normalizedRegionCode",
            )
            triggerDeviceStatus()
        } catch (e: InvalidUsageException) {
            emitError(ReaderErrorCode.SDK_INVALID_USAGE, "Invalid usage while setting reader region", e.info ?: e.message, e)
            updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Invalid usage while setting reader region", e)
            throw e
        } catch (e: OperationFailureException) {
            emitError(ReaderErrorCode.SDK_OPERATION_FAILURE, "Failed to set reader region", e.vendorMessage ?: e.statusDescription, e)
            updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Failed to set reader region", e)
            throw e
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
            if (scanningEnabledLastToggleMs == 0L) null else scanningEnabledLastToggleMs,
            inventoryActive,
            if (lastInventoryStartTimestamp == 0L) null else lastInventoryStartTimestamp,
            if (lastInventoryStopTimestamp == 0L) null else lastInventoryStopTimestamp,
            pendingPurgeRunnable != null,
            lastInventoryStopReason,
            lastInventoryStartReason
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
        } else {
            val hadStaleInventoryState = inventoryActive || inventoryWatchdogRunnable != null || pendingPurgeRunnable != null
            if (hadStaleInventoryState) {
                Log.w(TAG, "Scanning re-enabled with lingering inventory state; forcing trigger-ready recovery")
            }
            inventoryActive = false
            cancelInventoryWatchdog()
            cancelScheduledPurge()
            if (hadStaleInventoryState) {
                lastInventoryStopTimestamp = System.currentTimeMillis()
                lastInventoryStopReason = "scanning re-enabled recovery"
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

    @Synchronized
    fun startLocating(tags: List<RfidTag>, disableBeep: Boolean) {
        // Reject if a locate session is already active
        if (locateSessionActive) {
            Log.w(TAG, "startLocating rejected: locate session already active")
            throw IllegalStateException("Locate session already active. Call stopLocating() or resetLocateState() first.")
        }

        if (!isReaderConnected()) {
            Log.e(TAG, "startLocating aborted: reader not connected")
            throw IllegalStateException("Reader not connected")
        }

        Log.d(TAG, "startLocating: tags=${tags.size}, disableBeep=$disableBeep")
        
        // Store session parameters
        locateSessionActive = true
        locateTargetTags = tags
        locateDisableBeep = disableBeep
        locatePendingStart = true
        
        // Configure beeper if requested
        if (disableBeep) {
            try {
                // Store original volume to restore later
                locateOriginalBeeperVolume = reader!!.Config.beeperVolume
                Log.d(TAG, "Suppressing beeper (original volume: $locateOriginalBeeperVolume)")
                reader!!.Config.beeperVolume = BEEPER_VOLUME.QUIET_BEEP
            } catch (e: Exception) {
                Log.w(TAG, "Failed to suppress beeper: ${e.message}")
            }
        }
        
        // Wait for any pending purge to complete before starting locate
        waitForPurgeAndStartLocate()
    }

    @Synchronized
    private fun waitForPurgeAndStartLocate() {
        // Cancel any existing purge wait
        locatePurgeCompleteRunnable?.let { mainHandler.removeCallbacks(it) }
        
        // Schedule locate start after purge delay
        val runnable = Runnable {
            synchronized(this) {
                if (!locatePendingStart || !locateSessionActive) {
                    Log.d(TAG, "Locate start cancelled (pending=$locatePendingStart, active=$locateSessionActive)")
                    return@Runnable
                }
                
                Log.d(TAG, "Purge complete, locating ready for trigger")
                locatePendingStart = false
                // Locating will start when trigger is pulled
                // The actual locate operation is triggered in the HANDHELD_TRIGGER_PRESSED handler
            }
        }
        locatePurgeCompleteRunnable = runnable
        mainHandler.postDelayed(runnable, PURGE_TAGS_DELAY_MS)
    }

    @Synchronized
    private fun performLocate() {
        if (!locateSessionActive || locateTargetTags == null) {
            Log.w(TAG, "performLocate aborted: no active session")
            return
        }
        
        if (isLocating) {
            Log.d(TAG, "performLocate: already locating")
            return
        }

        Log.d(TAG, "performLocate: Starting locate operation for ${locateTargetTags!!.size} tags")
        
        isLocating = true
        val multiTagLocateTagMap = ArrayMap<String, String>()
        multiTagLocateTagMap.clear()
        locateTargetTags!!.forEach {
            // NOTE: Calibration RSSI helps achieve accurate distance measurements
            // based on tag types and environment
            multiTagLocateTagMap[it.id] = "-50"
        }
        
        try {
            reader!!.Actions.MultiTagLocate.purgeItemList()
            reader!!.Actions.MultiTagLocate.importItemList(multiTagLocateTagMap)
            reader!!.Actions.MultiTagLocate.perform()
            Log.d(TAG, "Locate operation started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting locate operation: ${e.message}", e)
            isLocating = false
            throw e
        }
    }

    @Synchronized
    private fun internalStopLocateOperation() {
        if (!isLocating) {
            return
        }
        
        try {
            reader!!.Actions.MultiTagLocate.stop()
            isLocating = false
            Log.d(TAG, "Locate operation stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping locate operation: ${e.message}")
            throw e
        }
    }

    @Synchronized
    fun stopLocating() {
        Log.d(TAG, "stopLocating called")
        
        if (!isReaderConnected()) {
            Log.w(TAG, "stopLocating: reader not connected")
            return
        }

        try {
            internalStopLocateOperation()
            
            // Purge the locate item list
            if (isReaderConnected()) {
                reader!!.Actions.MultiTagLocate.purgeItemList()
            }
            
            // Restore beeper if it was disabled
            if (locateDisableBeep && locateOriginalBeeperVolume != null) {
                try {
                    reader!!.Config.beeperVolume = locateOriginalBeeperVolume!!
                    Log.d(TAG, "Beeper restored to original volume: $locateOriginalBeeperVolume")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore beeper: ${e.message}")
                }
            }
            
            // Clean up session state (but keep session active for potential resume)
            locatePendingStart = false
            locatePurgeCompleteRunnable?.let { mainHandler.removeCallbacks(it) }
            locatePurgeCompleteRunnable = null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping locate: ${e.message}", e)
            throw e
        }
    }

    @Synchronized
    fun resetLocateState() {
        Log.d(TAG, "resetLocateState called")
        
        // Stop any active locating first
        if (isLocating) {
            try {
                stopLocating()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping locate during reset: ${e.message}")
            }
        }
        
        // Clear all session state
        locateSessionActive = false
        locateTargetTags = null
        locateDisableBeep = false
        locatePendingStart = false
        locatePurgeCompleteRunnable?.let { mainHandler.removeCallbacks(it) }
        locatePurgeCompleteRunnable = null
        locateOriginalBeeperVolume = null
        isLocating = false
        
        Log.d(TAG, "Locate state reset complete")
    }

    private fun setupReader() {
        if (!reader!!.isConnected) {
            Log.d(TAG, "Reader not connected, connecting...")
            reader!!.connect()
        }
        if (reader!!.isConnected) {
            Log.d(TAG, "Configuring reader...")
            val triggerInfo = TriggerInfo()
            triggerInfo.StartTrigger.triggerType = START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
            triggerInfo.StopTrigger.triggerType = STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE
            try {
                // receive events from reader
                Log.d(TAG, "Setting up event listeners...")
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
                Log.d(TAG, "Event listeners configured")

                // set start and stop triggers
                Log.d(TAG, "Setting trigger mode...")
                reader!!.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
                Log.d(TAG, "Setting start/stop triggers...")
                reader!!.Config.startTrigger = triggerInfo.StartTrigger
                reader!!.Config.stopTrigger = triggerInfo.StopTrigger
                Log.d(TAG, "Triggers configured")


                // set antenna configurations
                Log.d(TAG, "Configuring antenna...")
                val config: Antennas.AntennaRfConfig =
                    reader!!.Config.Antennas.getAntennaRfConfig(1)

                config.setrfModeTableIndex(0)
                config.setTari(0)
                reader!!.Config.Antennas.setAntennaRfConfig(1, config)
                Log.d(TAG, "Antenna RF config set")

                Log.d(TAG, "Configuring singulation control...")
                val s1_singulationControl: Antennas.SingulationControl =
                    reader!!.Config.Antennas.getSingulationControl(1)
                s1_singulationControl.setSession(SESSION.SESSION_S0)
                s1_singulationControl.Action.setInventoryState(INVENTORY_STATE.INVENTORY_STATE_A)
                s1_singulationControl.Action.setSLFlag(SL_FLAG.SL_ALL)
                reader!!.Config.Antennas.setSingulationControl(1, s1_singulationControl)
                Log.d(TAG, "Singulation control configured")

                // delete any prefilters (may not be supported on all readers like TC22)
                try {
                    Log.d(TAG, "Attempting to delete prefilters...")
                    reader!!.Actions.PreFilters.deleteAll()
                    Log.d(TAG, "Prefilters deleted successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not delete prefilters (not supported on this reader): ${e.message}")
                    // This is non-critical, continue anyway
                }

            } catch (e: InvalidUsageException) {
                Log.e(TAG, "InvalidUsageException configuring reader: ${e.message}", e)
                Log.e(TAG, "  Info: ${e.info}")
                throw Error("Error configuring reader: ${e.message}")
            } catch (e: OperationFailureException) {
                Log.e(TAG, "OperationFailureException configuring reader: ${e.message}", e)
                Log.e(TAG, "  Vendor message: ${e.vendorMessage}")
                Log.e(TAG, "  Status description: ${e.statusDescription}")
                throw Error("Error configuring reader: ${e.vendorMessage ?: e.message}")
            } catch (e: Throwable) {
                Log.e(TAG, "Error configuring reader: $e", e)
                throw Error("Error configuring reader: ${e.message}")
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
        val eventType = rfidStatusEvents.StatusEventData.statusEventType
        Log.d(TAG, "Status Notification: $eventType")
        // Verbose diagnostic dump for power/battery investigation
        try {
            val sb = StringBuilder("StatusEventDump type=$eventType")
            try {
                val b = rfidStatusEvents.StatusEventData.BatteryData
                if (b != null) sb.append(" | battery(level=").append(b.level).append(", charging=").append(b.charging).append(", cause=").append(b.cause).append(")")
            } catch (_: Throwable) {}
            // Attempt reflective power telemetry extraction (SDK variant may not expose PowerEventData accessor)
            try {
                val vc = extractPowerTelemetry(rfidStatusEvents)
                if (vc != null) sb.append(" | power(voltage=").append(vc.first).append("mV, current=").append(vc.second).append("mA)")
            } catch (_: Throwable) {}
            try {
                val hh = rfidStatusEvents.StatusEventData.HandheldTriggerEventData
                if (hh != null) sb.append(" | trigger=").append(hh.handheldEvent)
            } catch (_: Throwable) {}
            Log.d(TAG, sb.toString())
        } catch (t: Throwable) {
            Log.d(TAG, "Diagnostic dump error: ${t.message}")
        }
        when (rfidStatusEvents.StatusEventData.statusEventType) {
            STATUS_EVENT_TYPE.BATTERY_EVENT -> {
                val data = rfidStatusEvents.StatusEventData.BatteryData
                val batteryData = BatteryData(data.level.toLong(), data.charging, data.cause)
                lastBatteryLevel = data.level
                lastBatteryCharging = data.charging
                Log.d(
                    TAG,
                    "Battery data - level: ${batteryData.level}, isCharging: ${batteryData.isCharging}, cause: ${batteryData.cause}"
                )
                Handler(Looper.getMainLooper()).post {
                    callbacks.onBatteryDataReceived(batteryData) {}
                }
            }
            STATUS_EVENT_TYPE.POWER_EVENT -> {
                handlePowerEventFallback(rfidStatusEvents, fromExplicitPowerEvent = true)
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
                        
                        // Check if we're in locate mode and ready to start
                        if (locateSessionActive && !locatePendingStart) {
                            // Start locate operation on trigger press
                            Log.d(TAG, "Trigger pressed: Starting locate operation")
                            performLocate()
                        } else if (locateSessionActive && locatePendingStart) {
                            Log.d(TAG, "Trigger pressed: Locate session active but waiting for purge completion")
                        } else {
                            // Normal inventory mode
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
                        }
                    } else {
                        Log.d(TAG, "Handheld trigger released")
                        val elapsed = System.currentTimeMillis() - lastTriggerPressTimestamp
                        
                        // Check if we're in locate mode
                        if (locateSessionActive && isLocating) {
                            Log.d(TAG, "Trigger released: Stopping locate operation")
                            // Stop locate but keep session active for next trigger
                            try {
                                internalStopLocateOperation()
                                Log.d(TAG, "Locate operation stopped (session still active)")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error stopping locate on trigger release: ${e.message}")
                            }
                        } else if (elapsed < INVENTORY_RELEASE_DEBOUNCE_MS) {
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
                // As a fallback, attempt derivation on any event if conditions match and we can see voltage via reflection
                handlePowerEventFallback(rfidStatusEvents, fromExplicitPowerEvent = false)
            }
        }
    }

    private fun extractPowerTelemetry(rfidStatusEvents: RfidStatusEvents): Pair<Int, Int>? {
        return try {
            val statusData = rfidStatusEvents.StatusEventData
            // Attempt direct PowerEventData field
            val directField = statusData::class.java.declaredFields.firstOrNull { it.name.equals("PowerEventData", ignoreCase = true) }
            if (directField != null) {
                directField.isAccessible = true
                val powerObj = directField.get(statusData)
                if (powerObj != null) {
                    val voltageField = powerObj::class.java.declaredFields.firstOrNull { it.name.equals("voltage", true) }
                    val currentField = powerObj::class.java.declaredFields.firstOrNull { it.name.equals("current", true) }
                    if (voltageField != null && currentField != null) {
                        voltageField.isAccessible = true
                        currentField.isAccessible = true
                        val vRaw = voltageField.get(powerObj)
                        val cRaw = currentField.get(powerObj)
                        if (vRaw is Int) {
                            val c = if (cRaw is Int) cRaw else 0
                            return vRaw to c
                        }
                    }
                }
            }
            // Fallback scan
            for (f in statusData::class.java.declaredFields) {
                try {
                    f.isAccessible = true
                    val nested = f.get(statusData) ?: continue
                    val fields = nested::class.java.declaredFields
                    var v: Int? = null
                    var c: Int? = null
                    for (nf in fields) {
                        if (nf.name.equals("voltage", true)) {
                            nf.isAccessible = true
                            val vRaw = nf.get(nested)
                            if (vRaw is Int) v = vRaw
                        } else if (nf.name.equals("current", true)) {
                            nf.isAccessible = true
                            val cRaw = nf.get(nested)
                            if (cRaw is Int) c = cRaw
                        }
                    }
                    if (v != null) {
                        return v!! to (c ?: 0)
                    }
                } catch (_: Throwable) { }
            }
            null
        } catch (t: Throwable) {
            Log.d(TAG, "extractPowerTelemetry error: ${t.message}")
            null
        }
    }

    private fun handlePowerEventFallback(rfidStatusEvents: RfidStatusEvents, fromExplicitPowerEvent: Boolean) {
        try {
            if (lastBatteryCharging || (lastBatteryLevel != null && lastBatteryLevel != 0)) return
            val vc = extractPowerTelemetry(rfidStatusEvents) ?: return
            val (voltageMv, currentMa) = vc
            val derived = estimateBatteryPercentFromVoltageMv(voltageMv)
            val eventLabel = if (fromExplicitPowerEvent) "POWER_EVENT" else "POWER_FALLBACK"
            val causeLabel = if (fromExplicitPowerEvent) "derivedFromVoltage" else "derivedFromVoltageFallback"
            Log.d(
                TAG,
                "$eventLabel derive battery: voltage=${voltageMv}mV current=${currentMa}mA -> $derived% (original=${lastBatteryLevel})"
            )
            val synthetic = BatteryData(derived.toLong(), false, causeLabel)
            Handler(Looper.getMainLooper()).post { callbacks.onBatteryDataReceived(synthetic) {} }
        } catch (t: Throwable) {
            Log.d(TAG, "handlePowerEventFallback error: ${t.message}")
        }
    }

    @Synchronized
    fun performInventory(reason: String) {
        // Legacy direct call retained (now guarded via safeStartInventory). Prefer safeStartInventory.
        if (!isReaderConnected()) return
        if (inventoryActive) {
            Log.d(TAG, "performInventory() called but inventory already active; ignoring")
            return
        }
        // Set state eagerly before dispatching so duplicate trigger events are blocked immediately.
        inventoryActive = true
        lastInventoryStartTimestamp = System.currentTimeMillis()
        lastTagReadTimestamp = lastInventoryStartTimestamp
        lastInventoryStartReason = reason
        scheduleInventoryWatchdog()
        // Dispatch blocking SDK I/O to background thread — calling perform() on the main or SDK
        // callback thread causes RFID_API_COMMAND_TIMEOUT.
        val readerRef = reader!!
        ioExecutor.submit {
            try {
                readerRef.Actions.Inventory.perform()
                Log.d(TAG, "Inventory started (performInventory)")
            } catch (e: InvalidUsageException) {
                synchronized(this) {
                    inventoryActive = false
                    cancelInventoryWatchdog()
                }
                Log.d(TAG, "InvalidUsageException starting inventory: ${e.message}")
            } catch (e: OperationFailureException) {
                synchronized(this) {
                    inventoryActive = false
                    cancelInventoryWatchdog()
                }
                Log.d(TAG, "OperationFailureException starting inventory: ${e.message}")
            } catch (t: Throwable) {
                synchronized(this) {
                    inventoryActive = false
                    cancelInventoryWatchdog()
                }
                Log.d(TAG, "Unexpected error starting inventory: ${t.message}")
            }
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
        // Update state eagerly so watchdog and duplicate stop calls are blocked immediately.
        inventoryActive = false
        lastInventoryStopTimestamp = System.currentTimeMillis()
        cancelInventoryWatchdog()
        // Dispatch blocking SDK I/O to background thread — calling stop() on the main thread
        // (e.g. from the watchdog Handler) causes RFID_API_COMMAND_TIMEOUT.
        val readerRef = reader!!
        ioExecutor.submit {
            try {
                readerRef.Actions.Inventory.stop()
                Log.d(TAG, "Inventory stopped (stopInventory)")
            } catch (e: InvalidUsageException) {
                Log.w(TAG, "Inventory stop failed with InvalidUsageException: ${e.message}")
            } catch (e: OperationFailureException) {
                Log.w(TAG, "Inventory stop failed with OperationFailureException: ${e.message}")
            } catch (t: Throwable) {
                Log.w(TAG, "Inventory stop failed unexpectedly: ${t.message}")
            }
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
        performInventory(reason)
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
        lastInventoryStopReason = reason
        stopInventory()
        schedulePurgeTags()
    }

    @Synchronized
    private fun schedulePurgeTags() {
        cancelScheduledPurge()
        val runnable = Runnable {
            if (!isReaderConnected()) return@Runnable
            Log.d(TAG, "Purging tags after delay (${PURGE_TAGS_DELAY_MS}ms)")
            // Dispatch blocking purgeTags() off the main thread.
            val readerRef = reader ?: return@Runnable
            ioExecutor.submit {
                try {
                    readerRef.Actions?.purgeTags()
                } catch (t: Throwable) {
                    Log.d(TAG, "Error purging tags: ${t.message}")
                }
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