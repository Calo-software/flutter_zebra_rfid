package nz.calo.flutter_zebra_rfid.barcode

import Barcode
import BarcodeScanner
import BarcodeScannerEndpoint
import BarcodeScannerMode
import BarcodeScannerSource
import FlutterZebraBarcodeCallbacks
import ScannerConnectionStatus
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.zebra.scannercontrol.DCSSDKDefs
import com.zebra.scannercontrol.DCSScannerInfo
import com.zebra.scannercontrol.FirmwareUpdateEvent
import com.zebra.scannercontrol.IDcsSdkApiDelegate
import com.zebra.scannercontrol.SDKHandler
import java.nio.charset.Charset
import java.util.Collections
import nz.calo.flutter_zebra_rfid.capture.CaptureDiagnosticSink
import nz.calo.flutter_zebra_rfid.capture.record

internal fun stableScannerSdkEndpointId(
    scannerId: Int,
    hardwareIdentity: String?,
    scannerName: String?,
): String {
    val normalizedHardwareIdentity = hardwareIdentity
        ?.trim()
        ?.uppercase()
        ?.replace(Regex("[^A-Z0-9]+"), "-")
        ?.trim('-')
        ?.takeIf { it.isNotEmpty() }
    if (normalizedHardwareIdentity != null) {
        return "scanner-sdk:hardware:$normalizedHardwareIdentity"
    }

    val normalizedName = scannerName
        ?.trim()
        ?.uppercase()
        ?.replace(Regex("[^A-Z0-9]+"), "-")
        ?.trim('-')
        ?.takeIf { it.isNotEmpty() }
    if (normalizedName != null) {
        return "scanner-sdk:name:$normalizedName"
    }

    // The SDK index is only a last-resort runtime identity. The migration
    // below deliberately prevents this form from being restored on restart.
    return "scanner-sdk:runtime:$scannerId"
}

internal fun migratePreferredEndpointId(endpointId: String?): String? {
    if (endpointId == null) return null
    if (Regex("^scanner-sdk:\\d+$").matches(endpointId)) return null
    if (Regex("^scanner-sdk:runtime:\\d+$").matches(endpointId)) return null
    return endpointId
}

internal fun shouldInitializeScannerSdk(
    manufacturer: String,
    model: String,
    product: String,
    device: String,
): Boolean {
    val values = listOf(manufacturer, model, product, device).map { it.uppercase() }
    val isZebra = values.first().contains("ZEBRA") ||
        values.first().contains("MOTOROLA")
    val isTcSeries = values.drop(1).any {
        it.startsWith("TC") || it.contains("TC22") || it.contains("TC27")
    }
    return !(isZebra || isTcSeries)
}

/**
 * Coordinates barcode scanners exposed through Zebra Scanner Control SDK and
 * Android DataWedge. DataWedge is needed for built-in Zebra terminal scanners;
 * Scanner Control is still used for external scanners such as sled scanners.
 */
class BarcodeScannerInterface internal constructor(
    private val callbacks: FlutterZebraBarcodeCallbacks,
    private val sessionRunner: ScannerSdkSessionRunner = ScannerSdkSessionRunner(
        postToMain = { operation -> Handler(Looper.getMainLooper()).post(operation) },
    ),
    private val diagnostics: CaptureDiagnosticSink = CaptureDiagnosticSink.NONE,
) : IDcsSdkApiDelegate {
    private val tag = "FlutterZebraBarcode"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val availableScannerList: MutableList<DCSScannerInfo> =
        Collections.synchronizedList(ArrayList())

    private var sdkHandler: SDKHandler? = null
    @Volatile
    private var currentScanner: DCSScannerInfo? = null
    private var isInitialized = false
    private var applicationContext: Context? = null
    private var dataWedgeReceiverRegistered = false
    private var dataWedgeCoordinator: DataWedgeCommandCoordinator? = null
    private var dataWedgeEndpoints: List<DataWedgeScanner> = emptyList()
    private var dataWedgeScannerStatus: String? = null
    private var initialDataWedgeSetupComplete = false
    private var activeEndpointId: String? = null
    private var preferredEndpointId: String? = null
    private var captureDeviceOwnsConnection = false
    var endpointsChangedListener: (() -> Unit)? = null
    var connectionStatusListener: ((ScannerConnectionStatus) -> Unit)? = null

    fun updateAvailableScanners(
        context: Context,
        onComplete: (Result<Unit>) -> Unit = {},
    ) {
        refreshBarcodeScanners(context, onComplete)
    }

    fun refreshBarcodeScanners(context: Context) {
        refreshBarcodeScanners(context) { result ->
            result.exceptionOrNull()?.let {
                Log.e(tag, "Barcode scanner refresh failed", it)
            }
        }
    }

    fun refreshBarcodeScanners(
        context: Context,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        diagnostics.record("barcode", "refresh", "started")
        try {
            if (!isInitialized) {
                initialize(context.applicationContext)
            } else {
                ensureScannerSdkInitialized(context.applicationContext)
            }
            getAvailableScannerList()
            emitEndpoints()
            enqueueDataWedgeHealthCheck(onComplete)
        } catch (error: Throwable) {
            diagnostics.record(
                "barcode",
                "refresh",
                "failed",
                mapOf("error_type" to error::class.java.simpleName),
            )
            onComplete(Result.failure(error))
        }
    }

    fun claimCaptureDeviceOwnership() {
        captureDeviceOwnsConnection = true
    }

    fun releaseCaptureDeviceOwnership() {
        captureDeviceOwnsConnection = false
    }

    fun connectToScanner(
        scannerId: Int,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        if (captureDeviceOwnsConnection) {
            onComplete(
                Result.failure(
                    IllegalStateException("CAPTURE_DEVICE_OWNS_CONNECTION"),
                ),
            )
            return
        }
        connectToScannerInternal(scannerId, onComplete)
    }

    fun connectToScannerForCaptureDevice(
        scannerId: Int,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        claimCaptureDeviceOwnership()
        connectToScannerInternal(scannerId, onComplete)
    }

    fun enableScannerForCaptureDevice(
        scannerId: Int,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        claimCaptureDeviceOwnership()
        val scanner = currentScanner?.takeIf { it.scannerID == scannerId }
        if (scanner == null) {
            onComplete(
                Result.failure(
                    IllegalStateException("Scanner session is not connected"),
                ),
            )
            return
        }
        val handler = sdkHandler
        if (handler == null) {
            onComplete(
                Result.failure(
                    IllegalStateException("Barcode Scanner SDK is not initialized"),
                ),
            )
            return
        }
        sessionRunner.run(
            operation = ScannerSdkSessionOperation.ENABLE,
            scannerId = scannerId,
            sdkCall = {
                val inXml = "<inArgs><scannerID>$scannerId</scannerID></inArgs>"
                handler.dcssdkExecuteCommandOpCodeInXMLForScanner(
                    DCSSDKDefs.DCSSDK_COMMAND_OPCODE.DCSSDK_DEVICE_SCAN_ENABLE,
                    inXml,
                    StringBuilder(),
                    scannerId,
                )
            },
        ) { operationResult ->
            operationResult.fold(
                onSuccess = { result ->
                    if (result == DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS) {
                        Log.i(tag, "Scanner SDK barcode scanning re-enabled: $scannerId")
                        onComplete(Result.success(Unit))
                    } else {
                        onComplete(
                            Result.failure(
                                IllegalStateException(
                                    "Failed to enable barcode scanning for scanner " +
                                        "$scannerId: $result",
                                ),
                            ),
                        )
                    }
                },
                onFailure = { error -> onComplete(Result.failure(error)) },
            )
        }
    }

    private fun connectToScannerInternal(
        scannerId: Int,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val scanner = synchronized(availableScannerList) {
            availableScannerList.firstOrNull { it.scannerID == scannerId }
        } ?: currentScanner?.takeIf { it.scannerID == scannerId }
        if (scanner == null) {
            onComplete(Result.failure(IllegalStateException("Scanner not available")))
            return
        }

        val handler = sdkHandler
        if (handler == null) {
            onComplete(
                Result.failure(
                    IllegalStateException("Barcode Scanner SDK is not initialized"),
                ),
            )
            return
        }
        val expectedEndpointId = scannerSdkEndpointId(scanner)
        callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.CONNECTING) {}
        connectionStatusListener?.invoke(ScannerConnectionStatus.CONNECTING)
        diagnostics.record(
            "scanner_sdk",
            "establish_session",
            "started",
            mapOf(
                "scanner_id" to scanner.scannerID,
                "barcode_model" to scanner.scannerModel,
                "barcode_serial" to scanner.scannerHWSerialNumber,
            ),
        )
        sessionRunner.run(
            operation = ScannerSdkSessionOperation.ESTABLISH,
            scannerId = scanner.scannerID,
            sdkCall = {
                rearmScannerSdkEventDelivery(handler)
                handler.dcssdkEstablishCommunicationSession(scanner.scannerID)
            },
        ) { operationResult ->
            operationResult.fold(
                onSuccess = { result ->
                    diagnostics.record(
                        "scanner_sdk",
                        "establish_session",
                        if (result == DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS) {
                            "completed"
                        } else {
                            "sdk_result"
                        },
                        mapOf("scanner_id" to scanner.scannerID, "result" to result.name),
                    )
                    if (
                        result != DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS &&
                        currentScanner?.scannerID != scanner.scannerID
                    ) {
                        callbacks.onScannerConnectionStatusChanged(
                            ScannerConnectionStatus.DISCONNECTED,
                        ) {}
                        connectionStatusListener?.invoke(ScannerConnectionStatus.DISCONNECTED)
                        onComplete(
                            Result.failure(
                                IllegalStateException(
                                    "Failed to connect to scanner ${scanner.scannerName}: $result",
                                ),
                            ),
                        )
                    } else if (
                        activeEndpointId != null &&
                        activeEndpointId != expectedEndpointId
                    ) {
                        onComplete(
                            Result.failure(
                                IllegalStateException(
                                    "Scanner endpoint changed while connection was in progress",
                                ),
                            ),
                        )
                    } else {
                        setActiveEndpointInternal(expectedEndpointId)
                        if (
                            result != DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS &&
                            currentScanner?.scannerID == scanner.scannerID
                        ) {
                            // Scanner SDK can retain a session while its
                            // barcode callback thread stops delivering after
                            // foreground recovery. Rebinding the delegate and
                            // subscriptions above is the readiness operation;
                            // an already-established session may reject the
                            // duplicate establish call even though it remains
                            // the selected live session.
                            callbacks.onScannerConnectionStatusChanged(
                                ScannerConnectionStatus.CONNECTED,
                            ) {}
                            connectionStatusListener?.invoke(
                                ScannerConnectionStatus.CONNECTED,
                            )
                        }
                        onComplete(Result.success(Unit))
                    }
                },
                onFailure = { error ->
                    diagnostics.record(
                        "scanner_sdk",
                        "establish_session",
                        "failed",
                        mapOf(
                            "scanner_id" to scanner.scannerID,
                            "error_type" to error::class.java.simpleName,
                        ),
                    )
                    callbacks.onScannerConnectionStatusChanged(
                        ScannerConnectionStatus.DISCONNECTED,
                    ) {}
                    connectionStatusListener?.invoke(ScannerConnectionStatus.DISCONNECTED)
                    onComplete(Result.failure(error))
                },
            )
        }
    }

    fun disconnectCurrentScanner(onComplete: (Result<Unit>) -> Unit) {
        if (captureDeviceOwnsConnection) {
            onComplete(
                Result.failure(
                    IllegalStateException("CAPTURE_DEVICE_OWNS_CONNECTION"),
                ),
            )
            return
        }
        disconnectCurrentScannerInternal(onComplete)
    }

    fun disconnectCurrentScannerForCaptureDevice(
        onComplete: (Result<Unit>) -> Unit,
    ) {
        disconnectCurrentScannerInternal { result ->
            releaseCaptureDeviceOwnership()
            onComplete(result)
        }
    }

    private fun disconnectCurrentScannerInternal(
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val scanner = currentScanner
        if (scanner == null) {
            onComplete(Result.success(Unit))
            return
        }
        val handler = sdkHandler
        if (handler == null) {
            onComplete(
                Result.failure(
                    IllegalStateException("Barcode Scanner SDK is not initialized"),
                ),
            )
            return
        }
        callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.DISCONNECTING) {}
        connectionStatusListener?.invoke(ScannerConnectionStatus.DISCONNECTING)
        diagnostics.record(
            "scanner_sdk",
            "terminate_session",
            "started",
            mapOf("scanner_id" to scanner.scannerID),
        )
        sessionRunner.run(
            operation = ScannerSdkSessionOperation.TERMINATE,
            scannerId = scanner.scannerID,
            sdkCall = {
                handler.dcssdkTerminateCommunicationSession(scanner.scannerID)
            },
        ) { operationResult ->
            operationResult.fold(
                onSuccess = { result ->
                    diagnostics.record(
                        "scanner_sdk",
                        "terminate_session",
                        if (result == DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS) {
                            "completed"
                        } else {
                            "sdk_result"
                        },
                        mapOf("scanner_id" to scanner.scannerID, "result" to result.name),
                    )
                    if (result != DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS) {
                        callbacks.onScannerConnectionStatusChanged(
                            ScannerConnectionStatus.ERROR,
                        ) {}
                        connectionStatusListener?.invoke(ScannerConnectionStatus.ERROR)
                        onComplete(
                            Result.failure(
                                IllegalStateException(
                                    "Failed to disconnect from current scanner: $result",
                                ),
                            ),
                        )
                    } else {
                        onComplete(Result.success(Unit))
                    }
                },
                onFailure = { error ->
                    diagnostics.record(
                        "scanner_sdk",
                        "terminate_session",
                        "failed",
                        mapOf(
                            "scanner_id" to scanner.scannerID,
                            "error_type" to error::class.java.simpleName,
                        ),
                    )
                    callbacks.onScannerConnectionStatusChanged(
                        ScannerConnectionStatus.ERROR,
                    ) {}
                    connectionStatusListener?.invoke(ScannerConnectionStatus.ERROR)
                    onComplete(Result.failure(error))
                },
            )
        }
    }

    fun setActiveEndpoint(endpointId: String) {
        if (captureDeviceOwnsConnection) {
            throw IllegalStateException("CAPTURE_DEVICE_OWNS_CONNECTION")
        }
        setActiveEndpointInternal(endpointId)
    }

    fun setActiveEndpointForCaptureDevice(endpointId: String) {
        claimCaptureDeviceOwnership()
        setActiveEndpointInternal(endpointId)
    }

    private fun setActiveEndpointInternal(endpointId: String) {
        val endpoint = activateEndpoint(endpointId)
        if (endpoint.mode == BarcodeScannerMode.DATA_WEDGE) {
            recoverDataWedgeEndpoint(endpoint) { result ->
                result.exceptionOrNull()?.let {
                    Log.e(tag, "DataWedge endpoint activation failed", it)
                }
            }
        }
    }

    private fun rearmScannerSdkEventDelivery(handler: SDKHandler) {
        val delegateResult = handler.dcssdkSetDelegate(this)
        check(delegateResult == DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS) {
            "Failed to restore Scanner SDK event delegate: $delegateResult"
        }
        val subscriptionResult = handler.dcssdkSubsribeForEvents(scannerSdkNotificationsMask())
        check(subscriptionResult == DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS) {
            "Failed to restore Scanner SDK event subscriptions: $subscriptionResult"
        }
    }

    fun setActiveEndpoint(
        endpointId: String,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        if (captureDeviceOwnsConnection) {
            onComplete(
                Result.failure(
                    IllegalStateException("CAPTURE_DEVICE_OWNS_CONNECTION"),
                ),
            )
            return
        }
        setActiveEndpointInternal(endpointId, onComplete)
    }

    fun setActiveEndpointForCaptureDevice(
        endpointId: String,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        claimCaptureDeviceOwnership()
        setActiveEndpointInternal(endpointId, onComplete)
    }

    private fun setActiveEndpointInternal(
        endpointId: String,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        try {
            val endpoint = activateEndpoint(endpointId)
            if (endpoint.mode == BarcodeScannerMode.DATA_WEDGE) {
                recoverDataWedgeEndpoint(endpoint, onComplete)
            } else {
                onComplete(Result.success(Unit))
            }
        } catch (error: Throwable) {
            onComplete(Result.failure(error))
        }
    }

    private fun activateEndpoint(endpointId: String): BarcodeScannerEndpoint {
        val endpoint = currentEndpoints(includeActive = false)
            .firstOrNull { it.endpointId == endpointId }
            ?: throw Error("Barcode scanner endpoint not available: $endpointId")
        activeEndpointId = endpointId
        preferredEndpointId = endpointId
        savePreferredEndpoint(endpointId)
        emitEndpoints()
        return endpoint
    }

    fun clearActiveEndpoint() {
        activeEndpointId = null
        preferredEndpointId = null
        applicationContext
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(PREF_ACTIVE_ENDPOINT)
            ?.apply()
        emitEndpoints()
    }

    fun activeEndpoint(): BarcodeScannerEndpoint? =
        currentEndpoints().firstOrNull { it.endpointId == activeEndpointId }

    fun currentScanner(): BarcodeScanner? {
        val scanner = currentScanner ?: return null
        return BarcodeScanner(
            scanner.scannerName,
            scanner.scannerID.toLong(),
            scanner.scannerModel,
            scanner.scannerHWSerialNumber,
        )
    }

    fun onDestroy() {
        try {
            if (dataWedgeReceiverRegistered) {
                applicationContext?.unregisterReceiver(dataWedgeReceiver)
                dataWedgeReceiverRegistered = false
            }
            dataWedgeCoordinator?.cancel()
            dataWedgeCoordinator = null
            sessionRunner.dispose()
            sdkHandler = null
        } catch (e: Exception) {
            Log.w(tag, "Barcode dispose failed", e)
        }
    }

    override fun dcssdkEventScannerAppeared(scanner: DCSScannerInfo?) {
        Log.d(tag, "Scanner ${scanner?.scannerName} appeared")
        getAvailableScannerList()
        emitEndpoints()
    }

    override fun dcssdkEventScannerDisappeared(scannerIndex: Int) {
        Log.d(tag, "Scanner disappeared: $scannerIndex")
        getAvailableScannerList()
        if (currentScanner?.scannerID == scannerIndex) {
            currentScanner = null
        }
        emitEndpoints()
    }

    override fun dcssdkEventCommunicationSessionEstablished(scanner: DCSScannerInfo?) {
        Log.d(tag, "Scanner connected: ${scanner?.scannerName}")
        diagnostics.record(
            "scanner_sdk",
            "communication_session",
            "established",
            mapOf(
                "scanner_id" to scanner?.scannerID,
                "barcode_model" to scanner?.scannerModel,
                "barcode_serial" to scanner?.scannerHWSerialNumber,
            ),
        )
        currentScanner = scanner
        scanner?.let { activeEndpointId = scannerSdkEndpointId(it) }
        // Zebra starts its barcode-delivery thread only after this delegate
        // callback returns. Keep Flutter/channel work off the SDK callback so a
        // slow or failed client notification cannot leave decoded barcodes
        // queued without a sender thread.
        mainHandler.post {
            callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.CONNECTED) {}
            connectionStatusListener?.invoke(ScannerConnectionStatus.CONNECTED)
            emitEndpoints()
        }
    }

    override fun dcssdkEventCommunicationSessionTerminated(scannerId: Int) {
        Log.d(tag, "Scanner disconnected: $scannerId")
        diagnostics.record(
            "scanner_sdk",
            "communication_session",
            "terminated",
            mapOf("scanner_id" to scannerId),
        )
        if (currentScanner?.scannerID == scannerId) {
            currentScanner = null
        }
        mainHandler.post {
            callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.DISCONNECTED) {}
            connectionStatusListener?.invoke(ScannerConnectionStatus.DISCONNECTED)
            emitEndpoints()
        }
    }

    override fun dcssdkEventBarcode(barcodeData: ByteArray?, barcodeType: Int, scannerId: Int) {
        val data = barcodeData?.toString(Charset.defaultCharset()) ?: return
        diagnostics.record(
            "barcode",
            "decoded",
            "received",
            mapOf("scanner_id" to scannerId, "barcode_type" to barcodeType),
        )
        val endpoint = currentEndpoints().firstOrNull { it.scannerId == scannerId.toLong() }
        emitBarcode(
            Barcode(
                data,
                scannerId.toLong(),
                barcodeType.toLong(),
                endpoint?.endpointId,
                endpoint?.source ?: inferSdkSource(currentScanner),
                endpoint?.displayName ?: currentScanner?.scannerName,
            )
        )
    }

    override fun dcssdkEventImage(p0: ByteArray?, p1: Int) {}
    override fun dcssdkEventVideo(p0: ByteArray?, p1: Int) {}
    override fun dcssdkEventBinaryData(p0: ByteArray?, p1: Int) {}
    override fun dcssdkEventFirmwareUpdate(p0: FirmwareUpdateEvent?) {}
    override fun dcssdkEventAuxScannerAppeared(p0: DCSScannerInfo?, p1: DCSScannerInfo?) {}

    private fun initialize(context: Context) {
        applicationContext = context
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedPreferredEndpointId = preferences.getString(PREF_ACTIVE_ENDPOINT, null)
        preferredEndpointId = migratePreferredEndpointId(storedPreferredEndpointId)
        if (storedPreferredEndpointId != null && preferredEndpointId == null) {
            // Scanner SDK list indexes are assigned dynamically and can refer
            // to a different paired device after a process restart. Never
            // restore the legacy numeric endpoint as a hardware preference.
            preferences.edit().remove(PREF_ACTIVE_ENDPOINT).apply()
            Log.i(tag, "Cleared legacy Scanner SDK endpoint preference")
        }
        dataWedgeCoordinator = DataWedgeCommandCoordinator(
            sendIntent = { context.sendOrderedBroadcast(it, null) },
            scheduleTimeout = { runnable, delay -> mainHandler.postDelayed(runnable, delay) },
            cancelTimeout = { runnable -> mainHandler.removeCallbacks(runnable) },
            diagnostics = diagnostics,
        )
        registerDataWedgeReceiver(context)
        ensureScannerSdkInitialized(context)
        isInitialized = true
        enqueueDataWedgeStartup()
    }

    private fun ensureScannerSdkInitialized(context: Context) {
        if (sdkHandler != null) return
        if (
            !shouldInitializeScannerSdk(
                Build.MANUFACTURER.orEmpty(),
                Build.MODEL.orEmpty(),
                Build.PRODUCT.orEmpty(),
                Build.DEVICE.orEmpty(),
            )
        ) {
            diagnostics.record(
                "scanner_sdk",
                "initialization",
                "suppressed_for_terminal_datawedge",
                mapOf("terminal_model" to Build.MODEL.orEmpty()),
            )
            synchronized(availableScannerList) {
                availableScannerList.clear()
                callbacks.onAvailableScannersChanged(emptyList()) {}
            }
            return
        }
        if (!hasScannerSdkBluetoothPermission(context)) {
            Log.w(
                tag,
                "Skipping Zebra Scanner SDK Bluetooth initialization because Bluetooth permission is missing; DataWedge endpoints remain available",
            )
            synchronized(availableScannerList) {
                availableScannerList.clear()
                callbacks.onAvailableScannersChanged(emptyList()) {}
            }
            return
        }

        try {
            sdkHandler = SDKHandler(context).also { handler ->
                handler.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_BT_NORMAL)
                handler.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_USB_CDC)
                rearmScannerSdkEventDelivery(handler)
                handler.dcssdkEnableAvailableScannersDetection(true)
            }
        } catch (e: SecurityException) {
            Log.w(
                tag,
                "Zebra Scanner SDK initialization blocked by Bluetooth permission; DataWedge endpoints remain available",
                e,
            )
            sdkHandler = null
        }
    }

    private fun hasScannerSdkBluetoothPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun scannerSdkNotificationsMask(): Int =
        DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_APPEARANCE.value or
            DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_DISAPPEARANCE.value or
            DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_ESTABLISHMENT.value or
            DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_TERMINATION.value or
            DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_BARCODE.value

    private fun getAvailableScannerList() {
        val handler = sdkHandler ?: return
        try {
            synchronized(availableScannerList) {
                availableScannerList.clear()
                handler.dcssdkGetAvailableScannersList(availableScannerList)
                callbacks.onAvailableScannersChanged(availableScannerList.map {
                    BarcodeScanner(
                        it.scannerName,
                        it.scannerID.toLong(),
                        it.scannerModel,
                        it.scannerHWSerialNumber
                    )
                }) {}
            }
        } catch (e: SecurityException) {
            Log.w(tag, "Scanner SDK list unavailable because Bluetooth permission is missing", e)
        }
    }

    private fun currentEndpoints(includeActive: Boolean = true): List<BarcodeScannerEndpoint> {
        val preferred = preferredEndpointId
        val endpoints = ArrayList<BarcodeScannerEndpoint>()
        fun addScannerSdkEndpoint(scanner: DCSScannerInfo) {
            val endpointId = scannerSdkEndpointId(scanner)
            endpoints.add(
                BarcodeScannerEndpoint(
                    endpointId,
                    scanner.scannerName ?: "Scanner ${scanner.scannerID}",
                    inferSdkSource(scanner),
                    BarcodeScannerMode.SCANNER_SDK,
                    if (currentScanner?.scannerID == scanner.scannerID) {
                        ScannerConnectionStatus.CONNECTED
                    } else {
                        ScannerConnectionStatus.DISCONNECTED
                    },
                    includeActive && endpointId == activeEndpointId,
                    endpointId == preferred,
                    null,
                    null,
                    scanner.scannerID.toLong(),
                    scanner.scannerModel,
                    scanner.scannerHWSerialNumber,
                )
            )
        }
        synchronized(availableScannerList) {
            availableScannerList.forEach(::addScannerSdkEndpoint)
            currentScanner?.takeIf { connected ->
                availableScannerList.none { it.scannerID == connected.scannerID }
            }?.let { connected ->
                // Discovery can briefly omit an already-established RFD
                // Scanner SDK endpoint after foregrounding. Retain that exact
                // session identity so the coordinator can re-arm it.
                addScannerSdkEndpoint(connected)
            }
        }
        dataWedgeEndpoints.forEach { scanner ->
            val endpointId = dataWedgeEndpointId(scanner.identifier, scanner.index)
            endpoints.add(
                BarcodeScannerEndpoint(
                    endpointId,
                    scanner.name,
                    scanner.source,
                    BarcodeScannerMode.DATA_WEDGE,
                    scanner.status,
                    includeActive && endpointId == activeEndpointId,
                    endpointId == preferred,
                    scanner.identifier,
                    scanner.index?.toLong(),
                    null,
                    null,
                    null,
                )
            )
        }
        return endpoints.distinctBy { it.endpointId }
    }

    private fun emitEndpoints() {
        val endpoints = currentEndpoints(includeActive = false)
        if (activeEndpointId == null) {
            activeEndpointId = when {
                preferredEndpointId != null && endpoints.any { it.endpointId == preferredEndpointId } ->
                    preferredEndpointId
                endpoints.size == 1 -> endpoints.first().endpointId
                else -> null
            }
        }

        val active = activeEndpointId
        val resolved = endpoints.map {
            it.copy(active = it.endpointId == active)
        }
        callbacks.onAvailableBarcodeScannersChanged(resolved) {}
        callbacks.onActiveBarcodeScannerChanged(resolved.firstOrNull { it.active }) {}
        endpointsChangedListener?.invoke()
    }

    fun barcodeEndpoints(): List<BarcodeScannerEndpoint> = currentEndpoints()

    private fun emitBarcode(barcode: Barcode) {
        mainHandler.post {
            callbacks.onBarcodeRead(barcode) {}
        }
    }

    private fun savePreferredEndpoint(endpointId: String) {
        applicationContext
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(PREF_ACTIVE_ENDPOINT, endpointId)
            ?.apply()
    }

    private fun registerDataWedgeReceiver(context: Context) {
        if (dataWedgeReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_RESULT)
            addAction(ACTION_RESULT_NOTIFICATION)
            addAction(ACTION_BARCODE)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(dataWedgeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(dataWedgeReceiver, filter)
        }
        dataWedgeReceiverRegistered = true
    }

    private val dataWedgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_BARCODE -> handleDataWedgeBarcode(intent)
                ACTION_RESULT -> {
                    handleDataWedgeResult(intent)
                    dataWedgeCoordinator?.handleResult(intent)
                }
                ACTION_RESULT_NOTIFICATION -> handleDataWedgeNotification(intent)
            }
        }
    }

    private fun handleDataWedgeResult(intent: Intent) {
        if (intent.hasExtra(EXTRA_RESULT_ENUMERATE_SCANNERS)) {
            dataWedgeEndpoints = parseDataWedgeScanners(intent)
            diagnostics.record(
                "datawedge",
                "enumerate_scanners",
                "received",
                mapOf(
                    "endpoint_count" to dataWedgeEndpoints.size,
                    "endpoints" to dataWedgeEndpoints.joinToString(",") {
                        "${it.identifier}:${it.name}"
                    },
                ),
            )
            emitEndpoints()
        }
        if (intent.hasExtra(EXTRA_RESULT_SCANNER_STATUS)) {
            dataWedgeScannerStatus = intent.getStringExtra(EXTRA_RESULT_SCANNER_STATUS)
            diagnostics.record(
                "datawedge",
                "scanner_status",
                "received",
                mapOf("status" to dataWedgeScannerStatus),
            )
            emitEndpoints()
        }
    }

    private fun handleDataWedgeNotification(intent: Intent) {
        val extras = intent.getBundleExtra(EXTRA_RESULT_NOTIFICATION) ?: return
        if (extras.getString(EXTRA_RESULT_NOTIFICATION_TYPE) == NOTIFICATION_SCANNER_STATUS) {
            dataWedgeScannerStatus = extras.getString(NOTIFICATION_SCANNER_STATUS)
            diagnostics.record(
                "datawedge",
                "scanner_status_notification",
                "received",
                mapOf("status" to dataWedgeScannerStatus),
            )
            emitEndpoints()
        }
    }

    private fun handleDataWedgeBarcode(intent: Intent) {
        if (isDataWedgeRfidIntent(intent)) {
            Log.i(tag, "Ignoring DataWedge RFID payload on barcode stream")
            return
        }
        val data = intent.getStringExtra(DATAWEDGE_DATA_STRING) ?: return
        diagnostics.record(
            "barcode",
            "decoded",
            "received",
            mapOf("source" to "datawedge"),
        )
        val labelType = intent.getStringExtra(DATAWEDGE_LABEL_TYPE)
        val endpoint = activeEndpoint()
        emitBarcode(
            Barcode(
                data,
                endpoint?.scannerIndex ?: -1L,
                labelType?.hashCode()?.toLong(),
                endpoint?.endpointId,
                endpoint?.source ?: BarcodeScannerSource.BUILT_IN_TERMINAL,
                endpoint?.displayName ?: "DataWedge scanner",
            )
        )
    }

    private fun parseDataWedgeScanners(intent: Intent): List<DataWedgeScanner> {
        val raw = intent.getSerializableExtra(EXTRA_RESULT_ENUMERATE_SCANNERS)
        val bundles = when (raw) {
            is ArrayList<*> -> raw.filterIsInstance<Bundle>()
            else -> emptyList()
        }
        return bundles.mapIndexed { fallbackIndex, bundle ->
            val identifier = bundle.getString("SCANNER_IDENTIFIER")
                ?: bundle.getString("SCANNER_NAME")
                ?: "SCANNER_$fallbackIndex"
            val index = when (val rawIndex = bundle.get("SCANNER_INDEX")) {
                is Int -> rawIndex
                is String -> rawIndex.toIntOrNull() ?: fallbackIndex
                else -> fallbackIndex
            }
            val name = bundle.getString("SCANNER_NAME")
                ?: bundle.getString("SCANNER_IDENTIFIER")
                ?: "DataWedge scanner $index"
            DataWedgeScanner(
                identifier = identifier,
                index = index,
                name = name,
                source = inferDataWedgeSource(identifier, name),
                status = if (bundle.getBoolean("SCANNER_CONNECTION_STATE", false)) {
                    ScannerConnectionStatus.CONNECTED
                } else {
                    ScannerConnectionStatus.DISCONNECTED
                },
            )
        }
    }

    private fun enqueueDataWedgeStartup() {
        val context = applicationContext ?: return
        enqueueDataWedgeCommands(
            commands = listOf(
                dataWedgeStatusCommand(),
                registerNotificationCommand(context.packageName),
            ),
            operation = "DataWedge startup",
        )
    }

    private fun enqueueDataWedgeHealthCheck(onComplete: (Result<Unit>) -> Unit) {
        enqueueDataWedgeCommands(
            commands = listOf(
                dataWedgeStatusCommand(),
                DataWedgeCommand(
                    label = "enumerate-scanners",
                    extraKey = EXTRA_ENUMERATE_SCANNERS,
                    value = "",
                    responseExtra = EXTRA_RESULT_ENUMERATE_SCANNERS,
                ),
                scannerStatusCommand(),
            ),
            operation = "DataWedge health check",
            onSuccess = {
                val endpoint = activeEndpoint()
                if (
                    endpoint?.mode == BarcodeScannerMode.DATA_WEDGE &&
                    (!initialDataWedgeSetupComplete ||
                        !isDataWedgeScannerReady(dataWedgeScannerStatus))
                ) {
                    Log.i(
                        tag,
                        "Configuring DataWedge endpoint=${endpoint.endpointId} status=$dataWedgeScannerStatus",
                    )
                    recoverDataWedgeEndpoint(endpoint) { result ->
                        if (result.isSuccess) initialDataWedgeSetupComplete = true
                        onComplete(result)
                    }
                } else {
                    initialDataWedgeSetupComplete = true
                    onComplete(Result.success(Unit))
                }
            },
            onError = { onComplete(Result.failure(IllegalStateException(it))) },
        )
    }

    private fun recoverDataWedgeEndpoint(
        endpoint: BarcodeScannerEndpoint,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val context = applicationContext
        if (context == null) {
            onComplete(Result.failure(IllegalStateException("DataWedge is not initialized")))
            return
        }
        val profileName = dataWedgeProfileName(context.packageName)
        enqueueDataWedgeCommands(
            commands = listOf(
                dataWedgeStatusCommand(),
                profileConfigCommand(profileName, context.packageName, endpoint),
            ),
            operation = "DataWedge recovery",
            onSuccess = { verifyDataWedgeScannerReady(onComplete) },
            onError = { onComplete(Result.failure(IllegalStateException(it))) },
        )
    }

    private fun verifyDataWedgeScannerReady(
        onComplete: (Result<Unit>) -> Unit,
        attempt: Int = 1,
    ) {
        enqueueDataWedgeCommands(
            commands = listOf(scannerStatusCommand()),
            operation = "DataWedge readiness check",
            onSuccess = {
                when {
                    isDataWedgeScannerReady(dataWedgeScannerStatus) ->
                        onComplete(Result.success(Unit))
                    attempt >= DATAWEDGE_READY_ATTEMPTS -> onComplete(
                        Result.failure(
                            IllegalStateException(
                                "DataWedge recovery completed but scanner status is ${dataWedgeScannerStatus ?: "unknown"}",
                            ),
                        ),
                    )
                    else -> repairDataWedgeScannerState(onComplete, attempt)
                }
            },
            onError = { onComplete(Result.failure(IllegalStateException(it))) },
        )
    }

    private fun repairDataWedgeScannerState(
        onComplete: (Result<Unit>) -> Unit,
        attempt: Int,
    ) {
        val status = dataWedgeScannerStatus
        val command = if (status == "IDLE") {
            DataWedgeCommand(
                label = "resume-scanner",
                extraKey = EXTRA_SCANNER_INPUT_PLUGIN,
                value = "RESUME_PLUGIN",
                acceptedFailureCodes = setOf("SCANNER_ALREADY_RESUMED"),
                postCompletionDelayMs = DATAWEDGE_STATE_SETTLE_MS,
            )
        } else {
            DataWedgeCommand(
                label = "enable-scanner",
                extraKey = EXTRA_SCANNER_INPUT_PLUGIN,
                value = "ENABLE_PLUGIN",
                acceptedFailureCodes = setOf("SCANNER_ALREADY_ENABLED"),
                postCompletionDelayMs = DATAWEDGE_STATE_SETTLE_MS,
            )
        }
        Log.i(tag, "Repairing DataWedge scanner status=$status attempt=$attempt")
        enqueueDataWedgeCommands(
            commands = listOf(command),
            operation = "DataWedge scanner state repair",
            onSuccess = { verifyDataWedgeScannerReady(onComplete, attempt + 1) },
            onError = { onComplete(Result.failure(IllegalStateException(it))) },
        )
    }

    private fun dataWedgeStatusCommand() = DataWedgeCommand(
        label = "datawedge-status",
        extraKey = EXTRA_GET_DATAWEDGE_STATUS,
        value = "",
        responseExtra = EXTRA_RESULT_GET_DATAWEDGE_STATUS,
    )

    private fun scannerStatusCommand() = DataWedgeCommand(
        label = "scanner-status",
        extraKey = EXTRA_GET_SCANNER_STATUS,
        value = "",
        responseExtra = EXTRA_RESULT_SCANNER_STATUS,
    )

    private fun registerNotificationCommand(packageName: String) = DataWedgeCommand(
        label = "register-scanner-status",
        extraKey = EXTRA_REGISTER_NOTIFICATION,
        value = Bundle().apply {
            putString(EXTRA_APPLICATION_NAME, packageName)
            putString(EXTRA_NOTIFICATION_TYPE, NOTIFICATION_SCANNER_STATUS)
        },
        // This API returns a notification bundle rather than a correlated
        // RESULT_ACTION. Keep later commands serialized behind Android 14's
        // documented DataWedge intent delivery window.
        completionDelayMs = 600L,
    )

    private fun profileConfigCommand(
        profileName: String,
        packageName: String,
        endpoint: BarcodeScannerEndpoint?,
    ) = DataWedgeCommand(
        label = "configure-profile",
        extraKey = EXTRA_SET_CONFIG,
        value = buildDataWedgeProfileConfig(
            profileName = profileName,
            packageName = packageName,
            endpoint = endpoint,
            actionBarcode = ACTION_BARCODE,
        ),
        acceptedFailureCodes = setOf("APP_ALREADY_ASSOCIATED"),
        sendResult = SEND_RESULT_COMPLETE,
        // SET_CONFIG can report before its profile disable/re-enable cycle has
        // finished. DataWedge 15 recommends delaying subsequent critical APIs.
        postCompletionDelayMs = DATAWEDGE_PROFILE_SETTLE_MS,
    )

    private fun enqueueDataWedgeCommands(
        commands: List<DataWedgeCommand>,
        operation: String,
        onSuccess: (List<DataWedgeCommandResult>) -> Unit = {},
        onError: (String) -> Unit = { Log.e(tag, it) },
    ) {
        val coordinator = dataWedgeCoordinator
        if (coordinator == null) {
            onError("$operation failed: DataWedge coordinator is not initialized")
            return
        }
        coordinator.enqueue(
            commands = commands,
            onSuccess = onSuccess,
            onError = { onError("$operation failed: $it") },
        )
    }

    private fun isDataWedgeScannerReady(status: String?): Boolean =
        status == "WAITING" || status == "WAITFORTRIGGER" || status == "SCANNING"

    private fun isDataWedgeRfidIntent(intent: Intent): Boolean {
        val source = intent.getStringExtra(DATAWEDGE_SOURCE)
            ?: intent.getStringExtra(DATAWEDGE_LEGACY_SOURCE)
        if (source?.contains("rfid", ignoreCase = true) == true) {
            return true
        }

        val labelType = intent.getStringExtra(DATAWEDGE_LABEL_TYPE)
            ?: intent.getStringExtra(DATAWEDGE_LEGACY_LABEL_TYPE)
        if (labelType?.contains("rfid", ignoreCase = true) == true) {
            return true
        }

        val decodedMode = intent.getStringExtra(DATAWEDGE_DECODED_MODE)
        if (decodedMode?.contains("rfid", ignoreCase = true) == true) {
            return true
        }

        return false
    }

    private fun scannerSdkEndpointId(scanner: DCSScannerInfo): String =
        stableScannerSdkEndpointId(
            scannerId = scanner.scannerID,
            hardwareIdentity = scanner.scannerHWSerialNumber,
            scannerName = scanner.scannerName,
        )

    private fun dataWedgeEndpointId(identifier: String?, index: Int?): String =
        "datawedge:${identifier ?: "index-${index ?: "unknown"}"}"

    private fun inferDataWedgeSource(identifier: String?, name: String?): BarcodeScannerSource {
        val text = "${identifier.orEmpty()} ${name.orEmpty()}".uppercase()
        return when {
            text.contains("INTERNAL") -> BarcodeScannerSource.BUILT_IN_TERMINAL
            text.contains("RFD") -> BarcodeScannerSource.RFID_SLED
            text.contains("BLUETOOTH") || text.contains("BT") -> BarcodeScannerSource.EXTERNAL_BLUETOOTH
            text.contains("USB") -> BarcodeScannerSource.EXTERNAL_USB
            else -> BarcodeScannerSource.UNKNOWN
        }
    }

    private fun inferSdkSource(scanner: DCSScannerInfo?): BarcodeScannerSource {
        val text = "${scanner?.scannerName.orEmpty()} ${scanner?.scannerModel.orEmpty()}".uppercase()
        return when {
            text.contains("RFD") -> BarcodeScannerSource.RFID_SLED
            text.contains("USB") -> BarcodeScannerSource.EXTERNAL_USB
            text.contains("BT") || text.contains("BLUETOOTH") -> BarcodeScannerSource.EXTERNAL_BLUETOOTH
            else -> BarcodeScannerSource.UNKNOWN
        }
    }

    private data class DataWedgeScanner(
        val identifier: String?,
        val index: Int?,
        val name: String,
        val source: BarcodeScannerSource,
        val status: ScannerConnectionStatus,
    )

    companion object {
        private const val PREFS_NAME = "flutter_zebra_barcode"
        private const val PREF_ACTIVE_ENDPOINT = "active_endpoint"
        private const val ACTION_DATAWEDGE = "com.symbol.datawedge.api.ACTION"
        private const val ACTION_RESULT = "com.symbol.datawedge.api.RESULT_ACTION"
        private const val ACTION_RESULT_NOTIFICATION = "com.symbol.datawedge.api.NOTIFICATION_ACTION"
        private const val ACTION_BARCODE = "nz.calo.flutter_zebra_rfid.BARCODE"
        private const val EXTRA_ENUMERATE_SCANNERS = "com.symbol.datawedge.api.ENUMERATE_SCANNERS"
        private const val EXTRA_RESULT_ENUMERATE_SCANNERS =
            "com.symbol.datawedge.api.RESULT_ENUMERATE_SCANNERS"
        private const val EXTRA_GET_SCANNER_STATUS = "com.symbol.datawedge.api.GET_SCANNER_STATUS"
        private const val EXTRA_RESULT_SCANNER_STATUS =
            "com.symbol.datawedge.api.RESULT_SCANNER_STATUS"
        private const val EXTRA_SET_CONFIG = "com.symbol.datawedge.api.SET_CONFIG"
        private const val EXTRA_SCANNER_INPUT_PLUGIN =
            "com.symbol.datawedge.api.SCANNER_INPUT_PLUGIN"
        private const val EXTRA_GET_DATAWEDGE_STATUS =
            "com.symbol.datawedge.api.GET_DATAWEDGE_STATUS"
        private const val EXTRA_RESULT_GET_DATAWEDGE_STATUS =
            "com.symbol.datawedge.api.RESULT_GET_DATAWEDGE_STATUS"
        private const val EXTRA_REGISTER_NOTIFICATION =
            "com.symbol.datawedge.api.REGISTER_FOR_NOTIFICATION"
        private const val EXTRA_RESULT_NOTIFICATION = "com.symbol.datawedge.api.NOTIFICATION"
        private const val EXTRA_RESULT_NOTIFICATION_TYPE = "NOTIFICATION_TYPE"
        private const val EXTRA_APPLICATION_NAME = "com.symbol.datawedge.api.APPLICATION_NAME"
        private const val EXTRA_NOTIFICATION_TYPE = "com.symbol.datawedge.api.NOTIFICATION_TYPE"
        private const val NOTIFICATION_SCANNER_STATUS = "SCANNER_STATUS"
        private const val DATAWEDGE_PROFILE_SETTLE_MS = 750L
        private const val DATAWEDGE_STATE_SETTLE_MS = 600L
        private const val DATAWEDGE_READY_ATTEMPTS = 3
        private const val DATAWEDGE_DATA_STRING = "com.symbol.datawedge.data_string"
        private const val DATAWEDGE_LABEL_TYPE = "com.symbol.datawedge.label_type"
        private const val DATAWEDGE_SOURCE = "com.symbol.datawedge.source"
        private const val DATAWEDGE_DECODED_MODE = "com.symbol.datawedge.decoded_mode"
        private const val DATAWEDGE_LEGACY_SOURCE = "com.motorolasolutions.emdk.datawedge.source"
        private const val DATAWEDGE_LEGACY_LABEL_TYPE = "com.motorolasolutions.emdk.datawedge.label_type"
    }
}

internal fun dataWedgeProfileName(packageName: String): String = "$packageName.barcode"

internal fun buildDataWedgeBarcodeProfileConfig(
    profileName: String,
    packageName: String,
    endpoint: BarcodeScannerEndpoint?,
): Bundle = Bundle().apply {
    putString("PROFILE_NAME", profileName)
    putString("PROFILE_ENABLED", "true")
    putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
    putParcelableArray("APP_LIST", arrayOf(Bundle().apply {
        putString("PACKAGE_NAME", packageName)
        putStringArray("ACTIVITY_LIST", arrayOf("*"))
    }))
    putBundle("PLUGIN_CONFIG", buildDataWedgeBarcodePluginConfig(endpoint))
}

internal fun buildDataWedgeDisableRfidProfileConfig(profileName: String): Bundle =
    Bundle().apply {
        putString("PROFILE_NAME", profileName)
        putString("PROFILE_ENABLED", "true")
        putString("CONFIG_MODE", "UPDATE")
        putBundle("PLUGIN_CONFIG", buildDataWedgeRfidPluginConfig())
    }

internal fun buildDataWedgeIntentProfileConfig(
    profileName: String,
    actionBarcode: String,
): Bundle = Bundle().apply {
    putString("PROFILE_NAME", profileName)
    putString("PROFILE_ENABLED", "true")
    putString("CONFIG_MODE", "UPDATE")
    putBundle("PLUGIN_CONFIG", buildDataWedgeIntentPluginConfig(actionBarcode))
}

internal fun buildDataWedgeProfileConfig(
    profileName: String,
    packageName: String,
    endpoint: BarcodeScannerEndpoint?,
    actionBarcode: String,
): Bundle = Bundle().apply {
    putString("PROFILE_NAME", profileName)
    putString("PROFILE_ENABLED", "true")
    putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
    putParcelableArray("APP_LIST", arrayOf(Bundle().apply {
        putString("PACKAGE_NAME", packageName)
        putStringArray("ACTIVITY_LIST", arrayOf("*"))
    }))
    putParcelableArrayList(
        "PLUGIN_CONFIG",
        arrayListOf(
            buildDataWedgeBarcodePluginConfig(endpoint),
            buildDataWedgeRfidPluginConfig(),
            buildDataWedgeIntentPluginConfig(actionBarcode),
        ),
    )
}

private fun buildDataWedgeBarcodePluginConfig(
    endpoint: BarcodeScannerEndpoint?,
): Bundle = Bundle().apply {
    putString("PLUGIN_NAME", "BARCODE")
    putString("RESET_CONFIG", "false")
    putBundle("PARAM_LIST", Bundle().apply {
        putString("scanner_input_enabled", "true")
        endpoint?.zebraScannerIdentifier?.let {
            putString("scanner_selection_by_identifier", it)
        }
        endpoint?.scannerIndex?.let {
            putString("scanner_selection", it.toString())
        }
    })
}

private fun buildDataWedgeRfidPluginConfig(): Bundle = Bundle().apply {
    putString("PLUGIN_NAME", "RFID")
    putString("RESET_CONFIG", "false")
    putBundle("PARAM_LIST", Bundle().apply {
        putString("rfid_input_enabled", "false")
    })
}

private fun buildDataWedgeIntentPluginConfig(actionBarcode: String): Bundle = Bundle().apply {
    putString("PLUGIN_NAME", "INTENT")
    putString("RESET_CONFIG", "true")
    putBundle("PARAM_LIST", Bundle().apply {
        putString("intent_output_enabled", "true")
        putString("intent_action", actionBarcode)
        putString("intent_delivery", "2")
    })
}
