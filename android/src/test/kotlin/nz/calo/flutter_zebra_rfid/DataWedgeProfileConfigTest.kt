package nz.calo.flutter_zebra_rfid

import BarcodeScannerEndpoint
import BarcodeScannerMode
import BarcodeScannerSource
import ScannerConnectionStatus
import android.os.Bundle
import nz.calo.flutter_zebra_rfid.barcode.buildDataWedgeBarcodeProfileConfig
import nz.calo.flutter_zebra_rfid.barcode.buildDataWedgeCaptureProfileConfig
import nz.calo.flutter_zebra_rfid.barcode.buildDataWedgeDisableRfidProfileConfig
import nz.calo.flutter_zebra_rfid.barcode.buildDataWedgeIntentProfileConfig
import nz.calo.flutter_zebra_rfid.barcode.buildDataWedgeProfileConfig
import nz.calo.flutter_zebra_rfid.barcode.dataWedgeProfileName
import nz.calo.flutter_zebra_rfid.barcode.inferDataWedgeSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class DataWedgeProfileConfigTest {
  @Test
  fun inferDataWedgeSource_internalCameraIsBuiltInTerminalEndpoint() {
    assertEquals(
      BarcodeScannerSource.BUILT_IN_TERMINAL,
      inferDataWedgeSource("INTERNAL_CAMERA", "Camera Scanner"),
    )
  }

  @Test
  fun dataWedgeProfileName_scopesBarcodeProfileToPackage() {
    assertEquals(
      "nz.calo.flutter_zebra_rfid_example.barcode",
      dataWedgeProfileName("nz.calo.flutter_zebra_rfid_example"),
    )
  }

  @Test
  fun buildDataWedgeBarcodeProfileConfig_enablesSelectedBarcodeEndpointOnly() {
    val endpoint = dataWedgeEndpoint(
      zebraScannerIdentifier = "INTERNAL_CAMERA",
      scannerIndex = 2,
    )

    val config = buildDataWedgeBarcodeProfileConfig(
      profileName = "test.profile.barcode",
      packageName = "test.profile",
      endpoint = endpoint,
    )

    assertEquals("test.profile.barcode", config.getString("PROFILE_NAME"))
    assertEquals("CREATE_IF_NOT_EXIST", config.getString("CONFIG_MODE"))
    val app = config.getParcelableArray("APP_LIST")!!.single() as android.os.Bundle
    assertEquals("test.profile", app.getString("PACKAGE_NAME"))
    val plugin = config.getBundle("PLUGIN_CONFIG")!!
    assertEquals("BARCODE", plugin.getString("PLUGIN_NAME"))
    val params = plugin.getBundle("PARAM_LIST")!!
    assertEquals("true", params.getString("scanner_input_enabled"))
    assertEquals("INTERNAL_CAMERA", params.getString("scanner_selection_by_identifier"))
    assertEquals("2", params.getString("scanner_selection"))
  }

  @Test
  fun buildDataWedgeCaptureProfileConfig_idleDisablesBarcodeAndRfidAtomically() {
    val config = buildDataWedgeCaptureProfileConfig(
      "test.barcode",
      "test",
      null,
      "test.BARCODE",
    )

    assertEquals("true", config.getString("PROFILE_ENABLED"))
    val plugins = config.getParcelableArrayList<Bundle>("PLUGIN_CONFIG")!!
    assertEquals(listOf("BARCODE", "RFID", "INTENT"), plugins.map { it.getString("PLUGIN_NAME") })
    assertEquals(
      "false",
      plugins.first().getBundle("PARAM_LIST")!!.getString("scanner_input_enabled"),
    )
    assertEquals(
      "false",
      plugins[1].getBundle("PARAM_LIST")!!.getString("rfid_input_enabled"),
    )
  }

  @Test
  fun buildDataWedgeDisableRfidProfileConfig_disablesRfidInputForBarcodeProfile() {
    val config = buildDataWedgeDisableRfidProfileConfig("test.profile.barcode")

    assertEquals("test.profile.barcode", config.getString("PROFILE_NAME"))
    assertEquals("UPDATE", config.getString("CONFIG_MODE"))
    val plugin = config.getBundle("PLUGIN_CONFIG")!!
    assertEquals("RFID", plugin.getString("PLUGIN_NAME"))
    val params = plugin.getBundle("PARAM_LIST")!!
    assertEquals("false", params.getString("rfid_input_enabled"))
  }

  @Test
  fun buildDataWedgeIntentProfileConfig_routesBarcodeIntentToPluginAction() {
    val config = buildDataWedgeIntentProfileConfig(
      profileName = "test.profile.barcode",
      actionBarcode = "test.ACTION_BARCODE",
    )

    val plugin = config.getBundle("PLUGIN_CONFIG")!!
    assertEquals("INTENT", plugin.getString("PLUGIN_NAME"))
    val params = plugin.getBundle("PARAM_LIST")!!
    assertEquals("true", params.getString("intent_output_enabled"))
    assertEquals("test.ACTION_BARCODE", params.getString("intent_action"))
    assertEquals("2", params.getString("intent_delivery"))
  }

  @Test
  fun buildDataWedgeProfileConfig_configuresAllPluginsInOneCommand() {
    val endpoint = dataWedgeEndpoint(
      zebraScannerIdentifier = "INTERNAL_IMAGER",
      scannerIndex = 2,
    )

    val config = buildDataWedgeProfileConfig(
      profileName = "test.profile.barcode",
      packageName = "test.profile",
      endpoint = endpoint,
      actionBarcode = "test.ACTION_BARCODE",
    )

    assertEquals("CREATE_IF_NOT_EXIST", config.getString("CONFIG_MODE"))
    val plugins = config.getParcelableArrayList<android.os.Bundle>("PLUGIN_CONFIG")!!
    assertEquals(listOf("BARCODE", "RFID", "INTENT"), plugins.map {
      it.getString("PLUGIN_NAME")
    })
    assertEquals(
      "INTERNAL_IMAGER",
      plugins[0].getBundle("PARAM_LIST")!!
        .getString("scanner_selection_by_identifier"),
    )
    assertEquals(
      "false",
      plugins[1].getBundle("PARAM_LIST")!!.getString("rfid_input_enabled"),
    )
    assertEquals(
      "test.ACTION_BARCODE",
      plugins[2].getBundle("PARAM_LIST")!!.getString("intent_action"),
    )
  }

  private fun dataWedgeEndpoint(
    zebraScannerIdentifier: String,
    scannerIndex: Long,
  ): BarcodeScannerEndpoint =
    BarcodeScannerEndpoint(
      endpointId = "datawedge:$zebraScannerIdentifier",
      displayName = "Internal imager",
      source = BarcodeScannerSource.BUILT_IN_TERMINAL,
      mode = BarcodeScannerMode.DATA_WEDGE,
      connectionStatus = ScannerConnectionStatus.DISCONNECTED,
      active = false,
      preferred = false,
      zebraScannerIdentifier = zebraScannerIdentifier,
      scannerIndex = scannerIndex,
      scannerId = null,
      model = null,
      serialNumber = null,
    )
}
