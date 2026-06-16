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
import CaptureRfidCapability
import Reader
import ReaderConnectionStatus
import ScannerConnectionStatus

internal enum class CaptureDevicePlanningPlatform {
    ANDROID,
    IOS,
}

internal data class CaptureDevicePlanningState(
    val activeCaptureDeviceId: String?,
    val activeRfidStatus: CaptureCapabilityStatus?,
    val activeBarcodeStatus: CaptureCapabilityStatus?,
    val activeRfidError: String?,
    val activeBarcodeError: String?,
    val barcodeOverrides: Map<String, String>,
)

internal class CaptureDevicePlanner(
    private val platform: CaptureDevicePlanningPlatform,
) {
    fun buildDevices(
        readers: List<Reader>,
        endpoints: List<BarcodeScannerEndpoint>,
        state: CaptureDevicePlanningState,
    ): List<CaptureDevice> {
        val assignedEndpoints = linkedSetOf<String>()
        val devices = mutableListOf<CaptureDevice>()

        readers.forEach { reader ->
            val id = captureDeviceId(reader)
            val match = selectBarcodeEndpoint(id, reader, endpoints, state.barcodeOverrides)
            match.endpoint?.let { assignedEndpoints.add(it.endpointId) }
            devices.add(buildDevice(id, reader, match, state))
        }

        endpoints
            .filterNot { assignedEndpoints.contains(it.endpointId) }
            .forEach { endpoint ->
                devices.add(buildBarcodeOnlyDevice(endpoint, state))
            }

        return devices.sortedWith(
            compareByDescending<CaptureDevice> { it.active }
                .thenBy { it.displayName.lowercase() },
        )
    }

    private fun buildDevice(
        id: String,
        reader: Reader,
        match: BarcodeMatch,
        state: CaptureDevicePlanningState,
    ): CaptureDevice {
        val active = id == state.activeCaptureDeviceId
        val rfidStatus = if (active) {
            state.activeRfidStatus ?: CaptureCapabilityStatus.DISCONNECTED
        } else {
            CaptureCapabilityStatus.DISCONNECTED
        }
        val barcodeStatus = if (active && match.endpoint != null) {
            state.activeBarcodeStatus ?: match.endpoint.connectionStatus.toCaptureStatus()
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
            if (active) state.activeRfidError else null,
        )
        val barcode = match.endpoint?.toCaptureCapability(
            barcodeStatus,
            if (active) state.activeBarcodeError else null,
        )
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
            deriveStatus(rfid.status, barcode?.status),
            match.confidence,
            match.reason,
            active,
            rfid,
            barcode,
            listOfNotNull(rfid.error, barcode?.error).firstOrNull(),
        )
    }

    private fun buildBarcodeOnlyDevice(
        endpoint: BarcodeScannerEndpoint,
        state: CaptureDevicePlanningState,
    ): CaptureDevice {
        val id = "capture:barcode:${endpoint.endpointId}"
        val active = id == state.activeCaptureDeviceId
        val status = if (active) {
            state.activeBarcodeStatus ?: endpoint.connectionStatus.toCaptureStatus()
        } else {
            endpoint.connectionStatus.toCaptureStatus()
        }
        val barcode = endpoint.toCaptureCapability(
            status,
            if (active) state.activeBarcodeError else null,
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
        barcodeOverrides: Map<String, String>,
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

        if (platform == CaptureDevicePlanningPlatform.ANDROID) {
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

    private fun captureDeviceId(reader: Reader): String = "capture:rfid:${reader.id}"

    private fun topologyFor(
        reader: Reader,
        endpoint: BarcodeScannerEndpoint,
    ): CaptureDeviceTopology = when {
        platform == CaptureDevicePlanningPlatform.ANDROID &&
            endpoint.source == BarcodeScannerSource.BUILT_IN_TERMINAL &&
            looksLikeSled(reader) ->
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

    private data class BarcodeMatch(
        val endpoint: BarcodeScannerEndpoint?,
        val confidence: CaptureMatchConfidence,
        val reason: String,
        val topology: CaptureDeviceTopology,
    )
}

internal fun ReaderConnectionStatus.toCaptureStatus(): CaptureCapabilityStatus = when (this) {
    ReaderConnectionStatus.CONNECTING -> CaptureCapabilityStatus.CONNECTING
    ReaderConnectionStatus.CONNECTED -> CaptureCapabilityStatus.CONNECTED
    ReaderConnectionStatus.DISCONNECTING,
    ReaderConnectionStatus.DISCONNECTED -> CaptureCapabilityStatus.DISCONNECTED
    ReaderConnectionStatus.ERROR -> CaptureCapabilityStatus.ERROR
}

internal fun ScannerConnectionStatus.toCaptureStatus(): CaptureCapabilityStatus = when (this) {
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

internal fun deriveStatus(
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
