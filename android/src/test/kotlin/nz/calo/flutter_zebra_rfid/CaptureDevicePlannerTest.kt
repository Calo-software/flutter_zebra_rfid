package nz.calo.flutter_zebra_rfid

import BarcodeScannerEndpoint
import BarcodeScannerMode
import BarcodeScannerSource
import CaptureCapabilityStatus
import CaptureDeviceStatus
import CaptureDeviceTopology
import CaptureMatchConfidence
import Reader
import ReaderInfo
import ScannerConnectionStatus
import nz.calo.flutter_zebra_rfid.capture.CaptureDevicePlanner
import nz.calo.flutter_zebra_rfid.capture.CaptureDevicePlanningPlatform
import nz.calo.flutter_zebra_rfid.capture.CaptureDevicePlanningState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class CaptureDevicePlannerTest {
  @Test
  fun buildDevices_androidMatchesTc22SledToBuiltInTerminalBarcodeEndpoint() {
    val devices = androidPlanner.buildDevices(
      readers = listOf(
        reader(
          name = "RFD40 Sled",
          id = 1,
          model = "RFD40",
        ),
      ),
      endpoints = listOf(
        endpoint(
          endpointId = "datawedge:INTERNAL",
          displayName = "Internal imager",
          source = BarcodeScannerSource.BUILT_IN_TERMINAL,
          mode = BarcodeScannerMode.DATA_WEDGE,
        ),
      ),
      state = state(),
    )

    assertEquals(1, devices.size)
    assertEquals(CaptureDeviceTopology.TC22RFID_SLED, devices.single().topology)
    assertEquals(CaptureMatchConfidence.HIGH, devices.single().matchConfidence)
    assertEquals("RFD40 Sled + terminal barcode", devices.single().displayName)
    assertEquals("datawedge:INTERNAL", devices.single().barcode?.endpointId)
  }

  @Test
  fun buildDevices_iosDoesNotInferBuiltInTerminalBarcodeEndpoint() {
    val devices = iosPlanner.buildDevices(
      readers = listOf(
        reader(
          name = "RFD40 Sled",
          id = 1,
          model = "RFD40",
        ),
      ),
      endpoints = listOf(
        endpoint(
          endpointId = "datawedge:INTERNAL",
          displayName = "Internal imager",
          source = BarcodeScannerSource.BUILT_IN_TERMINAL,
          mode = BarcodeScannerMode.DATA_WEDGE,
        ),
      ),
      state = state(),
    )

    assertEquals(2, devices.size)
    assertEquals(CaptureDeviceTopology.BARCODE_ONLY, devices[0].topology)
    assertEquals(CaptureDeviceTopology.RFID_ONLY, devices[1].topology)
    assertNull(devices[1].barcode)
  }

  @Test
  fun buildDevices_manualOverrideWinsBeforeSerialMatch() {
    val devices = androidPlanner.buildDevices(
      readers = listOf(
        reader(
          name = "RFD40",
          id = 7,
          serial = "MATCHED-SERIAL",
          hardwareIdentity = "RFD40-ID",
        ),
      ),
      endpoints = listOf(
        endpoint(
          endpointId = "scanner-sdk:auto",
          displayName = "Auto matched barcode",
          source = BarcodeScannerSource.EXTERNAL_BLUETOOTH,
          mode = BarcodeScannerMode.SCANNER_SDK,
          serial = "MATCHED-SERIAL",
          scannerId = 100,
        ),
        endpoint(
          endpointId = "scanner-sdk:manual",
          displayName = "Manual barcode",
          source = BarcodeScannerSource.EXTERNAL_USB,
          mode = BarcodeScannerMode.SCANNER_SDK,
          scannerId = 200,
        ),
      ),
      state = state(
        barcodeOverrides = mapOf("capture:rfid:RFD40-ID" to "scanner-sdk:manual"),
      ),
    )

    val captureDevice = devices.first { it.id == "capture:rfid:RFD40-ID" }
    assertEquals(CaptureMatchConfidence.MANUAL, captureDevice.matchConfidence)
    assertEquals("scanner-sdk:manual", captureDevice.barcode?.endpointId)
    assertTrue(devices.any { it.id == "capture:barcode:scanner-sdk:auto" })
  }

  @Test
  fun buildDevices_zebraScannerIdentifierParticipatesInExactMatch() {
    val devices = androidPlanner.buildDevices(
      readers = listOf(
        reader(
          name = "RFD40",
          id = 3,
          serial = "ABC123",
        ),
      ),
      endpoints = listOf(
        endpoint(
          endpointId = "datawedge:RFD40",
          displayName = "RFD40 barcode",
          source = BarcodeScannerSource.RFID_SLED,
          mode = BarcodeScannerMode.DATA_WEDGE,
          zebraScannerIdentifier = "abc123",
        ),
      ),
      state = state(),
    )

    val captureDevice = devices.single()
    assertEquals(CaptureMatchConfidence.EXACT, captureDevice.matchConfidence)
    assertEquals(CaptureDeviceTopology.BLUETOOTH_COMBO_READER, captureDevice.topology)
  }

  @Test
  fun buildDevices_activeCapabilityErrorProducesDegradedCaptureDevice() {
    val devices = androidPlanner.buildDevices(
      readers = listOf(
        reader(
          name = "RFD40",
          id = 1,
          serial = "SERIAL",
          hardwareIdentity = "RFD40-ID",
        ),
      ),
      endpoints = listOf(
        endpoint(
          endpointId = "scanner-sdk:1",
          displayName = "RFD40 barcode",
          source = BarcodeScannerSource.EXTERNAL_BLUETOOTH,
          mode = BarcodeScannerMode.SCANNER_SDK,
          serial = "SERIAL",
          scannerId = 1,
        ),
      ),
      state = state(
        activeCaptureDeviceId = "capture:rfid:RFD40-ID",
        activeRfidStatus = CaptureCapabilityStatus.CONNECTED,
        activeBarcodeStatus = CaptureCapabilityStatus.ERROR,
        activeBarcodeError = "Scanner session failed",
      ),
    )

    val captureDevice = devices.single()
    assertEquals(CaptureDeviceStatus.DEGRADED, captureDevice.status)
    assertEquals("Scanner session failed", captureDevice.lastError)
    assertEquals(CaptureCapabilityStatus.CONNECTED, captureDevice.rfid?.status)
    assertEquals(CaptureCapabilityStatus.ERROR, captureDevice.barcode?.status)
  }

  @Test
  fun buildDevices_keepsCaptureIdentityStableWhenDiscoveryOrderChanges() {
    val firstPass = androidPlanner.buildDevices(
      readers = listOf(
        reader(name = "Other", id = 0, hardwareIdentity = "AA:BB:CC:00"),
        reader(name = "RFD40", id = 1, hardwareIdentity = "AA:BB:CC:40"),
      ),
      endpoints = emptyList(),
      state = state(),
    )
    val secondPass = androidPlanner.buildDevices(
      readers = listOf(
        reader(name = "RFD40", id = 0, hardwareIdentity = "AA:BB:CC:40"),
        reader(name = "Other", id = 1, hardwareIdentity = "AA:BB:CC:00"),
      ),
      endpoints = emptyList(),
      state = state(),
    )

    val firstIdentity = firstPass.single { it.displayName == "RFD40" }.id
    val secondIdentity = secondPass.single { it.displayName == "RFD40" }.id

    assertEquals("capture:rfid:AA:BB:CC:40", firstIdentity)
    assertEquals(firstIdentity, secondIdentity)
  }

  private val androidPlanner = CaptureDevicePlanner(CaptureDevicePlanningPlatform.ANDROID)
  private val iosPlanner = CaptureDevicePlanner(CaptureDevicePlanningPlatform.IOS)

  private fun state(
    activeCaptureDeviceId: String? = null,
    activeRfidStatus: CaptureCapabilityStatus? = null,
    activeBarcodeStatus: CaptureCapabilityStatus? = null,
    activeRfidError: String? = null,
    activeBarcodeError: String? = null,
    barcodeOverrides: Map<String, String> = emptyMap(),
  ): CaptureDevicePlanningState =
    CaptureDevicePlanningState(
      activeCaptureDeviceId = activeCaptureDeviceId,
      activeRfidStatus = activeRfidStatus,
      activeBarcodeStatus = activeBarcodeStatus,
      activeRfidError = activeRfidError,
      activeBarcodeError = activeBarcodeError,
      barcodeOverrides = barcodeOverrides,
    )

  private fun reader(
    name: String,
    id: Long,
    model: String? = null,
    serial: String? = null,
    hardwareIdentity: String? = null,
  ): Reader =
    Reader(
      name = name,
      id = id,
      info = ReaderInfo(
        transmitPowerLevels = emptyList<Any?>(),
        modelVersion = model,
        scannerName = name,
        serialNumber = serial,
      ),
      hardwareIdentity = hardwareIdentity,
    )

  private fun endpoint(
    endpointId: String,
    displayName: String,
    source: BarcodeScannerSource,
    mode: BarcodeScannerMode,
    serial: String? = null,
    zebraScannerIdentifier: String? = null,
    scannerId: Long? = null,
  ): BarcodeScannerEndpoint =
    BarcodeScannerEndpoint(
      endpointId = endpointId,
      displayName = displayName,
      source = source,
      mode = mode,
      connectionStatus = ScannerConnectionStatus.DISCONNECTED,
      active = false,
      preferred = false,
      zebraScannerIdentifier = zebraScannerIdentifier,
      scannerId = scannerId,
      model = displayName,
      serialNumber = serial,
    )
}
