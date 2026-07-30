package nz.calo.flutter_zebra_rfid

import android.content.Context
import android.hardware.usb.UsbManager
import Diagnostics
import FlutterZebraRfidCallbacks
import androidx.test.core.app.ApplicationProvider
import com.zebra.rfid.api3.Actions
import com.zebra.rfid.api3.Antennas
import com.zebra.rfid.api3.BatteryStatistics
import com.zebra.rfid.api3.Config
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.ENUM_NEW_KEYLAYOUT_TYPE
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE
import com.zebra.rfid.api3.Events
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE
import com.zebra.rfid.api3.Inventory
import com.zebra.rfid.api3.PreFilters
import com.zebra.rfid.api3.RegionInfo
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.RFIDResults
import com.zebra.rfid.api3.ReaderCapabilities
import com.zebra.rfid.api3.READER_POWER_STATE
import com.zebra.rfid.api3.ReaderDevice
import com.zebra.rfid.api3.OperationFailureException
import com.zebra.rfid.api3.RfidReadEvents
import com.zebra.rfid.api3.START_TRIGGER_TYPE
import com.zebra.rfid.api3.STOP_TRIGGER_TYPE
import com.zebra.rfid.api3.TagData
import io.flutter.plugin.common.BinaryMessenger
import nz.calo.flutter_zebra_rfid.rfid.buildRegulatoryConfigForSingleSupportedRegion
import nz.calo.flutter_zebra_rfid.rfid.batteryDataFromStatistics
import nz.calo.flutter_zebra_rfid.rfid.batteryDataFromReaderEvent
import nz.calo.flutter_zebra_rfid.rfid.buildInventoryTriggerInfo
import nz.calo.flutter_zebra_rfid.rfid.describeSupportedRegions
import nz.calo.flutter_zebra_rfid.rfid.RFIDReaderInterface
import nz.calo.flutter_zebra_rfid.rfid.CaptureDeviceBarcodeTriggerTarget
import nz.calo.flutter_zebra_rfid.rfid.readerConnectionTypeToDiscoveryTransports
import nz.calo.flutter_zebra_rfid.rfid.readerPowerStateLabel
import nz.calo.flutter_zebra_rfid.capture.RfidLifecycleGate
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import android.os.Looper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
internal class RFIDReaderInterfaceTest {

  @Test
  fun readerPowerStateLabel_mapsZebraPowerStates() {
    assertEquals("off", readerPowerStateLabel(READER_POWER_STATE.POWER_STATE_OFF))
    assertEquals("standby", readerPowerStateLabel(READER_POWER_STATE.POWER_STATE_STANDBY))
    assertEquals("active", readerPowerStateLabel(READER_POWER_STATE.POWER_STATE_ACTIVE))
    assertEquals("rfActive", readerPowerStateLabel(READER_POWER_STATE.POWER_STATE_RF_ACTIVE))
    assertEquals("bluetoothOff", readerPowerStateLabel(READER_POWER_STATE.POWER_STATE_BT_OFF))
    assertEquals("unknown", readerPowerStateLabel(READER_POWER_STATE.POWER_STATE_UNKNOWN))
  }

  @Test
  fun diagnostics_queriesConnectedReaderPowerState() {
    val subject = createSubject()
    val reader = mockReader()
    Mockito.`when`(reader.isConnected).thenReturn(true)
    Mockito.`when`(reader.Config.readerPowerState)
      .thenReturn(READER_POWER_STATE.POWER_STATE_STANDBY)
    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")

    var result: Result<Diagnostics>? = null
    subject.diagnosticsWithReaderPowerState { result = it }
    waitUntil {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      result != null
    }

    assertEquals("standby", result!!.getOrThrow().readerPowerState)
    assertNull(result!!.getOrThrow().readerPowerStateError)
    Mockito.verify(reader.Config).readerPowerState
  }

  @Test
  fun diagnostics_queuedBeforeRecoveryDefersPowerStateAtExecutionTime() {
    val subject = createSubject()
    val reader = mockReader()
    Mockito.`when`(reader.isConnected).thenReturn(true)
    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")

    val executor = getField<ExecutorService>(subject, "ioExecutor")!!
    val blockerStarted = CountDownLatch(1)
    val releaseBlocker = CountDownLatch(1)
    executor.submit {
      blockerStarted.countDown()
      releaseBlocker.await(2, TimeUnit.SECONDS)
    }
    assertTrue(blockerStarted.await(2, TimeUnit.SECONDS))

    var result: Result<Diagnostics>? = null
    subject.diagnosticsWithReaderPowerState { result = it }
    getField<RfidLifecycleGate>(subject, "lifecycleGate")!!
      .request("AA:BB:CC:40")
    releaseBlocker.countDown()

    waitUntil {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      result != null
    }

    assertEquals(
      "deferred_during_capture_device_recovery",
      result!!.getOrThrow().readerPowerStateError,
    )
    Mockito.verify(reader.Config, Mockito.never()).readerPowerState
  }

  @Test
  fun diagnostics_exposesReaderPowerStateFailureDetails() {
    val subject = createSubject()
    val reader = mockReader()
    val failure = Mockito.mock(OperationFailureException::class.java)
    Mockito.`when`(reader.isConnected).thenReturn(true)
    Mockito.`when`(failure.results).thenReturn(RFIDResults.RFID_READER_FUNCTION_UNSUPPORTED)
    Mockito.`when`(failure.statusDescription).thenReturn("Not supported")
    Mockito.`when`(failure.vendorMessage).thenReturn("Reader rejected command")
    Mockito.`when`(reader.Config.readerPowerState).thenThrow(failure)
    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")

    var result: Result<Diagnostics>? = null
    subject.diagnosticsWithReaderPowerState { result = it }
    waitUntil {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      result != null
    }

    val diagnostics = result!!.getOrThrow()
    assertEquals("unavailable", diagnostics.readerPowerState)
    assertEquals(
      "result=RFID_READER_FUNCTION_UNSUPPORTED, status=Not supported, vendor=Reader rejected command",
      diagnostics.readerPowerStateError,
    )
  }

  @Test
  fun diagnostics_twoCommandTimeoutsRetireStaleCaptureDeviceSessionOnce() {
    val subject = createSubject()
    val reader = mockReader()
    val failure = Mockito.mock(OperationFailureException::class.java)
    Mockito.`when`(reader.isConnected).thenReturn(true)
    Mockito.`when`(failure.results).thenReturn(RFIDResults.RFID_API_COMMAND_TIMEOUT)
    Mockito.`when`(failure.statusDescription).thenReturn("RFID_API_COMMAND_TIMEOUT")
    Mockito.`when`(failure.vendorMessage).thenReturn("Response timeout")
    Mockito.`when`(reader.Config.readerPowerState).thenThrow(failure)
    setField(subject, "reader", reader)
    setField(subject, "eventsBoundReader", reader)
    setEnumField(subject, "internalState", "CONNECTED")
    setEnumField(subject, "connectionOwnership", "CAPTURE_DEVICE")

    var recoveryReason: String? = null
    subject.managedRecoveryRequestListener = { recoveryReason = it }

    var firstResult: Result<Diagnostics>? = null
    subject.diagnosticsWithReaderPowerState { firstResult = it }
    waitUntil {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      firstResult != null
    }

    assertNull(recoveryReason)
    Mockito.verify(reader, Mockito.never()).disconnect()

    var secondResult: Result<Diagnostics>? = null
    subject.diagnosticsWithReaderPowerState { secondResult = it }
    waitUntil {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      recoveryReason != null && secondResult != null
    }

    assertEquals("rfid_command_timeout", recoveryReason)
    assertEquals(ReaderConnectionStatus.DISCONNECTED, subject.diagnostics().connectionState)
    val ordered = Mockito.inOrder(reader.Events, reader)
    ordered.verify(reader.Events).removeEventsListener(subject)
    ordered.verify(reader).disconnect()
    ordered.verify(reader).Dispose()
    assertNull(getField<RFIDReader>(subject, "reader"))
    assertNull(getField<RFIDReader>(subject, "eventsBoundReader"))
  }

  @Test
  fun diagnostics_nonTimeoutSdkResponseResetsCommandTimeoutStreak() {
    val subject = createSubject()
    val reader = mockReader()
    val timeout = Mockito.mock(OperationFailureException::class.java)
    val unsupported = Mockito.mock(OperationFailureException::class.java)
    Mockito.`when`(reader.isConnected).thenReturn(true)
    Mockito.`when`(timeout.results).thenReturn(RFIDResults.RFID_API_COMMAND_TIMEOUT)
    Mockito.`when`(unsupported.results)
      .thenReturn(RFIDResults.RFID_READER_FUNCTION_UNSUPPORTED)
    Mockito.`when`(reader.Config.readerPowerState)
      .thenThrow(timeout)
      .thenThrow(unsupported)
      .thenThrow(timeout)
    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")
    setEnumField(subject, "connectionOwnership", "CAPTURE_DEVICE")

    var recoveryReason: String? = null
    subject.managedRecoveryRequestListener = { recoveryReason = it }

    repeat(3) {
      var result: Result<Diagnostics>? = null
      subject.diagnosticsWithReaderPowerState { result = it }
      waitUntil {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        result != null
      }
    }

    assertNull(recoveryReason)
    assertEquals(ReaderConnectionStatus.CONNECTED, subject.diagnostics().connectionState)
    Mockito.verify(reader, Mockito.never()).disconnect()
  }

  @Test
  fun batteryStatistics_exposesExplicitPercentageAndHealth() {
    val statistics = BatteryStatistics().apply {
      percentage = 73
      charging = 1
      health = 91
      cycleCount = 42
    }

    val data = batteryDataFromStatistics(statistics)

    assertNotNull(data)
    assertEquals(73L, data!!.level)
    assertTrue(data.isCharging)
    assertEquals(BatteryDataSource.READER_STATISTICS, data.source)
    assertEquals(false, data.isPercentageEstimated)
    assertEquals(91L, data.healthPercentage)
    assertEquals(42L, data.cycleCount)
  }

  @Test
  fun batteryStatistics_rejectsInvalidPercentage() {
    val statistics = BatteryStatistics().apply { percentage = -1 }

    assertNull(batteryDataFromStatistics(statistics))
  }

  @Test
  fun coarseReaderEvent_doesNotOverwriteExplicitBatteryStatisticsPercentage() {
    val statistics = batteryDataFromStatistics(BatteryStatistics().apply {
      percentage = 73
      health = 91
      cycleCount = 42
    })

    val data = batteryDataFromReaderEvent(
      level = 0,
      isCharging = true,
      cause = "coarse event",
      previous = statistics,
    )

    assertEquals(73L, data.level)
    assertTrue(data.isCharging)
    assertEquals(BatteryDataSource.READER_STATISTICS, data.source)
    assertEquals(91L, data.healthPercentage)
    assertEquals(42L, data.cycleCount)
  }

  @Test
  fun connectReader_alreadyConnectedRearmsReaderSetup() {
    val subject = createSubject()
    val reader = mockReader()
    val readerDevice = Mockito.mock(ReaderDevice::class.java)
    val antennaConfig = Mockito.mock(Antennas.AntennaRfConfig::class.java)
    val singulationControl = Antennas.SingulationControl().apply {
      Action = Mockito.mock(Antennas.SingulationControl.SingulationAction::class.java)
    }

    Mockito.`when`(reader.isConnected).thenReturn(true)
    Mockito.`when`(reader.Config.Antennas.getAntennaRfConfig(1)).thenReturn(antennaConfig)
    Mockito.`when`(reader.Config.Antennas.getSingulationControl(1)).thenReturn(singulationControl)
    Mockito.`when`(readerDevice.name).thenReturn("RFD40")

    setField(subject, "reader", reader)
    setField(subject, "readerDevice", readerDevice)
    setField(subject, "availableRFIDReaderList", arrayListOf(readerDevice))

    val info = subject.connectReader(0)

    assertSame(info, getField(subject, "readerInfo"))
    waitUntil { subject.diagnostics().connectionState == ReaderConnectionStatus.CONNECTED }
    assertEquals(ReaderConnectionStatus.CONNECTED, subject.diagnostics().connectionState)
    Mockito.verify(reader.Events).addEventsListener(subject)
    Mockito.verify(reader.Config).setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
    Mockito.verify(
      reader.Config,
      Mockito.never(),
    ).setKeylayoutType(
      ENUM_NEW_KEYLAYOUT_TYPE.RFID,
      ENUM_NEW_KEYLAYOUT_TYPE.SLED_SCAN,
    )
  }

  @Test
  fun captureDevice_alreadyConnectedSetupFailureRetriesWithoutDisconnecting() {
    val subject = createSubject()
    val reader = mockReader()
    val readerDevice = Mockito.mock(ReaderDevice::class.java)
    val failure = Mockito.mock(OperationFailureException::class.java)
    Mockito.`when`(failure.vendorMessage).thenReturn("Response timeout")
    Mockito.`when`(reader.isConnected).thenReturn(true)
    Mockito.`when`(readerDevice.name).thenReturn("RFD40")
    Mockito.`when`(readerDevice.rfidReader).thenReturn(reader)
    Mockito.doThrow(failure).`when`(reader.Config)
      .setTriggerMode(Mockito.eq(ENUM_TRIGGER_MODE.RFID_MODE), Mockito.eq(false))

    setField(subject, "reader", reader)
    setField(subject, "readerDevice", readerDevice)
    setField(subject, "availableRFIDReaderList", arrayListOf(readerDevice))
    setEnumField(subject, "internalState", "CONNECTED")

    var recoveryReason: String? = null
    subject.managedRecoveryRequestListener = { recoveryReason = it }

    subject.connectReaderForCaptureDevice(
      readerId = 0,
      hardwareIdentity = "AA:BB:CC:40",
      barcodeTriggerTarget = CaptureDeviceBarcodeTriggerTarget.RFD_BARCODE_ENGINE,
    )

    waitUntil {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      recoveryReason != null
    }

    assertEquals("rfid_setup_retry", recoveryReason)
    Mockito.verify(reader, Mockito.never()).disconnect()
    assertEquals(
      ReaderConnectionStatus.CONNECTED,
      subject.diagnostics().connectionState,
    )
  }

  @Test
  fun captureDevice_unsupportedKeyLayoutStillCompletesReaderSetup() {
    val subject = createSubject()
    val reader = mockReader()
    val readerDevice = Mockito.mock(ReaderDevice::class.java)
    val antennaConfig = Mockito.mock(Antennas.AntennaRfConfig::class.java)
    val singulationControl = Antennas.SingulationControl().apply {
      Action = Mockito.mock(Antennas.SingulationControl.SingulationAction::class.java)
    }
    val unsupportedKeyLayout = Mockito.mock(OperationFailureException::class.java)

    Mockito.`when`(reader.isConnected).thenReturn(true)
    Mockito.`when`(readerDevice.name).thenReturn("RFD4030-G00B700-WR")
    Mockito.`when`(readerDevice.rfidReader).thenReturn(reader)
    Mockito.`when`(reader.Config.Antennas.getAntennaRfConfig(1)).thenReturn(antennaConfig)
    Mockito.`when`(reader.Config.Antennas.getSingulationControl(1)).thenReturn(singulationControl)
    Mockito.`when`(unsupportedKeyLayout.results)
      .thenReturn(RFIDResults.RFID_API_OPTION_NOT_ALLOWED)
    Mockito.`when`(unsupportedKeyLayout.vendorMessage)
      .thenReturn("Option Not Allowed for this Command")
    Mockito.doThrow(unsupportedKeyLayout).`when`(reader.Config)
      .setKeylayoutType(
        ENUM_NEW_KEYLAYOUT_TYPE.RFID,
        ENUM_NEW_KEYLAYOUT_TYPE.TERMINAL_SCAN,
      )

    setField(subject, "reader", reader)
    setField(subject, "readerDevice", readerDevice)
    setField(subject, "availableRFIDReaderList", arrayListOf(readerDevice))

    var recoveryReason: String? = null
    subject.managedRecoveryRequestListener = { recoveryReason = it }

    subject.connectReaderForCaptureDevice(
      readerId = 0,
      hardwareIdentity = "USB:RFD4030-G00B700-WR",
      barcodeTriggerTarget = CaptureDeviceBarcodeTriggerTarget.TERMINAL_IMAGER,
    )

    waitUntil {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      subject.diagnostics().connectionState == ReaderConnectionStatus.CONNECTED ||
        recoveryReason != null
    }

    assertNull(recoveryReason)
    assertEquals(
      ReaderConnectionStatus.CONNECTED,
      subject.diagnostics().connectionState,
    )
    Mockito.verify(reader.Config).startTrigger = Mockito.any()
    Mockito.verify(reader.Config).stopTrigger = Mockito.any()
    Mockito.verify(reader, Mockito.never()).disconnect()
  }

  @Test
  fun captureDevice_staleConnectedSessionWithSendErrorIsRetired() {
    val subject = createSubject()
    val reader = mockReader()
    val readerDevice = Mockito.mock(ReaderDevice::class.java)
    val failure = Mockito.mock(OperationFailureException::class.java)
    Mockito.`when`(failure.results).thenReturn(RFIDResults.RFID_COMM_SEND_ERROR)
    Mockito.`when`(failure.vendorMessage)
      .thenReturn("IO Error occurred in sending keymap command")
    Mockito.`when`(reader.isConnected).thenReturn(true)
    Mockito.`when`(readerDevice.name).thenReturn("RFD40")
    Mockito.`when`(readerDevice.rfidReader).thenReturn(reader)
    Mockito.doThrow(failure).`when`(reader.Config)
      .setTriggerMode(Mockito.eq(ENUM_TRIGGER_MODE.RFID_MODE), Mockito.eq(false))

    setField(subject, "reader", reader)
    setField(subject, "readerDevice", readerDevice)
    setField(subject, "availableRFIDReaderList", arrayListOf(readerDevice))
    setEnumField(subject, "internalState", "CONNECTED")

    var recoveryReason: String? = null
    subject.managedRecoveryRequestListener = { recoveryReason = it }

    subject.connectReaderForCaptureDevice(
      readerId = 0,
      hardwareIdentity = "AA:BB:CC:40",
      barcodeTriggerTarget = CaptureDeviceBarcodeTriggerTarget.RFD_BARCODE_ENGINE,
    )

    waitUntil {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      recoveryReason != null
    }

    assertEquals("rfid_session_lost", recoveryReason)
    Mockito.verify(reader).disconnect()
    assertEquals(
      ReaderConnectionStatus.DISCONNECTED,
      subject.diagnostics().connectionState,
    )
  }

  @Test
  fun captureDevice_replacementReaderRetiresPreviousSessionBeforeConnecting() {
    val subject = createSubject()
    val previousReader = mockReader()
    val replacementReader = mockReader()
    val readerDevice = Mockito.mock(ReaderDevice::class.java)
    val antennaConfig = Mockito.mock(Antennas.AntennaRfConfig::class.java)
    val singulationControl = Antennas.SingulationControl().apply {
      Action = Mockito.mock(Antennas.SingulationControl.SingulationAction::class.java)
    }

    Mockito.`when`(previousReader.isConnected).thenReturn(false)
    Mockito.`when`(replacementReader.isConnected).thenReturn(false, true)
    Mockito.`when`(replacementReader.Config.Antennas.getAntennaRfConfig(1))
      .thenReturn(antennaConfig)
    Mockito.`when`(replacementReader.Config.Antennas.getSingulationControl(1))
      .thenReturn(singulationControl)
    Mockito.`when`(readerDevice.name).thenReturn("RFD40")
    Mockito.`when`(readerDevice.rfidReader).thenReturn(replacementReader)

    setField(subject, "reader", previousReader)
    setField(subject, "eventsBoundReader", previousReader)
    setField(subject, "availableRFIDReaderList", arrayListOf(readerDevice))

    subject.connectReaderForCaptureDevice(
      readerId = 0,
      hardwareIdentity = "AA:BB:CC:40",
      barcodeTriggerTarget = CaptureDeviceBarcodeTriggerTarget.RFD_BARCODE_ENGINE,
    )

    waitUntil {
      subject.diagnostics().connectionState == ReaderConnectionStatus.CONNECTED
    }

    val ordered = Mockito.inOrder(
      previousReader.Events,
      previousReader,
      replacementReader,
    )
    ordered.verify(previousReader.Events).removeEventsListener(subject)
    ordered.verify(replacementReader).connect()
    Mockito.verify(previousReader, Mockito.never()).Dispose()
    Mockito.verify(replacementReader.Events).addEventsListener(subject)
    Mockito.verify(replacementReader.Config).setKeylayoutType(
      ENUM_NEW_KEYLAYOUT_TYPE.RFID,
      ENUM_NEW_KEYLAYOUT_TYPE.SLED_SCAN,
    )
  }

  @Test
  fun captureDevice_physicalDisconnectDisposesSessionBeforeRediscovery() {
    val subject = createSubject()
    val reader = mockReader()
    Mockito.`when`(reader.isConnected).thenReturn(false)
    setField(subject, "reader", reader)
    setField(subject, "eventsBoundReader", reader)
    setEnumField(subject, "connectionOwnership", "CAPTURE_DEVICE")
    setEnumField(subject, "internalState", "CONNECTED")

    var recoveryReason: String? = null
    subject.managedRecoveryRequestListener = { recoveryReason = it }

    val disconnect = subject.javaClass.getDeclaredMethod(
      "handleUnexpectedReaderDisconnect",
      String::class.java,
    )
    disconnect.isAccessible = true
    disconnect.invoke(subject, "test physical disconnect")

    waitUntil {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      recoveryReason != null
    }

    assertEquals("physical_reader_disconnect", recoveryReason)
    val ordered = Mockito.inOrder(reader.Events, reader)
    ordered.verify(reader.Events).removeEventsListener(subject)
    ordered.verify(reader).Dispose()
    assertNull(getField<RFIDReader>(subject, "reader"))
    assertNull(getField<RFIDReader>(subject, "eventsBoundReader"))
  }

  @Test
  fun captureDevice_matchingRfdUsbReattachInvalidatesSilentConnectedSession() {
    val subject = createSubject()
    val reader = mockReader()
    val readerDevice = ReaderDevice(
      "RFD4030-G00B700-WR::",
      "USB_PORT",
      reader,
    )
    readerDevice.transport = "SERVICE_USB"
    Mockito.`when`(reader.isConnected).thenReturn(false)
    setField(subject, "reader", reader)
    setField(subject, "readerDevice", readerDevice)
    setField(subject, "eventsBoundReader", reader)
    setEnumField(subject, "connectionOwnership", "CAPTURE_DEVICE")
    setEnumField(subject, "internalState", "CONNECTED")

    var recoveryReason: String? = null
    subject.managedRecoveryRequestListener = { recoveryReason = it }

    subject.handleUsbDeviceConnectionEvent(
      action = UsbManager.ACTION_USB_DEVICE_ATTACHED,
      vendorId = 1504,
      productName = "RFD4030-G00B700-WR::::EA",
      manufacturerName = "Zebra Technologies, Inc",
    )

    waitUntil {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      recoveryReason != null
    }

    assertEquals(ReaderConnectionStatus.DISCONNECTED, subject.diagnostics().connectionState)
    assertEquals("physical_reader_disconnect", recoveryReason)
    val ordered = Mockito.inOrder(reader.Events, reader)
    ordered.verify(reader.Events).removeEventsListener(subject)
    ordered.verify(reader).Dispose()
  }

  @Test
  fun usbAttachIsIgnoredBeforeReaderSessionIsConnected() {
    val subject = createSubject()
    setField(
      subject,
      "readerDevice",
      ReaderDevice("RFD4030-G00B700-WR::", "USB_PORT"),
    )
    var recoveryRequests = 0
    subject.managedRecoveryRequestListener = { recoveryRequests += 1 }

    subject.handleUsbDeviceConnectionEvent(
      action = UsbManager.ACTION_USB_DEVICE_ATTACHED,
      vendorId = 1504,
      productName = "RFD4030-G00B700-WR::::EA",
      manufacturerName = "Zebra Technologies, Inc",
    )
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals(ReaderConnectionStatus.DISCONNECTED, subject.diagnostics().connectionState)
    assertEquals(0, recoveryRequests)
  }

  @Test
  fun unrelatedUsbAttachIsIgnoredWhileRfdReaderIsConnected() {
    val subject = createSubject()
    val reader = mockReader()
    setField(subject, "reader", reader)
    setField(
      subject,
      "readerDevice",
      ReaderDevice("RFD4030-G00B700-WR::", "USB_PORT", reader),
    )
    setEnumField(subject, "connectionOwnership", "CAPTURE_DEVICE")
    setEnumField(subject, "internalState", "CONNECTED")
    var recoveryRequests = 0
    subject.managedRecoveryRequestListener = { recoveryRequests += 1 }

    subject.handleUsbDeviceConnectionEvent(
      action = UsbManager.ACTION_USB_DEVICE_ATTACHED,
      vendorId = 1234,
      productName = "USB keyboard",
      manufacturerName = "Other",
    )
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals(ReaderConnectionStatus.CONNECTED, subject.diagnostics().connectionState)
    assertEquals(0, recoveryRequests)
  }

  @Test
  fun matchingRfdUsbDetachInvalidatesConnectedSession() {
    val subject = createSubject()
    val reader = mockReader()
    setField(subject, "reader", reader)
    setField(
      subject,
      "readerDevice",
      ReaderDevice("RFD4030-G00B700-WR::", "USB_PORT", reader),
    )
    setEnumField(subject, "connectionOwnership", "CAPTURE_DEVICE")
    setEnumField(subject, "internalState", "CONNECTED")
    var recoveryReason: String? = null
    subject.managedRecoveryRequestListener = { recoveryReason = it }

    subject.handleUsbDeviceConnectionEvent(
      action = UsbManager.ACTION_USB_DEVICE_DETACHED,
      vendorId = 1504,
      productName = "RFD4030-G00B700-WR::::EA",
      manufacturerName = "Zebra Technologies, Inc",
    )

    waitUntil {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      recoveryReason != null
    }

    assertEquals(ReaderConnectionStatus.DISCONNECTED, subject.diagnostics().connectionState)
    assertEquals("physical_reader_disconnect", recoveryReason)
  }

  @Test
  fun captureDevice_reassertsRfidTriggerOwnershipAfterBarcodeRestoration() {
    val subject = createSubject()
    val reader = mockReader()
    Mockito.`when`(reader.isConnected).thenReturn(true)
    setField(subject, "reader", reader)
    setEnumField(subject, "connectionOwnership", "CAPTURE_DEVICE")
    setField(subject, "captureDeviceControlsBarcode", true)
    setField(
      subject,
      "captureDeviceBarcodeTriggerTarget",
      CaptureDeviceBarcodeTriggerTarget.RFD_BARCODE_ENGINE,
    )
    setField(subject, "captureDeviceTriggerRearmPending", true)

    var result: Result<Unit>? = null
    subject.reassertCaptureDeviceTriggerOwnership { result = it }

    waitUntil {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      result != null
    }

    assertTrue(result!!.isSuccess)
    val ordered = Mockito.inOrder(reader.Events, reader.Config)
    ordered.verify(reader.Events).setHandheldEvent(false)
    ordered.verify(reader.Events).removeEventsListener(subject)
    ordered.verify(reader.Events).addEventsListener(subject)
    ordered.verify(reader.Events).setHandheldEvent(true)
    ordered.verify(reader.Events).setTagReadEvent(true)
    ordered.verify(reader.Events).setReaderDisconnectEvent(true)
    ordered.verify(reader.Config).setTriggerMode(
      ENUM_TRIGGER_MODE.RFID_MODE,
      false,
    )
    ordered.verify(reader.Config).setKeylayoutType(
      ENUM_NEW_KEYLAYOUT_TYPE.RFID,
      ENUM_NEW_KEYLAYOUT_TYPE.SLED_SCAN,
    )
    Mockito.verify(reader.Config).startTrigger = Mockito.any()
    Mockito.verify(reader.Config).stopTrigger = Mockito.any()
    assertEquals(false, getField<Boolean>(subject, "captureDeviceTriggerRearmPending"))
    Mockito.verify(reader, Mockito.never()).connect()
    Mockito.verify(reader, Mockito.never()).disconnect()
  }

  @Test
  fun captureDevice_ignoresTriggerUntilSharedOwnershipRearmCompletes() {
    val subject = createSubject()
    val reader = mockReader()
    Mockito.`when`(reader.isConnected).thenReturn(true)
    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")
    setEnumField(subject, "connectionOwnership", "CAPTURE_DEVICE")
    setField(subject, "captureDeviceControlsBarcode", true)
    setField(subject, "captureDeviceTriggerRearmPending", true)
    var readinessActivityCount = 0
    subject.managedReadinessActivityListener = { readinessActivityCount += 1 }

    subject.handleHandheldTriggerEvent(
      HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED,
    )

    assertEquals(false, subject.diagnostics().inventoryActive)
    assertEquals(0, readinessActivityCount)
    Mockito.verify(reader.Actions.Inventory, Mockito.never()).perform()
  }

  @Test
  fun captureDevice_releaseWithoutPressRunsBoundedProbeWithoutPrematureReadiness() {
    val subject = createSubject()
    val reader = mockReader()
    Mockito.`when`(reader.isConnected).thenReturn(true)
    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")
    setEnumField(subject, "connectionOwnership", "CAPTURE_DEVICE")
    setField(subject, "captureDeviceControlsBarcode", true)
    setField(subject, "captureDeviceTriggerRearmPending", false)
    setField(subject, "captureDeviceTriggerPressObserved", false)
    var readinessActivityCount = 0
    subject.managedReadinessActivityListener = { readinessActivityCount += 1 }

    subject.handleHandheldTriggerEvent(
      HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED,
    )
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    assertEquals(0, readinessActivityCount)
    assertEquals(false, subject.diagnostics().inventoryActive)
    Mockito.verify(reader.Actions.Inventory, Mockito.timeout(1_000)).perform()
    Mockito.verify(reader.Actions.Inventory, Mockito.timeout(1_000)).stop()
  }

  @Test
  fun captureDevice_pressDuringRecoveryProbeOwnsInventoryUntilPhysicalRelease() {
    val subject = createSubject()
    val reader = mockReader()
    Mockito.`when`(reader.isConnected).thenReturn(true)
    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")
    setEnumField(subject, "connectionOwnership", "CAPTURE_DEVICE")
    setField(subject, "captureDeviceControlsBarcode", true)
    setField(subject, "captureDeviceTriggerRearmPending", false)
    setField(subject, "captureDeviceTriggerPressObserved", false)

    subject.handleHandheldTriggerEvent(
      HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED,
    )
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(675))
    subject.handleHandheldTriggerEvent(
      HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED,
    )
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    assertEquals(true, subject.diagnostics().inventoryActive)
    Mockito.verify(reader.Actions.Inventory, Mockito.timeout(1_000)).perform()
    Mockito.verify(reader.Actions.Inventory, Mockito.never()).stop()

    subject.handleHandheldTriggerEvent(
      HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED,
    )

    assertEquals(false, subject.diagnostics().inventoryActive)
    Mockito.verify(reader.Actions.Inventory, Mockito.timeout(1_000)).stop()
  }

  @Test
  fun captureDevice_watchdogStopMakesDelayedReleaseRunRecoveryProbe() {
    val subject = createSubject()
    val reader = mockReader()
    Mockito.`when`(reader.isConnected).thenReturn(true)
    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")
    setEnumField(subject, "connectionOwnership", "CAPTURE_DEVICE")
    setField(subject, "captureDeviceControlsBarcode", true)
    setField(subject, "captureDeviceTriggerRearmPending", false)
    setField(subject, "captureDeviceTriggerPressObserved", true)
    setField(subject, "inventoryActive", true)

    val safeStop = subject.javaClass.getDeclaredMethod(
      "safeStopInventory",
      String::class.java,
    )
    safeStop.isAccessible = true
    safeStop.invoke(subject, "watchdog inactivity")
    subject.handleHandheldTriggerEvent(
      HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED,
    )
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    assertEquals(false, subject.diagnostics().inventoryActive)
    Mockito.verify(reader.Actions.Inventory, Mockito.timeout(1_000)).perform()
    Mockito.verify(reader.Actions.Inventory, Mockito.timeout(1_000).times(2)).stop()
  }

  @Test
  fun buildInventoryTriggerInfo_stopsOnHandheldRelease() {
    val triggerInfo = buildInventoryTriggerInfo()

    assertEquals(
      START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE,
      triggerInfo.StartTrigger.triggerType,
    )
    assertEquals(
      STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_HANDHELD_WITH_TIMEOUT,
      triggerInfo.StopTrigger.triggerType,
    )
    assertEquals(
      HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED,
      triggerInfo.StopTrigger.Handheld.handheldTriggerEvent,
    )
    assertEquals(30_000, triggerInfo.StopTrigger.Handheld.handheldTriggerTimeout)
  }

  @Test
  fun connectReader_commOpenErrorSettlesDisconnected() {
    val subject = createSubject()
    val reader = mockReader()
    val readerDevice = Mockito.mock(ReaderDevice::class.java)

    Mockito.`when`(reader.isConnected).thenReturn(false)
    val failure = Mockito.mock(OperationFailureException::class.java)
    Mockito.`when`(failure.results).thenReturn(RFIDResults.RFID_COMM_OPEN_ERROR)
    Mockito.`when`(failure.statusDescription).thenReturn("RFID_COMM_OPEN_ERROR")
    Mockito.doThrow(failure).`when`(reader).connect()
    Mockito.`when`(readerDevice.name).thenReturn("RFD40")
    Mockito.`when`(readerDevice.rfidReader).thenReturn(reader)

    setField(subject, "availableRFIDReaderList", arrayListOf(readerDevice))

    subject.connectReader(0)

    waitUntil {
      val diagnostics = subject.diagnostics()
      diagnostics.connectAttempts == 1L &&
        diagnostics.connectionState == ReaderConnectionStatus.DISCONNECTED
    }
    val diagnostics = subject.diagnostics()
    assertEquals(ReaderConnectionStatus.DISCONNECTED, diagnostics.connectionState)
    assertNull(diagnostics.lastErrorCode)
  }

  @Test
  fun fastTriggerReleaseStopsInventoryImmediately() {
    val subject = createSubject()
    val reader = mockReader()
    Mockito.`when`(reader.isConnected).thenReturn(true)

    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")

    subject.handleHandheldTriggerEvent(HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED)
    subject.handleHandheldTriggerEvent(HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED)

    val diagnostics = subject.diagnostics()
    assertEquals(false, diagnostics.inventoryActive)
    assertEquals("trigger released", diagnostics.lastInventoryStopReason)
    assertNotNull(diagnostics.lastInventoryStopMs)
    Mockito.verify(reader.Actions.Inventory, Mockito.timeout(1_000)).perform()
    Mockito.verify(reader.Actions.Inventory, Mockito.timeout(1_000)).stop()
  }

  @Test
  fun triggerPressDoesNotConfirmManagedReadinessBeforeTagDelivery() {
    val subject = createSubject()
    val reader = mockReader()
    Mockito.`when`(reader.isConnected).thenReturn(true)
    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")
    var readinessActivityCount = 0
    subject.managedReadinessActivityListener = { readinessActivityCount += 1 }

    subject.handleHandheldTriggerEvent(HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED)
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals(0, readinessActivityCount)
  }

  @Test
  fun tagDeliveryConfirmsManagedReadiness() {
    val subject = createSubject()
    val reader = mockReader()
    val tag = Mockito.mock(TagData::class.java)
    Mockito.`when`(tag.tagID).thenReturn("test-tag")
    Mockito.`when`(tag.peakRSSI).thenReturn(-42)
    Mockito.`when`(reader.Actions.getReadTags(100)).thenReturn(arrayOf(tag))
    setField(subject, "reader", reader)
    var readinessActivityCount = 0
    subject.managedReadinessActivityListener = { readinessActivityCount += 1 }

    subject.eventReadNotify(Mockito.mock(RfidReadEvents::class.java))
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, readinessActivityCount)
  }

  @Test
  fun disabledScanningIgnoresTriggerPress() {
    val subject = createSubject()
    val reader = mockReader()
    Mockito.`when`(reader.isConnected).thenReturn(true)

    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")
    subject.setScanningEnabled(false)

    subject.handleHandheldTriggerEvent(HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED)

    assertEquals(false, subject.diagnostics().inventoryActive)
    Mockito.verify(reader.Actions.Inventory, Mockito.never()).perform()
  }

  @Test
  fun recoveryVerificationAllowsTagProofWhileWorkflowScanningIsDisabled() {
    val subject = createSubject()
    val reader = mockReader()
    val tag = Mockito.mock(TagData::class.java)
    Mockito.`when`(reader.isConnected).thenReturn(true)
    Mockito.`when`(tag.tagID).thenReturn("verification-tag")
    Mockito.`when`(tag.peakRSSI).thenReturn(-42)
    Mockito.`when`(reader.Actions.getReadTags(100)).thenReturn(arrayOf(tag))
    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")
    setEnumField(subject, "connectionOwnership", "CAPTURE_DEVICE")
    subject.setScanningEnabled(false)
    subject.setRecoveryVerificationScanEnabled(true)
    var readinessActivityCount = 0
    subject.managedReadinessActivityListener = { readinessActivityCount += 1 }

    subject.handleHandheldTriggerEvent(
      HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED,
    )
    subject.eventReadNotify(Mockito.mock(RfidReadEvents::class.java))
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals(true, subject.diagnostics().inventoryActive)
    assertEquals(1, readinessActivityCount)
    assertFalse(subject.shouldForwardReadTagsToFlutter())
    Mockito.verify(reader.Actions.Inventory).perform()

    subject.handleHandheldTriggerEvent(
      HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED,
    )
    assertEquals(false, subject.diagnostics().inventoryActive)
  }

  @Test
  fun buildRegulatoryConfigForSingleSupportedRegion_returnsConfigForSingleRegion() {
    val regionInfo = mockRegionInfo(
      regionCode = "US",
      standardName = "FCC",
      supportedChannels = arrayOf("1", "2"),
      hoppingConfigurable = true,
      channelSelectable = true,
      lbtConfigurable = false,
    )

    val regulatoryConfig = buildRegulatoryConfigForSingleSupportedRegion(listOf(regionInfo))

    assertNotNull(regulatoryConfig)
    assertEquals("US", regulatoryConfig!!.getRegion())
    assertEquals("FCC", regulatoryConfig.getStandardName())
    assertTrue(regulatoryConfig.isHoppingon())
    assertTrue(regulatoryConfig.isChannelSelectable())
    assertFalse(regulatoryConfig.isLBTConfigurable())
    assertArrayEquals(arrayOf("1", "2"), regulatoryConfig.getEnabledchannels())
  }

  @Test
  fun buildRegulatoryConfigForSingleSupportedRegion_returnsNullForAmbiguousRegions() {
    val usRegion = mockRegionInfo(regionCode = "US", standardName = "FCC")
    val euRegion = mockRegionInfo(regionCode = "EU", standardName = "ETSI")

    val regulatoryConfig = buildRegulatoryConfigForSingleSupportedRegion(listOf(usRegion, euRegion))

    assertNull(regulatoryConfig)
    assertEquals("US (FCC), EU (ETSI)", describeSupportedRegions(listOf(usRegion, euRegion)))
  }

  @Test
  fun readerConnectionTypeToDiscoveryTransports_usbIncludesSerialAndUsbOnly() {
    val transports = readerConnectionTypeToDiscoveryTransports(ReaderConnectionType.USB)

    assertEquals(
      listOf(ENUM_TRANSPORT.SERVICE_SERIAL, ENUM_TRANSPORT.SERVICE_USB),
      transports,
    )
  }

  @Test
  fun readerConnectionTypeToDiscoveryTransports_allPrefersLocalBeforeBluetooth() {
    val transports = readerConnectionTypeToDiscoveryTransports(ReaderConnectionType.ALL)

    assertEquals(
      listOf(
        ENUM_TRANSPORT.SERVICE_SERIAL,
        ENUM_TRANSPORT.SERVICE_USB,
        ENUM_TRANSPORT.BLUETOOTH,
      ),
      transports,
    )
  }

  @Test
  fun setScanningEnabled_reenableClearsLingeringInventoryState() {
    val subject = createSubject()
    setField(subject, "scanningEnabled", false)
    setField(subject, "inventoryActive", true)
    setField(subject, "inventoryWatchdogRunnable", Runnable {})
    setField(subject, "pendingPurgeRunnable", Runnable {})

    subject.setScanningEnabled(true)

    val diagnostics = subject.diagnostics()
    assertEquals(true, diagnostics.scanningEnabled)
    assertEquals(false, diagnostics.inventoryActive)
    assertEquals(false, diagnostics.pendingPurgeActive)
    assertEquals("scanning re-enabled recovery", diagnostics.lastInventoryStopReason)
    assertNotNull(diagnostics.lastInventoryStopMs)
    assertNull(getField<Any?>(subject, "inventoryWatchdogRunnable"))
  }

  @Test
  fun stopInventory_stopFailureStillClearsRecoveryState() {
    val subject = createSubject()
    val reader = mockReader()
    Mockito.`when`(reader.isConnected).thenReturn(true)
    Mockito.doThrow(RuntimeException("boom")).`when`(reader.Actions.Inventory).stop()

    setField(subject, "reader", reader)
    setEnumField(subject, "internalState", "CONNECTED")
    setField(subject, "inventoryActive", true)
    setField(subject, "inventoryWatchdogRunnable", Runnable {})

    subject.stopInventory()

    val diagnostics = subject.diagnostics()
    assertEquals(false, diagnostics.inventoryActive)
    assertNotNull(diagnostics.lastInventoryStopMs)
    assertNull(getField<Any?>(subject, "inventoryWatchdogRunnable"))
  }

  @Test
  fun diagnostics_includesExpandedInventoryFields() {
    val subject = createSubject()
    setField(subject, "inventoryActive", true)
    setField(subject, "lastInventoryStartTimestamp", 101L)
    setField(subject, "lastInventoryStopTimestamp", 202L)
    setField(subject, "lastInventoryStartReason", "trigger pressed")
    setField(subject, "lastInventoryStopReason", "watchdog inactivity")
    setField(subject, "pendingPurgeRunnable", Runnable {})

    val diagnostics = subject.diagnostics()

    assertEquals(true, diagnostics.inventoryActive)
    assertEquals(101L, diagnostics.lastInventoryStartMs)
    assertEquals(202L, diagnostics.lastInventoryStopMs)
    assertEquals(true, diagnostics.pendingPurgeActive)
    assertEquals("trigger pressed", diagnostics.lastInventoryStartReason)
    assertEquals("watchdog inactivity", diagnostics.lastInventoryStopReason)
  }

  private fun createSubject(): RFIDReaderInterface {
    val binaryMessenger = Mockito.mock(BinaryMessenger::class.java)
    val callbacks = FlutterZebraRfidCallbacks(binaryMessenger)
    val context = ApplicationProvider.getApplicationContext<Context>()
    return RFIDReaderInterface(callbacks, context)
  }

  private fun mockReader(): RFIDReader {
    val reader = Mockito.mock(RFIDReader::class.java)
    val config = Mockito.mock(Config::class.java)
    val actions = Mockito.mock(Actions::class.java)

    config.Antennas = Mockito.mock(Antennas::class.java)
    actions.Inventory = Mockito.mock(Inventory::class.java)
    actions.PreFilters = Mockito.mock(PreFilters::class.java)

    reader.Config = config
    reader.Actions = actions
    reader.Events = Mockito.mock(Events::class.java)
    reader.ReaderCapabilities = Mockito.mock(ReaderCapabilities::class.java)
    Mockito.`when`(reader.ReaderCapabilities.transmitPowerLevelValues).thenReturn(intArrayOf(1, 2))
    Mockito.`when`(reader.ReaderCapabilities.firwareVersion).thenReturn("fw")
    Mockito.`when`(reader.ReaderCapabilities.modelName).thenReturn("model")
    Mockito.`when`(reader.ReaderCapabilities.scannerName).thenReturn("scanner")
    Mockito.`when`(reader.ReaderCapabilities.serialNumber).thenReturn("serial")
    Mockito.`when`(
      config.setKeylayoutType(
        ENUM_NEW_KEYLAYOUT_TYPE.RFID,
        ENUM_NEW_KEYLAYOUT_TYPE.SLED_SCAN,
      ),
    ).thenReturn(RFIDResults.RFID_API_SUCCESS)

    return reader
  }

  private fun mockRegionInfo(
    regionCode: String,
    standardName: String? = null,
    supportedChannels: Array<String> = emptyArray(),
    hoppingConfigurable: Boolean = false,
    channelSelectable: Boolean = false,
    lbtConfigurable: Boolean = false,
  ): RegionInfo {
    val regionInfo = Mockito.mock(RegionInfo::class.java)
    Mockito.`when`(regionInfo.regionCode).thenReturn(regionCode)
    Mockito.`when`(regionInfo.standardName).thenReturn(standardName)
    Mockito.`when`(regionInfo.supportedChannels).thenReturn(supportedChannels)
    Mockito.`when`(regionInfo.isHoppingConfigurable()).thenReturn(hoppingConfigurable)
    Mockito.`when`(regionInfo.isChannelSelectable()).thenReturn(channelSelectable)
    Mockito.`when`(regionInfo.isLBTConfigurable()).thenReturn(lbtConfigurable)
    return regionInfo
  }

  private fun setEnumField(target: Any, fieldName: String, enumName: String) {
    val field = target.javaClass.getDeclaredField(fieldName)
    field.isAccessible = true
    val enumClass = field.type.asSubclass(Enum::class.java)
    @Suppress("UNCHECKED_CAST")
    val value = java.lang.Enum.valueOf(enumClass as Class<out Enum<*>>, enumName)
    field.set(target, value)
  }

  private fun setField(target: Any, fieldName: String, value: Any?) {
    val field = target.javaClass.getDeclaredField(fieldName)
    field.isAccessible = true
    field.set(target, value)
  }

  private fun <T> getField(target: Any, fieldName: String): T? {
    val field = target.javaClass.getDeclaredField(fieldName)
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return field.get(target) as T?
  }

  private fun waitUntil(predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 2_000
    while (System.currentTimeMillis() < deadline) {
      if (predicate()) {
        return
      }
      Thread.sleep(20)
    }
  }
}
