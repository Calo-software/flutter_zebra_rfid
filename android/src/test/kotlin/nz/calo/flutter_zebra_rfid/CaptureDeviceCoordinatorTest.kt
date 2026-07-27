package nz.calo.flutter_zebra_rfid

import BarcodeScannerEndpoint
import BarcodeScannerMode
import BarcodeScannerSource
import FlutterZebraCaptureCallbacks
import ScannerConnectionStatus
import android.content.Context
import nz.calo.flutter_zebra_rfid.barcode.BarcodeScannerInterface
import nz.calo.flutter_zebra_rfid.capture.CaptureDeviceCoordinator
import nz.calo.flutter_zebra_rfid.rfid.RFIDReaderInterface
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

internal class CaptureDeviceCoordinatorTest {
    @Test
    fun scannerSdkCaptureDeviceCompletesAfterAsyncBarcodeConnection() {
        val context = Mockito.mock(Context::class.java)
        val rfid = Mockito.mock(RFIDReaderInterface::class.java)
        val barcode = Mockito.mock(BarcodeScannerInterface::class.java)
        val callbacks = Mockito.mock(FlutterZebraCaptureCallbacks::class.java)
        val endpoint = BarcodeScannerEndpoint(
            endpointId = "scanner-sdk:7",
            displayName = "RFD40 barcode",
            source = BarcodeScannerSource.EXTERNAL_BLUETOOTH,
            mode = BarcodeScannerMode.SCANNER_SDK,
            connectionStatus = ScannerConnectionStatus.DISCONNECTED,
            active = false,
            preferred = false,
            scannerId = 7,
        )
        var pendingConnection: ((Result<Unit>) -> Unit)? = null
        var result: Result<Unit>? = null

        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(emptyList())
        Mockito.`when`(barcode.barcodeEndpoints()).thenReturn(listOf(endpoint))
        Mockito.doAnswer { invocation ->
            pendingConnection = invocation.getArgument(1)
            null
        }.`when`(barcode).connectToScanner(Mockito.eq(7), anyValue())

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice("capture:barcode:scanner-sdk:7", null) {
            result = it
        }

        assertNull(result)
        assertNotNull(pendingConnection)

        pendingConnection!!.invoke(Result.success(Unit))

        assertTrue(result!!.isSuccess)
        Mockito.verify(barcode).connectToScanner(Mockito.eq(7), anyValue())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        Mockito.any<T>()
        return null as T
    }
}
