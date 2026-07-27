package nz.calo.flutter_zebra_rfid

import BarcodeScannerEndpoint
import BarcodeScannerMode
import BarcodeScannerSource
import CaptureReaderConfig
import CaptureCapabilityStatus
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
import org.junit.Assert.assertEquals
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
    fun refreshWaitsForDataWedgeHealthAndDelayedEndpointEnumeration() {
        val context = Mockito.mock(Context::class.java)
        val rfid = Mockito.mock(RFIDReaderInterface::class.java)
        val barcode = Mockito.mock(BarcodeScannerInterface::class.java)
        val callbacks = Mockito.mock(FlutterZebraCaptureCallbacks::class.java)
        val endpoint = BarcodeScannerEndpoint(
            endpointId = "datawedge:INTERNAL_IMAGER",
            displayName = "TC22 internal imager",
            source = BarcodeScannerSource.BUILT_IN_TERMINAL,
            mode = BarcodeScannerMode.DATA_WEDGE,
            connectionStatus = ScannerConnectionStatus.CONNECTED,
            active = false,
            preferred = false,
            zebraScannerIdentifier = "INTERNAL_IMAGER",
        )
        var endpoints = emptyList<BarcodeScannerEndpoint>()
        var healthCompletion: ((Result<Unit>) -> Unit)? = null
        var result: Result<Unit>? = null

        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(emptyList())
        Mockito.`when`(barcode.barcodeEndpoints()).thenAnswer { endpoints }
        Mockito.doAnswer { invocation ->
            healthCompletion = invocation.getArgument(1)
            null
        }.`when`(barcode).refreshBarcodeScanners(
            anyValue(),
            anyValue(),
        )

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.refreshCaptureDevices { result = it }

        assertNull(result)
        assertNotNull(healthCompletion)

        endpoints = listOf(endpoint)
        healthCompletion!!.invoke(Result.success(Unit))

        assertTrue(result!!.isSuccess)
    }

    @Test
    fun lateTc22DataWedgeEndpointUsesBarcodeOnlyRecoveryWithoutRfidReconnect() {
        val context = Mockito.mock(Context::class.java)
        val rfid = Mockito.mock(RFIDReaderInterface::class.java)
        val barcode = Mockito.mock(BarcodeScannerInterface::class.java)
        val callbacks = Mockito.mock(FlutterZebraCaptureCallbacks::class.java)
        val reader = Reader(
            name = "RFD40",
            id = 0,
            info = null,
            hardwareIdentity = "USB:RFD40",
        )
        val endpoint = BarcodeScannerEndpoint(
            endpointId = "datawedge:INTERNAL_IMAGER",
            displayName = "TC22 internal imager",
            source = BarcodeScannerSource.BUILT_IN_TERMINAL,
            mode = BarcodeScannerMode.DATA_WEDGE,
            connectionStatus = ScannerConnectionStatus.DISCONNECTED,
            active = false,
            preferred = false,
            zebraScannerIdentifier = "INTERNAL_IMAGER",
        )
        var endpoints = emptyList<BarcodeScannerEndpoint>()
        var endpointsChanged: (() -> Unit)? = null
        var rfidStatusListener: ((ReaderConnectionStatus) -> Unit)? = null

        Mockito.doAnswer { invocation ->
            endpointsChanged = invocation.getArgument(0)
            null
        }.`when`(barcode).endpointsChangedListener = anyValue()
        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(listOf(reader))
        Mockito.`when`(barcode.barcodeEndpoints()).thenAnswer { endpoints }
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(Result<Unit>) -> Unit>(1)
                .invoke(Result.success(Unit))
            null
        }.`when`(barcode).setActiveEndpointForCaptureDevice(
            Mockito.anyString(),
            anyValue(),
        )
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(Result<Unit>) -> Unit>(0)
                .invoke(Result.success(Unit))
            null
        }.`when`(rfid).reassertCaptureDeviceTriggerOwnership(anyValue())

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice("capture:rfid:USB:RFD40", null) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        endpoints = listOf(endpoint)
        endpointsChanged!!.invoke()

        Mockito.verify(rfid, Mockito.times(1)).connectReaderForCaptureDevice(
            0,
            "USB:RFD40",
            false,
        )
        Mockito.verify(barcode, Mockito.times(1))
            .setActiveEndpointForCaptureDevice(
                Mockito.anyString(),
                anyValue(),
            )
        Mockito.verify(barcode, Mockito.never())
            .connectToScannerForCaptureDevice(Mockito.anyInt(), anyValue())
        assertEquals(
            CaptureCapabilityStatus.CONNECTED,
            coordinator.activeCaptureDevice()?.barcode?.status,
        )
    }

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
        var triggerOwnershipCompletion: ((Result<Unit>) -> Unit)? = null
        var barcodeEnableCompletion: ((Result<Unit>) -> Unit)? = null
        var connectionResult: Result<Unit>? = null

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
        Mockito.doAnswer { invocation ->
            triggerOwnershipCompletion = invocation.getArgument(0)
            null
        }.`when`(rfid).reassertCaptureDeviceTriggerOwnership(anyValue())
        Mockito.doAnswer { invocation ->
            barcodeEnableCompletion = invocation.getArgument(1)
            null
        }.`when`(barcode).enableScannerForCaptureDevice(
            Mockito.eq(7),
            anyValue(),
        )

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice(
            "capture:rfid:AA:BB:CC:40",
            null,
        ) { connectionResult = it }

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
        barcodeCompletion!!.invoke(Result.success(Unit))

        assertNotNull(barcodeEnableCompletion)
        assertNull(connectionResult)
        barcodeEnableCompletion!!.invoke(Result.success(Unit))
        assertNotNull(triggerOwnershipCompletion)
        assertNull(connectionResult)
        triggerOwnershipCompletion!!.invoke(Result.success(Unit))
        assertTrue(connectionResult!!.isSuccess)
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
    fun foregroundResumeRearmsSelectedBarcodeDuringTransientDiscoveryGap() {
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
            connectionStatus = ScannerConnectionStatus.CONNECTED,
            active = true,
            preferred = true,
            scannerId = 7,
        )
        var discoveredEndpoints = listOf(endpoint)
        var rfidStatusListener: ((ReaderConnectionStatus) -> Unit)? = null

        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(listOf(reader))
        Mockito.`when`(barcode.barcodeEndpoints()).thenAnswer { discoveredEndpoints }
        Mockito.doAnswer { invocation ->
            val completion = invocation.getArgument<(Result<Unit>) -> Unit>(1)
            completion(Result.success(Unit))
            null
        }.`when`(barcode).connectToScannerForCaptureDevice(
            Mockito.eq(7),
            anyValue(),
        )
        Mockito.doAnswer { invocation ->
            val completion = invocation.getArgument<(Result<Unit>) -> Unit>(0)
            completion(Result.success(Unit))
            null
        }.`when`(rfid).reassertCaptureDeviceTriggerOwnership(anyValue())
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(Result<Unit>) -> Unit>(1)
                .invoke(Result.success(Unit))
            null
        }.`when`(barcode).enableScannerForCaptureDevice(
            Mockito.eq(7),
            anyValue(),
        )

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice(
            "capture:rfid:AA:BB:CC:40",
            null,
        ) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        coordinator.setCaptureDeviceForeground(false) {}
        discoveredEndpoints = emptyList()
        coordinator.setCaptureDeviceForeground(true) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        assertNotNull(coordinator.activeCaptureDevice()?.barcode)
        assertEquals(
            CaptureCapabilityStatus.CONNECTED,
            coordinator.activeCaptureDevice()?.barcode?.status,
        )
        Mockito.verify(rfid, Mockito.times(2)).connectReaderForCaptureDevice(
            0,
            "AA:BB:CC:40",
            true,
        )
        Mockito.verify(barcode, Mockito.times(2)).connectToScannerForCaptureDevice(
            Mockito.eq(7),
            anyValue(),
        )
        Mockito.verify(barcode, Mockito.times(2))
            .setActiveEndpointForCaptureDevice("scanner-sdk:7")
    }

    @Test
    fun foregroundResumeEnablesBarcodeBeforeFinalRfidRearmAndWaitsForTag() {
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
            connectionStatus = ScannerConnectionStatus.CONNECTED,
            active = true,
            preferred = true,
            scannerId = 7,
        )
        var rfidStatusListener: ((ReaderConnectionStatus) -> Unit)? = null
        var readinessActivityListener: (() -> Unit)? = null
        val recoverySequence = mutableListOf<String>()

        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.doAnswer { invocation ->
            readinessActivityListener = invocation.getArgument(0)
            null
        }.`when`(rfid).managedReadinessActivityListener = anyValue()
        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(listOf(reader))
        Mockito.`when`(barcode.barcodeEndpoints()).thenReturn(listOf(endpoint))
        Mockito.doAnswer { invocation ->
            recoverySequence.add("barcode_connect")
            invocation.getArgument<(Result<Unit>) -> Unit>(1)
                .invoke(Result.success(Unit))
            null
        }.`when`(barcode).connectToScannerForCaptureDevice(
            Mockito.eq(7),
            anyValue(),
        )
        Mockito.doAnswer { invocation ->
            recoverySequence.add("barcode_enable")
            invocation.getArgument<(Result<Unit>) -> Unit>(1)
                .invoke(Result.success(Unit))
            null
        }.`when`(barcode).enableScannerForCaptureDevice(
            Mockito.eq(7),
            anyValue(),
        )
        Mockito.doAnswer { invocation ->
            recoverySequence.add("rfid_reassert")
            invocation.getArgument<(Result<Unit>) -> Unit>(0)
                .invoke(Result.success(Unit))
            null
        }.`when`(rfid).reassertCaptureDeviceTriggerOwnership(anyValue())

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice(
            "capture:rfid:AA:BB:CC:40",
            null,
        ) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)
        recoverySequence.clear()

        coordinator.setCaptureDeviceForeground(false) {}
        coordinator.setCaptureDeviceForeground(true) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        assertEquals(
            listOf(
                "rfid_reassert",
                "barcode_connect",
                "barcode_enable",
                "rfid_reassert",
            ),
            recoverySequence,
        )
        assertEquals(
            CaptureCapabilityStatus.CONNECTING,
            coordinator.activeCaptureDevice()?.rfid?.status,
        )

        readinessActivityListener!!.invoke()

        assertEquals(
            CaptureCapabilityStatus.CONNECTED,
            coordinator.activeCaptureDevice()?.rfid?.status,
        )
    }

    @Test
    fun tc22DataWedgeCompletionPrecedesFinalRfidRearm() {
        val context = Mockito.mock(Context::class.java)
        val rfid = Mockito.mock(RFIDReaderInterface::class.java)
        val barcode = Mockito.mock(BarcodeScannerInterface::class.java)
        val callbacks = Mockito.mock(FlutterZebraCaptureCallbacks::class.java)
        val reader = Reader(
            name = "RFD40 Sled",
            id = 0,
            info = null,
            hardwareIdentity = "USB:RFD40",
        )
        val endpoint = BarcodeScannerEndpoint(
            endpointId = "datawedge:INTERNAL",
            displayName = "TC22 internal imager",
            source = BarcodeScannerSource.BUILT_IN_TERMINAL,
            mode = BarcodeScannerMode.DATA_WEDGE,
            connectionStatus = ScannerConnectionStatus.CONNECTED,
            active = true,
            preferred = true,
            scannerId = null,
        )
        var rfidStatusListener: ((ReaderConnectionStatus) -> Unit)? = null
        var dataWedgeCompletion: ((Result<Unit>) -> Unit)? = null
        var triggerOwnershipCompletion: ((Result<Unit>) -> Unit)? = null
        var connectionResult: Result<Unit>? = null

        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(listOf(reader))
        Mockito.`when`(barcode.barcodeEndpoints()).thenReturn(listOf(endpoint))
        Mockito.doAnswer { invocation ->
            dataWedgeCompletion = invocation.getArgument(1)
            null
        }.`when`(barcode).setActiveEndpointForCaptureDevice(
            Mockito.anyString(),
            anyValue(),
        )
        Mockito.doAnswer { invocation ->
            triggerOwnershipCompletion = invocation.getArgument(0)
            null
        }.`when`(rfid).reassertCaptureDeviceTriggerOwnership(anyValue())

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice(
            "capture:rfid:USB:RFD40",
            null,
        ) { connectionResult = it }
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        assertNotNull(dataWedgeCompletion)
        assertNull(triggerOwnershipCompletion)
        assertNull(connectionResult)

        dataWedgeCompletion!!.invoke(Result.success(Unit))

        assertNotNull(triggerOwnershipCompletion)
        assertNull(connectionResult)
        triggerOwnershipCompletion!!.invoke(Result.success(Unit))

        assertTrue(connectionResult!!.isSuccess)
        assertEquals(
            CaptureCapabilityStatus.CONNECTED,
            coordinator.activeCaptureDevice()?.barcode?.status,
        )
        Mockito.verify(barcode, Mockito.never()).connectToScannerForCaptureDevice(
            Mockito.anyInt(),
            anyValue(),
        )
        Mockito.verify(barcode, Mockito.never()).enableScannerForCaptureDevice(
            Mockito.anyInt(),
            anyValue(),
        )
    }

    @Test
    fun tc22DataWedgeFailureKeepsRfidUsableAndReportsDegraded() {
        val context = Mockito.mock(Context::class.java)
        val rfid = Mockito.mock(RFIDReaderInterface::class.java)
        val barcode = Mockito.mock(BarcodeScannerInterface::class.java)
        val callbacks = Mockito.mock(FlutterZebraCaptureCallbacks::class.java)
        val reader = Reader(
            name = "RFD40 Sled",
            id = 0,
            info = null,
            hardwareIdentity = "USB:RFD40",
        )
        val endpoint = BarcodeScannerEndpoint(
            endpointId = "datawedge:INTERNAL",
            displayName = "TC22 internal imager",
            source = BarcodeScannerSource.BUILT_IN_TERMINAL,
            mode = BarcodeScannerMode.DATA_WEDGE,
            connectionStatus = ScannerConnectionStatus.CONNECTED,
            active = true,
            preferred = true,
            scannerId = null,
        )
        var rfidStatusListener: ((ReaderConnectionStatus) -> Unit)? = null
        var dataWedgeCompletion: ((Result<Unit>) -> Unit)? = null

        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(listOf(reader))
        Mockito.`when`(barcode.barcodeEndpoints()).thenReturn(listOf(endpoint))
        Mockito.doAnswer { invocation ->
            dataWedgeCompletion = invocation.getArgument(1)
            null
        }.`when`(barcode).setActiveEndpointForCaptureDevice(
            Mockito.anyString(),
            anyValue(),
        )
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(Result<Unit>) -> Unit>(0)
                .invoke(Result.success(Unit))
            null
        }.`when`(rfid).reassertCaptureDeviceTriggerOwnership(anyValue())

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice(
            "capture:rfid:USB:RFD40",
            null,
        ) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        dataWedgeCompletion!!.invoke(
            Result.failure(IllegalStateException("DataWedge unavailable")),
        )

        val device = coordinator.activeCaptureDevice()!!
        assertEquals(CaptureCapabilityStatus.CONNECTED, device.rfid?.status)
        assertEquals(CaptureCapabilityStatus.ERROR, device.barcode?.status)
        assertEquals("degraded", device.status.name.lowercase())
        Mockito.verify(rfid, Mockito.never()).disconnectCurrentReader(
            fromCaptureDevice = true,
        )
    }

    @Test
    fun tc22ForegroundResumeWaitsForOneDataWedgeRecovery() {
        val context = Mockito.mock(Context::class.java)
        val rfid = Mockito.mock(RFIDReaderInterface::class.java)
        val barcode = Mockito.mock(BarcodeScannerInterface::class.java)
        val callbacks = Mockito.mock(FlutterZebraCaptureCallbacks::class.java)
        val reader = Reader(
            name = "RFD40 Sled",
            id = 0,
            info = null,
            hardwareIdentity = "USB:RFD40",
        )
        val endpoint = BarcodeScannerEndpoint(
            endpointId = "datawedge:INTERNAL",
            displayName = "TC22 internal imager",
            source = BarcodeScannerSource.BUILT_IN_TERMINAL,
            mode = BarcodeScannerMode.DATA_WEDGE,
            connectionStatus = ScannerConnectionStatus.CONNECTED,
            active = true,
            preferred = true,
            scannerId = null,
        )
        var rfidStatusListener: ((ReaderConnectionStatus) -> Unit)? = null
        val dataWedgeCompletions = mutableListOf<(Result<Unit>) -> Unit>()

        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(listOf(reader))
        Mockito.`when`(barcode.barcodeEndpoints()).thenReturn(listOf(endpoint))
        Mockito.doAnswer { invocation ->
            dataWedgeCompletions.add(invocation.getArgument(1))
            null
        }.`when`(barcode).setActiveEndpointForCaptureDevice(
            Mockito.anyString(),
            anyValue(),
        )
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(Result<Unit>) -> Unit>(0)
                .invoke(Result.success(Unit))
            null
        }.`when`(rfid).reassertCaptureDeviceTriggerOwnership(anyValue())

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice(
            "capture:rfid:USB:RFD40",
            null,
        ) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)
        dataWedgeCompletions.single().invoke(Result.success(Unit))
        Mockito.verify(rfid, Mockito.times(1))
            .reassertCaptureDeviceTriggerOwnership(anyValue())

        coordinator.setCaptureDeviceForeground(false) {}
        coordinator.setCaptureDeviceForeground(true) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        assertEquals(2, dataWedgeCompletions.size)
        Mockito.verify(rfid, Mockito.times(1))
            .reassertCaptureDeviceTriggerOwnership(anyValue())
        dataWedgeCompletions.last().invoke(Result.success(Unit))

        Mockito.verify(rfid, Mockito.times(2))
            .reassertCaptureDeviceTriggerOwnership(anyValue())
        Mockito.verify(rfid, Mockito.times(2)).connectReaderForCaptureDevice(
            0,
            "USB:RFD40",
            true,
        )
        Mockito.verify(barcode, Mockito.never()).connectToScannerForCaptureDevice(
            Mockito.anyInt(),
            anyValue(),
        )
        assertEquals(
            CaptureCapabilityStatus.CONNECTED,
            coordinator.activeCaptureDevice()?.rfid?.status,
        )
        assertEquals(
            CaptureCapabilityStatus.CONNECTED,
            coordinator.activeCaptureDevice()?.barcode?.status,
        )
    }

    @Test
    fun tc22PhysicalDisconnectUsesStandardFirstRetryWithoutScannerSdk() {
        val context = Mockito.mock(Context::class.java)
        val rfid = Mockito.mock(RFIDReaderInterface::class.java)
        val barcode = Mockito.mock(BarcodeScannerInterface::class.java)
        val callbacks = Mockito.mock(FlutterZebraCaptureCallbacks::class.java)
        val reader = Reader(
            name = "RFD40 Sled",
            id = 0,
            info = null,
            hardwareIdentity = "USB:RFD40",
        )
        val endpoint = BarcodeScannerEndpoint(
            endpointId = "datawedge:INTERNAL",
            displayName = "TC22 internal imager",
            source = BarcodeScannerSource.BUILT_IN_TERMINAL,
            mode = BarcodeScannerMode.DATA_WEDGE,
            connectionStatus = ScannerConnectionStatus.CONNECTED,
            active = true,
            preferred = true,
            scannerId = null,
        )
        var rfidStatusListener: ((ReaderConnectionStatus) -> Unit)? = null
        var recoveryRequestListener: ((String) -> Unit)? = null
        var dataWedgeCompletion: ((Result<Unit>) -> Unit)? = null

        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.doAnswer { invocation ->
            recoveryRequestListener = invocation.getArgument(0)
            null
        }.`when`(rfid).managedRecoveryRequestListener = anyValue()
        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(listOf(reader))
        Mockito.`when`(barcode.barcodeEndpoints()).thenReturn(listOf(endpoint))
        Mockito.doAnswer { invocation ->
            dataWedgeCompletion = invocation.getArgument(1)
            null
        }.`when`(barcode).setActiveEndpointForCaptureDevice(
            Mockito.anyString(),
            anyValue(),
        )
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(Result<Unit>) -> Unit>(0)
                .invoke(Result.success(Unit))
            null
        }.`when`(rfid).reassertCaptureDeviceTriggerOwnership(anyValue())

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice(
            "capture:rfid:USB:RFD40",
            null,
        ) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)
        dataWedgeCompletion!!.invoke(Result.success(Unit))

        rfidStatusListener!!.invoke(ReaderConnectionStatus.DISCONNECTED)
        recoveryRequestListener!!.invoke("physical_reader_disconnect")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        Mockito.verify(rfid, Mockito.times(1)).connectReaderForCaptureDevice(
            0,
            "USB:RFD40",
            true,
        )

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        Mockito.verify(rfid, Mockito.times(2)).connectReaderForCaptureDevice(
            0,
            "USB:RFD40",
            true,
        )
        Mockito.verify(barcode, Mockito.never()).connectToScannerForCaptureDevice(
            Mockito.anyInt(),
            anyValue(),
        )
        Mockito.verify(barcode, Mockito.never()).enableScannerForCaptureDevice(
            Mockito.anyInt(),
            anyValue(),
        )
    }

    @Test
    fun recoveredRfidRemainsConnectingUntilPostRecoveryTriggerActivity() {
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
        var readinessActivityListener: (() -> Unit)? = null
        var recoveryRequestListener: ((String) -> Unit)? = null

        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.doAnswer { invocation ->
            readinessActivityListener = invocation.getArgument(0)
            null
        }.`when`(rfid).managedReadinessActivityListener = anyValue()
        Mockito.doAnswer { invocation ->
            recoveryRequestListener = invocation.getArgument(0)
            null
        }.`when`(rfid).managedRecoveryRequestListener = anyValue()
        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(listOf(reader))
        Mockito.`when`(barcode.barcodeEndpoints()).thenReturn(emptyList())

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice(
            "capture:rfid:AA:BB:CC:40",
            null,
        ) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        assertTrue(
            coordinator.activeCaptureDevice()!!.rfid!!.status ==
                CaptureCapabilityStatus.CONNECTED,
        )

        rfidStatusListener!!.invoke(ReaderConnectionStatus.DISCONNECTED)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        Mockito.verify(rfid, Mockito.times(1)).connectReaderForCaptureDevice(
            0,
            "AA:BB:CC:40",
            false,
        )
        recoveryRequestListener!!.invoke("physical_reader_disconnect")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        assertTrue(
            coordinator.activeCaptureDevice()!!.rfid!!.status ==
                CaptureCapabilityStatus.CONNECTING,
        )

        readinessActivityListener!!.invoke()

        assertTrue(
            coordinator.activeCaptureDevice()!!.rfid!!.status ==
                CaptureCapabilityStatus.CONNECTED,
        )
    }

    @Test
    fun recoveredRfidCanBeConfirmedWhenBarcodeRestorationFails() {
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
        var barcodeStatusListener: ((ScannerConnectionStatus) -> Unit)? = null
        var readinessActivityListener: (() -> Unit)? = null
        var recoveryRequestListener: ((String) -> Unit)? = null
        var barcodeCompletion: ((Result<Unit>) -> Unit)? = null

        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.doAnswer { invocation ->
            barcodeStatusListener = invocation.getArgument(0)
            null
        }.`when`(barcode).connectionStatusListener = anyValue()
        Mockito.doAnswer { invocation ->
            readinessActivityListener = invocation.getArgument(0)
            null
        }.`when`(rfid).managedReadinessActivityListener = anyValue()
        Mockito.doAnswer { invocation ->
            recoveryRequestListener = invocation.getArgument(0)
            null
        }.`when`(rfid).managedRecoveryRequestListener = anyValue()
        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(listOf(reader))
        Mockito.`when`(barcode.barcodeEndpoints()).thenReturn(listOf(endpoint))
        Mockito.doAnswer { invocation ->
            barcodeCompletion = invocation.getArgument(1)
            null
        }.`when`(barcode).connectToScannerForCaptureDevice(
            Mockito.eq(7),
            anyValue(),
        )
        Mockito.doAnswer { invocation ->
            val completion = invocation.getArgument<(Result<Unit>) -> Unit>(0)
            completion(Result.success(Unit))
            null
        }.`when`(rfid).reassertCaptureDeviceTriggerOwnership(anyValue())

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice(
            "capture:rfid:AA:BB:CC:40",
            null,
        ) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)
        barcodeCompletion!!.invoke(Result.success(Unit))
        val initialBarcodeCompletion = barcodeCompletion

        rfidStatusListener!!.invoke(ReaderConnectionStatus.DISCONNECTED)
        barcodeStatusListener!!.invoke(ScannerConnectionStatus.DISCONNECTED)
        recoveryRequestListener!!.invoke("physical_reader_disconnect")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        val recoveredBarcodeCompletion = barcodeCompletion
        assertTrue(
            coordinator.activeCaptureDevice()!!.rfid!!.status ==
                CaptureCapabilityStatus.CONNECTING,
        )
        assertTrue(recoveredBarcodeCompletion != null)
        assertTrue(recoveredBarcodeCompletion !== initialBarcodeCompletion)
        recoveredBarcodeCompletion!!.invoke(
            Result.failure(IllegalStateException("barcode unavailable")),
        )
        readinessActivityListener!!.invoke()

        val device = coordinator.activeCaptureDevice()!!
        assertTrue(device.rfid!!.status == CaptureCapabilityStatus.CONNECTED)
        assertTrue(device.barcode!!.status == CaptureCapabilityStatus.ERROR)
    }

    @Test
    fun recoveredComboDeviceRestoresBarcodeBeforeWaitingForRealRfidActivity() {
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
        var readinessActivityListener: (() -> Unit)? = null
        var recoveryRequestListener: ((String) -> Unit)? = null
        val barcodeCompletions =
            mutableListOf<(Result<Unit>) -> Unit>()

        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.doAnswer { invocation ->
            readinessActivityListener = invocation.getArgument(0)
            null
        }.`when`(rfid).managedReadinessActivityListener = anyValue()
        Mockito.doAnswer { invocation ->
            recoveryRequestListener = invocation.getArgument(0)
            null
        }.`when`(rfid).managedRecoveryRequestListener = anyValue()
        Mockito.`when`(rfid.availableReadersSnapshot()).thenReturn(listOf(reader))
        Mockito.`when`(barcode.barcodeEndpoints()).thenReturn(listOf(endpoint))
        Mockito.doAnswer { invocation ->
            barcodeCompletions.add(invocation.getArgument(1))
            null
        }.`when`(barcode).connectToScannerForCaptureDevice(
            Mockito.eq(7),
            anyValue(),
        )
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(Result<Unit>) -> Unit>(0)
                .invoke(Result.success(Unit))
            null
        }.`when`(rfid).reassertCaptureDeviceTriggerOwnership(anyValue())
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(Result<Unit>) -> Unit>(1)
                .invoke(Result.success(Unit))
            null
        }.`when`(barcode).enableScannerForCaptureDevice(
            Mockito.eq(7),
            anyValue(),
        )

        val coordinator = CaptureDeviceCoordinator(context, rfid, barcode, callbacks)
        coordinator.connectCaptureDevice(
            "capture:rfid:AA:BB:CC:40",
            null,
        ) {}
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)
        barcodeCompletions.single().invoke(Result.success(Unit))

        rfidStatusListener!!.invoke(ReaderConnectionStatus.DISCONNECTED)
        recoveryRequestListener!!.invoke("physical_reader_disconnect")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        Mockito.verify(rfid, Mockito.times(1)).connectReaderForCaptureDevice(
            0,
            "AA:BB:CC:40",
            true,
        )
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        Mockito.verify(rfid, Mockito.times(2)).connectReaderForCaptureDevice(
            0,
            "AA:BB:CC:40",
            true,
        )
        rfidStatusListener!!.invoke(ReaderConnectionStatus.CONNECTED)

        assertTrue(barcodeCompletions.size == 2)
        barcodeCompletions.last().invoke(Result.success(Unit))
        assertTrue(
            coordinator.activeCaptureDevice()!!.rfid!!.status ==
                CaptureCapabilityStatus.CONNECTING,
        )
        assertTrue(
            coordinator.activeCaptureDevice()!!.barcode!!.status ==
                CaptureCapabilityStatus.CONNECTED,
        )

        readinessActivityListener!!.invoke()

        assertTrue(barcodeCompletions.size == 2)
        assertTrue(
            coordinator.activeCaptureDevice()!!.rfid!!.status ==
                CaptureCapabilityStatus.CONNECTED,
        )
        Mockito.verify(rfid, Mockito.times(3))
            .reassertCaptureDeviceTriggerOwnership(anyValue())
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
        var recoveryRequestListener: ((String) -> Unit)? = null

        Mockito.doAnswer { invocation ->
            rfidStatusListener = invocation.getArgument(0)
            null
        }.`when`(rfid).connectionStatusListener = anyValue()
        Mockito.doAnswer { invocation ->
            recoveryRequestListener = invocation.getArgument(0)
            null
        }.`when`(rfid).managedRecoveryRequestListener = anyValue()
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
        recoveryRequestListener!!.invoke("physical_reader_disconnect")
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
