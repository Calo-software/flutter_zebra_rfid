package nz.calo.flutter_zebra_rfid

import FlutterZebraBarcodeCallbacks
import android.content.Intent
import android.os.Looper
import android.os.Bundle
import com.zebra.scannercontrol.DCSSDKDefs
import com.zebra.scannercontrol.DCSScannerInfo
import com.zebra.scannercontrol.SDKHandler
import nz.calo.flutter_zebra_rfid.barcode.BarcodeScannerInterface
import nz.calo.flutter_zebra_rfid.barcode.DataWedgeReadinessLatch
import nz.calo.flutter_zebra_rfid.barcode.migratePreferredEndpointId
import nz.calo.flutter_zebra_rfid.barcode.shouldInitializeScannerSdk
import nz.calo.flutter_zebra_rfid.barcode.shouldUseDataWedge
import nz.calo.flutter_zebra_rfid.barcode.stableScannerSdkEndpointId
import nz.calo.flutter_zebra_rfid.barcode.scannerStatusFromNotification
import nz.calo.flutter_zebra_rfid.capture.CaptureDiagnosticSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
internal class BarcodeScannerInterfaceTest {
    @Test
    fun decodedDataReachesFlutterBeforeDiagnosticBookkeeping() {
        var delivered = false
        val callbacks = Mockito.mock(FlutterZebraBarcodeCallbacks::class.java)
        Mockito.doAnswer {
            delivered = true
            null
        }.`when`(callbacks).onBarcodeRead(
            anyValue(),
            anyValue(),
        )
        val diagnostics = CaptureDiagnosticSink { _, operation, _, _ ->
            if (operation == "decoded") {
                assertTrue("diagnostics ran before barcode delivery", delivered)
            }
        }
        val subject = BarcodeScannerInterface(
            callbacks = callbacks,
            diagnostics = diagnostics,
        )
        val intent = Intent().putExtra(
            "com.symbol.datawedge.data_string",
            "order-123",
        )
        val method = subject.javaClass.getDeclaredMethod(
            "handleDataWedgeBarcode",
            Intent::class.java,
        )
        method.isAccessible = true

        method.invoke(subject, intent)

        assertTrue(delivered)
        subject.onDestroy()
    }

    @Test
    fun unchangedEndpointSnapshotIsNotEmittedTwice() {
        val callbacks = Mockito.mock(FlutterZebraBarcodeCallbacks::class.java)
        val scanner = Mockito.mock(DCSScannerInfo::class.java)
        Mockito.`when`(scanner.scannerID).thenReturn(7)
        Mockito.`when`(scanner.scannerName).thenReturn("RFD40 barcode")
        val subject = BarcodeScannerInterface(callbacks)
        scannerList(subject).add(scanner)
        val method = subject.javaClass.getDeclaredMethod("emitEndpoints")
        method.isAccessible = true

        method.invoke(subject)
        method.invoke(subject)

        Mockito.verify(callbacks, Mockito.times(1))
            .onAvailableBarcodeScannersChanged(anyValue(), anyValue())
        Mockito.verify(callbacks, Mockito.times(1))
            .onActiveBarcodeScannerChanged(anyValue(), anyValue())
        subject.onDestroy()
    }

    @Test
    fun retainedReadinessCompletesOnceWhenWaitingNotificationArrives() {
        var completions = 0
        val latch = DataWedgeReadinessLatch { completions += 1 }

        assertFalse(latch.observe("DISABLED"))
        assertTrue(latch.observe("WAITING"))
        assertFalse(latch.observe("WAITFORTRIGGER"))
        assertEquals(1, completions)
    }

    @Test
    fun scannerStatusNotificationReadsDocumentedStatusField() {
        val notification = Bundle().apply {
            putString("NOTIFICATION_TYPE", "SCANNER_STATUS")
            putString("STATUS", "WAITING")
            putString("PROFILE_NAME", "com.example.app.barcode")
        }

        assertEquals("WAITING", scannerStatusFromNotification(notification))
    }

    @Test
    fun tc22UsesDataWedgeWithoutInitializingScannerSdk() {
        assertFalse(
            shouldInitializeScannerSdk(
                manufacturer = "Zebra Technologies",
                model = "TC22",
                product = "TC22",
                device = "TC22",
            ),
        )
        assertTrue(
            shouldInitializeScannerSdk(
                manufacturer = "Samsung",
                model = "SM-A546E",
                product = "a54x",
                device = "a54x",
            ),
        )
    }

    @Test
    fun onlyZebraTerminalTopologyUsesDataWedge() {
        assertTrue(
            shouldUseDataWedge(
                manufacturer = "Zebra Technologies",
                model = "TC22",
                product = "TC22",
                device = "TC22",
            ),
        )
        assertFalse(
            shouldUseDataWedge(
                manufacturer = "Samsung",
                model = "SM-A546E",
                product = "a54x",
                device = "a54x",
            ),
        )
    }

    @Test
    fun scannerSdkEndpointIdentitySurvivesNumericIdReassignment() {
        val firstLaunch = stableScannerSdkEndpointId(
            scannerId = 4,
            hardwareIdentity = "24:22:95:25:10:12:64",
            scannerName = "RFD40+",
        )
        val secondLaunch = stableScannerSdkEndpointId(
            scannerId = 2,
            hardwareIdentity = "24:22:95:25:10:12:64",
            scannerName = "RFD40+",
        )
        val differentDeviceReusingOldId = stableScannerSdkEndpointId(
            scannerId = 4,
            hardwareIdentity = "AA:BB:CC:DD:EE:FF",
            scannerName = "Galaxy Watch",
        )

        assertEquals(firstLaunch, secondLaunch)
        assertNotEquals(firstLaunch, differentDeviceReusingOldId)
    }

    @Test
    fun legacyNumericScannerPreferenceIsNotRestored() {
        assertNull(migratePreferredEndpointId("scanner-sdk:4"))
        assertNull(migratePreferredEndpointId("scanner-sdk:runtime:4"))
        assertEquals(
            "scanner-sdk:hardware:24229525101264",
            migratePreferredEndpointId("scanner-sdk:hardware:24229525101264"),
        )
        assertEquals(
            "datawedge:INTERNAL_IMAGER",
            migratePreferredEndpointId("datawedge:INTERNAL_IMAGER"),
        )
    }

    @Test
    fun sessionEstablishedReturnsBeforeFlutterCallbacksAreDelivered() {
        val callbacks = Mockito.mock(FlutterZebraBarcodeCallbacks::class.java)
        val scanner = Mockito.mock(DCSScannerInfo::class.java)
        val subject = BarcodeScannerInterface(callbacks)

        Mockito.`when`(scanner.scannerID).thenReturn(7)
        Mockito.`when`(scanner.scannerName).thenReturn("RFD40 barcode")

        subject.dcssdkEventCommunicationSessionEstablished(scanner)

        assertTrue(Mockito.mockingDetails(callbacks).invocations.isEmpty())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Mockito.mockingDetails(callbacks).invocations.isNotEmpty())
        subject.onDestroy()
    }

    @Test
    fun sessionEstablishedEventWinsOverFailedReturnAndReconnectRearmsEvents() {
        val callbacks = Mockito.mock(FlutterZebraBarcodeCallbacks::class.java)
        val handler = Mockito.mock(SDKHandler::class.java)
        val scanner = Mockito.mock(DCSScannerInfo::class.java)
        val subject = BarcodeScannerInterface(callbacks)
        var result: Result<Unit>? = null

        Mockito.`when`(scanner.scannerID).thenReturn(7)
        Mockito.`when`(scanner.scannerName).thenReturn("RFD40 barcode")
        scannerList(subject).add(scanner)
        setField(subject, "sdkHandler", handler)
        Mockito.`when`(handler.dcssdkSetDelegate(subject))
            .thenReturn(DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS)
        Mockito.`when`(handler.dcssdkSubsribeForEvents(Mockito.anyInt()))
            .thenReturn(DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS)
        Mockito.`when`(handler.dcssdkEstablishCommunicationSession(7)).thenAnswer {
            subject.dcssdkEventCommunicationSessionEstablished(scanner)
            DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_FAILURE
        }

        subject.connectToScannerForCaptureDevice(7) {
            result = it
        }
        waitUntil {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            result != null
        }

        assertNotNull(result)
        assertTrue(result!!.isSuccess)
        val ordered = Mockito.inOrder(handler)
        ordered.verify(handler).dcssdkSetDelegate(subject)
        ordered.verify(handler).dcssdkSubsribeForEvents(Mockito.anyInt())
        ordered.verify(handler).dcssdkEstablishCommunicationSession(7)
        subject.onDestroy()
    }

    @Test
    fun captureDeviceReadinessRearmsConnectedScannerDuringDiscoveryGap() {
        val callbacks = Mockito.mock(FlutterZebraBarcodeCallbacks::class.java)
        val handler = Mockito.mock(SDKHandler::class.java)
        val scanner = Mockito.mock(DCSScannerInfo::class.java)
        val subject = BarcodeScannerInterface(callbacks)
        var result: Result<Unit>? = null

        Mockito.`when`(scanner.scannerID).thenReturn(7)
        Mockito.`when`(scanner.scannerName).thenReturn("RFD40 barcode")
        setField(subject, "sdkHandler", handler)
        setField(subject, "currentScanner", scanner)
        Mockito.`when`(handler.dcssdkSetDelegate(subject))
            .thenReturn(DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS)
        Mockito.`when`(handler.dcssdkSubsribeForEvents(Mockito.anyInt()))
            .thenReturn(DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS)
        Mockito.`when`(handler.dcssdkEstablishCommunicationSession(7))
            .thenReturn(DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_FAILURE)

        subject.connectToScannerForCaptureDevice(7) {
            result = it
        }
        waitUntil {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            result != null
        }

        assertTrue(result!!.isSuccess)
        val ordered = Mockito.inOrder(handler)
        ordered.verify(handler).dcssdkSetDelegate(subject)
        ordered.verify(handler).dcssdkSubsribeForEvents(Mockito.anyInt())
        ordered.verify(handler).dcssdkEstablishCommunicationSession(7)
        subject.onDestroy()
    }

    @Test
    fun captureDeviceScanEnableRunsOnSerializedScannerExecutor() {
        val callbacks = Mockito.mock(FlutterZebraBarcodeCallbacks::class.java)
        val handler = Mockito.mock(SDKHandler::class.java)
        val scanner = Mockito.mock(DCSScannerInfo::class.java)
        val subject = BarcodeScannerInterface(callbacks)
        var result: Result<Unit>? = null

        Mockito.`when`(scanner.scannerID).thenReturn(7)
        setField(subject, "sdkHandler", handler)
        setField(subject, "currentScanner", scanner)
        Mockito.`when`(
            handler.dcssdkExecuteCommandOpCodeInXMLForScanner(
                Mockito.eq(
                    DCSSDKDefs.DCSSDK_COMMAND_OPCODE.DCSSDK_DEVICE_SCAN_ENABLE,
                ),
                Mockito.anyString(),
                Mockito.any(StringBuilder::class.java),
                Mockito.eq(7),
            ),
        ).thenReturn(DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS)

        subject.enableScannerForCaptureDevice(7) {
            result = it
        }
        waitUntil {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            result != null
        }

        assertTrue(result!!.isSuccess)
        Mockito.verify(handler).dcssdkExecuteCommandOpCodeInXMLForScanner(
            Mockito.eq(
                DCSSDKDefs.DCSSDK_COMMAND_OPCODE.DCSSDK_DEVICE_SCAN_ENABLE,
            ),
            Mockito.eq("<inArgs><scannerID>7</scannerID></inArgs>"),
            Mockito.any(StringBuilder::class.java),
            Mockito.eq(7),
        )
        subject.onDestroy()
    }

    @Suppress("UNCHECKED_CAST")
    private fun scannerList(
        subject: BarcodeScannerInterface,
    ): MutableList<DCSScannerInfo> {
        val field = subject.javaClass.getDeclaredField("availableScannerList")
        field.isAccessible = true
        return field.get(subject) as MutableList<DCSScannerInfo>
    }

    private fun setField(subject: Any, name: String, value: Any?) {
        val field = subject.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(subject, value)
    }

    private fun waitUntil(
        timeoutMs: Long = 2_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("Condition was not met within ${timeoutMs}ms", condition())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        Mockito.any<T>()
        return null as T
    }
}
