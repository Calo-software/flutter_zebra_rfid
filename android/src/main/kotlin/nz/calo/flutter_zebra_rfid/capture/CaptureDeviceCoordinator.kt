package nz.calo.flutter_zebra_rfid.capture

import BarcodeScannerEndpoint
import BarcodeScannerMode
import BarcodeScannerSource
import CaptureBarcodeCapability
import CaptureBarcodeMode
import CaptureBarcodeSource
import CaptureCapabilityStatus
import CaptureDevice
import CaptureDeviceStatus
import CaptureDeviceTopology
import CaptureMatchConfidence
import CaptureReaderBeeperVolume
import CaptureReaderConfig
import CaptureReaderConfigBatchMode
import CaptureRfidCapability
import FlutterZebraCapture
import FlutterZebraCaptureCallbacks
import Reader
import ReaderBeeperVolume
import ReaderConfig
import ReaderConfigBatchMode
import ReaderConnectionStatus
import ReaderConnectionType
import ReaderError
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
                        barcodeInterface.connectToScanner(endpoint.scannerId.toInt())
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
        val readers = rfidInterface.availableReadersSnapshot()
        val endpoints = barcodeInterface.barcodeEndpoints()
        val assignedEndpoints = linkedSetOf<String>()
        val devices = mutableListOf<CaptureDevice>()

        readers.forEach { reader ->
            val id = captureDeviceId(reader)
            val match = selectBarcodeEndpoint(id, reader, endpoints)
            match.endpoint?.let { assignedEndpoints.add(it.endpointId) }
            devices.add(buildDevice(id, reader, match))
        }

        endpoints
            .filterNot { assignedEndpoints.contains(it.endpointId) }
            .forEach { endpoint ->
                devices.add(buildBarcodeOnlyDevice(endpoint))
            }

        return devices.sortedWith(
            compareByDescending<CaptureDevice> { it.active }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun buildDevice(
        id: String,
        reader: Reader,
        match: BarcodeMatch,
    ): CaptureDevice {
        val active = id == activeCaptureDeviceId
        val rfidStatus = if (active) {
            activeRfidStatus ?: CaptureCapabilityStatus.DISCONNECTED
        } else {
            CaptureCapabilityStatus.DISCONNECTED
        }
        val barcodeStatus = if (active && match.endpoint != null) {
            activeBarcodeStatus ?: match.endpoint.connectionStatus.toCaptureStatus()
        } else {
            match.endpoint?.connectionStatus?.toCaptureStatus()
                ?: CaptureCapabilityStatus.UNAVAILABLE
        }

        val rfid = CaptureRfidCapability(
            reader.id,
            reader.name ?: "RFID reader ${reader.id}",
            rfidStatus,
            reader.info?.modelVersion,
            reader.info?.serialNumber,
            if (active) activeRfidError else null,
        )
        val barcode = match.endpoint?.toCaptureCapability(
            barcodeStatus,
            if (active) activeBarcodeError else null,
        )
        val status = deriveStatus(rfid.status, barcode?.status)
        val displayName = when {
            match.topology == CaptureDeviceTopology.TC22RFID_SLED ->
                "${reader.name ?: "RFID sled"} + terminal barcode"
            barcode != null -> "${reader.name ?: "RFID reader"} + ${barcode.displayName}"
            else -> reader.name ?: "RFID reader ${reader.id}"
        }

        return CaptureDevice(
            id,
            displayName,
            match.topology,
            status,
            match.confidence,
            match.reason,
            active,
            rfid,
            barcode,
            listOfNotNull(rfid.error, barcode?.error).firstOrNull(),
        )
    }

    private fun buildBarcodeOnlyDevice(endpoint: BarcodeScannerEndpoint): CaptureDevice {
        val id = "capture:barcode:${endpoint.endpointId}"
        val active = id == activeCaptureDeviceId
        val status = if (active) {
            activeBarcodeStatus ?: endpoint.connectionStatus.toCaptureStatus()
        } else {
            endpoint.connectionStatus.toCaptureStatus()
        }
        val barcode = endpoint.toCaptureCapability(
            status,
            if (active) activeBarcodeError else null,
        )
        return CaptureDevice(
            id,
            endpoint.displayName,
            CaptureDeviceTopology.BARCODE_ONLY,
            deriveStatus(null, barcode.status),
            CaptureMatchConfidence.HIGH,
            "Barcode endpoint reported without a matching RFID reader.",
            active,
            null,
            barcode,
            barcode.error,
        )
    }

    private fun selectBarcodeEndpoint(
        captureDeviceId: String,
        reader: Reader,
        endpoints: List<BarcodeScannerEndpoint>,
    ): BarcodeMatch {
        val override = barcodeOverrides[captureDeviceId]
        if (override != null) {
            val endpoint = endpoints.firstOrNull { it.endpointId == override }
            if (endpoint != null) {
                return BarcodeMatch(
                    endpoint,
                    CaptureMatchConfidence.MANUAL,
                    "Barcode Endpoint was manually selected for this Capture Device.",
                    topologyFor(reader, endpoint),
                )
            }
        }

        val readerSerial = reader.info?.serialNumber?.trim()?.uppercase()
        if (!readerSerial.isNullOrEmpty()) {
            endpoints.firstOrNull {
                it.serialNumber?.trim()?.uppercase() == readerSerial ||
                    it.zebraScannerIdentifier?.trim()?.uppercase() == readerSerial
            }?.let {
                return BarcodeMatch(
                    it,
                    CaptureMatchConfidence.EXACT,
                    "RFID Reader and Barcode Endpoint share serial $readerSerial.",
                    topologyFor(reader, it),
                )
            }
        }

        endpoints.firstOrNull {
            it.source == BarcodeScannerSource.BUILT_IN_TERMINAL && looksLikeSled(reader)
        }?.let {
            return BarcodeMatch(
                it,
                CaptureMatchConfidence.HIGH,
                "RFID sled paired with the terminal built-in Barcode Endpoint.",
                CaptureDeviceTopology.TC22RFID_SLED,
            )
        }

        endpoints.firstOrNull {
            it.source == BarcodeScannerSource.EXTERNAL_BLUETOOTH && nameTokensOverlap(reader, it)
        }?.let {
            return BarcodeMatch(
                it,
                CaptureMatchConfidence.MEDIUM,
                "RFID Reader and Barcode Endpoint have overlapping Zebra model/name tokens.",
                CaptureDeviceTopology.BLUETOOTH_COMBO_READER,
            )
        }

        endpoints.firstOrNull {
            it.source == BarcodeScannerSource.RFID_SLED && nameTokensOverlap(reader, it)
        }?.let {
            return BarcodeMatch(
                it,
                CaptureMatchConfidence.MEDIUM,
                "RFID Reader matched an RFID sled Barcode Endpoint by model/name tokens.",
                CaptureDeviceTopology.BLUETOOTH_COMBO_READER,
            )
        }

        return BarcodeMatch(
            null,
            CaptureMatchConfidence.LOW,
            "No Barcode Endpoint could be confidently matched; select one manually if barcode capture is required.",
            CaptureDeviceTopology.RFID_ONLY,
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

    private fun captureDeviceId(reader: Reader): String = "capture:rfid:${reader.id}"

    private fun topologyFor(
        reader: Reader,
        endpoint: BarcodeScannerEndpoint,
    ): CaptureDeviceTopology = when {
        endpoint.source == BarcodeScannerSource.BUILT_IN_TERMINAL && looksLikeSled(reader) ->
            CaptureDeviceTopology.TC22RFID_SLED
        endpoint.source == BarcodeScannerSource.BUILT_IN_TERMINAL ->
            CaptureDeviceTopology.EXTERNAL_RFID_WITH_TERMINAL_BARCODE
        endpoint.source == BarcodeScannerSource.EXTERNAL_BLUETOOTH ||
            endpoint.source == BarcodeScannerSource.RFID_SLED ->
            CaptureDeviceTopology.BLUETOOTH_COMBO_READER
        else -> CaptureDeviceTopology.UNKNOWN
    }

    private fun looksLikeSled(reader: Reader): Boolean {
        val text = "${reader.name.orEmpty()} ${reader.info?.modelVersion.orEmpty()} ${reader.info?.scannerName.orEmpty()}".uppercase()
        return text.contains("RFD") || text.contains("SLED")
    }

    private fun nameTokensOverlap(reader: Reader, endpoint: BarcodeScannerEndpoint): Boolean {
        val readerTokens = tokens("${reader.name.orEmpty()} ${reader.info?.modelVersion.orEmpty()} ${reader.info?.scannerName.orEmpty()}")
        val endpointTokens = tokens("${endpoint.displayName} ${endpoint.model.orEmpty()} ${endpoint.zebraScannerIdentifier.orEmpty()}")
        return readerTokens.intersect(endpointTokens).any { it.length >= 3 }
    }

    private fun tokens(value: String): Set<String> =
        value.uppercase()
            .split(Regex("[^A-Z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()

    private fun deriveStatus(
        rfidStatus: CaptureCapabilityStatus?,
        barcodeStatus: CaptureCapabilityStatus?,
    ): CaptureDeviceStatus {
        val statuses = listOfNotNull(rfidStatus, barcodeStatus)
        if (statuses.isEmpty()) return CaptureDeviceStatus.DISCONNECTED
        if (statuses.any { it == CaptureCapabilityStatus.CONNECTING }) {
            return CaptureDeviceStatus.CONNECTING
        }
        val hasConnected = statuses.any { it == CaptureCapabilityStatus.CONNECTED }
        val hasError = statuses.any { it == CaptureCapabilityStatus.ERROR }
        if (hasConnected && hasError) return CaptureDeviceStatus.DEGRADED
        if (hasError) return CaptureDeviceStatus.ERROR
        if (hasConnected && statuses.all { it == CaptureCapabilityStatus.CONNECTED || it == CaptureCapabilityStatus.UNAVAILABLE }) {
            return CaptureDeviceStatus.CONNECTED
        }
        return CaptureDeviceStatus.DISCONNECTED
    }

    private data class BarcodeMatch(
        val endpoint: BarcodeScannerEndpoint?,
        val confidence: CaptureMatchConfidence,
        val reason: String,
        val topology: CaptureDeviceTopology,
    )
}

private fun ReaderConnectionStatus.toCaptureStatus(): CaptureCapabilityStatus = when (this) {
    ReaderConnectionStatus.CONNECTING -> CaptureCapabilityStatus.CONNECTING
    ReaderConnectionStatus.CONNECTED -> CaptureCapabilityStatus.CONNECTED
    ReaderConnectionStatus.DISCONNECTING,
    ReaderConnectionStatus.DISCONNECTED -> CaptureCapabilityStatus.DISCONNECTED
    ReaderConnectionStatus.ERROR -> CaptureCapabilityStatus.ERROR
}

private fun ScannerConnectionStatus.toCaptureStatus(): CaptureCapabilityStatus = when (this) {
    ScannerConnectionStatus.CONNECTING -> CaptureCapabilityStatus.CONNECTING
    ScannerConnectionStatus.CONNECTED -> CaptureCapabilityStatus.CONNECTED
    ScannerConnectionStatus.DISCONNECTING,
    ScannerConnectionStatus.DISCONNECTED -> CaptureCapabilityStatus.DISCONNECTED
    ScannerConnectionStatus.ERROR -> CaptureCapabilityStatus.ERROR
}

private fun BarcodeScannerEndpoint.toCaptureCapability(
    status: CaptureCapabilityStatus,
    error: String?,
): CaptureBarcodeCapability = CaptureBarcodeCapability(
    endpointId,
    displayName,
    source.toCaptureSource(),
    mode.toCaptureMode(),
    status,
    preferred,
    scannerId,
    model,
    serialNumber,
    error,
)

private fun BarcodeScannerSource.toCaptureSource(): CaptureBarcodeSource = when (this) {
    BarcodeScannerSource.BUILT_IN_TERMINAL -> CaptureBarcodeSource.BUILT_IN_TERMINAL
    BarcodeScannerSource.RFID_SLED -> CaptureBarcodeSource.RFID_SLED
    BarcodeScannerSource.EXTERNAL_BLUETOOTH -> CaptureBarcodeSource.EXTERNAL_BLUETOOTH
    BarcodeScannerSource.EXTERNAL_USB -> CaptureBarcodeSource.EXTERNAL_USB
    BarcodeScannerSource.UNKNOWN -> CaptureBarcodeSource.UNKNOWN
}

private fun BarcodeScannerMode.toCaptureMode(): CaptureBarcodeMode = when (this) {
    BarcodeScannerMode.AUTO -> CaptureBarcodeMode.AUTO
    BarcodeScannerMode.DATA_WEDGE -> CaptureBarcodeMode.DATA_WEDGE
    BarcodeScannerMode.SCANNER_SDK -> CaptureBarcodeMode.SCANNER_SDK
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
