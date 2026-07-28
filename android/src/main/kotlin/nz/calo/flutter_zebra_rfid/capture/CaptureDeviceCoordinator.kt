package nz.calo.flutter_zebra_rfid.capture

import BarcodeScannerMode
import BarcodeScannerEndpoint
import CaptureBarcodeSource
import CaptureDiagnosticEvent
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
import nz.calo.flutter_zebra_rfid.rfid.CaptureDeviceBarcodeTriggerTarget
import org.json.JSONObject

/**
 * Owns the lifecycle of the RFID Reader and Barcode Endpoint that together form
 * the active Capture Device.
 */
class CaptureDeviceCoordinator(
    private val context: Context,
    private val rfidInterface: RFIDReaderInterface,
    private val barcodeInterface: BarcodeScannerInterface,
    private val callbacks: FlutterZebraCaptureCallbacks,
    private val diagnosticStore: CaptureDiagnosticStore? = null,
) : FlutterZebraCapture {
    private val tag = "FlutterZebraCapture"
    private val planner = CaptureDevicePlanner(CaptureDevicePlanningPlatform.ANDROID)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val barcodeOverrides = linkedMapOf<String, String>()
    private val joinedCallbacks = mutableListOf<(Result<Unit>) -> Unit>()
    private val retryDelaysMs = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    private val bluetoothReaderBootGraceMs = 8_000L

    private enum class RecoveryDomain {
        RFID,
        CONFIGURATION,
    }

    private var activeCaptureDeviceId: String? = null
    private var selectedHardwareIdentity: String? = null
    private var selectedBarcodeEndpoint: BarcodeScannerEndpoint? = null
    private var foregroundResumeBarcodeEndpointId: String? = null
    private var foregroundReadinessCheckGeneration = 0L
    private var barcodeSessionConnected = false
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
    private var rfidWasReady = false
    private var rfidTransportReady = false
    private var rfidRecoveryNeedsConfirmation = false
    private var rfidRecoveryLifecycleFinished = false
    private var rfidRecoveryActivityObserved = false
    private var disposed = false

    init {
        rfidInterface.readersChangedListener = {
            recordReaderSnapshot("readers_changed")
            emitDevices()
            if (pendingReadiness && foreground && !lifecycleOperationActive) {
                requestReadiness("reader_discovery", explicit = false)
            }
        }
        rfidInterface.connectionStatusListener = { status ->
            recordDiagnostic("rfid", "connection_status", status.name)
            activeRfidStatus = status.toCaptureStatus()
            when (status) {
                ReaderConnectionStatus.CONNECTED -> onRfidTransportReady()
                ReaderConnectionStatus.DISCONNECTED,
                ReaderConnectionStatus.ERROR,
                -> {
                    if (activeCaptureDeviceId != null) {
                        if (rfidWasReady) {
                            rfidRecoveryNeedsConfirmation = true
                            rfidRecoveryLifecycleFinished = false
                            rfidRecoveryActivityObserved = false
                        }
                        rfidTransportReady = false
                        pendingReadiness = true
                        // The RFID interface owns serialized native cleanup.
                        // Wait for managedRecoveryRequestListener before
                        // rediscovery; starting from this status notification
                        // can create the replacement reader before the old
                        // Zebra session has been disposed.
                        emitDevices()
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
            recordDiagnostic(
                category = "rfid",
                operation = "command_liveness",
                outcome = "recovery_requested",
                details = mapOf(
                    "reason" to reason,
                    "generation" to recoveryGeneration,
                    "rfid_status" to activeRfidStatus?.name,
                    "barcode_status" to activeBarcodeStatus?.name,
                ),
            )
            lifecycleOperationActive = false
            pendingReadiness = true
            scheduleRecovery(reason)
        }
        rfidInterface.managedReadinessActivityListener = {
            if (rfidRecoveryNeedsConfirmation) {
                rfidRecoveryActivityObserved = true
                confirmRecoveredRfidIfReady()
            }
        }
        barcodeInterface.endpointsChangedListener = {
            recordBarcodeEndpointSnapshot("endpoints_changed")
            refreshSelectedBarcodeEndpointSnapshot()
            adoptLateBarcodeEndpointIfAvailable()
            emitDevices()
        }
        barcodeInterface.connectionStatusListener = { status ->
            recordDiagnostic(
                category = "barcode",
                operation = "connection_status",
                outcome = status.name,
                details = barcodeDetails(selectedBarcodeEndpoint),
            )
            activeBarcodeStatus = status.toCaptureStatus()
            if (status == ScannerConnectionStatus.CONNECTED) {
                barcodeSessionConnected = true
                activeBarcodeError = null
            } else if (
                status == ScannerConnectionStatus.DISCONNECTED ||
                status == ScannerConnectionStatus.ERROR
            ) {
                barcodeSessionConnected = false
                foregroundResumeBarcodeEndpointId = null
                recoverBarcodeOnly("barcode_session_lost")
            }
            emitDevices()
        }
    }

    override fun refreshCaptureDevices(callback: (Result<Unit>) -> Unit) {
        recordDiagnostic("capture_device", "refresh", "started")
        runCatching {
            rfidInterface.getAvailableReaderList(ReaderConnectionType.ALL)
        }.onFailure {
            recordDiagnostic(
                "capture_device",
                "refresh",
                "failed",
                mapOf(
                    "stage" to "rfid_discovery",
                    "error_type" to it.javaClass.simpleName,
                ),
            )
            callback(Result.failure(it))
            return
        }
        barcodeInterface.refreshBarcodeScanners(context) { result ->
            result.fold(
                onSuccess = {
                    refreshSelectedBarcodeEndpointSnapshot()
                    recordReaderSnapshot("refresh_complete")
                    recordBarcodeEndpointSnapshot("refresh_complete")
                    emitDevices()
                    recordDiagnostic("capture_device", "refresh", "completed")
                    callback(Result.success(Unit))
                },
                onFailure = {
                    recordDiagnostic(
                        "capture_device",
                        "refresh",
                        "failed",
                        mapOf(
                            "stage" to "datawedge_health",
                            "error_type" to it.javaClass.simpleName,
                        ),
                    )
                    emitDevices()
                    // DataWedge failure must not prevent the RFID-only portion
                    // of a Capture Device from connecting. The coordinator
                    // will expose a degraded barcode capability and recover it
                    // when DataWedge later enumerates a usable endpoint.
                    callback(Result.success(Unit))
                },
            )
        }
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
        recordDiagnostic(
            category = "capture_device",
            operation = "connect_requested",
            outcome = "selected",
            details = captureDeviceDetails(device),
        )

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
            resetRfidReadiness()
        }
        activeCaptureDeviceId = captureDeviceId
        selectedHardwareIdentity = device.rfid?.hardwareIdentity
        foregroundResumeBarcodeEndpointId = null
        barcodeSessionConnected = false
        selectedBarcodeEndpoint = device.barcode?.endpointId?.let { endpointId ->
            barcodeInterface.barcodeEndpoints()
                .firstOrNull { it.endpointId == endpointId }
        }
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
        foregroundResumeBarcodeEndpointId = null
        barcodeSessionConnected = false
        resetRfidReadiness()
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
            selectedBarcodeEndpoint = null
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
        if (activeCaptureDeviceId == captureDeviceId) {
            foregroundResumeBarcodeEndpointId = null
            barcodeSessionConnected = false
            selectedBarcodeEndpoint = barcodeInterface.barcodeEndpoints()
                .firstOrNull { it.endpointId == barcodeEndpointId }
        }
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
        recordDiagnostic(
            category = "capture_device",
            operation = "foreground",
            outcome = if (foreground) "resumed" else "backgrounded",
        )
        if (this.foreground == foreground) {
            callback(Result.success(Unit))
            return
        }
        this.foreground = foreground
        if (!foreground) {
            foregroundReadinessCheckGeneration += 1
            cancelRetry()
        } else if (activeCaptureDeviceId != null) {
            retryAttempt = 0
            if (
                rfidWasReady &&
                selectedBarcodeEndpoint?.mode == BarcodeScannerMode.SCANNER_SDK
            ) {
                // A Bluetooth combo RFD can retain both SDK sessions across
                // screen lock while losing usable shared-trigger routing.
                // Transport and configuration success do not prove that the
                // first post-resume RFID trigger can still deliver a tag.
                rfidRecoveryNeedsConfirmation = true
                rfidRecoveryLifecycleFinished = false
                rfidRecoveryActivityObserved = false
            }
            val endpoint = selectedBarcodeEndpoint
            if (
                activeBarcodeStatus == CaptureCapabilityStatus.CONNECTED &&
                endpoint?.mode == BarcodeScannerMode.DATA_WEDGE
            ) {
                val checkGeneration = ++foregroundReadinessCheckGeneration
                barcodeInterface.verifyRetainedDataWedgeEndpointReady(
                    endpoint.endpointId,
                ) { ready ->
                    if (
                        disposed ||
                        !this.foreground ||
                        checkGeneration != foregroundReadinessCheckGeneration ||
                        selectedBarcodeEndpoint?.endpointId != endpoint.endpointId
                    ) {
                        return@verifyRetainedDataWedgeEndpointReady
                    }
                    foregroundResumeBarcodeEndpointId =
                        endpoint.endpointId.takeIf { ready }
                    if (ready && rfidWasReady) {
                        rearmRetainedRfidAfterForeground(
                            checkGeneration = checkGeneration,
                            endpointId = endpoint.endpointId,
                        )
                    } else {
                        requestReadiness("foreground_resume", explicit = true)
                    }
                }
            } else {
                foregroundReadinessCheckGeneration += 1
                requestReadiness("foreground_resume", explicit = true)
            }
        }
        callback(Result.success(Unit))
    }

    private fun rearmRetainedRfidAfterForeground(
        checkGeneration: Long,
        endpointId: String,
    ) {
        recordDiagnostic(
            category = "rfid",
            operation = "foreground_readiness",
            outcome = "retained_session_check",
            details = mapOf("barcode_endpoint_id" to endpointId),
        )
        rfidInterface.reassertCaptureDeviceTriggerOwnership { result ->
            if (
                disposed ||
                !foreground ||
                checkGeneration != foregroundReadinessCheckGeneration ||
                selectedBarcodeEndpoint?.endpointId != endpointId
            ) {
                return@reassertCaptureDeviceTriggerOwnership
            }
            if (result.isSuccess) {
                foregroundResumeBarcodeEndpointId = null
                activeRfidError = null
                activeRfidStatus = CaptureCapabilityStatus.CONNECTED
                activeBarcodeError = null
                activeBarcodeStatus = CaptureCapabilityStatus.CONNECTED
                recordDiagnostic(
                    category = "rfid",
                    operation = "foreground_readiness",
                    outcome = "retained_ready",
                    details = mapOf("barcode_endpoint_id" to endpointId),
                )
                emitDevices()
            } else {
                recordDiagnostic(
                    category = "rfid",
                    operation = "foreground_readiness",
                    outcome = "retained_check_failed",
                    details = mapOf(
                        "barcode_endpoint_id" to endpointId,
                        "failure_type" to (
                            result.exceptionOrNull()?.javaClass?.simpleName
                                ?: "unknown"
                            ),
                    ),
                )
                requestReadiness("foreground_resume", explicit = true)
            }
        }
    }

    override fun activeCaptureDevice(): CaptureDevice? =
        buildDevices().firstOrNull { it.id == activeCaptureDeviceId }

    override fun captureDiagnostics(): List<CaptureDiagnosticEvent> =
        diagnosticStore?.snapshot()?.map { record ->
            CaptureDiagnosticEvent(
                timestampMs = record.timestampMs,
                sequence = record.sequence,
                category = record.category,
                operation = record.operation,
                outcome = record.outcome,
                detailsJson = JSONObject(record.details).toString(),
            )
        }.orEmpty()

    override fun clearCaptureDiagnostics() {
        diagnosticStore?.clear()
    }

    fun dispose() {
        recordDiagnostic("capture_device", "dispose", "started")
        disposed = true
        recoveryGeneration += 1
        cancelRetry()
        foregroundResumeBarcodeEndpointId = null
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
        foregroundResumeBarcodeEndpointId =
            if (
                reason == "foreground_resume" &&
                activeBarcodeStatus == CaptureCapabilityStatus.CONNECTED &&
                selectedBarcodeEndpoint?.endpointId?.let {
                    barcodeInterface.isRetainedDataWedgeEndpointReady(it)
                } == true
            ) {
                selectedBarcodeEndpoint?.endpointId
            } else {
                null
            }
        logDebug("RFID recovery generation=$generation stage=discovery reason=$reason")
        recordDiagnostic(
            category = "capture_device",
            operation = "readiness",
            outcome = "started",
            details = mapOf(
                "reason" to reason,
                "generation" to generation,
                "attempt" to retryAttempt,
                "joined_requests" to joinedCallbacks.size,
            ),
        )

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
        val barcodeTriggerTarget = when (device.barcode?.source) {
            CaptureBarcodeSource.BUILT_IN_TERMINAL ->
                CaptureDeviceBarcodeTriggerTarget.TERMINAL_IMAGER
            null -> CaptureDeviceBarcodeTriggerTarget.NONE
            else -> CaptureDeviceBarcodeTriggerTarget.RFD_BARCODE_ENGINE
        }
        runCatching {
            rfidInterface.connectReaderForCaptureDevice(
                readerId = rfid.readerId,
                hardwareIdentity = rfid.hardwareIdentity,
                barcodeTriggerTarget = barcodeTriggerTarget,
            )
        }.onFailure {
            failRecovery(generation, "rfid_session_lost", it)
        }
    }

    private fun onRfidTransportReady() {
        if (activeCaptureDeviceId == null || disposed) return
        activeRfidError = null
        rfidTransportReady = true
        activeRfidStatus = if (rfidRecoveryNeedsConfirmation) {
            CaptureCapabilityStatus.CONNECTING
        } else {
            rfidWasReady = true
            CaptureCapabilityStatus.CONNECTED
        }
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
        continueAfterRfidSetup(generation)
    }

    private fun continueAfterRfidSetup(generation: Long) {
        if (generation != recoveryGeneration || disposed) return
        if (!rfidRecoveryNeedsConfirmation) {
            restoreBarcode(generation)
            return
        }

        val device = buildDevices()
            .firstOrNull { it.id == activeCaptureDeviceId }
        if (device?.barcode != null) {
            // A shared RFD session starts recovery with trigger events gated.
            // Arm RFID before waiting for tag proof; the second reassertion
            // still occurs after barcode restoration.
            rfidInterface.reassertCaptureDeviceTriggerOwnership { result ->
                if (generation != recoveryGeneration || disposed) {
                    return@reassertCaptureDeviceTriggerOwnership
                }
                result.fold(
                    onSuccess = {
                        // The RFD40 shares trigger routing between the RFID SDK
                        // and Scanner SDK. Restore the barcode session before
                        // waiting for tag proof, then reassert RFID ownership
                        // once more after barcode scan-enable. Some
                        // devices do not resume handheld trigger events until
                        // both native sessions have been restored.
                        restoreBarcode(generation)
                    },
                    onFailure = { error ->
                        failRecovery(generation, "configuration_retry", error)
                    },
                )
            }
            return
        }
        waitForRecoveredRfidTag(generation, hasBarcode = false)
    }

    private fun waitForRecoveredRfidTag(
        generation: Long,
        hasBarcode: Boolean,
    ) {
        if (generation != recoveryGeneration || disposed) return
        // Prove that the recovered RFID event/inventory path can deliver a
        // real tag. A connected transport or successful configuration is not
        // sufficient evidence of recovery.
        lifecycleOperationActive = false
        pendingReadiness = false
        rfidRecoveryLifecycleFinished = true
        activeRfidStatus = CaptureCapabilityStatus.CONNECTING
        activeBarcodeStatus = if (hasBarcode) {
            if (barcodeSessionConnected) {
                CaptureCapabilityStatus.CONNECTED
            } else {
                CaptureCapabilityStatus.DISCONNECTED
            }
        } else {
            CaptureCapabilityStatus.UNAVAILABLE
        }
        logDebug(
            "RFID setup complete; awaiting real tag " +
                "generation=$generation",
        )
        emitDevices()
    }

    private fun restoreBarcode(generation: Long) {
        if (generation != recoveryGeneration || disposed) return
        val device = buildDevices().firstOrNull { it.id == activeCaptureDeviceId }
        val barcode = device?.barcode
        if (barcode == null) {
            recordDiagnostic(
                category = "barcode",
                operation = "restore",
                outcome = "capability_missing",
                details = mapOf("generation" to generation),
            )
            foregroundResumeBarcodeEndpointId = null
            barcodeSessionConnected = false
            activeBarcodeStatus = CaptureCapabilityStatus.UNAVAILABLE
            finishAfterBarcodeRestoration(generation)
            return
        }

        val retainedEndpointId = foregroundResumeBarcodeEndpointId
        if (
            retainedEndpointId == barcode.endpointId &&
            barcodeInterface.isRetainedDataWedgeEndpointReady(retainedEndpointId)
        ) {
            foregroundResumeBarcodeEndpointId = null
            barcodeSessionConnected = true
            activeBarcodeStatus = CaptureCapabilityStatus.CONNECTED
            activeBarcodeError = null
            recordDiagnostic(
                category = "barcode",
                operation = "foreground_readiness",
                outcome = "retained_ready",
                details = barcodeDetails(selectedBarcodeEndpoint).plus(
                    "generation" to generation,
                ),
            )
            finishAfterBarcodeRestoration(generation)
            return
        }

        val discoveredBarcode = barcodeInterface.barcodeEndpoints()
            .firstOrNull { it.endpointId == barcode.endpointId }
        foregroundResumeBarcodeEndpointId = null
        activeBarcodeStatus = CaptureCapabilityStatus.CONNECTING
        emitDevices()
        try {
            val endpoint = discoveredBarcode ?: selectedBarcodeEndpoint
            recordDiagnostic(
                category = "barcode",
                operation = "restore",
                outcome = "started",
                details = barcodeDetails(endpoint).plus(
                    mapOf(
                        "generation" to generation,
                        "discovered" to (discoveredBarcode != null),
                    ),
                ),
            )
            if (endpoint?.mode == BarcodeScannerMode.DATA_WEDGE) {
                barcodeInterface.setActiveEndpointForCaptureDevice(
                    barcode.endpointId,
                ) { result ->
                    if (generation != recoveryGeneration || disposed) {
                        return@setActiveEndpointForCaptureDevice
                    }
                    completeBarcodeRestoration(generation, result)
                }
                return
            }

            barcodeInterface.setActiveEndpointForCaptureDevice(barcode.endpointId)
            if (
                endpoint?.mode == BarcodeScannerMode.SCANNER_SDK &&
                endpoint.scannerId != null
            ) {
                barcodeInterface.connectToScannerForCaptureDevice(
                    endpoint.scannerId.toInt(),
                ) { result ->
                    if (generation != recoveryGeneration || disposed) {
                        return@connectToScannerForCaptureDevice
                    }
                    completeBarcodeRestoration(generation, result)
                }
            } else {
                completeBarcodeRestoration(
                    generation,
                    Result.success(Unit),
                )
            }
        } catch (error: Throwable) {
            completeBarcodeRestoration(generation, Result.failure(error))
        }
    }

    private fun completeBarcodeRestoration(
        generation: Long,
        result: Result<Unit>,
    ) {
        if (generation != recoveryGeneration || disposed) return
        result.fold(
            onSuccess = {
                recordDiagnostic(
                    category = "barcode",
                    operation = "restore",
                    outcome = "completed",
                    details = barcodeDetails(selectedBarcodeEndpoint).plus(
                        "generation" to generation,
                    ),
                )
                barcodeSessionConnected = true
                activeBarcodeStatus = CaptureCapabilityStatus.CONNECTED
                activeBarcodeError = null
            },
            onFailure = { error ->
                recordDiagnostic(
                    category = "barcode",
                    operation = "restore",
                    outcome = "failed",
                    details = barcodeDetails(selectedBarcodeEndpoint).plus(
                        mapOf(
                            "generation" to generation,
                            "error_type" to error.javaClass.simpleName,
                        ),
                    ),
                )
                barcodeSessionConnected = false
                activeBarcodeStatus = CaptureCapabilityStatus.ERROR
                activeBarcodeError = error.message ?: error.toString()
            },
        )
        finishAfterBarcodeRestoration(generation)
    }

    private fun finishAfterBarcodeRestoration(generation: Long) {
        if (generation != recoveryGeneration || disposed) return
        val device = buildDevices().firstOrNull { it.id == activeCaptureDeviceId }
        if (
            device?.rfid == null ||
            device.barcode == null ||
            !rfidTransportReady
        ) {
            finishRecovery(generation)
            return
        }
        val endpoint = selectedBarcodeEndpoint
        if (
            activeBarcodeStatus != CaptureCapabilityStatus.CONNECTED ||
            endpoint?.mode != BarcodeScannerMode.SCANNER_SDK ||
            endpoint.scannerId == null
        ) {
            reassertFinalRfidTriggerOwnership(generation)
            return
        }
        barcodeInterface.enableScannerForCaptureDevice(
            endpoint.scannerId.toInt(),
        ) { result ->
            if (generation != recoveryGeneration || disposed) {
                return@enableScannerForCaptureDevice
            }
            result.fold(
                onSuccess = {
                    barcodeSessionConnected = true
                    activeBarcodeStatus = CaptureCapabilityStatus.CONNECTED
                    activeBarcodeError = null
                    logDebug(
                        "Barcode scanning enabled before final RFID trigger ownership " +
                            "generation=$generation",
                    )
                },
                onFailure = { error ->
                    barcodeSessionConnected = false
                    activeBarcodeStatus = CaptureCapabilityStatus.ERROR
                    activeBarcodeError = error.message ?: error.toString()
                    logWarning(
                        "Barcode scan-enable failed before final RFID trigger ownership",
                        error,
                    )
                },
            )
            reassertFinalRfidTriggerOwnership(generation)
        }
    }

    private fun reassertFinalRfidTriggerOwnership(generation: Long) {
        if (generation != recoveryGeneration || disposed) return
        rfidInterface.reassertCaptureDeviceTriggerOwnership { result ->
            if (generation != recoveryGeneration || disposed) {
                return@reassertCaptureDeviceTriggerOwnership
            }
            result.fold(
                onSuccess = {
                    logDebug(
                        "RFID trigger ownership restored after barcode " +
                            "generation=$generation",
                    )
                    finishRecovery(generation)
                },
                onFailure = { error ->
                    failRecovery(generation, "configuration_retry", error)
                },
            )
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
        recordDiagnostic(
            category = "recovery",
            operation = "capture_device_recovery",
            outcome = "lifecycle_complete",
            details = mapOf(
                "generation" to generation,
                "attempt" to retryAttempt,
                "rfid_status" to activeRfidStatus?.name,
                "barcode_status" to activeBarcodeStatus?.name,
            ),
        )
        lifecycleOperationActive = false
        pendingReadiness = false
        retryAttempt = 0
        recoveryDomain = null
        activeRfidError = null
        if (activeBarcodeStatus == CaptureCapabilityStatus.CONNECTED) {
            activeBarcodeError = null
        }
        rfidRecoveryLifecycleFinished = true
        if (rfidRecoveryNeedsConfirmation) {
            if (rfidRecoveryActivityObserved) {
                confirmRecoveredRfidIfReady()
            } else {
                logDebug(
                    "RFID and barcode sessions restored; awaiting real " +
                        "post-recovery tag generation=$generation",
                )
                emitDevices()
            }
            return
        }
        logDebug("capture_device_recovered generation=$generation")
        emitDevices()
        completeJoined(Result.success(Unit))
    }

    private fun confirmRecoveredRfidIfReady() {
        if (
            !rfidRecoveryNeedsConfirmation ||
            !rfidTransportReady ||
            !rfidRecoveryLifecycleFinished ||
            !rfidRecoveryActivityObserved
        ) {
            return
        }
        rfidRecoveryNeedsConfirmation = false
        rfidWasReady = true
        activeRfidStatus = CaptureCapabilityStatus.CONNECTED
        activeRfidError = null
        logDebug("RFID recovery confirmed by real post-recovery tag")
        emitDevices()
        finishRecovery(recoveryGeneration)
    }

    private fun resetRfidReadiness() {
        rfidWasReady = false
        rfidTransportReady = false
        rfidRecoveryNeedsConfirmation = false
        rfidRecoveryLifecycleFinished = false
        rfidRecoveryActivityObserved = false
    }

    private fun failRecovery(
        generation: Long,
        stage: String,
        error: Throwable,
    ) {
        if (generation != recoveryGeneration || disposed) return
        recordDiagnostic(
            category = "recovery",
            operation = stage,
            outcome = "failed",
            details = mapOf(
                "generation" to generation,
                "attempt" to retryAttempt,
                "error_type" to error::class.java.simpleName,
            ),
        )
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
        val usesBluetoothBootGrace =
            reason == "physical_reader_disconnect" &&
            retryAttempt == 0 &&
            selectedBarcodeEndpoint?.mode == BarcodeScannerMode.SCANNER_SDK
        val delay = if (usesBluetoothBootGrace) {
            // A powered-off Bluetooth RFD remains in Zebra's paired discovery
            // list. Connecting immediately can enter the SDK's non-cancellable
            // socket timeout for roughly a minute. Give the sled time to boot
            // before starting the one permitted native RFID connection.
            bluetoothReaderBootGraceMs
        } else {
            retryDelaysMs[retryAttempt]
        }
        retryAttempt += 1
        if (usesBluetoothBootGrace) {
            logDebug(
                "Bluetooth RFD boot grace scheduled delay=${delay}ms " +
                    "generation=$recoveryGeneration",
            )
        }
        val generation = recoveryGeneration
        val runnable = Runnable {
            retryRunnable = null
            if (
                !disposed &&
                foreground &&
                generation == recoveryGeneration &&
                activeCaptureDeviceId != null
            ) {
                if (reason == "configuration_retry") {
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
                activeRfidStatus = if (rfidRecoveryNeedsConfirmation) {
                    CaptureCapabilityStatus.CONNECTING
                } else {
                    CaptureCapabilityStatus.CONNECTED
                }
                activeRfidError = null
                retryAttempt = 0
                recoveryDomain = null
                continueAfterRfidSetup(generation)
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
        if (reason == "configuration_retry" || reason == "rfid_setup_retry") {
            RecoveryDomain.CONFIGURATION
        } else {
            RecoveryDomain.RFID
        }

    private fun exhaustRecovery(reason: String) {
        recordDiagnostic(
            category = "recovery",
            operation = "capture_device_recovery",
            outcome = "exhausted",
            details = mapOf(
                "generation" to recoveryGeneration,
                "attempt" to retryAttempt,
                "reason" to reason,
            ),
        )
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

    private fun recordDiagnostic(
        category: String,
        operation: String,
        outcome: String,
        details: Map<String, Any?> = emptyMap(),
    ) {
        diagnosticStore?.record(category, operation, outcome, details)
    }

    private fun recordReaderSnapshot(reason: String) {
        val readers = rfidInterface.availableReadersSnapshot()
        recordDiagnostic(
            category = "rfid",
            operation = "reader_snapshot",
            outcome = reason,
            details = mapOf("reader_count" to readers.size),
        )
        readers.forEach { reader ->
            recordDiagnostic(
                category = "rfid",
                operation = "reader_discovered",
                outcome = reason,
                details = mapOf(
                    "reader_id" to reader.id,
                    "hardware_identity" to reader.hardwareIdentity,
                    "reader_name" to reader.name,
                    "reader_serial" to reader.info?.serialNumber,
                    "reader_model" to reader.info?.modelVersion,
                    "reader_firmware" to reader.info?.firmwareVersion,
                ),
            )
        }
    }

    private fun recordBarcodeEndpointSnapshot(reason: String) {
        val endpoints = barcodeInterface.barcodeEndpoints()
        recordDiagnostic(
            category = "barcode",
            operation = "endpoint_snapshot",
            outcome = reason,
            details = mapOf("endpoint_count" to endpoints.size),
        )
        endpoints.forEach { endpoint ->
            recordDiagnostic(
                category = "barcode",
                operation = "endpoint_discovered",
                outcome = reason,
                details = barcodeDetails(endpoint),
            )
        }
    }

    private fun barcodeDetails(endpoint: BarcodeScannerEndpoint?): Map<String, Any?> =
        if (endpoint == null) {
            emptyMap()
        } else {
            mapOf(
                "endpoint_id" to endpoint.endpointId,
                "endpoint_name" to endpoint.displayName,
                "endpoint_source" to endpoint.source.name,
                "endpoint_mode" to endpoint.mode.name,
                "endpoint_status" to endpoint.connectionStatus.name,
                "endpoint_active" to endpoint.active,
                "endpoint_preferred" to endpoint.preferred,
                "scanner_id" to endpoint.scannerId,
                "barcode_model" to endpoint.model,
                "barcode_serial" to endpoint.serialNumber,
            )
        }

    private fun captureDeviceDetails(device: CaptureDevice): Map<String, Any?> =
        mapOf(
            "capture_device_id" to device.id,
            "topology" to device.topology.name,
            "capture_status" to device.status.name,
            "match_confidence" to device.matchConfidence.name,
            "match_reason" to device.matchReason,
            "rfid_status" to device.rfid?.status?.name,
            "barcode_status" to device.barcode?.status?.name,
            "reader_serial" to device.rfid?.serialNumber,
            "reader_model" to device.rfid?.model,
            "endpoint_id" to device.barcode?.endpointId,
            "endpoint_mode" to device.barcode?.mode?.name,
            "endpoint_source" to device.barcode?.source?.name,
        )

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
        if (foregroundResumeBarcodeEndpointId == null) {
            barcodeInterface.refreshBarcodeScanners(context)
        } else {
            recordDiagnostic(
                category = "barcode",
                operation = "foreground_readiness",
                outcome = "retained_fast_path",
                details = barcodeDetails(selectedBarcodeEndpoint),
            )
        }
        refreshSelectedBarcodeEndpointSnapshot()
    }

    private fun refreshSelectedBarcodeEndpointSnapshot() {
        val selectedId = selectedBarcodeEndpoint?.endpointId ?: return
        barcodeInterface.barcodeEndpoints()
            .firstOrNull { it.endpointId == selectedId }
            ?.let { selectedBarcodeEndpoint = it }
    }

    private fun adoptLateBarcodeEndpointIfAvailable() {
        if (
            disposed ||
            activeCaptureDeviceId == null ||
            activeRfidStatus != CaptureCapabilityStatus.CONNECTED
        ) {
            return
        }
        val active = buildDevices().firstOrNull { it.id == activeCaptureDeviceId }
            ?: return
        val endpointId = active.barcode?.endpointId ?: return
        if (selectedBarcodeEndpoint?.endpointId == endpointId &&
            activeBarcodeStatus == CaptureCapabilityStatus.CONNECTED
        ) {
            return
        }
        val endpoint = barcodeInterface.barcodeEndpoints()
            .firstOrNull { it.endpointId == endpointId }
            ?: return
        selectedBarcodeEndpoint = endpoint
        activeBarcodeStatus = CaptureCapabilityStatus.CONNECTING
        activeBarcodeError = null
        recordDiagnostic(
            category = "barcode",
            operation = "late_endpoint",
            outcome = "adopted",
            details = barcodeDetails(endpoint),
        )
        if (!lifecycleOperationActive) {
            recoverBarcodeOnly("late_endpoint")
        } else {
            pendingReadiness = true
        }
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
            endpoints = barcodeEndpointsForPlanning(),
            state = CaptureDevicePlanningState(
                activeCaptureDeviceId = activeCaptureDeviceId,
                activeRfidStatus = activeRfidStatus,
                activeBarcodeStatus = activeBarcodeStatus,
                activeRfidError = activeRfidError,
                activeBarcodeError = activeBarcodeError,
                barcodeOverrides = barcodeOverrides,
            ),
        )

    private fun barcodeEndpointsForPlanning(): List<BarcodeScannerEndpoint> {
        val endpoints = barcodeInterface.barcodeEndpoints()
        val selected = selectedBarcodeEndpoint ?: return endpoints
        if (endpoints.any { it.endpointId == selected.endpointId }) {
            return endpoints
        }
        // Scanner SDK discovery can transiently omit an already-owned RFD
        // Barcode Endpoint during foreground resume. Preserve the coordinator's
        // selected capability until the SDK reports a definitive disconnect.
        return endpoints + selected
    }
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
