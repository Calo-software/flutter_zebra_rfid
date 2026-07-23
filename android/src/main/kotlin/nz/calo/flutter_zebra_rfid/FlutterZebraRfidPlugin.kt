package nz.calo.flutter_zebra_rfid

import BarcodeScanner
import BarcodeScannerEndpoint
import FlutterZebraBarcode
import FlutterZebraBarcodeCallbacks
import FlutterZebraCapture
import FlutterZebraCaptureCallbacks
import FlutterZebraRfid
import FlutterZebraRfidCallbacks
import BluetoothDevice
import BluetoothScanStatus
import Reader
import ReaderConfig
import ReaderConnectionType
import ReaderRegion
import RfidTag
import Diagnostics
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.PluginRegistry
import nz.calo.flutter_zebra_rfid.barcode.BarcodeScannerInterface
import nz.calo.flutter_zebra_rfid.bluetooth.BluetoothPairingManager
import nz.calo.flutter_zebra_rfid.capture.CaptureDeviceCoordinator
import nz.calo.flutter_zebra_rfid.rfid.RFIDReaderInterface


/** FlutterZebraRfidPlugin */
class FlutterZebraRfidPlugin : FlutterPlugin,
    PluginRegistry.RequestPermissionsResultListener,
    PluginRegistry.ActivityResultListener,
    ActivityAware,
    FlutterZebraRfid,
    FlutterZebraBarcode {

    private val TAG: String = "FlutterZebraRfidPlugin"

    private lateinit var applicationContext: Context
    private lateinit var rfidCallbacks: FlutterZebraRfidCallbacks
    private lateinit var scannerCallbacks: FlutterZebraBarcodeCallbacks
    private lateinit var captureCallbacks: FlutterZebraCaptureCallbacks
    private var bluetoothPairingManager: BluetoothPairingManager? = null

    private val operationsOnPermission: MutableMap<Int, OperationOnPermission> = HashMap()
    private var lastEventId = 1751
    private var activityBinding: ActivityPluginBinding? = null

    private interface OperationOnPermission {
        fun op(granted: Boolean, permission: String?)
    }


    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = flutterPluginBinding.applicationContext

        rfidCallbacks = FlutterZebraRfidCallbacks(flutterPluginBinding.binaryMessenger)
        rfidInterface = RFIDReaderInterface(rfidCallbacks, applicationContext)
        bluetoothPairingManager = BluetoothPairingManager(applicationContext,
            object : BluetoothPairingManager.Callbacks {
                override fun onBluetoothDeviceDiscovered(device: BluetoothDevice) {
                    rfidCallbacks.onBluetoothDeviceDiscovered(device) { }
                }

                override fun onBluetoothScanStatusChanged(status: BluetoothScanStatus) {
                    rfidCallbacks.onBluetoothScanStatusChanged(status) { }
                }

                override fun onBluetoothPairingResult(device: BluetoothDevice, success: Boolean) {
                    rfidCallbacks.onBluetoothPairingResult(device, success) { }
                }
            })
        scannerCallbacks = FlutterZebraBarcodeCallbacks(flutterPluginBinding.binaryMessenger)
        scannerInterface = BarcodeScannerInterface(scannerCallbacks)
        scannerInterface!!.prepareDataWedgeControl(applicationContext)
        captureCallbacks = FlutterZebraCaptureCallbacks(flutterPluginBinding.binaryMessenger)
        captureCoordinator = CaptureDeviceCoordinator(
            applicationContext,
            rfidInterface!!,
            scannerInterface!!,
            captureCallbacks,
        )

        FlutterZebraRfid.setUp(flutterPluginBinding.binaryMessenger, this)
        FlutterZebraBarcode.setUp(flutterPluginBinding.binaryMessenger, this)
        FlutterZebraCapture.setUp(flutterPluginBinding.binaryMessenger, captureCoordinator)
    }


    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        dispose()
        FlutterZebraRfid.setUp(binding.binaryMessenger, null)
        FlutterZebraBarcode.setUp(binding.binaryMessenger, null)
        FlutterZebraCapture.setUp(binding.binaryMessenger, null)
    }

    private fun bluetoothPermissions(includeDiscoveryPermissions: Boolean): List<String> {
        val permissions = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT <= 30) {
            permissions.add(Manifest.permission.BLUETOOTH)
            if (includeDiscoveryPermissions) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return permissions
    }

    private fun logBluetoothPermissionState(includeDiscoveryPermissions: Boolean) {
        val permissions = bluetoothPermissions(includeDiscoveryPermissions)
        if (permissions.isEmpty()) {
            Log.d(TAG, "No Bluetooth runtime permissions required for this API level")
            return
        }
        permissions.forEach { permission ->
            val granted = ContextCompat.checkSelfPermission(applicationContext, permission) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission state: $permission granted=$granted")
        }
    }

    private fun ensurePermissions(permissions: List<String>, operation: OperationOnPermission) {
        if (permissions.isNotEmpty()) {
            Log.d(TAG, "Ensuring permissions: ${permissions.joinToString()}")
        }
        // only request permission we don't already have
        val permissionsNeeded: MutableList<String> = ArrayList()
        for (permission in permissions) {
            if (permission != null && ContextCompat.checkSelfPermission(
                    applicationContext,
                    permission
                )
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(permission)
            }
        }

        // no work to do?
        if (permissionsNeeded.isEmpty()) {
            Log.d(TAG, "All requested permissions already granted")
            operation.op(true, null)
            return
        }
        Log.d(TAG, "Requesting missing permissions: ${permissionsNeeded.joinToString()}")
        askPermission(permissionsNeeded, operation)
    }

    private fun askPermission(
        permissionsNeeded: MutableList<String>,
        operation: OperationOnPermission
    ) {
        // finished asking for permission? call callback
        if (permissionsNeeded.isEmpty()) {
            operation.op(true, null)
            return
        }
        val nextPermission: String = permissionsNeeded.removeAt(0)
        operationsOnPermission[lastEventId] =
            object : OperationOnPermission {
                override fun op(granted: Boolean, permission: String?) {
                    operationsOnPermission.remove(lastEventId)
                    if (!granted) {
                        operation.op(false, permission)
                        return
                    }
                    // recursively ask for next permission
                    askPermission(permissionsNeeded, operation)

                }
            }
        ActivityCompat.requestPermissions(
            activityBinding!!.activity, arrayOf<String>(nextPermission),
            lastEventId
        )
        Log.d(TAG, "Requested permission $nextPermission with requestCode=$lastEventId")
        lastEventId++
    }

    // FlutterZebraRfid overrides
    override fun updateAvailableReaders(
        connectionType: ReaderConnectionType,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            val needsBluetooth = connectionType == ReaderConnectionType.BLUETOOTH
            if (needsBluetooth) {
                val permissions = bluetoothPermissions(includeDiscoveryPermissions = false)
                ensurePermissions(permissions,
                    object : OperationOnPermission {
                        override fun op(granted: Boolean, permission: String?) {
                            if (!granted) {
                                callback(Result.failure(Error("Bluetooth permissions are required to discover wireless readers")))
                                Log.e(TAG, "BLE permission not granted for Bluetooth discovery")
                                return
                            }
                            Log.d(TAG, "BLE permission granted, can continue...")

                            rfidInterface!!.getAvailableReaderList(
                                connectionType
                            )
                            callback(Result.success(Unit))
                        }
                    })
            } else {
                rfidInterface!!.getAvailableReaderList(
                    connectionType
                )
                callback(Result.success(Unit))
            }
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun startBluetoothScan(callback: (Result<Unit>) -> Unit) {
        try {
            Log.d(TAG, "startBluetoothScan invoked")
            logBluetoothPermissionState(includeDiscoveryPermissions = true)
            ensurePermissions(bluetoothPermissions(includeDiscoveryPermissions = true),
                object : OperationOnPermission {
                    override fun op(granted: Boolean, permission: String?) {
                        if (!granted) {
                            Log.w(TAG, "Bluetooth scan blocked because permission was denied: ${permission ?: "unknown"}")
                            callback(Result.failure(Error("Bluetooth scan requires permission: ${permission ?: "unknown"}")))
                            return
                        }
                        try {
                            Log.d(TAG, "Permissions satisfied, delegating Bluetooth scan to pairing manager")
                            bluetoothPairingManager!!.startScan()
                            callback(Result.success(Unit))
                        } catch (e: Throwable) {
                            Log.e(TAG, "Bluetooth scan failed", e)
                            callback(Result.failure(e))
                        }
                    }
                })
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun stopBluetoothScan(callback: (Result<Unit>) -> Unit) {
        try {
            bluetoothPairingManager?.stopScan()
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun getBondedDevices(callback: (Result<List<BluetoothDevice>>) -> Unit) {
        try {
            Log.d(TAG, "getBondedDevices invoked")
            logBluetoothPermissionState(includeDiscoveryPermissions = false)
            ensurePermissions(bluetoothPermissions(includeDiscoveryPermissions = false),
                object : OperationOnPermission {
                    override fun op(granted: Boolean, permission: String?) {
                        if (!granted) {
                            Log.w(TAG, "getBondedDevices blocked because permission was denied: ${permission ?: "unknown"}")
                            callback(Result.failure(Error("Bluetooth access requires permission: ${permission ?: "unknown"}")))
                            return
                        }
                        try {
                            val devices = bluetoothPairingManager!!.getBondedDevices()
                            Log.d(TAG, "Delivering ${devices.size} bonded Bluetooth device(s) to Flutter")
                            callback(Result.success(devices))
                        } catch (e: Throwable) {
                            Log.e(TAG, "getBondedDevices failed", e)
                            callback(Result.failure(e))
                        }
                    }
                })
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun pairBluetoothDevice(address: String, callback: (Result<Unit>) -> Unit) {
        try {
            ensurePermissions(bluetoothPermissions(includeDiscoveryPermissions = false),
                object : OperationOnPermission {
                    override fun op(granted: Boolean, permission: String?) {
                        if (!granted) {
                            callback(Result.failure(Error("Bluetooth pairing requires permission: ${permission ?: "unknown"}")))
                            return
                        }
                        try {
                            bluetoothPairingManager!!.pairDevice(address)
                            callback(Result.success(Unit))
                        } catch (e: Throwable) {
                            callback(Result.failure(e))
                        }
                    }
                })
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun connectReader(readerId: Long, callback: (Result<Unit>) -> Unit) {
        try {
            // Kick off async connect (result will be surfaced via callbacks streams)
            rfidInterface!!.connectReader(readerId)
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun configureReader(
        config: ReaderConfig,
        shouldPersist: Boolean,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            rfidInterface!!.configureReader(config, shouldPersist)
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun disconnectReader(callback: (Result<Unit>) -> Unit) {
        try {
            rfidInterface!!.disconnectCurrentReader()
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun triggerDeviceStatus(callback: (Result<Unit>) -> Unit) {
        try {
            rfidInterface!!.triggerDeviceStatus()
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun currentReader(): Reader? {
        return rfidInterface!!.currentReader()
    }

    override fun readerConfig(callback: (Result<ReaderConfig>) -> Unit) {
        try {
            val config = rfidInterface!!.getReaderConfig()
            callback(Result.success(config))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun supportedReaderRegions(callback: (Result<List<ReaderRegion>>) -> Unit) {
        try {
            callback(Result.success(rfidInterface!!.supportedReaderRegions()))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun setReaderRegion(regionCode: String, callback: (Result<Unit>) -> Unit) {
        try {
            rfidInterface!!.setReaderRegion(regionCode)
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun startLocating(tags: List<RfidTag>, disableBeep: Boolean?, callback: (Result<Unit>) -> Unit) {
        try {
            rfidInterface!!.startLocating(tags, disableBeep ?: false)
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun stopLocating(callback: (Result<Unit>) -> Unit) {
        try {
            rfidInterface!!.stopLocating()
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun resetLocateState(callback: (Result<Unit>) -> Unit) {
        try {
            rfidInterface!!.resetLocateState()
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun diagnostics(callback: (Result<Diagnostics>) -> Unit) {
        try {
            rfidInterface!!.diagnosticsWithReaderPowerState(callback)
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun setScanningEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        try {
            rfidInterface?.setScanningEnabled(enabled)
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    // =============================
    // FlutterZebraBarcode overrides
    // =============================
    override fun updateAvailableScanners(callback: (Result<Unit>) -> Unit) {
        try {
            val permissions = ArrayList<String>()
            if (Build.VERSION.SDK_INT >= 31) { // Android 12 (October 2021)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }

            if (Build.VERSION.SDK_INT <= 30) { // Android 11 (September 2020)
                permissions.add(Manifest.permission.BLUETOOTH);
            }
            ensurePermissions(permissions,
                object : OperationOnPermission {
                    override fun op(granted: Boolean, permission: String?) {
                        if (!granted) {
                            callback(Result.failure(Error("You need to grant BLE permissions")))
                            Log.e(TAG, "BLE permission not granted!")
                            return
                        }
                        Log.e(TAG, "BLE permission granted, can continue...")
                        scannerInterface!!.updateAvailableScanners(
                            applicationContext,
                            callback,
                        )
                    }
                })
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun connectScanner(scannerId: Long, callback: (Result<Unit>) -> Unit) {
        try {
            scannerInterface!!.connectToScanner(scannerId.toInt())
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun disconnectScanner(callback: (Result<Unit>) -> Unit) {
        try {
            scannerInterface!!.disconnectCurrentScanner()
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun refreshBarcodeScanners(callback: (Result<Unit>) -> Unit) {
        try {
            scannerInterface!!.refreshBarcodeScanners(applicationContext, callback)
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun setActiveBarcodeScanner(endpointId: String, callback: (Result<Unit>) -> Unit) {
        try {
            scannerInterface!!.setActiveEndpoint(endpointId, callback)
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun clearActiveBarcodeScanner(callback: (Result<Unit>) -> Unit) {
        try {
            scannerInterface!!.clearActiveEndpoint()
            callback(Result.success(Unit))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun currentScanner(): BarcodeScanner? {
        return scannerInterface!!.currentScanner()
    }

    override fun activeBarcodeScanner(): BarcodeScannerEndpoint? {
        return scannerInterface!!.activeEndpoint()
    }


//    override fun onDestroy() {
//        super.onDestroy()
//        dispose()
//    }

    // Zebra API3 overrides
    private fun dispose() {
        captureCoordinator = null
        bluetoothPairingManager?.dispose()
        bluetoothPairingManager = null
        if (rfidInterface != null) {
            rfidInterface!!.onDestroy()
        }
        if (scannerInterface != null) {
            scannerInterface!!.onDestroy()
        }
    }

    companion object {
        private var rfidInterface: RFIDReaderInterface? = null
        private var scannerInterface: BarcodeScannerInterface? = null
        private var captureCoordinator: CaptureDeviceCoordinator? = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        val operation = operationsOnPermission[requestCode]

        if (permissions.isNotEmpty() && grantResults.isNotEmpty()) {
            Log.d(
                TAG,
                "Permission result requestCode=$requestCode permission=${permissions[0]} granted=${grantResults[0] == PackageManager.PERMISSION_GRANTED}",
            )
        }

        return if (operation != null && grantResults.isNotEmpty()) {
            operation.op(grantResults[0] === PackageManager.PERMISSION_GRANTED, permissions[0])
            true
        } else {
            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
//        if (requestCode === enableBluetoothRequestCode) {
//
//            // see: BmTurnOnResponse
//            val map = HashMap<String, Any>()
//            map["user_accepted"] = resultCode === Activity.RESULT_OK
//            invokeMethodUIThread("OnTurnOnResponse", map)
//            return true
//        }
//
        return false // did no

    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding;
        activityBinding!!.addRequestPermissionsResultListener(this)
        activityBinding!!.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activityBinding!!.removeRequestPermissionsResultListener(this)
        activityBinding = null
    }
}
