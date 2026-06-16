package nz.calo.flutter_zebra_rfid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zebra.rfid.api3.Antennas
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE
import com.zebra.rfid.api3.RegionInfo
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.ReaderDevice
import io.flutter.plugin.common.BinaryMessenger
import nz.calo.flutter_zebra_rfid.rfid.buildRegulatoryConfigForSingleSupportedRegion
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
  fun connectReader_alreadyConnectedRearmsReaderSetup() {
    val subject = createSubject()
    val reader = Mockito.mock(RFIDReader::class.java, Mockito.RETURNS_DEEP_STUBS)
    val readerDevice = Mockito.mock(ReaderDevice::class.java)
    val antennaConfig = Mockito.mock(Antennas.AntennaRfConfig::class.java)
    val singulationControl = Mockito.mock(Antennas.SingulationControl::class.java, Mockito.RETURNS_DEEP_STUBS)

    Mockito.`when`(reader.isConnected).thenReturn(true)
    Mockito.`when`(reader.ReaderCapabilities.transmitPowerLevelValues).thenReturn(intArrayOf(1, 2))
    Mockito.`when`(reader.ReaderCapabilities.firwareVersion).thenReturn("fw")
    Mockito.`when`(reader.ReaderCapabilities.modelName).thenReturn("model")
    Mockito.`when`(reader.ReaderCapabilities.scannerName).thenReturn("scanner")
    Mockito.`when`(reader.ReaderCapabilities.serialNumber).thenReturn("serial")
    Mockito.`when`(reader.Config.Antennas.getAntennaRfConfig(1)).thenReturn(antennaConfig)
    Mockito.`when`(reader.Config.Antennas.getSingulationControl(1)).thenReturn(singulationControl)
    Mockito.`when`(readerDevice.name).thenReturn("RFD40")

    setField(subject, "reader", reader)
    setField(subject, "readerDevice", readerDevice)
    setField(subject, "availableRFIDReaderList", arrayListOf(readerDevice))

    val info = subject.connectReader(0)

    assertSame(info, getField(subject, "readerInfo"))
    assertEquals(ReaderConnectionStatus.CONNECTED, subject.diagnostics().connectionState)
    Mockito.verify(reader.Events).addEventsListener(subject)
    Mockito.verify(reader.Config).setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
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
    val reader = Mockito.mock(RFIDReader::class.java, Mockito.RETURNS_DEEP_STUBS)
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
}
