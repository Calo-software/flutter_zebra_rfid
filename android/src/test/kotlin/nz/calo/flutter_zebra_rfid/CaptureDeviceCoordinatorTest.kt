package nz.calo.flutter_zebra_rfid

import BarcodeScannerEndpoint
import BarcodeScannerMode
import BarcodeScannerSource
import CaptureReaderConfig
import FlutterZebraCaptureCallbacks
import ScannerConnectionStatus
import Reader
import ReaderConnectionStatus
import android.content.Context
import android.os.Looper
import java.time.Duration
import nz.calo.flutter_zebra_rfid.barcode.BarcodeScannerInterface
import nz.calo.flutter_zebra_rfid.capture.CaptureDeviceCoordinator
import nz.calo.flutter_zebra_rfid.rfid.RFIDReaderInterface
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
internal class CaptureDeviceCoordinatorTest {
    @Test
    fun comboCaptureDeviceRestoresBarcodeOnlyAfterRfidIsReady() {
        val context = Mockito.mock(Context::class.java)
        val rfid = Mockito.mock(RFIDReaderInterface::class.java)
        val barcode = Mockito.mock(BarcodeScannerInterface::class.java)
        val callbacks = Mockito.mock(FlutterZebraCaptureCallbacks::class.java)
        val reader = Reader(
            name = "RFD40",
            id = 0,
            info = null,
            hardwareIdentity = "AA:BB:CC:40",
        )
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
        var rfidStatusListener: ((ReaderConnectionStatus) -> Unit)? = null
        var barcodeCompletion: ((Result<Unit>) -> Unit)? = null

        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(listOf(reader))
        Mockito.`when`(barcode.barcodeEndpoints()).thenReturn(listOf(endpoint))
        Mockito.doAnswer { invocation ->
            barcodeCompletion = invocation.getArgument(1)
            null
        }.`when`(barcode).connectToScannerForCaptureDevice(
            Mockito.eq(7),
            anyValue(),
        )

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice(
            "capture:rfid:AA:BB:CC:40",
            null,
        ) {}

        Mockito.verify(rfid).connectReaderForCaptureDevice(
            0,
            "AA:BB:CC:40",
            true,
        )
        Mockito.verify(barcode, Mockito.never())
            .setActiveEndpointForCaptureDevice(Mockito.anyString())

        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        Mockito.verify(barcode).setActiveEndpointForCaptureDevice("scanner-sdk:7")
        assertNotNull(barcodeCompletion)
    }

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
        }.`when`(barcode).connectToScannerForCaptureDevice(
            Mockito.eq(7),
            anyValue(),
        )

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice("capture:barcode:scanner-sdk:7", null) {
            result = it
        }

        assertNull(result)
        assertNotNull(pendingConnection)

        pendingConnection!!.invoke(Result.success(Unit))

        assertTrue(result!!.isSuccess)
        Mockito.verify(barcode).connectToScannerForCaptureDevice(
            Mockito.eq(7),
            anyValue(),
        )
    }

    @Test
    fun exhaustedConfigurationRetriesDoNotBlockPhysicalDisconnectRecovery() {
        val context = Mockito.mock(Context::class.java)
        val rfid = Mockito.mock(RFIDReaderInterface::class.java)
        val barcode = Mockito.mock(BarcodeScannerInterface::class.java)
        val callbacks = Mockito.mock(FlutterZebraCaptureCallbacks::class.java)
        val reader = Reader(
            name = "RFD40",
            id = 0,
            info = null,
            hardwareIdentity = "AA:BB:CC:40",
        )
        var rfidStatusListener: ((ReaderConnectionStatus) -> Unit)? = null

        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(listOf(reader))
        Mockito.`when`(barcode.barcodeEndpoints()).thenReturn(emptyList())
        Mockito.doThrow(IllegalStateException("configuration failed"))
            .`when`(rfid)
            .configureReader(anyValue(), Mockito.eq(false), Mockito.eq(true))

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice(
            "capture:rfid:AA:BB:CC:40",
            CaptureReaderConfig(transmitPowerIndex = 0L),
        ) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        listOf(1L, 2L, 4L, 8L, 15L).forEach { seconds ->
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofSeconds(seconds))
        }

        rfidStatusListener!!.invoke(ReaderConnectionStatus.DISCONNECTED)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        Mockito.verify(rfid, Mockito.times(2)).connectReaderForCaptureDevice(
            0,
            "AA:BB:CC:40",
            false,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        Mockito.any<T>()
        return null as T
    }
}
