package nz.calo.flutter_zebra_rfid.rfid

import BatteryData
import BatteryDataSource
import FlutterZebraRfidCallbacks
import Reader
import ReaderBeeperVolume
import ReaderConfig
import ReaderConfigBatchMode
import ReaderConnectionStatus
import ReaderConnectionType
import ReaderInfo
import ReaderInventorySession
import ReaderRegion
import RfidTag
import ReaderErrorCode
import ReaderError
import Diagnostics
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.ArrayMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import android.util.Log
import android.os.Build
import androidx.core.content.ContextCompat
import com.zebra.rfid.api3.ACCESS_OPERATION_STATUS
import com.zebra.rfid.api3.Antennas
import com.zebra.rfid.api3.BATCH_MODE
import com.zebra.rfid.api3.BatteryStatistics
import com.zebra.rfid.api3.BEEPER_VOLUME
import com.zebra.rfid.api3.DYNAMIC_POWER_OPTIMIZATION
import com.zebra.rfid.api3.ENUM_NEW_KEYLAYOUT_TYPE
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
import com.zebra.rfid.api3.READER_POWER_STATE
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
import com.zebra.rfid.api3.UNIQUE_TAG_REPORT_SETTING
import nz.calo.flutter_zebra_rfid.capture.RfidLifecycleCompletion
import nz.calo.flutter_zebra_rfid.capture.RfidLifecycleGate
import nz.calo.flutter_zebra_rfid.capture.RfidLifecycleRequest


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
            ENUM_TRANSPORT.SERVICE_SERIAL,
            ENUM_TRANSPORT.SERVICE_USB,
            ENUM_TRANSPORT.BLUETOOTH,
        )
    }
}

enum class CaptureDeviceBarcodeTriggerTarget {
    NONE,
    TERMINAL_IMAGER,
    RFD_BARCODE_ENGINE,
}

internal fun batteryDataFromStatistics(statistics: BatteryStatistics): BatteryData? {
    val percentage = statistics.percentage.takeIf { it in 0..100 } ?: return null
    return BatteryData(
        level = percentage.toLong(),
        isCharging = statistics.charging > 0,
        cause = "Zebra PP+ battery statistics",
        source = BatteryDataSource.READER_STATISTICS,
        isPercentageEstimated = false,
        healthPercentage = statistics.health.takeIf { it in 0..100 }?.toLong(),
        cycleCount = statistics.cycleCount.takeIf { it >= 0 }?.toLong(),
    )
}

internal fun batteryDataFromReaderEvent(
    level: Int,
    isCharging: Boolean,
    cause: String,
    previous: BatteryData?,
): BatteryData {
    val statistics = previous?.takeIf { it.source == BatteryDataSource.READER_STATISTICS }
    return BatteryData(
        level = statistics?.level ?: level.coerceIn(0, 100).toLong(),
        isCharging = isCharging,
        cause = statistics?.cause ?: cause,
        source = statistics?.source ?: BatteryDataSource.READER_EVENT,
        isPercentageEstimated = false,
        healthPercentage = previous?.healthPercentage,
        cycleCount = previous?.cycleCount,
    )
}

internal fun readerPowerStateLabel(state: READER_POWER_STATE): String {
    return when (state) {
        READER_POWER_STATE.POWER_STATE_OFF -> "off"
        READER_POWER_STATE.POWER_STATE_STANDBY -> "standby"
        READER_POWER_STATE.POWER_STATE_ACTIVE -> "active"
        READER_POWER_STATE.POWER_STATE_RF_ACTIVE -> "rfActive"
        READER_POWER_STATE.POWER_STATE_BT_OFF -> "bluetoothOff"
        else -> "unknown"
    }
}

private data class ReaderPowerStateDiagnostic(
    val state: String?,
    val error: String? = null,
)

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

internal fun buildInventoryTriggerInfo(): TriggerInfo {
    return TriggerInfo().apply {
        StartTrigger.triggerType = START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
        StopTrigger.triggerType = STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_HANDHELD_WITH_TIMEOUT
        StopTrigger.Handheld.handheldTriggerEvent = HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED
        StopTrigger.Handheld.handheldTriggerTimeout = 30_000
    }
}

private fun isReaderUnavailableDuringConnect(error: OperationFailureException): Boolean {
    return error.results == RFIDResults.RFID_COMM_OPEN_ERROR ||
        error.results == RFIDResults.RFID_COMM_NO_CONNECTION ||
        error.results == RFIDResults.RFID_INVALID_SOCKET ||
        error.results == RFIDResults.RFID_RECONNECT_FAILED
}

internal enum class RfidConnectionOwnership {
    LEGACY,
    CAPTURE_DEVICE,
}

internal class CaptureDeviceOwnershipException :
    IllegalStateException("CAPTURE_DEVICE_OWNS_CONNECTION")

class RFIDReaderInterface(
    private var callbacks: FlutterZebraRfidCallbacks,
    private var applicationContext: Context
) : RfidEventsListener, RFIDReaderEventHandler {

    private val TAG: String = "FlutterZebraRfidPlugin"
    private val DEBUG = false // Enable verbose logging for troubleshooting

    private var readers: Readers? = null
    private val discoveryReaderManagers = mutableListOf<Readers>()
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
    @Volatile private var recoveryVerificationScanEnabled: Boolean = false
    private var scanningEnabledLastToggleMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    // --- Inventory / trigger guarding ---
    @Volatile private var inventoryActive: Boolean = false
    private var lastInventoryStartTimestamp: Long = 0L
    private var lastInventoryStopTimestamp: Long = 0L
    private var lastInventoryStartReason: String? = null
    private var lastInventoryStopReason: String? = null
    private var pendingPurgeRunnable: Runnable? = null
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
    private val lifecycleGate = RfidLifecycleGate()
    private var connectionOwnership = RfidConnectionOwnership.LEGACY
    private var managedHardwareIdentity: String? = null
    private var captureDeviceControlsBarcode = false
    private var captureDeviceBarcodeTriggerTarget =
        CaptureDeviceBarcodeTriggerTarget.NONE
    @Volatile private var captureDeviceTriggerRearmPending = false
    @Volatile private var captureDeviceTriggerPressObserved = false
    private var recoveryProbeStopRunnable: Runnable? = null
    private var eventsBoundReader: RFIDReader? = null
    private var consecutiveCommandTimeouts = 0
    private var commandTimeoutRecoveryRequested = false

    private val CONNECT_TIMEOUT_MS = 10_000L
    private val RETRY_BACKOFF_MS = 2_000L
    private val MAX_CONNECT_ATTEMPTS = 2 // initial + 1 retry
    private val DEVICE_STATUS_RETRY_DELAY_MS = 500L
    private val MAX_DEVICE_STATUS_ATTEMPTS = 3

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

    private var lastBatteryData: BatteryData? = null
    private var usbConnectionReceiverRegistered = false
    var readersChangedListener: (() -> Unit)? = null
    var connectionStatusListener: ((ReaderConnectionStatus) -> Unit)? = null
    var connectionErrorListener: ((ReaderError) -> Unit)? = null
    var managedRecoveryRequestListener: ((String) -> Unit)? = null
    var managedReadinessActivityListener: (() -> Unit)? = null

    private fun emitBatteryData(data: BatteryData) {
        lastBatteryData = data
        Handler(Looper.getMainLooper()).post {
            callbacks.onBatteryDataReceived(data) {}
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
                beginAsyncConnect(
                    readerId,
                    isRetry = reconnectAttempt > 1,
                    fromAutoReconnect = true,
                )
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
        if (newState == InternalConnectionState.CONNECTED || newState == InternalConnectionState.DISCONNECTED) {
            lastErrorCode = null
            lastErrorMessage = null
        }
        if (newState == InternalConnectionState.CONNECTED) {
            recordResponsiveReaderActivity()
        }
        internalState = newState
        externalStatus?.let { status ->
            mainHandler.post {
                callbacks.onReaderConnectionStatusChanged(status) {}
                connectionStatusListener?.invoke(status)
            }
        }
    }

    @Synchronized
    private fun handleUnexpectedReaderDisconnect(reason: String) {
        if (internalState == InternalConnectionState.DISCONNECTED) {
            Log.d(TAG, "Unexpected disconnect ignored; reader already disconnected (reason=$reason)")
            return
        }
        Log.d(TAG, "Unexpected disconnect ($reason); initiating auto-reconnect sequence")
        lastDisconnectTimestamp = System.currentTimeMillis()
        unexpectedDisconnectCount += 1
        if (inventoryActive) {
            inventoryActive = false
            lastInventoryStopTimestamp = lastDisconnectTimestamp
            lastInventoryStopReason = reason
        }
        cancelInventoryWatchdog()
        cancelScheduledPurge()
        cancelRecoveryProbeStop()
        recoveryVerificationScanEnabled = false
        updateConnectionState(
            InternalConnectionState.DISCONNECTED,
            ReaderConnectionStatus.DISCONNECTED,
            "Reader disconnected: $reason",
        )
        if (connectionOwnership == RfidConnectionOwnership.CAPTURE_DEVICE) {
            val disconnectedReader = reader
            captureDeviceTriggerRearmPending = captureDeviceControlsBarcode
            captureDeviceTriggerPressObserved = false
            lifecycleGate.invalidate()
            if (!lifecycleGate.hasActiveOperation()) {
                // The Zebra SDK keeps an event-notification worker attached to
                // each RFIDReader instance. Retire the disconnected instance on
                // the serialized I/O executor before rediscovery can replace it,
                // otherwise repeated recovery cycles accumulate workers that
                // compete for the same reader events.
                ioExecutor.submit {
                    disconnectedReader?.let {
                        retireReaderSession(
                            targetReader = it,
                            reason = "physical reader disconnect",
                            disposeBeforeRediscovery = true,
                        )
                    }
                    mainHandler.post {
                        managedRecoveryRequestListener?.invoke(
                            "physical_reader_disconnect",
                        )
                    }
                }
            }
        } else {
            scheduleAutoReconnect(reason)
        }
    }

    private fun emitError(code: ReaderErrorCode, message: String, details: String? = null, throwable: Throwable? = null) {
        Log.e(TAG, "[ReaderError][$code] $message ${details ?: ""} ${throwable?.message ?: ""}")
        val err = ReaderError(code, message, details ?: throwable?.message)
        lastErrorCode = code
        lastErrorMessage = message
        mainHandler.post {
            callbacks.onReaderConnectionError(err) {}
            connectionErrorListener?.invoke(err)
        }
    }

    private fun retireReaderSession(
        targetReader: RFIDReader,
        reason: String,
        disposeBeforeRediscovery: Boolean = false,
    ) {
        Log.d(TAG, "Retiring RFID reader session: $reason")
        runCatching {
            targetReader.Events.removeEventsListener(this)
        }.onFailure {
            Log.d(TAG, "RFID event listener was already detached while retiring: $reason")
        }
        runCatching {
            // Zebra requires disconnect after a connection attempt even when
            // isConnected is stale or false.
            targetReader.disconnect()
        }.onFailure {
            Log.w(TAG, "Failed to disconnect RFID session while retiring: $reason", it)
        }
        if (disposeBeforeRediscovery) {
            runCatching {
                targetReader.Dispose()
                Log.d(TAG, "Disposed retired RFID session before rediscovery: $reason")
            }.onFailure {
                Log.w(
                    TAG,
                    "Failed to dispose retired RFID session before rediscovery: $reason",
                    it,
                )
            }
        }
        synchronized(this) {
            if (eventsBoundReader === targetReader) {
                eventsBoundReader = null
            }
            if (reader === targetReader) {
                reader = null
            }
        }
    }

    private val usbConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = usbDeviceFromIntent(intent) ?: return
            handleUsbDeviceConnectionEvent(
                action = intent.action,
                vendorId = device.vendorId,
                productName = device.productName,
                manufacturerName = device.manufacturerName,
            )
        }
    }

    init {
        Log.d(TAG, "Initializing RFID SDK...")
        Readers.attach(this)
        registerUsbConnectionReceiver()
    }

    private fun registerUsbConnectionReceiver() {
        if (usbConnectionReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            applicationContext.registerReceiver(
                usbConnectionReceiver,
                filter,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(usbConnectionReceiver, filter)
        }
        usbConnectionReceiverRegistered = true
    }

    @Suppress("DEPRECATION")
    private fun usbDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    internal fun handleUsbDeviceConnectionEvent(
        action: String?,
        vendorId: Int,
        productName: String?,
        manufacturerName: String?,
    ) {
        if (
            action != UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            action != UsbManager.ACTION_USB_DEVICE_DETACHED
        ) {
            return
        }
        if (internalState != InternalConnectionState.CONNECTED) {
            Log.d(TAG, "Ignoring USB event while RFID state is $internalState")
            return
        }
        if (!activeReaderUsesLocalUsbTransport()) {
            Log.d(TAG, "Ignoring USB event for non-USB RFID session")
            return
        }

        val selectedReaderName = readerDevice?.name.orEmpty()
        val isZebraRfd =
            vendorId == ZEBRA_USB_VENDOR_ID &&
                listOf(productName, selectedReaderName).any {
                    it?.trim()?.uppercase()?.startsWith("RFD") == true
                }
        if (!isZebraRfd) {
            Log.d(
                TAG,
                "Ignoring unrelated USB event action=$action vendor=$vendorId " +
                    "manufacturer=$manufacturerName product=$productName",
            )
            return
        }

        val reason = when (action) {
            UsbManager.ACTION_USB_DEVICE_DETACHED -> "RFD USB device detached"
            else -> "RFD USB device reattached after silent transport reset"
        }
        Log.w(
            TAG,
            "Detected $reason (vendor=$vendorId manufacturer=$manufacturerName " +
                "product=$productName reader=$selectedReaderName)",
        )
        handleUnexpectedReaderDisconnect(reason)
    }

    private fun activeReaderUsesLocalUsbTransport(): Boolean {
        val device = readerDevice ?: return false
        val transportHints = listOf(
            runCatching { device.address }.getOrNull(),
            runCatching { device.transport }.getOrNull(),
            runCatching { reader?.transport }.getOrNull(),
        )
        return transportHints.any { hint ->
            val normalized = hint?.trim()?.uppercase().orEmpty()
            normalized.contains("USB") || normalized.contains("SERIAL")
        } || currentConnectionType == ReaderConnectionType.USB
    }

    fun getAvailableReaderList(
        connectionType: ReaderConnectionType
    ) {
        Log.i(TAG, "========== READER DISCOVERY STARTED ==========")
        Log.i(TAG, "Requested connection type: $connectionType")
        if (
            internalState == InternalConnectionState.CONNECTED ||
            internalState == InternalConnectionState.CONNECTING
        ) {
            Log.i(
                TAG,
                "RFID discovery refresh joined existing $internalState session",
            )
            val snapshot = availableReadersSnapshot()
            callbacks.onAvailableReadersChanged(snapshot) {}
            readersChangedListener?.invoke()
            return
        }
        disposeDiscoveryReaderManagers("before RFID rediscovery")
        val preferLocalTransports = shouldPreferLocalTransports(connectionType)
        if (preferLocalTransports) {
            Log.i(TAG, "Zebra terminal detected; preferring local RFID transports before Bluetooth fallback")
        }

        val transports = readerConnectionTypeToDiscoveryTransports(connectionType).filter { transport ->
            if (transport != ENUM_TRANSPORT.BLUETOOTH || hasBluetoothDiscoveryPermission()) {
                true
            } else {
                Log.w(TAG, "Skipping Bluetooth RFID discovery because Bluetooth permission is missing")
                false
            }
        }
        Log.i(TAG, "Using SDK transports: ${transports.joinToString()}")

        try {
            val mergedDevices = arrayListOf<ReaderDevice>()
            val seenKeys = linkedSetOf<String>()
            var primaryReaders: Readers? = null
            val createdManagers = mutableListOf<Readers>()
            val discoveryFailures = mutableListOf<String>()

            for (transport in transports) {
                try {
                    Log.d(TAG, "Creating Readers instance with transport: $transport")
                    val transportReaders = Readers(applicationContext, transport)
                    createdManagers.add(transportReaders)

                    Log.d(TAG, "Calling GetAvailableRFIDReaderList() for transport=$transport")
                    val discoveredDevices = transportReaders.GetAvailableRFIDReaderList() ?: arrayListOf()
                    Log.i(TAG, "Transport $transport discovered ${discoveredDevices.size} reader(s)")
                    if (primaryReaders == null && discoveredDevices.isNotEmpty()) {
                        // Keep the manager that actually owns the selected
                        // ReaderDevice, rather than the first empty transport.
                        primaryReaders = transportReaders
                    }

                    discoveredDevices.forEach { device ->
                        val deviceKey = buildReaderDiscoveryKey(device)
                        if (seenKeys.add(deviceKey)) {
                            mergedDevices.add(device)
                        } else {
                            Log.d(TAG, "Skipping duplicate reader from transport=$transport key=$deviceKey")
                        }
                    }
                    if (
                        preferLocalTransports &&
                        transport != ENUM_TRANSPORT.BLUETOOTH &&
                        mergedDevices.isNotEmpty()
                    ) {
                        Log.i(TAG, "Local RFID reader found; suppressing Bluetooth fallback for this discovery pass")
                        break
                    }
                } catch (transportError: Exception) {
                    val failure = "$transport: ${transportError.javaClass.simpleName}: ${transportError.message}"
                    discoveryFailures.add(failure)
                    Log.e(TAG, "Discovery failed for transport=$transport", transportError)
                }
            }

            discoveryReaderManagers.addAll(createdManagers)
            readers = primaryReaders ?: createdManagers.firstOrNull()
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
                Reader(
                    reader.name,
                    index.toLong(),
                    null,
                    buildReaderDiscoveryKey(reader),
                )
            }
            callbacks.onAvailableReadersChanged(readers) {}
            readersChangedListener?.invoke()

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

    private fun disposeDiscoveryReaderManagers(reason: String) {
        if (discoveryReaderManagers.isEmpty()) {
            readers = null
            return
        }
        discoveryReaderManagers.forEach { manager ->
            runCatching { manager.Dispose() }
                .onFailure {
                    Log.w(TAG, "Failed to dispose RFID discovery manager: $reason", it)
                }
        }
        Log.d(
            TAG,
            "Disposed ${discoveryReaderManagers.size} RFID discovery manager(s): $reason",
        )
        discoveryReaderManagers.clear()
        readers = null
    }

    private fun buildReaderDiscoveryKey(device: ReaderDevice): String {
        val address = runCatching { device.address }.getOrNull()?.trim().orEmpty()
        val name = device.name?.trim().orEmpty()
        return listOf(name, address)
            .filter { it.isNotEmpty() }
            .joinToString("|")
            .ifEmpty { device.toString() }
    }

    private fun hasBluetoothDiscoveryPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.BLUETOOTH,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun shouldPreferLocalTransports(connectionType: ReaderConnectionType): Boolean {
        if (connectionType != ReaderConnectionType.ALL) return false
        val manufacturer = Build.MANUFACTURER.orEmpty().uppercase()
        val model = Build.MODEL.orEmpty().uppercase()
        val product = Build.PRODUCT.orEmpty().uppercase()
        val device = Build.DEVICE.orEmpty().uppercase()
        val isZebra = manufacturer.contains("ZEBRA") || manufacturer.contains("MOTOROLA")
        val isTcSeries = listOf(model, product, device).any {
            it.startsWith("TC") || it.contains("TC22") || it.contains("TC27")
        }
        return isZebra || isTcSeries
    }

    fun connectReader(readerId: Long): ReaderInfo? =
        connectReaderInternal(
            readerId = readerId,
            ownership = RfidConnectionOwnership.LEGACY,
            hardwareIdentity = null,
        )

    fun connectReaderForCaptureDevice(
        readerId: Long,
        hardwareIdentity: String,
        barcodeTriggerTarget: CaptureDeviceBarcodeTriggerTarget,
    ): ReaderInfo? =
        connectReaderInternal(
            readerId = readerId,
            ownership = RfidConnectionOwnership.CAPTURE_DEVICE,
            hardwareIdentity = hardwareIdentity,
            barcodeTriggerTarget = barcodeTriggerTarget,
        )

    @Synchronized
    private fun connectReaderInternal(
        readerId: Long,
        ownership: RfidConnectionOwnership,
        hardwareIdentity: String?,
        barcodeTriggerTarget: CaptureDeviceBarcodeTriggerTarget =
            CaptureDeviceBarcodeTriggerTarget.NONE,
    ): ReaderInfo? {
        Log.i(TAG, "========== CONNECT READER STARTED ==========")
        Log.i(TAG, "Requested reader ID: $readerId")

        if (
            ownership == RfidConnectionOwnership.LEGACY &&
            connectionOwnership == RfidConnectionOwnership.CAPTURE_DEVICE
        ) {
            emitError(
                ReaderErrorCode.CAPTURE_DEVICE_OWNS_CONNECTION,
                "Capture Device owns the RFID connection",
            )
            throw CaptureDeviceOwnershipException()
        }

        if (ownership == RfidConnectionOwnership.CAPTURE_DEVICE) {
            val identity = requireNotNull(hardwareIdentity)
            val controlsBarcode =
                barcodeTriggerTarget != CaptureDeviceBarcodeTriggerTarget.NONE
            connectionOwnership = ownership
            managedHardwareIdentity = identity
            captureDeviceControlsBarcode = controlsBarcode
            captureDeviceBarcodeTriggerTarget = barcodeTriggerTarget
            captureDeviceTriggerRearmPending = controlsBarcode
            captureDeviceTriggerPressObserved = false
            autoReconnectEnabled = false
        } else {
            connectionOwnership = ownership
            autoReconnectEnabled = true
        }
        
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
                val managedGeneration =
                    managedLifecycleGenerationForRearm(ownership, hardwareIdentity)
                        ?: if (ownership == RfidConnectionOwnership.CAPTURE_DEVICE) {
                            return null
                        } else {
                            null
                        }
                return rearmConnectedReaderSession(
                    existing,
                    "Reader $readerId already connected (idempotent connect)",
                    managedGeneration,
                    hardwareIdentity,
                )
            }
        }

        if (internalState == InternalConnectionState.CONNECTING) {
            if (ownership == RfidConnectionOwnership.CAPTURE_DEVICE) {
                Log.d(TAG, "Capture Device readiness request joined active RFID connection")
                return null
            }
            emitError(ReaderErrorCode.ALREADY_CONNECTING, "Connect already in progress; ignoring duplicate request")
            Log.d(TAG, "Connect already in progress; ignoring duplicate request")
            return null
        }

        readerDevice = list[readerId.toInt()]
        lastBatteryData = null
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
        
        val previousReader = reader?.takeIf { it !== targetReader }
        reader = targetReader
        Log.d(TAG, "RFIDReader object obtained: $targetReader")

        if (targetReader.isConnected) {
            val managedGeneration =
                managedLifecycleGenerationForRearm(ownership, hardwareIdentity)
                    ?: if (ownership == RfidConnectionOwnership.CAPTURE_DEVICE) {
                        return null
                    } else {
                        null
                    }
            return rearmConnectedReaderSession(
                targetReader,
                "Reader already physically connected",
                managedGeneration,
                hardwareIdentity,
            )
        }

        val managedGeneration = if (ownership == RfidConnectionOwnership.CAPTURE_DEVICE) {
            when (val request = lifecycleGate.request(requireNotNull(hardwareIdentity))) {
                is RfidLifecycleRequest.Joined -> {
                    Log.d(
                        TAG,
                        "Capture Device readiness request joined generation ${request.generation}",
                    )
                    return null
                }
                is RfidLifecycleRequest.Start -> request.generation
            }
        } else {
            null
        }
        
        Log.i(TAG, "Reader not connected, starting connection sequence...")

        // New attempt sequence
        connectAttempt = 1
        totalConnectAttemptsCounter += 1
        Log.d(TAG, "Connect attempt: $connectAttempt, Total attempts: $totalConnectAttemptsCounter")
        beginAsyncConnect(
            readerId = readerId,
            managedGeneration = managedGeneration,
            hardwareIdentity = hardwareIdentity,
            readerToRetire = previousReader,
        )
        return null // async result
    }

    @Synchronized
    private fun beginAsyncConnect(
        readerId: Long,
        isRetry: Boolean = false,
        fromAutoReconnect: Boolean = false,
        managedGeneration: Long? = null,
        hardwareIdentity: String? = null,
        readerToRetire: RFIDReader? = null,
    ) {
        val targetReader = reader ?: return
        
        val attemptType = if (isRetry) "Retrying" else "Starting"
        Log.i(TAG, "$attemptType connection attempt #$connectAttempt to readerId=$readerId")
        Log.i(TAG, "  Reader name: ${readerDevice?.name}")
        Log.i(TAG, "  Reader object: $targetReader")
        
        updateConnectionState(InternalConnectionState.CONNECTING, ReaderConnectionStatus.CONNECTING, "$attemptType connection attempt #$connectAttempt to readerId=$readerId (${readerDevice?.name})")
        lastConnectStartTimestamp = System.currentTimeMillis()
        scheduleConnectTimeout(readerId, connectAttempt, managedGeneration)
        
        Log.d(TAG, "Launching blocking connect() call on background thread...")
        // Launch blocking connect off main thread
        pendingConnectFuture = ioExecutor.submit {
            try {
                readerToRetire?.let {
                    retireReaderSession(
                        targetReader = it,
                        reason = "before replacement RFID connection",
                    )
                }
                Log.d(TAG, "Calling targetReader.connect()...")
                connectWithRegionRecovery(targetReader)
                Log.i(TAG, "targetReader.connect() completed successfully!")
                if (managedGeneration != null && hardwareIdentity != null) {
                    when (
                        lifecycleGate.completeSuccess(
                            managedGeneration,
                            hardwareIdentity,
                        )
                    ) {
                        RfidLifecycleCompletion.TERMINATE_STALE_SESSION -> {
                            Log.w(
                                TAG,
                                "Late RFID connection generation $managedGeneration must be terminated before retry",
                            )
                            retireReaderSession(
                                targetReader = targetReader,
                                reason = "stale late RFID connection",
                            )
                            synchronized(this) {
                                clearConnectTimeout()
                                lifecycleGate.completeTermination(managedGeneration)
                                updateConnectionState(
                                    InternalConnectionState.DISCONNECTED,
                                    ReaderConnectionStatus.DISCONNECTED,
                                    "Late RFID session terminated",
                                )
                            }
                            mainHandler.post {
                                managedRecoveryRequestListener?.invoke("late_session_terminated")
                            }
                            return@submit
                        }
                        RfidLifecycleCompletion.ADOPT_SESSION -> {
                            Log.d(
                                TAG,
                                "Adopting RFID connection generation $managedGeneration",
                            )
                        }
                        else -> return@submit
                    }
                } else {
                    synchronized(this) {
                        if (internalState != InternalConnectionState.CONNECTING) {
                            Log.w(
                                TAG,
                                "Connection succeeded but state changed to $internalState; terminating stale session",
                            )
                            runCatching {
                                if (targetReader.isConnected) targetReader.disconnect()
                            }
                            return@submit
                        }
                    }
                }
                // The timeout only observes the blocking SDK call. Reader setup can
                // legitimately complete after that diagnostic deadline.
                synchronized(this) {
                    clearConnectTimeout()
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
                    lastConnectDurationMs = System.currentTimeMillis() - lastConnectStartTimestamp
                    updateConnectionState(InternalConnectionState.CONNECTED, ReaderConnectionStatus.CONNECTED, "Reader connected (attempt #$connectAttempt)")
                }
                triggerDeviceStatus()
            } catch (e: InvalidUsageException) {
                synchronized(this) {
                    clearConnectTimeout()
                    Log.e(TAG, "InvalidUsageException during connect: ${e.message}", e)
                    Log.e(TAG, "  Info: ${e.info}")
                    if (managedGeneration != null) {
                        lifecycleGate.completeFailure(managedGeneration)
                        updateConnectionState(
                            InternalConnectionState.DISCONNECTED,
                            ReaderConnectionStatus.DISCONNECTED,
                            "Managed RFID connection failed",
                            e,
                        )
                        mainHandler.post {
                            managedRecoveryRequestListener?.invoke("rfid_session_lost")
                        }
                        return@submit
                    }
                    if (internalState != InternalConnectionState.CONNECTING) {
                        Log.d(TAG, "Ignoring connect failure because state is $internalState", e)
                        return@submit
                    }
                    if (fromAutoReconnect) {
                        updateConnectionState(
                            InternalConnectionState.DISCONNECTED,
                            ReaderConnectionStatus.DISCONNECTED,
                            "Auto-reconnect failed; reader remains disconnected",
                            e,
                        )
                        scheduleAutoReconnect("auto-reconnect invalid usage")
                        return@submit
                    }
                    emitError(ReaderErrorCode.SDK_INVALID_USAGE, "Invalid usage while connecting", e.message, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Invalid usage while connecting", e)
                }
            } catch (e: ReaderRegionConfigurationException) {
                synchronized(this) {
                    clearConnectTimeout()
                    Log.e(TAG, "Reader region configuration required during connect: ${e.message}", e)
                    if (managedGeneration != null) {
                        lifecycleGate.completeFailure(managedGeneration)
                        emitError(ReaderErrorCode.SDK_OPERATION_FAILURE, "Reader region is not configured", e.message, e)
                        updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Reader region is not configured", e)
                        mainHandler.post {
                            managedRecoveryRequestListener?.invoke("configuration_retry")
                        }
                        return@submit
                    }
                    if (internalState != InternalConnectionState.CONNECTING) {
                        Log.d(TAG, "Ignoring connect failure because state is $internalState", e)
                        return@submit
                    }
                    emitError(ReaderErrorCode.SDK_OPERATION_FAILURE, "Reader region is not configured", e.message, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Reader region is not configured", e)
                }
            } catch (e: OperationFailureException) {
                synchronized(this) {
                    clearConnectTimeout()
                    val readerUnavailable = isReaderUnavailableDuringConnect(e)
                    if (readerUnavailable) {
                        Log.i(TAG, "Reader unavailable during connect: ${e.results} (${e.statusDescription})")
                    } else {
                        Log.e(TAG, "OperationFailureException during connect: ${e.message}", e)
                        Log.e(TAG, "  Vendor message: ${e.vendorMessage}")
                        Log.e(TAG, "  Status description: ${e.statusDescription}")
                        Log.e(TAG, "  Results: ${e.results}")
                    }
                    if (managedGeneration != null) {
                        lifecycleGate.completeFailure(managedGeneration)
                        updateConnectionState(
                            InternalConnectionState.DISCONNECTED,
                            ReaderConnectionStatus.DISCONNECTED,
                            "Managed RFID connection failed",
                            e,
                        )
                        mainHandler.post {
                            managedRecoveryRequestListener?.invoke("rfid_session_lost")
                        }
                        return@submit
                    }
                    if (internalState != InternalConnectionState.CONNECTING) {
                        Log.d(TAG, "Ignoring connect failure because state is $internalState", e)
                        return@submit
                    }
                    if (readerUnavailable) {
                        updateConnectionState(
                            InternalConnectionState.DISCONNECTED,
                            ReaderConnectionStatus.DISCONNECTED,
                            "Reader unavailable while connecting",
                        )
                        return@submit
                    }
                    if (fromAutoReconnect) {
                        updateConnectionState(
                            InternalConnectionState.DISCONNECTED,
                            ReaderConnectionStatus.DISCONNECTED,
                            "Auto-reconnect failed; reader remains disconnected",
                            e,
                        )
                        scheduleAutoReconnect("auto-reconnect operation failure")
                        return@submit
                    }
                    emitError(ReaderErrorCode.SDK_OPERATION_FAILURE, "Operation failed while connecting", e.vendorMessage, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Operation failed while connecting", e)
                }
            } catch (e: Throwable) {
                synchronized(this) {
                    clearConnectTimeout()
                    Log.e(TAG, "Unexpected error during connect: ${e.message}", e)
                    Log.e(TAG, "  Exception type: ${e.javaClass.name}")
                    e.printStackTrace()
                    if (managedGeneration != null) {
                        lifecycleGate.completeFailure(managedGeneration)
                        emitError(ReaderErrorCode.UNKNOWN, "Managed RFID recovery failed", e.message, e)
                        updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Managed RFID recovery failed", e)
                        mainHandler.post {
                            managedRecoveryRequestListener?.invoke("rfid_session_lost")
                        }
                        return@submit
                    }
                    if (internalState != InternalConnectionState.CONNECTING) {
                        Log.d(TAG, "Ignoring connect failure because state is $internalState", e)
                        return@submit
                    }
                    if (fromAutoReconnect) {
                        updateConnectionState(
                            InternalConnectionState.DISCONNECTED,
                            ReaderConnectionStatus.DISCONNECTED,
                            "Auto-reconnect failed; reader remains disconnected",
                            e,
                        )
                        scheduleAutoReconnect("auto-reconnect unexpected failure")
                        return@submit
                    }
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
        } catch (e: InvalidUsageException) {
            val sdkMessage = e.info ?: e.message.orEmpty()
            if (!sdkMessage.contains("Try Reconnect", ignoreCase = true)) {
                throw e
            }
            Log.i(TAG, "SDK requested reconnect() instead of connect(); retrying with reconnect()")
            targetReader.reconnect()
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

    private fun managedLifecycleGenerationForRearm(
        ownership: RfidConnectionOwnership,
        hardwareIdentity: String?,
    ): Long? {
        if (ownership != RfidConnectionOwnership.CAPTURE_DEVICE) return null
        return when (
            val request = lifecycleGate.request(requireNotNull(hardwareIdentity))
        ) {
            is RfidLifecycleRequest.Joined -> {
                Log.d(
                    TAG,
                    "Capture Device readiness request joined generation ${request.generation}",
                )
                null
            }
            is RfidLifecycleRequest.Start -> request.generation
        }
    }

    @Synchronized
    private fun rearmConnectedReaderSession(
        targetReader: RFIDReader,
        logMessage: String,
        managedGeneration: Long? = null,
        hardwareIdentity: String? = null,
    ): ReaderInfo? {
        Log.i(TAG, logMessage)
        // Dispatch hardware I/O off the main thread — SDK commands like setStartTrigger
        // timeout when called on the main (Pigeon message handler) thread.
        ioExecutor.submit {
            try {
                setupReader()
                val info = refreshReaderInfo(targetReader)
                if (managedGeneration != null && hardwareIdentity != null) {
                    when (
                        lifecycleGate.completeSuccess(
                            managedGeneration,
                            hardwareIdentity,
                        )
                    ) {
                        RfidLifecycleCompletion.TERMINATE_STALE_SESSION -> {
                            retireReaderSession(
                                targetReader = targetReader,
                                reason = "stale re-armed RFID session",
                            )
                            synchronized(this) {
                                lifecycleGate.completeTermination(managedGeneration)
                                updateConnectionState(
                                    InternalConnectionState.DISCONNECTED,
                                    ReaderConnectionStatus.DISCONNECTED,
                                    "Stale re-armed RFID session terminated",
                                )
                            }
                            mainHandler.post {
                                managedRecoveryRequestListener?.invoke(
                                    "late_session_terminated",
                                )
                            }
                            return@submit
                        }
                        RfidLifecycleCompletion.ADOPT_SESSION -> Unit
                        else -> return@submit
                    }
                }
                synchronized(this) {
                    readerInfo = info
                    clearConnectTimeout()
                    if (lastConnectStartTimestamp != 0L) {
                        lastConnectDurationMs = System.currentTimeMillis() - lastConnectStartTimestamp
                    }
                    val wasAlreadyConnected =
                        internalState == InternalConnectionState.CONNECTED
                    updateConnectionState(
                        InternalConnectionState.CONNECTED,
                        ReaderConnectionStatus.CONNECTED,
                        logMessage,
                    )
                    if (wasAlreadyConnected) {
                        mainHandler.post {
                            callbacks.onReaderConnectionStatusChanged(
                                ReaderConnectionStatus.CONNECTED,
                            ) {}
                            connectionStatusListener?.invoke(
                                ReaderConnectionStatus.CONNECTED,
                            )
                        }
                    }
                }
                triggerDeviceStatus()
            } catch (e: OperationFailureException) {
                failRearmConnectedReaderSession(
                    targetReader = targetReader,
                    managedGeneration = managedGeneration,
                    errorCode = ReaderErrorCode.SDK_OPERATION_FAILURE,
                    message = "Operation failed re-arming reader session",
                    details = e.vendorMessage,
                    error = e,
                )
            } catch (e: InvalidUsageException) {
                failRearmConnectedReaderSession(
                    targetReader = targetReader,
                    managedGeneration = managedGeneration,
                    errorCode = ReaderErrorCode.SDK_INVALID_USAGE,
                    message = "Invalid usage re-arming reader session",
                    details = e.message,
                    error = e,
                )
            } catch (e: Throwable) {
                failRearmConnectedReaderSession(
                    targetReader = targetReader,
                    managedGeneration = managedGeneration,
                    errorCode = ReaderErrorCode.UNKNOWN,
                    message = "Unexpected error re-arming reader session",
                    details = e.message,
                    error = e,
                )
            }
        }
        return null
    }

    private fun failRearmConnectedReaderSession(
        targetReader: RFIDReader,
        managedGeneration: Long?,
        errorCode: ReaderErrorCode,
        message: String,
        details: String?,
        error: Throwable,
    ) {
        Log.e(TAG, "$message: ${error.message}", error)
        if (managedGeneration == null) {
            synchronized(this) {
                emitError(errorCode, message, details, error)
                updateConnectionState(
                    InternalConnectionState.ERROR,
                    ReaderConnectionStatus.ERROR,
                    message,
                    error,
                )
            }
            return
        }

        if (
            targetReader.isConnected &&
            !requiresReaderSessionRetirement(error)
        ) {
            // A setup/trigger command timeout does not prove that the RFID
            // transport was lost. Keep the existing session and let the
            // Capture Device coordinator retry configuration on that
            // serialized session before escalating to physical recovery.
            synchronized(this) {
                lifecycleGate.completeFailure(managedGeneration)
                emitError(errorCode, message, details, error)
            }
            mainHandler.post {
                managedRecoveryRequestListener?.invoke("rfid_setup_retry")
            }
            return
        }

        retireReaderSession(
            targetReader = targetReader,
            reason = "unusable re-armed RFID session",
        )
        synchronized(this) {
            lifecycleGate.completeFailure(managedGeneration)
            emitError(errorCode, message, details, error)
            updateConnectionState(
                InternalConnectionState.DISCONNECTED,
                ReaderConnectionStatus.DISCONNECTED,
                message,
                error,
            )
        }
        mainHandler.post {
            managedRecoveryRequestListener?.invoke("rfid_session_lost")
        }
    }

    private fun requiresReaderSessionRetirement(error: Throwable): Boolean {
        val operationFailure = generateSequence(error as Throwable?) { it.cause }
            .filterIsInstance<OperationFailureException>()
            .firstOrNull()
            ?: return false
        return operationFailure.results in setOf(
            RFIDResults.RFID_COMM_OPEN_ERROR,
            RFIDResults.RFID_COMM_RESOLVE_ERROR,
            RFIDResults.RFID_COMM_SEND_ERROR,
            RFIDResults.RFID_COMM_RECV_ERROR,
            RFIDResults.RFID_COMM_NO_CONNECTION,
            RFIDResults.RFID_INVALID_SOCKET,
            RFIDResults.RFID_RECONNECT_FAILED,
        )
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
    private fun scheduleConnectTimeout(
        readerId: Long,
        attempt: Int,
        managedGeneration: Long? = null,
    ) {
        clearConnectTimeout()
        val runnable = Runnable {
            synchronized(this) {
                if (internalState != InternalConnectionState.CONNECTING) return@synchronized
                emitError(ReaderErrorCode.TIMEOUT, "Connection attempt #$attempt timed out after ${CONNECT_TIMEOUT_MS}ms")
                managedGeneration?.let(lifecycleGate::recordTimeout)
                Log.w(
                    TAG,
                    "RFID connect deadline exceeded; native operation remains active and will be reconciled",
                )
                clearConnectTimeout()
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
    fun diagnostics(): Diagnostics = diagnosticsSnapshot(
        readerPowerState = null,
        readerPowerStateError = null,
    )

    fun diagnosticsWithReaderPowerState(callback: (Result<Diagnostics>) -> Unit) {
        if (lifecycleGate.hasActiveOperation()) {
            val snapshot = diagnosticsSnapshot(
                readerPowerState = null,
                readerPowerStateError = "deferred_during_capture_device_recovery",
            )
            mainHandler.post { callback(Result.success(snapshot)) }
            return
        }
        ioExecutor.submit {
            val snapshot = if (lifecycleGate.hasActiveOperation()) {
                diagnosticsSnapshot(
                    readerPowerState = null,
                    readerPowerStateError =
                        "deferred_during_capture_device_recovery",
                )
            } else {
                val powerState = currentReaderPowerState()
                diagnosticsSnapshot(powerState.state, powerState.error)
            }
            mainHandler.post { callback(Result.success(snapshot)) }
        }
    }

    private fun currentReaderPowerState(): ReaderPowerStateDiagnostic {
        val targetReader = reader ?: return ReaderPowerStateDiagnostic(null)
        if (!targetReader.isConnected) return ReaderPowerStateDiagnostic(null)
        return try {
            val state = readerPowerStateLabel(targetReader.Config.readerPowerState)
            recordResponsiveReaderActivity()
            ReaderPowerStateDiagnostic(state)
        } catch (error: OperationFailureException) {
            val details = listOfNotNull(
                error.results?.let { "result=$it" },
                error.statusDescription?.takeIf { it.isNotBlank() }?.let { "status=$it" },
                error.vendorMessage?.takeIf { it.isNotBlank() }?.let { "vendor=$it" },
            ).joinToString(", ").ifBlank { "OperationFailureException" }
            Log.w(TAG, "Reader power-state diagnostics unavailable: $details", error)
            if (error.results == RFIDResults.RFID_API_COMMAND_TIMEOUT) {
                recordReaderCommandTimeout(targetReader, details)
            } else {
                // An explicit SDK response, including unsupported-command,
                // proves the command channel is still responsive.
                recordResponsiveReaderActivity()
            }
            ReaderPowerStateDiagnostic("unavailable", details)
        } catch (error: InvalidUsageException) {
            val details = listOfNotNull(
                error.info?.takeIf { it.isNotBlank() }?.let { "info=$it" },
                error.vendorMessage?.takeIf { it.isNotBlank() }?.let { "vendor=$it" },
            ).joinToString(", ").ifBlank { "InvalidUsageException" }
            Log.w(TAG, "Reader power-state diagnostics unavailable: $details", error)
            ReaderPowerStateDiagnostic("unavailable", details)
        } catch (error: Throwable) {
            val details = error.message?.takeIf { it.isNotBlank() }
                ?: error.javaClass.simpleName
            Log.w(TAG, "Reader power-state diagnostics unavailable: $details", error)
            ReaderPowerStateDiagnostic("unavailable", details)
        }
    }

    @Synchronized
    private fun recordResponsiveReaderActivity() {
        consecutiveCommandTimeouts = 0
        commandTimeoutRecoveryRequested = false
    }

    private fun recordReaderCommandTimeout(
        targetReader: RFIDReader,
        details: String,
    ) {
        val shouldRecover = synchronized(this) {
            if (
                reader !== targetReader ||
                connectionOwnership != RfidConnectionOwnership.CAPTURE_DEVICE ||
                internalState != InternalConnectionState.CONNECTED ||
                lifecycleGate.hasActiveOperation() ||
                commandTimeoutRecoveryRequested
            ) {
                false
            } else {
                consecutiveCommandTimeouts += 1
                if (consecutiveCommandTimeouts >= 2) {
                    commandTimeoutRecoveryRequested = true
                    true
                } else {
                    false
                }
            }
        }
        if (!shouldRecover) return

        Log.w(
            TAG,
            "RFID command channel timed out twice; retiring stale Capture Device session: $details",
        )
        lastDisconnectTimestamp = System.currentTimeMillis()
        unexpectedDisconnectCount += 1
        if (inventoryActive) {
            inventoryActive = false
            lastInventoryStopTimestamp = lastDisconnectTimestamp
            lastInventoryStopReason = "RFID command timeout"
        }
        cancelInventoryWatchdog()
        cancelScheduledPurge()
        cancelRecoveryProbeStop()
        captureDeviceTriggerRearmPending = captureDeviceControlsBarcode
        captureDeviceTriggerPressObserved = false
        lifecycleGate.invalidate()
        updateConnectionState(
            InternalConnectionState.DISCONNECTED,
            ReaderConnectionStatus.DISCONNECTED,
            "Reader command channel is unresponsive",
        )
        retireReaderSession(
            targetReader = targetReader,
            reason = "RFID command timeout",
            disposeBeforeRediscovery = true,
        )
        mainHandler.post {
            managedRecoveryRequestListener?.invoke("rfid_command_timeout")
        }
    }

    @Synchronized
    private fun diagnosticsSnapshot(
        readerPowerState: String?,
        readerPowerStateError: String?,
    ): Diagnostics {
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
            lastInventoryStartReason,
            readerPowerState,
            readerPowerStateError,
        )
    }

    @Synchronized
    fun availableReadersSnapshot(): List<Reader> {
        val list = availableRFIDReaderList ?: return emptyList()
        return list.mapIndexed { index, device ->
            val info = if (device == readerDevice) readerInfo else null
            Reader(
                device.name,
                index.toLong(),
                info,
                buildReaderDiscoveryKey(device),
            )
        }
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

    internal fun setRecoveryVerificationScanEnabled(enabled: Boolean) {
        recoveryVerificationScanEnabled = enabled
        Log.d(TAG, "Recovery verification scan enabled set to $enabled")
    }

    internal fun shouldForwardReadTagsToFlutter(): Boolean = scanningEnabled

    fun configureReader(
        config: ReaderConfig,
        shouldPersist: Boolean,
        fromCaptureDevice: Boolean = false,
    ) {
        if (
            connectionOwnership == RfidConnectionOwnership.CAPTURE_DEVICE &&
            !fromCaptureDevice
        ) {
            emitError(
                ReaderErrorCode.CAPTURE_DEVICE_OWNS_CONNECTION,
                "Capture Device owns RFID configuration",
            )
            throw CaptureDeviceOwnershipException()
        }
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

        if (config.inventorySession != null || config.estimatedTagPopulation != null) {
            val singulationControl = reader!!.Config.Antennas.getSingulationControl(1)
            config.inventorySession?.let { session ->
                singulationControl.session = when (session) {
                    ReaderInventorySession.S0 -> SESSION.SESSION_S0
                    ReaderInventorySession.S1 -> SESSION.SESSION_S1
                    ReaderInventorySession.S2 -> SESSION.SESSION_S2
                    ReaderInventorySession.S3 -> SESSION.SESSION_S3
                }
            }
            config.estimatedTagPopulation?.let { population ->
                require(population in 1L..Short.MAX_VALUE.toLong()) {
                    "Estimated tag population must be between 1 and ${Short.MAX_VALUE}"
                }
                singulationControl.tagPopulation = population.toShort()
            }
            singulationControl.Action.inventoryState = INVENTORY_STATE.INVENTORY_STATE_A
            singulationControl.Action.slFlag = SL_FLAG.SL_ALL
            reader!!.Config.Antennas.setSingulationControl(1, singulationControl)

            val appliedSingulation = reader!!.Config.Antennas.getSingulationControl(1)
            config.inventorySession?.let {
                if (appliedSingulation.session != singulationControl.session) {
                    throw IllegalStateException(
                        "RFID inventory session verification failed: " +
                            "requested=${singulationControl.session} " +
                            "actual=${appliedSingulation.session}",
                    )
                }
            }
            config.estimatedTagPopulation?.let { population ->
                if (appliedSingulation.tagPopulation.toLong() != population) {
                    throw IllegalStateException(
                        "RFID estimated tag population verification failed: " +
                            "requested=$population " +
                            "actual=${appliedSingulation.tagPopulation}",
                    )
                }
            }
        }

        config.uniqueTagReporting?.let { enabled ->
            if (!reader!!.Config.setUniqueTagReport(enabled)) {
                throw IllegalStateException(
                    "RFID unique tag reporting configuration was rejected: requested=$enabled",
                )
            }
            val actual = reader!!.Config.uniqueTagReport == UNIQUE_TAG_REPORT_SETTING.ENABLE
            if (actual != enabled) {
                throw IllegalStateException(
                    "RFID unique tag reporting verification failed: " +
                        "requested=$enabled actual=$actual",
                )
            }
        }

        if (shouldPersist) reader!!.Config.saveConfig()

    }

    fun disconnectCurrentReader(fromCaptureDevice: Boolean = false) {
        if (
            connectionOwnership == RfidConnectionOwnership.CAPTURE_DEVICE &&
            !fromCaptureDevice
        ) {
            emitError(
                ReaderErrorCode.CAPTURE_DEVICE_OWNS_CONNECTION,
                "Capture Device owns the RFID connection",
            )
            throw CaptureDeviceOwnershipException()
        }

        synchronized(this) {
            clearConnectTimeout()
            if (connectionOwnership == RfidConnectionOwnership.CAPTURE_DEVICE) {
                lifecycleGate.invalidateSelection()
            }
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
        ioExecutor.submit {
            try {
                reader?.let {
                    retireReaderSession(
                        targetReader = it,
                        reason = "explicit RFID disconnect",
                    )
                }
                synchronized(this) {
                    updateConnectionState(InternalConnectionState.DISCONNECTED, ReaderConnectionStatus.DISCONNECTED, "Reader disconnected")
                    cancelPendingReconnect()
                    reconnectAttempt = 0
                    if (fromCaptureDevice) {
                        connectionOwnership = RfidConnectionOwnership.LEGACY
                        managedHardwareIdentity = null
                        captureDeviceControlsBarcode = false
                        captureDeviceBarcodeTriggerTarget =
                            CaptureDeviceBarcodeTriggerTarget.NONE
                        autoReconnectEnabled = true
                    }
                }
            } catch (e: Throwable) {
                synchronized(this) {
                    emitError(ReaderErrorCode.UNKNOWN, "Error during disconnect", e.message, e)
                    updateConnectionState(InternalConnectionState.ERROR, ReaderConnectionStatus.ERROR, "Error during disconnect", e)
                }
            }
        }
    }

    fun currentReader(): Reader? {
        if (readerDevice != null) {
            return Reader(
                readerDevice!!.name,
                availableRFIDReaderList!!.indexOf(readerDevice!!).toLong(),
                readerInfo,
                buildReaderDiscoveryKey(readerDevice!!),
            )
        }
        return null
    }

    fun triggerDeviceStatus() {
        enqueueDeviceStatusRefresh(attempt = 1)
    }

    private fun enqueueDeviceStatusRefresh(attempt: Int) {
        ioExecutor.submit {
            val targetReader = reader ?: return@submit
            if (!targetReader.isConnected) return@submit
            try {
                try {
                    // Some RFD40 connections report the capability flag as false even
                    // though the PP+ battery statistics API is available. Ask the SDK
                    // directly and retain the standard battery event as the fallback.
                    batteryDataFromStatistics(targetReader.Config.getBatteryStats())
                        ?.let(::emitBatteryData)
                } catch (error: Throwable) {
                    // Battery statistics are only available on supported PP+ sleds.
                    Log.d(TAG, "Battery statistics unavailable: ${error.message}")
                }
                targetReader.Config.getDeviceStatus(true, true, true)
                Log.d(TAG, "Device status refresh requested (attempt $attempt)")
            } catch (error: OperationFailureException) {
                val lockBusy = error.results == RFIDResults.RFID_API_LOCK_ACQUIRE_FAILURE ||
                    error.vendorMessage?.contains("LOCK_ACQUIRE_FAILURE", ignoreCase = true) == true ||
                    error.statusDescription?.contains("LOCK_ACQUIRE_FAILURE", ignoreCase = true) == true
                if (lockBusy && attempt < MAX_DEVICE_STATUS_ATTEMPTS) {
                    Log.w(
                        TAG,
                        "RFID SDK busy during device status refresh; retrying " +
                            "attempt ${attempt + 1}/$MAX_DEVICE_STATUS_ATTEMPTS",
                    )
                    mainHandler.postDelayed(
                        { enqueueDeviceStatusRefresh(attempt + 1) },
                        DEVICE_STATUS_RETRY_DELAY_MS,
                    )
                } else {
                    Log.w(
                        TAG,
                        "Device status refresh failed without changing reader connection state: " +
                            "${error.results} (${error.statusDescription})",
                    )
                }
            } catch (error: Throwable) {
                Log.w(
                    TAG,
                    "Device status refresh failed without changing reader connection state: ${error.message}",
                )
            }
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

    /**
     * Scanner SDK session restoration can change the shared RFD trigger route.
     * Reapply Capture Device RFID ownership on the serialized reader executor
     * after barcode recovery has completed.
     */
    fun reassertCaptureDeviceTriggerOwnership(
        callback: (Result<Unit>) -> Unit,
    ) {
        ioExecutor.submit {
            val result = runCatching {
                val targetReader = synchronized(this) {
                    check(connectionOwnership == RfidConnectionOwnership.CAPTURE_DEVICE) {
                        "Capture Device does not own the RFID connection"
                    }
                    check(captureDeviceControlsBarcode) {
                        "Capture Device does not share the RFD trigger with barcode"
                    }
                    reader?.takeIf { it.isConnected }
                        ?: error("RFID reader is not connected")
                }
                // Scanner SDK recovery can leave Zebra's handheld-event state
                // latched at RELEASED or detach the recovered reader's event
                // worker even though the RFID transport is ready. Rebind the
                // complete event set before restoring the shared trigger route.
                bindReaderEvents(
                    targetReader = targetReader,
                    resetHandheldEvent = true,
                )
                applyCaptureDeviceTriggerOwnership(targetReader)
                captureDeviceTriggerPressObserved = false
                captureDeviceTriggerRearmPending = false
                Log.i(
                    TAG,
                    "RFD event listener rebound and trigger ownership reasserted " +
                        "after barcode restoration",
                )
                Unit
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun applyCaptureDeviceTriggerOwnership(targetReader: RFIDReader) {
        val triggerInfo = buildInventoryTriggerInfo()
        targetReader.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, false)
        val lowerTrigger = when (captureDeviceBarcodeTriggerTarget) {
            CaptureDeviceBarcodeTriggerTarget.TERMINAL_IMAGER ->
                ENUM_NEW_KEYLAYOUT_TYPE.TERMINAL_SCAN
            CaptureDeviceBarcodeTriggerTarget.RFD_BARCODE_ENGINE ->
                ENUM_NEW_KEYLAYOUT_TYPE.SLED_SCAN
            CaptureDeviceBarcodeTriggerTarget.NONE ->
                error("Capture Device does not share the RFD trigger with barcode")
        }
        Log.i(
            TAG,
            "Applying RFD trigger layout: upper=RFID lower=${lowerTrigger.name}",
        )
        val keyLayoutResult = try {
            targetReader.Config.setKeylayoutType(
                ENUM_NEW_KEYLAYOUT_TYPE.RFID,
                lowerTrigger,
            )
        } catch (error: OperationFailureException) {
            if (error.results != RFIDResults.RFID_API_OPTION_NOT_ALLOWED) {
                throw error
            }
            logUnsupportedCaptureDeviceKeyLayout()
            null
        }
        when (keyLayoutResult) {
            null, RFIDResults.RFID_API_SUCCESS -> Unit
            RFIDResults.RFID_API_OPTION_NOT_ALLOWED ->
                logUnsupportedCaptureDeviceKeyLayout()
            else -> error("Failed to restore RFD trigger ownership: $keyLayoutResult")
        }
        targetReader.Config.startTrigger = triggerInfo.StartTrigger
        targetReader.Config.stopTrigger = triggerInfo.StopTrigger
    }

    private fun logUnsupportedCaptureDeviceKeyLayout() {
        // USB-attached RFID-only RFD40 variants (including the TC22 sled
        // topology) do not expose the programmable barcode key layout.
        // Trigger mode plus start/stop triggers are sufficient for RFID
        // ownership on those readers.
        Log.w(
            TAG,
            "RFD key layout is not supported by this reader; " +
                "continuing with RFID trigger configuration",
        )
    }

    private fun bindReaderEvents(
        targetReader: RFIDReader,
        resetHandheldEvent: Boolean,
    ) {
        if (resetHandheldEvent) {
            targetReader.Events.setHandheldEvent(false)
        }
        val previouslyBoundReader = eventsBoundReader
        if (previouslyBoundReader != null || resetHandheldEvent) {
            runCatching {
                (previouslyBoundReader ?: targetReader)
                    .Events
                    .removeEventsListener(this)
            }.onFailure {
                Log.d(TAG, "Existing RFID event listener was already detached")
            }
        }
        targetReader.Events.addEventsListener(this)
        eventsBoundReader = targetReader
        targetReader.Events.setHandheldEvent(true)
        targetReader.Events.setTagReadEvent(true)
        targetReader.Events.setAttachTagDataWithReadEvent(false)
        targetReader.Events.setBatteryEvent(true)
        targetReader.Events.setInventoryStartEvent(true)
        targetReader.Events.setInventoryStopEvent(true)
        targetReader.Events.setReaderDisconnectEvent(true)
        targetReader.Events.setAntennaEvent(true)
        targetReader.Events.setTemperatureAlarmEvent(true)
        targetReader.Events.setPowerEvent(true)
    }

    private fun setupReader() {
        if (!reader!!.isConnected) {
            Log.d(TAG, "Reader not connected, connecting...")
            reader!!.connect()
        }
        if (reader!!.isConnected) {
            Log.d(TAG, "Configuring reader...")
            val triggerInfo = buildInventoryTriggerInfo()
            try {
                // receive events from reader
                Log.d(TAG, "Setting up event listeners...")
                bindReaderEvents(
                    targetReader = reader!!,
                    resetHandheldEvent = false,
                )
                Log.d(TAG, "Event listeners configured")

                // set start and stop triggers
                Log.d(TAG, "Setting trigger mode...")
                if (captureDeviceControlsBarcode) {
                    applyCaptureDeviceTriggerOwnership(reader!!)
                } else {
                    reader!!.Config.setTriggerMode(
                        ENUM_TRIGGER_MODE.RFID_MODE,
                        true,
                    )
                    Log.d(TAG, "Setting start/stop triggers...")
                    reader!!.Config.startTrigger = triggerInfo.StartTrigger
                    reader!!.Config.stopTrigger = triggerInfo.StopTrigger
                }
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
                throw e
            } catch (e: OperationFailureException) {
                Log.e(TAG, "OperationFailureException configuring reader: ${e.message}", e)
                Log.e(TAG, "  Vendor message: ${e.vendorMessage}")
                Log.e(TAG, "  Status description: ${e.statusDescription}")
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Error configuring reader: $e", e)
                throw e
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

            val antennaRfConfig = runCatching {
                reader!!.Config.Antennas.getAntennaRfConfig(1)
            }.onFailure {
                Log.w(TAG, "Unable to read antenna RF config; returning partial config", it)
            }.getOrNull()
            val transmitPowerIndex = antennaRfConfig?.transmitPowerIndex
            Log.d(TAG, "Transmit Power Index: $transmitPowerIndex")

            val receiveSensitivityIndex = antennaRfConfig?.receiveSensitivityIndex
            // rfModeTableIndex getter not available; returning null for now.
            val rfModeIndex: Int? = null
            Log.d(TAG, "Receive Sensitivity Index: $receiveSensitivityIndex")
            Log.d(TAG, "RF Mode Table Index: $rfModeIndex")

            val tari = antennaRfConfig?.tari
            Log.d(TAG, "Tari: $tari")

            var beeperVolume: ReaderBeeperVolume? = null

            when (runCatching { reader!!.Config.beeperVolume }.getOrNull()) {
                BEEPER_VOLUME.HIGH_BEEP -> beeperVolume = ReaderBeeperVolume.HIGH
                BEEPER_VOLUME.MEDIUM_BEEP -> beeperVolume = ReaderBeeperVolume.MEDIUM
                BEEPER_VOLUME.LOW_BEEP -> beeperVolume = ReaderBeeperVolume.LOW
                BEEPER_VOLUME.QUIET_BEEP -> beeperVolume = ReaderBeeperVolume.QUIET
            }
            Log.d(TAG, "Beeper volume: $beeperVolume")

            var batchMode: ReaderConfigBatchMode? = null
            when (runCatching { reader!!.Config.batchModeConfig }.getOrNull()) {
                BATCH_MODE.AUTO -> batchMode = ReaderConfigBatchMode.AUTO
                BATCH_MODE.ENABLE -> batchMode = ReaderConfigBatchMode.ENABLED
                BATCH_MODE.DISABLE -> batchMode = ReaderConfigBatchMode.DISABLED
            }
            Log.d(TAG, "Batch mode: $batchMode")

            var scanBatchMode: ReaderConfigBatchMode? = null
            when (runCatching { reader!!.Config.scanBatchModeConfig }.getOrNull()) {
                SCAN_BATCH_MODE.AUTO -> scanBatchMode = ReaderConfigBatchMode.AUTO
                SCAN_BATCH_MODE.ENABLE -> scanBatchMode = ReaderConfigBatchMode.ENABLED
                SCAN_BATCH_MODE.DISABLE -> scanBatchMode = ReaderConfigBatchMode.DISABLED
            }
            Log.d(TAG, "Scan batch mode: $scanBatchMode")

            val dpoEnabled = runCatching {
                reader!!.Config.dpoState == DYNAMIC_POWER_OPTIMIZATION.ENABLE
            }.getOrNull()
            val singulationControl = runCatching {
                reader!!.Config.Antennas.getSingulationControl(1)
            }.onFailure {
                Log.w(TAG, "Unable to read singulation config", it)
            }.getOrNull()
            val inventorySession = when (singulationControl?.session) {
                SESSION.SESSION_S0 -> ReaderInventorySession.S0
                SESSION.SESSION_S1 -> ReaderInventorySession.S1
                SESSION.SESSION_S2 -> ReaderInventorySession.S2
                SESSION.SESSION_S3 -> ReaderInventorySession.S3
                else -> null
            }
            val uniqueTagReporting = runCatching {
                reader!!.Config.uniqueTagReport == UNIQUE_TAG_REPORT_SETTING.ENABLE
            }.onFailure {
                Log.w(TAG, "Unable to read unique tag reporting config", it)
            }.getOrNull()

            return ReaderConfig(
                transmitPowerIndex = transmitPowerIndex?.toLong(),
                tari = tari?.toLong(),
                beeperVolume = beeperVolume,
                enableDynamicPower = dpoEnabled,
                // NOTE: SDK doesn't provide this LED blink read API reliably; leaving null
                enableLedBlink = null,
                batchMode = batchMode,
                scanBatchMode = scanBatchMode,
                rfModeTableIndex = rfModeIndex?.toLong(),
                receiveSensitivityIndex = receiveSensitivityIndex?.toLong(),
                inventorySession = inventorySession,
                estimatedTagPopulation = singulationControl?.tagPopulation?.toLong(),
                uniqueTagReporting = uniqueTagReporting,
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
        when (rfidStatusEvents.StatusEventData.statusEventType) {
            STATUS_EVENT_TYPE.BATTERY_EVENT -> {
                val data = rfidStatusEvents.StatusEventData.BatteryData
                val batteryData = batteryDataFromReaderEvent(
                    level = data.level,
                    isCharging = data.charging,
                    cause = data.cause,
                    previous = lastBatteryData,
                )
                Log.d(
                    TAG,
                    "Battery data - level: ${batteryData.level}, isCharging: ${batteryData.isCharging}, cause: ${batteryData.cause}"
                )
                emitBatteryData(batteryData)
            }
            STATUS_EVENT_TYPE.POWER_EVENT -> {
                val powerData = rfidStatusEvents.StatusEventData.PowerData
                Log.d(
                    TAG,
                    "Power data - voltage: ${powerData.voltage}, current: ${powerData.current}, power: ${powerData.power}",
                )
            }

            STATUS_EVENT_TYPE.DISCONNECTION_EVENT -> {
                handleUnexpectedReaderDisconnect("status disconnection event")
            }

            STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
                Log.d(TAG, "Handheld trigger event detected")
                handleHandheldTriggerEvent(rfidStatusEvents.StatusEventData.HandheldTriggerEventData.handheldEvent)
            }

            else -> {
                Log.d(
                    TAG,
                    "Unhandled status event type: ${rfidStatusEvents.StatusEventData.statusEventType}"
                )
            }
        }
    }

    internal fun handleHandheldTriggerEvent(handheldEvent: HANDHELD_TRIGGER_EVENT_TYPE) {
        try {
            val verificationInteraction =
                recoveryVerificationScanEnabled ||
                    captureDeviceTriggerPressObserved ||
                    recoveryProbeStopRunnable != null
            if (!scanningEnabled && !verificationInteraction) {
                Log.d(TAG, "Trigger event ignored (scanning disabled)")
                return
            }
            if (
                connectionOwnership == RfidConnectionOwnership.CAPTURE_DEVICE &&
                captureDeviceTriggerRearmPending
            ) {
                Log.d(
                    TAG,
                    "Trigger event ignored while Capture Device recovery restores ownership",
                )
                return
            }
            if (handheldEvent === HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                captureDeviceTriggerPressObserved = true
                promoteRecoveryProbeToPhysicalPress()
                Log.d(TAG, "Handheld trigger pressed")

                // Check if we're in locate mode and ready to start
                if (locateSessionActive && !locatePendingStart) {
                    // Start locate operation on trigger press
                    Log.d(TAG, "Trigger pressed: Starting locate operation")
                    performLocate()
                } else if (locateSessionActive && locatePendingStart) {
                    Log.d(TAG, "Trigger pressed: Locate session active but waiting for purge completion")
                } else {
                    // Normal inventory mode
                    val reason = if (!scanningEnabled) {
                        "recovery verification trigger pressed"
                    } else {
                        "trigger pressed"
                    }
                    safeStartInventory(reason)
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
            } else if (
                connectionOwnership == RfidConnectionOwnership.CAPTURE_DEVICE &&
                captureDeviceControlsBarcode &&
                !captureDeviceTriggerPressObserved
            ) {
                // After Scanner SDK restoration, Zebra can emit only RELEASED
                // for a physical top-trigger pull. Reapplying the key layout
                // does not reliably restore PRESSED events on the affected
                // RFD40. Treat the release as the operator's scan intent and
                // run a bounded inventory probe. Only an actual tag callback
                // confirms recovery.
                Log.w(
                    TAG,
                    "Capture Device trigger released without a preceding press; " +
                        "starting bounded RFID recovery probe",
                )
                safeStartInventory("release-only recovery probe")
                scheduleRecoveryProbeStop()
                return
            } else {
                captureDeviceTriggerPressObserved = false
                Log.d(TAG, "Handheld trigger released")

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
                } else {
                    safeStopInventory("trigger released")
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Error handling handheld trigger event: $e")
        }
    }

    @Synchronized
    private fun promoteRecoveryProbeToPhysicalPress() {
        val pendingStop = recoveryProbeStopRunnable ?: return
        mainHandler.removeCallbacks(pendingStop)
        recoveryProbeStopRunnable = null
        if (
            inventoryActive &&
            lastInventoryStartReason == "release-only recovery probe"
        ) {
            lastInventoryStartReason = "trigger pressed during recovery probe"
            Log.d(
                TAG,
                "Physical trigger press adopted active RFID recovery probe inventory",
            )
        }
    }

    @Synchronized
    private fun scheduleRecoveryProbeStop() {
        cancelRecoveryProbeStop()
        lateinit var runnable: Runnable
        runnable = Runnable {
            synchronized(this) {
                if (recoveryProbeStopRunnable !== runnable) {
                    return@synchronized
                }
                recoveryProbeStopRunnable = null
                if (
                    inventoryActive &&
                    lastInventoryStartReason == "release-only recovery probe"
                ) {
                    safeStopInventory("release-only recovery probe complete")
                }
            }
        }
        recoveryProbeStopRunnable = runnable
        mainHandler.postDelayed(runnable, 750L)
    }

    @Synchronized
    private fun cancelRecoveryProbeStop() {
        recoveryProbeStopRunnable?.let { mainHandler.removeCallbacks(it) }
        recoveryProbeStopRunnable = null
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
        if (
            reason == "watchdog inactivity" &&
            connectionOwnership == RfidConnectionOwnership.CAPTURE_DEVICE
        ) {
            // Zebra may omit the physical RELEASED event. Once the watchdog
            // ends that inventory, the next RELEASED-only event must be
            // treated as a new recovery scan intent rather than the end of
            // the stale press cycle.
            captureDeviceTriggerPressObserved = false
            Log.d(TAG, "RFID trigger press state reset after watchdog stop")
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
                val forwardToFlutter = shouldForwardReadTagsToFlutter()
                Log.d(TAG, "Tags read: $readTags")
                if (readTags.isNotEmpty()) {
                    lastTagReadTimestamp = System.currentTimeMillis()
                    recordResponsiveReaderActivity()
                }
                Handler(Looper.getMainLooper()).post {
                    if (readTags.isNotEmpty()) {
                        managedReadinessActivityListener?.invoke()
                    }
                    if (forwardToFlutter) {
                        callbacks.onTagsRead(readTags.map {
                            RfidTag(
                                it.tagID,
                                it.peakRSSI.toLong()
                            )
                        }) {}
                    } else {
                        Log.d(
                            TAG,
                            "Recovery verification tags withheld from Flutter workflow",
                        )
                    }
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
        if (usbConnectionReceiverRegistered) {
            runCatching { applicationContext.unregisterReceiver(usbConnectionReceiver) }
                .onFailure { Log.w(TAG, "Failed to unregister RFID USB receiver", it) }
            usbConnectionReceiverRegistered = false
        }
        try {
            if (reader != null) {
                reader!!.Events?.removeEventsListener(this)
                reader!!.disconnect()
                reader!!.Dispose()
            }
            disposeDiscoveryReaderManagers("plugin shutdown")
            Readers.deattach(this)
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
                handleUnexpectedReaderDisconnect("device disappeared")
            }
        }
    }

    private companion object {
        const val ZEBRA_USB_VENDOR_ID = 1504
    }
}
