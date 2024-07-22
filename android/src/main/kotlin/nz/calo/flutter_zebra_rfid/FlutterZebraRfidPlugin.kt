package nz.calo.flutter_zebra_rfid

import FlutterZebraRfid
import ReaderConnectionType
import android.content.Context
import androidx.annotation.NonNull

import io.flutter.embedding.engine.plugins.FlutterPlugin
import nz.calo.flutter_zebra_rfid.rfid.IRFIDReaderListener
import nz.calo.flutter_zebra_rfid.rfid.RFIDReaderInterface

/** FlutterZebraRfidPlugin */
class FlutterZebraRfidPlugin : FlutterPlugin, FlutterZebraRfid, IRFIDReaderListener {
    private lateinit var applicationContext: Context

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        rfidInterface = RFIDReaderInterface(this)
        applicationContext = flutterPluginBinding.applicationContext

        FlutterZebraRfid.setUp(flutterPluginBinding.binaryMessenger, this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        FlutterZebraRfid.setUp(binding.binaryMessenger, null)
    }

    // FlutterZebraRfid overrides
    override fun getAvailableReaders(
        connectionType: ReaderConnectionType,
        callback: (Result<List<String>>) -> Unit
    ) {
        try {
            val list =
                rfidInterface!!.getAvailableReaderList(
                    applicationContext,
                    connectionType
                )
            callback(Result.success(list))
        } catch (e: Throwable) {
            callback(Result.failure(e))
        }
    }

    override fun connectReader(readerName: String, callback: (Result<Boolean>) -> Unit) {
        return callback(Result.success(rfidInterface!!.connectReader(readerName)))
    }

    override fun disconnectReader(callback: (Result<Boolean>) -> Unit) {
        return callback(Result.success(rfidInterface!!.disconnectCurrentReader()))
    }

//    override fun onDestroy() {
//        super.onDestroy()
//        dispose()
//    }

    // Zebra API3 overrides
    override fun newTagRead(epc: String?) {
        TODO("Not yet implemented")
    }

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
}
