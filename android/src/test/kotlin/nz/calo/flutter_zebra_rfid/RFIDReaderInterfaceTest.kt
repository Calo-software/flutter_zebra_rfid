package nz.calo.flutter_zebra_rfid

import android.content.Context
import FlutterZebraRfidCallbacks
import androidx.test.core.app.ApplicationProvider
import com.zebra.rfid.api3.Actions
import com.zebra.rfid.api3.Antennas
import com.zebra.rfid.api3.BatteryStatistics
import com.zebra.rfid.api3.Config
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE
import com.zebra.rfid.api3.Events
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE
import com.zebra.rfid.api3.Inventory
import com.zebra.rfid.api3.PreFilters
import com.zebra.rfid.api3.RegionInfo
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.RFIDResults
import com.zebra.rfid.api3.ReaderCapabilities
import com.zebra.rfid.api3.ReaderDevice
import com.zebra.rfid.api3.OperationFailureException
import com.zebra.rfid.api3.START_TRIGGER_TYPE
import com.zebra.rfid.api3.STOP_TRIGGER_TYPE
import io.flutter.plugin.common.BinaryMessenger
import nz.calo.flutter_zebra_rfid.rfid.buildRegulatoryConfigForSingleSupportedRegion
import nz.calo.flutter_zebra_rfid.rfid.batteryDataFromStatistics
import nz.calo.flutter_zebra_rfid.rfid.batteryDataFromReaderEvent
import nz.calo.flutter_zebra_rfid.rfid.buildInventoryTriggerInfo
import nz.calo.flutter_zebra_rfid.rfid.describeSupportedRegions
import nz.calo.flutter_zebra_rfid.rfid.RFIDReaderInterface
import nz.calo.flutter_zebra_rfid.rfid.readerConnectionTypeToDiscoveryTransports
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

@RunWith(RobolectricTestRunner::class)
internal class RFIDReaderInterfaceTest {

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
