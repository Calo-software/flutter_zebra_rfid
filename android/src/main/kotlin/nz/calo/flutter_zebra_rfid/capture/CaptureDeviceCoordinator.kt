package nz.calo.flutter_zebra_rfid.capture

import BarcodeScannerMode
import CaptureCapabilityStatus
import CaptureDevice
import CaptureDeviceTopology
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
import nz.calo.flutter_zebra_rfid.hardware.currentZebraHostIdentity
import nz.calo.flutter_zebra_rfid.rfid.RFIDReaderInterface

class CaptureDeviceCoordinator(
    private val context: Context,
    private val rfidInterface: RFIDReaderInterface,
    private val barcodeInterface: BarcodeScannerInterface,
    private val callbacks: FlutterZebraCaptureCallbacks,
) : FlutterZebraCapture {
    private val tag = "FlutterZebraCapture"
    private val hostIdentity = currentZebraHostIdentity()
    private val planner = CaptureDevicePlanner(
        CaptureDevicePlanningPlatform.ANDROID,
        hostIsEm45 = hostIdentity.isEm45,
    )
    private val barcodeOverrides = linkedMapOf<String, String>()
    private var activeCaptureDeviceId: String? = null
    private var activeRfidStatus: CaptureCapabilityStatus? = null
    private var activeBarcodeStatus: CaptureCapabilityStatus? = null
    private var activeRfidError: String? = null
    private var activeBarcodeError: String? = null
    private var pendingRfidConfig: CaptureReaderConfig? = null
    private var pendingRfidConfigApplied = false
    private var pendingBarcodeEndpointId: String? = null

    init {
        rfidInterface.readersChangedListener = { emitDevices() }
        rfidInterface.connectionStatusListener = { status ->
            activeRfidStatus = status.toCaptureStatus()
            if (status == ReaderConnectionStatus.CONNECTED) {
                activeRfidError = null
                applyPendingRfidConfig()
                connectPendingBarcodeEndpoint()
            } else if (
                pendingBarcodeEndpointId != null &&
                (status == ReaderConnectionStatus.DISCONNECTED || status == ReaderConnectionStatus.ERROR)
            ) {
                pendingBarcodeEndpointId = null
                activeBarcodeStatus = CaptureCapabilityStatus.DISCONNECTED
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
            pendingBarcodeEndpointId = null

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

            val integratedEm45 = device.topology == CaptureDeviceTopology.INTEGRATED_MOBILE_COMPUTER
            val connectRfid = {
                val rfid = device.rfid
                if (rfid != null) {
                    try {
                        rfidInterface.connectReader(rfid.readerId.toLong())
                    } catch (e: Throwable) {
                        activeRfidStatus = CaptureCapabilityStatus.ERROR
                        activeRfidError = e.message ?: e.toString()
                        Log.e(tag, "RFID connect failed", e)
                    }
                }
            }

            if (integratedEm45 && device.rfid != null && device.barcode != null) {
                pendingBarcodeEndpointId = device.barcode.endpointId
                Log.i(
                    tag,
                    "EM45 Capture Device releasing DataWedge Barcode Endpoint before RFID connect " +
                        "endpoint=${device.barcode.endpointId}",
                )
                barcodeInterface.prepareForIntegratedRfid(connectRfid)
            } else {
                connectRfid()
            }

            if (!integratedEm45) device.barcode?.let { barcode ->
                try {
                    connectBarcodeEndpoint(barcode.endpointId)
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
                pendingBarcodeEndpointId = null
            }
            rfidInterface.disconnectCurrentReader()
            if (hostIdentity.isEm45) {
                barcodeInterface.configureDataWedgeIdle()
            }
            barcodeInterface.disconnectCurrentScanner()
            if (activeCaptureDeviceId == captureDeviceId) {
                activeCaptureDeviceId = null
            }
            emitDevices()
            callback(Result.success(Unit))
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
            integratedReaderIds = rfidInterface.integratedReaderIdsSnapshot(),
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

    private fun connectPendingBarcodeEndpoint() {
        val endpointId = pendingBarcodeEndpointId ?: return
        pendingBarcodeEndpointId = null
        try {
            Log.i(tag, "EM45 RFID connected; activating Barcode Endpoint endpoint=$endpointId")
            connectBarcodeEndpoint(endpointId)
        } catch (e: Throwable) {
            activeBarcodeStatus = CaptureCapabilityStatus.ERROR
            activeBarcodeError = e.message ?: e.toString()
            Log.e(tag, "Deferred Barcode connect failed", e)
        }
    }

    private fun connectBarcodeEndpoint(endpointId: String) {
        barcodeInterface.setActiveEndpoint(endpointId)
        val endpoint = barcodeInterface.barcodeEndpoints()
            .firstOrNull { it.endpointId == endpointId }
        if (endpoint?.mode == BarcodeScannerMode.SCANNER_SDK && endpoint.scannerId != null) {
            barcodeInterface.connectToScanner(endpoint.scannerId.toInt())
        } else {
            activeBarcodeStatus = CaptureCapabilityStatus.CONNECTED
        }
        activeBarcodeError = null
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
