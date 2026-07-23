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
import nz.calo.flutter_zebra_rfid.hardware.currentZebraHostIdentity

/**
 * Coordinates barcode scanners exposed through Zebra Scanner Control SDK and
 * Android DataWedge. DataWedge is needed for built-in Zebra terminal scanners;
 * Scanner Control is still used for external scanners such as sled scanners.
 */
class BarcodeScannerInterface(
    private val callbacks: FlutterZebraBarcodeCallbacks
) : IDcsSdkApiDelegate {
    private val tag = "FlutterZebraBarcode"
    private val hostIdentity = currentZebraHostIdentity()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val availableScannerList: MutableList<DCSScannerInfo> =
        Collections.synchronizedList(ArrayList())

    private var sdkHandler: SDKHandler? = null
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
    var endpointsChangedListener: (() -> Unit)? = null
    var connectionStatusListener: ((ScannerConnectionStatus) -> Unit)? = null

    /**
     * Install an app-associated DataWedge profile before any RFID work starts.
     * Leaving this app on Profile0 allows DataWedge to open INTERNAL_CAMERA as
     * soon as the activity is foregrounded, which prevents EM45 API3 from
     * opening its integrated RFID transport.
     */
    fun prepareDataWedgeControl(context: Context) {
        ensureDataWedgeInitialized(context.applicationContext)
        if (hostIdentity.isEm45) {
            configureDataWedgeIdle()
        }
    }

    fun configureDataWedgeIdle() {
        configureDataWedgeIdle { result ->
            result.exceptionOrNull()?.let {
                Log.e(tag, "Failed to release DataWedge barcode capture", it)
            }
        }
    }

    private fun configureDataWedgeIdle(onComplete: (Result<Unit>) -> Unit) {
        activeEndpointId = null
        initialDataWedgeSetupComplete = false
        emitEndpoints()
        val context = applicationContext
        if (context == null) {
            onComplete(Result.failure(IllegalStateException("DataWedge is not initialized")))
            return
        }
        enqueueDataWedgeCommands(
            commands = listOf(
                profileConfigCommand(dataWedgeProfileName(context.packageName), context.packageName, null),
            ),
            operation = "DataWedge idle configuration",
            onSuccess = { onComplete(Result.success(Unit)) },
            onError = { onComplete(Result.failure(IllegalStateException(it))) },
        )
    }

    fun prepareForIntegratedRfid(onReady: () -> Unit) {
        configureDataWedgeIdle { result ->
            result.exceptionOrNull()?.let {
                Log.e(tag, "Proceeding with integrated RFID after DataWedge release failed", it)
            }
            onReady()
        }
    }

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
            onComplete(Result.failure(error))
        }
    }

    fun connectToScanner(scannerId: Int) {
        val scanner = synchronized(availableScannerList) {
            availableScannerList.firstOrNull { it.scannerID == scannerId }
        } ?: throw Error("Scanner not available")

        if (scanner == currentScanner) {
            setActiveEndpoint(scannerSdkEndpointId(scanner.scannerID))
            return
        }

        val handler = sdkHandler ?: throw Error("Barcode Scanner SDK is not initialized")
        callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.CONNECTING) {}
        connectionStatusListener?.invoke(ScannerConnectionStatus.CONNECTING)
        val result = handler.dcssdkEstablishCommunicationSession(scanner.scannerID)
        if (result != DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS) {
            callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.DISCONNECTED) {}
            connectionStatusListener?.invoke(ScannerConnectionStatus.DISCONNECTED)
            throw Error("Failed to connect to scanner ${scanner.scannerName}: $result")
        }
        setActiveEndpoint(scannerSdkEndpointId(scanner.scannerID))
    }

    fun disconnectCurrentScanner() {
        val scanner = currentScanner ?: return
        callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.DISCONNECTING) {}
        connectionStatusListener?.invoke(ScannerConnectionStatus.DISCONNECTING)
        val result = sdkHandler?.dcssdkTerminateCommunicationSession(scanner.scannerID)
        if (result != DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS) {
            callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.ERROR) {}
            connectionStatusListener?.invoke(ScannerConnectionStatus.ERROR)
            throw Error("Failed to disconnect from current scanner")
        }
    }

    fun setActiveEndpoint(endpointId: String) {
        val endpoint = activateEndpoint(endpointId)
        if (endpoint.mode == BarcodeScannerMode.DATA_WEDGE) {
            recoverDataWedgeEndpoint(endpoint) { result ->
                result.exceptionOrNull()?.let {
                    Log.e(tag, "DataWedge endpoint activation failed", it)
                }
            }
        }
    }

    fun setActiveEndpoint(
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
        if (hostIdentity.isEm45) {
            Log.i(
                tag,
                "EM45 selected Barcode Endpoint id=${endpoint.endpointId} " +
                    "identifier=${endpoint.zebraScannerIdentifier} source=${endpoint.source} mode=${endpoint.mode}",
            )
        }
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
        currentScanner = scanner
        scanner?.let { activeEndpointId = scannerSdkEndpointId(it.scannerID) }
        callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.CONNECTED) {}
        connectionStatusListener?.invoke(ScannerConnectionStatus.CONNECTED)
        emitEndpoints()
    }

    override fun dcssdkEventCommunicationSessionTerminated(scannerId: Int) {
        Log.d(tag, "Scanner disconnected: $scannerId")
        if (currentScanner?.scannerID == scannerId) {
            currentScanner = null
        }
        callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.DISCONNECTED) {}
        connectionStatusListener?.invoke(ScannerConnectionStatus.DISCONNECTED)
        emitEndpoints()
    }

    override fun dcssdkEventBarcode(barcodeData: ByteArray?, barcodeType: Int, scannerId: Int) {
        val data = barcodeData?.toString(Charset.defaultCharset()) ?: return
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
        ensureDataWedgeInitialized(context)
        ensureScannerSdkInitialized(context)
        isInitialized = true
    }

    private fun ensureDataWedgeInitialized(context: Context) {
        if (dataWedgeCoordinator != null) return
        applicationContext = context
        preferredEndpointId = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_ACTIVE_ENDPOINT, null)
        dataWedgeCoordinator = DataWedgeCommandCoordinator(
            sendIntent = { context.sendOrderedBroadcast(it, null) },
            scheduleTimeout = { runnable, delay -> mainHandler.postDelayed(runnable, delay) },
            cancelTimeout = { runnable -> mainHandler.removeCallbacks(runnable) },
        )
        registerDataWedgeReceiver(context)
        enqueueDataWedgeStartup()
    }

    private fun ensureScannerSdkInitialized(context: Context) {
        if (sdkHandler != null) return
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
                if (shouldEnableScannerSdkUsbCdc()) {
                    handler.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_USB_CDC)
                } else {
                    Log.i(
                        tag,
                        "Suppressing Scanner SDK USB CDC on Zebra terminal so RFID sled USB remains owned by RFID SDK",
                    )
                }
                handler.dcssdkSetDelegate(this)
                val notificationsMask =
                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_APPEARANCE.value or
                        DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_DISAPPEARANCE.value or
                        DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_ESTABLISHMENT.value or
                        DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_TERMINATION.value or
                        DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_BARCODE.value
                handler.dcssdkSubsribeForEvents(notificationsMask)
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

    private fun shouldEnableScannerSdkUsbCdc(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().uppercase()
        val model = Build.MODEL.orEmpty().uppercase()
        val product = Build.PRODUCT.orEmpty().uppercase()
        val device = Build.DEVICE.orEmpty().uppercase()
        val isZebra = manufacturer.contains("ZEBRA") || manufacturer.contains("MOTOROLA")
        val isTcSeries = listOf(model, product, device).any {
            it.startsWith("TC") || it.contains("TC22") || it.contains("TC27")
        }
        return !(isZebra || isTcSeries)
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
        synchronized(availableScannerList) {
            availableScannerList.forEach { scanner ->
                val endpointId = scannerSdkEndpointId(scanner.scannerID)
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
            emitEndpoints()
        }
        if (intent.hasExtra(EXTRA_RESULT_SCANNER_STATUS)) {
            dataWedgeScannerStatus = intent.getStringExtra(EXTRA_RESULT_SCANNER_STATUS)
            emitEndpoints()
        }
    }

    private fun handleDataWedgeNotification(intent: Intent) {
        val extras = intent.getBundleExtra(EXTRA_RESULT_NOTIFICATION) ?: return
        if (extras.getString(EXTRA_RESULT_NOTIFICATION_TYPE) == NOTIFICATION_SCANNER_STATUS) {
            dataWedgeScannerStatus = extras.getString(NOTIFICATION_SCANNER_STATUS)
            emitEndpoints()
        }
    }

    private fun handleDataWedgeBarcode(intent: Intent) {
        if (isDataWedgeRfidIntent(intent)) {
            Log.i(tag, "Ignoring DataWedge RFID payload on barcode stream")
            return
        }
        val data = intent.getStringExtra(DATAWEDGE_DATA_STRING) ?: return
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
        val scanners = bundles.mapIndexed { fallbackIndex, bundle ->
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
        if (hostIdentity.isEm45) {
            scanners.forEach { scanner ->
                Log.i(
                    tag,
                    "EM45 DataWedge endpoint identifier=${scanner.identifier} index=${scanner.index} " +
                        "name=${scanner.name} source=${scanner.source} status=${scanner.status}",
                )
            }
        }
        return scanners
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
        status == "WAITING" || status == "SCANNING"

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

    private fun scannerSdkEndpointId(scannerId: Int): String = "scanner-sdk:$scannerId"

    private fun dataWedgeEndpointId(identifier: String?, index: Int?): String =
        "datawedge:${identifier ?: "index-${index ?: "unknown"}"}"

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

internal fun inferDataWedgeSource(identifier: String?, name: String?): BarcodeScannerSource {
    val text = "${identifier.orEmpty()} ${name.orEmpty()}".uppercase()
    return when {
        text.contains("INTERNAL") -> BarcodeScannerSource.BUILT_IN_TERMINAL
        text.contains("RFD") -> BarcodeScannerSource.RFID_SLED
        text.contains("BLUETOOTH") || text.contains("BT") -> BarcodeScannerSource.EXTERNAL_BLUETOOTH
        text.contains("USB") -> BarcodeScannerSource.EXTERNAL_USB
        else -> BarcodeScannerSource.UNKNOWN
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

internal fun buildDataWedgeCaptureProfileConfig(
    profileName: String,
    packageName: String,
    endpoint: BarcodeScannerEndpoint?,
    actionBarcode: String,
): Bundle {
    val barcode = buildDataWedgeBarcodeProfileConfig(profileName, packageName, endpoint)
    val rfid = buildDataWedgeDisableRfidProfileConfig(profileName)
    val intent = buildDataWedgeIntentProfileConfig(profileName, actionBarcode)
    return Bundle().apply {
        putString("PROFILE_NAME", profileName)
        putString("PROFILE_ENABLED", "true")
        putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
        putParcelableArray("APP_LIST", barcode.getParcelableArray("APP_LIST"))
        putParcelableArrayList(
            "PLUGIN_CONFIG",
            arrayListOf(
                barcode.getBundle("PLUGIN_CONFIG")!!,
                rfid.getBundle("PLUGIN_CONFIG")!!,
                intent.getBundle("PLUGIN_CONFIG")!!,
            ),
        )
    }
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
        putString("scanner_input_enabled", (endpoint != null).toString())
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
