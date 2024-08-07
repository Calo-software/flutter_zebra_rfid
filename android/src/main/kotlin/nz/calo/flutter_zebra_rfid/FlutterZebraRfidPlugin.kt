package nz.calo.flutter_zebra_rfid

import FlutterZebraRfid
import FlutterZebraRfidCallbacks
import Reader
import ReaderConfig
import ReaderConnectionType
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
import nz.calo.flutter_zebra_rfid.rfid.RFIDReaderInterface


/** FlutterZebraRfidPlugin */
class FlutterZebraRfidPlugin : FlutterPlugin,
    PluginRegistry.RequestPermissionsResultListener,
    PluginRegistry.ActivityResultListener,
    ActivityAware,
    FlutterZebraRfid {

    private val TAG: String = "FlutterZebraRfidPlugin"

    private lateinit var applicationContext: Context
    private lateinit var rfidCallbacks: FlutterZebraRfidCallbacks

    private val operationsOnPermission: MutableMap<Int, OperationOnPermission> = HashMap()
    private var lastEventId = 1751
    private var activityBinding: ActivityPluginBinding? = null

    private interface OperationOnPermission {
        fun op(granted: Boolean, permission: String?)
    }


    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        rfidCallbacks = FlutterZebraRfidCallbacks(flutterPluginBinding.binaryMessenger)
        rfidInterface = RFIDReaderInterface(rfidCallbacks)
        applicationContext = flutterPluginBinding.applicationContext

        FlutterZebraRfid.setUp(flutterPluginBinding.binaryMessenger, this)
    }


    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        FlutterZebraRfid.setUp(binding.binaryMessenger, null)
    }

    private fun ensurePermissions(permissions: List<String>, operation: OperationOnPermission) {
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
            operation.op(true, null)
            return
        }
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
        lastEventId++
    }

//    private fun requestPermission(result: IntentResult) {
//        val permissionReceiver = object : BroadcastReceiver() {
//            init {
//                val intentFilter = IntentFilter()
//                intentFilter.addAction(ACTION_PERMISSIONS_GRANTED)
//                intentFilter.addAction(ACTION_PERMISSIONS_DENIED)
//                applicationContext.registerReceiver(this, intentFilter)
//            }
//
//            override fun onReceive(context: Context, intent: Intent) {
//                when {
//                    intent.action == ACTION_PERMISSIONS_GRANTED -> {
//                        result.success(2)
//                        context.unregisterReceiver(this)
//                    }
//                    intent.action == ACTION_PERMISSIONS_DENIED -> {
//                        result.success(1)
//                        context.unregisterReceiver(this)
//                    }
//
//                }
//            }
//        }
//        val intent = Intent(applicationContext, PushPermissionActivity::class.java)
//        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK;
//        applicationContext.startActivity(intent)
//    }

    // FlutterZebraRfid overrides
    override fun updateAvailableReaders(
        connectionType: ReaderConnectionType,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            if (connectionType == ReaderConnectionType.BLUETOOTH) {
                val permissions = ArrayList<String>()
                if (Build.VERSION.SDK_INT >= 31) { // Android 12 (October 2021)
                    permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
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

                            rfidInterface!!.getAvailableReaderList(
                                applicationContext,
                                connectionType
                            )
                            callback(Result.success(Unit))
                        }
                    })
            } else {
                rfidInterface!!.getAvailableReaderList(
                    applicationContext,
                    connectionType
                )
                callback(Result.success(Unit))
            }
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun connectReader(readerId: Long, callback: (Result<Unit>) -> Unit) {
        try {
            rfidInterface!!.connectReader(readerId)
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

//    override fun onDestroy() {
//        super.onDestroy()
//        dispose()
//    }

    // Zebra API3 overrides
    private fun dispose() {
        if (rfidInterface != null) {
            rfidInterface!!.onDestroy()
        }
//        if (scannerInterface != null) {
//            scannerInterface!!.onDestroy()
//        }
    }

//    private fun configureDevice() {
//
//        // Configure BT barcode scanner
//        if (scannerInterface == null)
//            scannerInterface = BarcodeScannerInterface(this)
//
//        var availableScannerList = scannerInterface!!.getAvailableScanners(applicationContext)
//        if (availableScannerList.size > 1) {
//            val items = availableScannerList.map { x -> x.scannerName }.toTypedArray()
//            var checkedItem = 0
//
//        } else if (availableScannerList.first() != null) {
//            configureScanner(availableScannerList.first().scannerID)
//        } else {
//            Toast.makeText(
//                applicationContext,
//                "No available scanner",
//                Toast.LENGTH_LONG
//            ).show()
//        }
//    }

    companion object {
        private var rfidInterface: RFIDReaderInterface? = null
        //    private var scannerInterface: BarcodeScannerInterface? = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        val operation = operationsOnPermission[requestCode]

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
