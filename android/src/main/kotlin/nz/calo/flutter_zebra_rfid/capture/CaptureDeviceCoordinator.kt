package nz.calo.flutter_zebra_rfid.capture

import BarcodeScannerMode
import CaptureCapabilityStatus
import CaptureDevice
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
import android.util.Log
import nz.calo.flutter_zebra_rfid.barcode.BarcodeScannerInterface
import nz.calo.flutter_zebra_rfid.rfid.RFIDReaderInterface

class CaptureDeviceCoordinator(
    private val context: Context,
    private val rfidInterface: RFIDReaderInterface,
    private val barcodeInterface: BarcodeScannerInterface,
    private val callbacks: FlutterZebraCaptureCallbacks,
) : FlutterZebraCapture {
    private val tag = "FlutterZebraCapture"
    private val planner = CaptureDevicePlanner(CaptureDevicePlanningPlatform.ANDROID)
    private val barcodeOverrides = linkedMapOf<String, String>()
    private var activeCaptureDeviceId: String? = null
    private var activeRfidStatus: CaptureCapabilityStatus? = null
    private var activeBarcodeStatus: CaptureCapabilityStatus? = null
    private var activeRfidError: String? = null
    private var activeBarcodeError: String? = null
    private var pendingRfidConfig: CaptureReaderConfig? = null
    private var pendingRfidConfigApplied = false

    init {
        rfidInterface.readersChangedListener = { emitDevices() }
        rfidInterface.connectionStatusListener = { status ->
            activeRfidStatus = status.toCaptureStatus()
            if (status == ReaderConnectionStatus.CONNECTED) {
                activeRfidError = null
                applyPendingRfidConfig()
            }
            emitDevices()
        }
        rfidInterface.connectionErrorListener = { error ->
            activeRfidStatus = CaptureCapabilityStatus.ERROR
            activeRfidError = error.message
            emitDevices()
        }
        barcodeInterface.endpointsChangedListener = { emitDevices() }
        barcodeInterface.connectionStatusListener = { status ->
            activeBarcodeStatus = status.toCaptureStatus()
            if (status == ScannerConnectionStatus.CONNECTED) {
                activeBarcodeError = null
            }
            emitDevices()
        }
    }

    override fun refreshCaptureDevices(callback: (Result<Unit>) -> Unit) {
        try {
            rfidInterface.getAvailableReaderList(ReaderConnectionType.ALL)
            barcodeInterface.refreshBarcodeScanners(context)
            emitDevices()
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun connectCaptureDevice(
        captureDeviceId: String,
        rfidConfig: CaptureReaderConfig?,
        callback: (Result<Unit>) -> Unit,
    ) {
        try {
            val device = buildDevices().firstOrNull { it.id == captureDeviceId }
                ?: throw IllegalArgumentException("Capture Device not available: $captureDeviceId")

            activeCaptureDeviceId = captureDeviceId
            activeRfidError = null
            activeBarcodeError = null
            pendingRfidConfig = rfidConfig
            pendingRfidConfigApplied = false

            activeRfidStatus = if (device.rfid != null) {
                CaptureCapabilityStatus.CONNECTING
            } else {
                CaptureCapabilityStatus.UNAVAILABLE
            }
            activeBarcodeStatus = if (device.barcode != null) {
                CaptureCapabilityStatus.CONNECTING
            } else {
                CaptureCapabilityStatus.UNAVAILABLE
            }
            emitDevices()

            device.rfid?.let { rfid ->
                try {
                    rfidInterface.connectReader(rfid.readerId.toLong())
                } catch (e: Throwable) {
                    activeRfidStatus = CaptureCapabilityStatus.ERROR
                    activeRfidError = e.message ?: e.toString()
                    Log.e(tag, "RFID connect failed", e)
                }
            }

            device.barcode?.let { barcode ->
                try {
                    barcodeInterface.setActiveEndpoint(barcode.endpointId)
                    val endpoint = barcodeInterface.barcodeEndpoints()
                        .firstOrNull { it.endpointId == barcode.endpointId }
                    if (endpoint?.mode == BarcodeScannerMode.SCANNER_SDK && endpoint.scannerId != null) {
                        barcodeInterface.connectToScanner(endpoint.scannerId.toInt()) { result ->
                            result.fold(
                                onSuccess = {
                                    activeBarcodeError = null
                                },
                                onFailure = { error ->
                                    activeBarcodeStatus = CaptureCapabilityStatus.ERROR
                                    activeBarcodeError = error.message ?: error.toString()
                                    Log.e(tag, "Barcode connect failed", error)
                                },
                            )
                            emitDevices()
                            callback(result)
                        }
                        return
                    } else {
                        activeBarcodeStatus = CaptureCapabilityStatus.CONNECTED
                    }
                    activeBarcodeError = null
                } catch (e: Throwable) {
                    activeBarcodeStatus = CaptureCapabilityStatus.ERROR
                    activeBarcodeError = e.message ?: e.toString()
                    Log.e(tag, "Barcode connect failed", e)
                }
            }

            emitDevices()
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun disconnectCaptureDevice(
        captureDeviceId: String,
        callback: (Result<Unit>) -> Unit,
    ) {
        try {
            if (activeCaptureDeviceId == captureDeviceId) {
                activeRfidStatus = CaptureCapabilityStatus.DISCONNECTED
                activeBarcodeStatus = CaptureCapabilityStatus.DISCONNECTED
                activeRfidError = null
                activeBarcodeError = null
                pendingRfidConfig = null
                pendingRfidConfigApplied = false
            }
            rfidInterface.disconnectCurrentReader()
            barcodeInterface.disconnectCurrentScanner { result ->
                if (activeCaptureDeviceId == captureDeviceId) {
                    activeCaptureDeviceId = null
                }
                emitDevices()
                callback(result)
            }
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun setCaptureDeviceBarcodeOverride(
        captureDeviceId: String,
        barcodeEndpointId: String,
        callback: (Result<Unit>) -> Unit,
    ) {
        try {
            val endpointExists = barcodeInterface.barcodeEndpoints()
                .any { it.endpointId == barcodeEndpointId }
            if (!endpointExists) {
                throw IllegalArgumentException("Barcode Endpoint not available: $barcodeEndpointId")
            }
            barcodeOverrides[captureDeviceId] = barcodeEndpointId
            emitDevices()
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun activeCaptureDevice(): CaptureDevice? =
        buildDevices().firstOrNull { it.id == activeCaptureDeviceId }

    private fun emitDevices() {
        val devices = buildDevices()
        callbacks.onAvailableCaptureDevicesChanged(devices) {}
        val active = devices.firstOrNull { it.id == activeCaptureDeviceId }
        callbacks.onActiveCaptureDeviceChanged(active) {}
        active?.let { callbacks.onCaptureDeviceStatusChanged(it) {} }
    }

    private fun buildDevices(): List<CaptureDevice> {
        return planner.buildDevices(
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

    private fun applyPendingRfidConfig() {
        val config = pendingRfidConfig ?: return
        if (pendingRfidConfigApplied) return
        try {
            rfidInterface.configureReader(config.toReaderConfig(), shouldPersist = false)
            pendingRfidConfigApplied = true
        } catch (e: Throwable) {
            activeRfidStatus = CaptureCapabilityStatus.ERROR
            activeRfidError = "Connected, but RFID configuration failed: ${e.message ?: e}"
            Log.e(tag, "Pending RFID config failed", e)
        }
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

private fun CaptureReaderBeeperVolume.toReaderBeeperVolume(): ReaderBeeperVolume = when (this) {
    CaptureReaderBeeperVolume.QUIET -> ReaderBeeperVolume.QUIET
    CaptureReaderBeeperVolume.LOW -> ReaderBeeperVolume.LOW
    CaptureReaderBeeperVolume.MEDIUM -> ReaderBeeperVolume.MEDIUM
    CaptureReaderBeeperVolume.HIGH -> ReaderBeeperVolume.HIGH
}

private fun CaptureReaderConfigBatchMode.toReaderConfigBatchMode(): ReaderConfigBatchMode = when (this) {
    CaptureReaderConfigBatchMode.AUTO -> ReaderConfigBatchMode.AUTO
    CaptureReaderConfigBatchMode.ENABLED -> ReaderConfigBatchMode.ENABLED
    CaptureReaderConfigBatchMode.DISABLED -> ReaderConfigBatchMode.DISABLED
}
