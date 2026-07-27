package nz.calo.flutter_zebra_rfid.capture

import BarcodeScannerMode
import CaptureCapabilityStatus
import CaptureDevice
import CaptureDeviceStatus
import CaptureReaderBeeperVolume
import CaptureReaderConfig
import CaptureReaderConfigBatchMode
import FlutterZebraCapture
import FlutterZebraCaptureCallbacks
import ReaderBeeperVolume
import ReaderConfig
import ReaderConfigBatchMode
import ReaderConnectionStatus
import ReaderConnectionType
import ScannerConnectionStatus
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import nz.calo.flutter_zebra_rfid.barcode.BarcodeScannerInterface
import nz.calo.flutter_zebra_rfid.rfid.RFIDReaderInterface

/**
 * Owns the lifecycle of the RFID Reader and Barcode Endpoint that together form
 * the active Capture Device.
 */
class CaptureDeviceCoordinator(
    private val context: Context,
    private val rfidInterface: RFIDReaderInterface,
    private val barcodeInterface: BarcodeScannerInterface,
    private val callbacks: FlutterZebraCaptureCallbacks,
) : FlutterZebraCapture {
    private val tag = "FlutterZebraCapture"
    private val planner = CaptureDevicePlanner(CaptureDevicePlanningPlatform.ANDROID)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val barcodeOverrides = linkedMapOf<String, String>()
    private val joinedCallbacks = mutableListOf<(Result<Unit>) -> Unit>()
    private val retryDelaysMs = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

    private enum class RecoveryDomain {
        RFID,
        CONFIGURATION,
    }

    private var activeCaptureDeviceId: String? = null
    private var selectedHardwareIdentity: String? = null
    private var activeRfidStatus: CaptureCapabilityStatus? = null
    private var activeBarcodeStatus: CaptureCapabilityStatus? = null
    private var activeRfidError: String? = null
    private var activeBarcodeError: String? = null
    private var pendingRfidConfig: CaptureReaderConfig? = null
    private var foreground = true
    private var lifecycleOperationActive = false
    private var pendingReadiness = false
    private var retryAttempt = 0
    private var recoveryDomain: RecoveryDomain? = null
    private var recoveryGeneration = 0L
    private var retryRunnable: Runnable? = null
    private var disposed = false

    init {
        rfidInterface.readersChangedListener = {
            emitDevices()
            if (pendingReadiness && foreground && !lifecycleOperationActive) {
                requestReadiness("reader_discovery", explicit = false)
            }
        }
        rfidInterface.connectionStatusListener = { status ->
            activeRfidStatus = status.toCaptureStatus()
            when (status) {
                ReaderConnectionStatus.CONNECTED -> onRfidTransportReady()
                ReaderConnectionStatus.DISCONNECTED,
                ReaderConnectionStatus.ERROR,
                -> {
                    if (activeCaptureDeviceId != null) {
                        pendingReadiness = true
                        scheduleRecovery("rfid_session_lost")
                    }
                }
                else -> emitDevices()
            }
        }
        rfidInterface.connectionErrorListener = { error ->
            activeRfidStatus = CaptureCapabilityStatus.ERROR
            activeRfidError = error.message
            emitDevices()
        }
        rfidInterface.managedRecoveryRequestListener = { reason ->
            lifecycleOperationActive = false
            pendingReadiness = true
            scheduleRecovery(reason)
        }
        barcodeInterface.endpointsChangedListener = { emitDevices() }
        barcodeInterface.connectionStatusListener = { status ->
            activeBarcodeStatus = status.toCaptureStatus()
            if (status == ScannerConnectionStatus.CONNECTED) {
                activeBarcodeError = null
            } else if (
                status == ScannerConnectionStatus.DISCONNECTED ||
                status == ScannerConnectionStatus.ERROR
            ) {
                recoverBarcodeOnly("barcode_session_lost")
            }
            emitDevices()
        }
    }

    override fun refreshCaptureDevices(callback: (Result<Unit>) -> Unit) {
        runCatching {
            refreshDiscovery()
            emitDevices()
        }.fold(
            onSuccess = { callback(Result.success(Unit)) },
            onFailure = { callback(Result.failure(it)) },
        )
    }

    override fun connectCaptureDevice(
        captureDeviceId: String,
        rfidConfig: CaptureReaderConfig?,
        callback: (Result<Unit>) -> Unit,
    ) {
        val device = buildDevices().firstOrNull { it.id == captureDeviceId }
        if (device == null) {
            callback(
                Result.failure(
                    IllegalArgumentException("Capture Device not available: $captureDeviceId"),
                ),
            )
            return
        }

        if (
            activeCaptureDeviceId == captureDeviceId &&
            !lifecycleOperationActive &&
            device.status == CaptureDeviceStatus.CONNECTED
        ) {
            callback(Result.success(Unit))
            return
        }

        if (activeCaptureDeviceId == captureDeviceId && lifecycleOperationActive) {
            joinedCallbacks.add(callback)
            logDebug("duplicate_readiness_request joined generation=$recoveryGeneration")
            return
        }

        if (activeCaptureDeviceId != captureDeviceId) {
            recoveryGeneration += 1
            retryAttempt = 0
            recoveryDomain = null
        }
        activeCaptureDeviceId = captureDeviceId
        selectedHardwareIdentity = device.rfid?.hardwareIdentity
        pendingRfidConfig = rfidConfig
        activeRfidError = null
        activeBarcodeError = null
        activeRfidStatus = if (device.rfid == null) {
            CaptureCapabilityStatus.UNAVAILABLE
        } else {
            CaptureCapabilityStatus.CONNECTING
        }
        activeBarcodeStatus = if (device.barcode == null) {
            CaptureCapabilityStatus.UNAVAILABLE
        } else {
            CaptureCapabilityStatus.CONNECTING
        }
        joinedCallbacks.add(callback)
        retryAttempt = 0
        recoveryDomain = null
        requestReadiness("explicit_connect", explicit = true)
    }

    override fun disconnectCaptureDevice(
        captureDeviceId: String,
        callback: (Result<Unit>) -> Unit,
    ) {
        if (activeCaptureDeviceId != captureDeviceId) {
            callback(Result.success(Unit))
            return
        }
        recoveryGeneration += 1
        cancelRetry()
        pendingReadiness = false
        lifecycleOperationActive = true
        activeRfidStatus = CaptureCapabilityStatus.DISCONNECTED
        activeBarcodeStatus = CaptureCapabilityStatus.DISCONNECTED
        activeRfidError = null
        activeBarcodeError = null
        emitDevices()
        rfidInterface.disconnectCurrentReader(fromCaptureDevice = true)
        barcodeInterface.disconnectCurrentScannerForCaptureDevice { result ->
            lifecycleOperationActive = false
            activeCaptureDeviceId = null
            selectedHardwareIdentity = null
            pendingRfidConfig = null
            completeJoined(result)
            emitDevices()
            callback(result)
        }
    }

    override fun setCaptureDeviceBarcodeOverride(
        captureDeviceId: String,
        barcodeEndpointId: String,
        callback: (Result<Unit>) -> Unit,
    ) {
        val endpointExists = barcodeInterface.barcodeEndpoints()
            .any { it.endpointId == barcodeEndpointId }
        if (!endpointExists) {
            callback(
                Result.failure(
                    IllegalArgumentException(
                        "Barcode Endpoint not available: $barcodeEndpointId",
                    ),
                ),
            )
            return
        }
        barcodeOverrides[captureDeviceId] = barcodeEndpointId
        emitDevices()
        callback(Result.success(Unit))
    }

    override fun configureCaptureDevice(
        captureDeviceId: String,
        rfidConfig: CaptureReaderConfig,
        shouldPersist: Boolean,
        callback: (Result<Unit>) -> Unit,
    ) {
        if (activeCaptureDeviceId != captureDeviceId) {
            callback(
                Result.failure(
                    IllegalStateException(
                        "Capture Device is not active: $captureDeviceId",
                    ),
                ),
            )
            return
        }
        runCatching {
            pendingRfidConfig = rfidConfig
            rfidInterface.configureReader(
                rfidConfig.toReaderConfig(),
                shouldPersist = shouldPersist,
                fromCaptureDevice = true,
            )
        }.fold(
            onSuccess = {
                retryAttempt = 0
                recoveryDomain = null
                callback(Result.success(Unit))
            },
            onFailure = {
                activeRfidStatus = CaptureCapabilityStatus.ERROR
                activeRfidError = it.message ?: it.toString()
                emitDevices()
                scheduleRecovery("configuration_retry")
                callback(Result.failure(it))
            },
        )
    }

    override fun setCaptureDeviceForeground(
        foreground: Boolean,
        callback: (Result<Unit>) -> Unit,
    ) {
        if (this.foreground == foreground) {
            callback(Result.success(Unit))
            return
        }
        this.foreground = foreground
        if (!foreground) {
            cancelRetry()
        } else if (activeCaptureDeviceId != null) {
            retryAttempt = 0
            requestReadiness("foreground_resume", explicit = true)
        }
        callback(Result.success(Unit))
    }

    override fun activeCaptureDevice(): CaptureDevice? =
        buildDevices().firstOrNull { it.id == activeCaptureDeviceId }

    fun dispose() {
        disposed = true
        recoveryGeneration += 1
        cancelRetry()
        joinedCallbacks.clear()
    }

    private fun requestReadiness(reason: String, explicit: Boolean) {
        if (disposed || activeCaptureDeviceId == null) return
        if (!foreground) {
            pendingReadiness = true
            return
        }
        if (lifecycleOperationActive) {
            pendingReadiness = true
            logDebug("duplicate_readiness_request reason=$reason")
            return
        }
        if (retryAttempt >= retryDelaysMs.size && !explicit) {
            exhaustRecovery(reason)
            return
        }

        pendingReadiness = false
        lifecycleOperationActive = true
        recoveryGeneration += 1
        val generation = recoveryGeneration
        logDebug("RFID recovery generation=$generation stage=discovery reason=$reason")

        runCatching { refreshDiscovery() }.onFailure {
            failRecovery(generation, "reader_discovery", it)
            return
        }

        val device = buildDevices().firstOrNull { it.id == activeCaptureDeviceId }
        val rfid = device?.rfid
        if (device == null) {
            failRecovery(
                generation,
                "reader_discovery",
                IllegalStateException("Selected Capture Device is not discoverable"),
            )
            return
        }
        if (rfid == null) {
            activeRfidStatus = CaptureCapabilityStatus.UNAVAILABLE
            restoreBarcode(generation)
            return
        }
        if (
            selectedHardwareIdentity != null &&
            rfid.hardwareIdentity != selectedHardwareIdentity
        ) {
            failRecovery(
                generation,
                "reader_discovery",
                IllegalStateException("Discovered RFID Reader identity changed"),
            )
            return
        }

        activeRfidStatus = CaptureCapabilityStatus.CONNECTING
        emitDevices()
        val controlsBarcode = device.barcode != null
        runCatching {
            rfidInterface.connectReaderForCaptureDevice(
                readerId = rfid.readerId,
                hardwareIdentity = rfid.hardwareIdentity,
                controlsBarcode = controlsBarcode,
            )
        }.onFailure {
            failRecovery(generation, "rfid_session_lost", it)
        }
    }

    private fun onRfidTransportReady() {
        if (activeCaptureDeviceId == null || disposed) return
        activeRfidError = null
        activeRfidStatus = CaptureCapabilityStatus.CONNECTED
        val generation = recoveryGeneration

        val config = pendingRfidConfig
        if (config != null) {
            try {
                rfidInterface.configureReader(
                    config.toReaderConfig(),
                    shouldPersist = false,
                    fromCaptureDevice = true,
                )
            } catch (error: Throwable) {
                activeRfidStatus = CaptureCapabilityStatus.ERROR
                activeRfidError =
                    "Connected, but RFID configuration failed: ${error.message ?: error}"
                lifecycleOperationActive = false
                scheduleRecovery("configuration_retry")
                emitDevices()
                return
            }
        }

        retryAttempt = 0
        recoveryDomain = null
        restoreBarcode(generation)
    }

    private fun restoreBarcode(generation: Long) {
        if (generation != recoveryGeneration || disposed) return
        val device = buildDevices().firstOrNull { it.id == activeCaptureDeviceId }
        val barcode = device?.barcode
        if (barcode == null) {
            activeBarcodeStatus = CaptureCapabilityStatus.UNAVAILABLE
            finishRecovery(generation)
            return
        }

        activeBarcodeStatus = CaptureCapabilityStatus.CONNECTING
        emitDevices()
        try {
            barcodeInterface.setActiveEndpointForCaptureDevice(barcode.endpointId)
            val endpoint = barcodeInterface.barcodeEndpoints()
                .firstOrNull { it.endpointId == barcode.endpointId }
            if (
                endpoint?.mode == BarcodeScannerMode.SCANNER_SDK &&
                endpoint.scannerId != null
            ) {
                barcodeInterface.connectToScannerForCaptureDevice(
                    endpoint.scannerId.toInt(),
                ) { result ->
                    if (generation != recoveryGeneration) {
                        return@connectToScannerForCaptureDevice
                    }
                    result.fold(
                        onSuccess = {
                            activeBarcodeStatus = CaptureCapabilityStatus.CONNECTED
                            activeBarcodeError = null
                            finishRecovery(generation)
                        },
                        onFailure = { error ->
                            activeBarcodeStatus = CaptureCapabilityStatus.ERROR
                            activeBarcodeError = error.message ?: error.toString()
                            lifecycleOperationActive = false
                            emitDevices()
                            completeJoined(Result.success(Unit))
                        },
                    )
                }
            } else {
                activeBarcodeStatus = CaptureCapabilityStatus.CONNECTED
                activeBarcodeError = null
                finishRecovery(generation)
            }
        } catch (error: Throwable) {
            activeBarcodeStatus = CaptureCapabilityStatus.ERROR
            activeBarcodeError = error.message ?: error.toString()
            lifecycleOperationActive = false
            emitDevices()
            completeJoined(Result.success(Unit))
        }
    }

    private fun recoverBarcodeOnly(reason: String) {
        if (
            disposed ||
            !foreground ||
            activeCaptureDeviceId == null ||
            activeRfidStatus != CaptureCapabilityStatus.CONNECTED ||
            lifecycleOperationActive
        ) {
            return
        }
        logDebug("barcode-only recovery reason=$reason")
        lifecycleOperationActive = true
        recoveryGeneration += 1
        restoreBarcode(recoveryGeneration)
    }

    private fun finishRecovery(generation: Long) {
        if (generation != recoveryGeneration || disposed) return
        lifecycleOperationActive = false
        pendingReadiness = false
        retryAttempt = 0
        recoveryDomain = null
        activeRfidError = null
        if (activeBarcodeStatus == CaptureCapabilityStatus.CONNECTED) {
            activeBarcodeError = null
        }
        logDebug("capture_device_recovered generation=$generation")
        emitDevices()
        completeJoined(Result.success(Unit))
    }

    private fun failRecovery(
        generation: Long,
        stage: String,
        error: Throwable,
    ) {
        if (generation != recoveryGeneration || disposed) return
        lifecycleOperationActive = false
        activeRfidStatus = CaptureCapabilityStatus.ERROR
        activeRfidError = error.message ?: error.toString()
        pendingReadiness = true
        logWarning("Capture Device recovery failed stage=$stage", error)
        emitDevices()
        scheduleRecovery(stage)
    }

    private fun scheduleRecovery(reason: String) {
        if (disposed || activeCaptureDeviceId == null) return
        val requestedDomain = recoveryDomainFor(reason)
        if (recoveryDomain != requestedDomain) {
            cancelRetry()
            retryAttempt = 0
            recoveryDomain = requestedDomain
        }
        if (!foreground) {
            pendingReadiness = true
            return
        }
        if (lifecycleOperationActive || retryRunnable != null) {
            pendingReadiness = true
            return
        }
        if (retryAttempt >= retryDelaysMs.size) {
            exhaustRecovery(reason)
            return
        }
        val delay = retryDelaysMs[retryAttempt]
        retryAttempt += 1
        val generation = recoveryGeneration
        val runnable = Runnable {
            retryRunnable = null
            if (
                !disposed &&
                foreground &&
                generation == recoveryGeneration &&
                activeCaptureDeviceId != null
            ) {
                if (requestedDomain == RecoveryDomain.CONFIGURATION) {
                    retryConfiguration(generation)
                } else {
                    requestReadiness(reason, explicit = false)
                }
            }
        }
        retryRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun retryConfiguration(generation: Long) {
        if (
            generation != recoveryGeneration ||
            disposed ||
            !foreground ||
            lifecycleOperationActive
        ) {
            return
        }
        val config = pendingRfidConfig ?: run {
            retryAttempt = 0
            recoveryDomain = null
            return
        }
        lifecycleOperationActive = true
        logDebug(
            "RFID recovery generation=$generation stage=configuration_retry " +
                "attempt=$retryAttempt",
        )
        runCatching {
            rfidInterface.configureReader(
                config.toReaderConfig(),
                shouldPersist = false,
                fromCaptureDevice = true,
            )
        }.fold(
            onSuccess = {
                activeRfidStatus = CaptureCapabilityStatus.CONNECTED
                activeRfidError = null
                retryAttempt = 0
                recoveryDomain = null
                restoreBarcode(generation)
            },
            onFailure = { error ->
                lifecycleOperationActive = false
                activeRfidStatus = CaptureCapabilityStatus.ERROR
                activeRfidError =
                    "Connected, but RFID configuration failed: " +
                    (error.message ?: error.toString())
                emitDevices()
                scheduleRecovery("configuration_retry")
            },
        )
    }

    private fun recoveryDomainFor(reason: String): RecoveryDomain =
        if (reason == "configuration_retry") {
            RecoveryDomain.CONFIGURATION
        } else {
            RecoveryDomain.RFID
        }

    private fun exhaustRecovery(reason: String) {
        pendingReadiness = false
        lifecycleOperationActive = false
        activeRfidStatus = CaptureCapabilityStatus.ERROR
        activeRfidError = "Capture Device recovery exhausted: $reason"
        logWarning("recovery_exhausted reason=$reason")
        emitDevices()
        completeJoined(Result.failure(IllegalStateException(activeRfidError)))
    }

    private fun cancelRetry() {
        retryRunnable?.let(mainHandler::removeCallbacks)
        retryRunnable = null
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(tag, message) }
    }

    private fun logWarning(message: String, error: Throwable? = null) {
        runCatching {
            if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
        }
    }

    private fun completeJoined(result: Result<Unit>) {
        val callbacks = joinedCallbacks.toList()
        joinedCallbacks.clear()
        callbacks.forEach { it(result) }
    }

    private fun refreshDiscovery() {
        rfidInterface.getAvailableReaderList(ReaderConnectionType.ALL)
        barcodeInterface.refreshBarcodeScanners(context)
    }

    private fun emitDevices() {
        val devices = buildDevices()
        callbacks.onAvailableCaptureDevicesChanged(devices) {}
        val active = devices.firstOrNull { it.id == activeCaptureDeviceId }
        callbacks.onActiveCaptureDeviceChanged(active) {}
        active?.let { callbacks.onCaptureDeviceStatusChanged(it) {} }
    }

    private fun buildDevices(): List<CaptureDevice> =
        planner.buildDevices(
            readers = rfidInterface.availableReadersSnapshot(),
            endpoints = barcodeInterface.barcodeEndpoints(),
            state = CaptureDevicePlanningState(
                activeCaptureDeviceId = activeCaptureDeviceId,
                activeRfidStatus = activeRfidStatus,
                activeBarcodeStatus = activeBarcodeStatus,
                activeRfidError = activeRfidError,
                activeBarcodeError = activeBarcodeError,
                barcodeOverrides = barcodeOverrides,
            ),
        )
}

private fun CaptureReaderConfig.toReaderConfig(): ReaderConfig = ReaderConfig(
    transmitPowerIndex,
    tari,
    beeperVolume?.toReaderBeeperVolume(),
    enableDynamicPower,
    enableLedBlink,
    batchMode?.toReaderConfigBatchMode(),
    scanBatchMode?.toReaderConfigBatchMode(),
    rfModeTableIndex,
    receiveSensitivityIndex,
)

private fun CaptureReaderBeeperVolume.toReaderBeeperVolume(): ReaderBeeperVolume =
    when (this) {
        CaptureReaderBeeperVolume.QUIET -> ReaderBeeperVolume.QUIET
        CaptureReaderBeeperVolume.LOW -> ReaderBeeperVolume.LOW
        CaptureReaderBeeperVolume.MEDIUM -> ReaderBeeperVolume.MEDIUM
        CaptureReaderBeeperVolume.HIGH -> ReaderBeeperVolume.HIGH
    }

private fun CaptureReaderConfigBatchMode.toReaderConfigBatchMode(): ReaderConfigBatchMode =
    when (this) {
        CaptureReaderConfigBatchMode.AUTO -> ReaderConfigBatchMode.AUTO
        CaptureReaderConfigBatchMode.ENABLED -> ReaderConfigBatchMode.ENABLED
        CaptureReaderConfigBatchMode.DISABLED -> ReaderConfigBatchMode.DISABLED
    }
