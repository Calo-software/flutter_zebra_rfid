package nz.calo.flutter_zebra_rfid.barcode

import Barcode
import BarcodeScanner
import FlutterZebraBarcodeCallbacks
import ScannerConnectionStatus
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zebra.rfid.api3.InvalidUsageException
import com.zebra.rfid.api3.OperationFailureException
import com.zebra.scannercontrol.*
import java.util.*


class BarcodeScannerInterface(
    private var callbacks: FlutterZebraBarcodeCallbacks
) : IDcsSdkApiDelegate {
    private val TAG: String = "FlutterZebraRfidPlugin"

    private var sdkHandler: SDKHandler? = null
    private var availableScannerList: ArrayList<DCSScannerInfo> = ArrayList()

    private var currentScanner: DCSScannerInfo? = null

    fun updateAvailableScanners(context: Context) {
        if (sdkHandler == null)
            sdkHandler = SDKHandler(context)

        sdkHandler!!.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_BT_NORMAL)
//        sdkHandler!!.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_BT_LE)
        sdkHandler!!.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_USB_CDC)

        sdkHandler!!.dcssdkSetDelegate(this);
        var notificationsMask = 0
        notificationsMask =
            notificationsMask or (DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_APPEARANCE.value or
                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_DISAPPEARANCE.value)
        notificationsMask =
            notificationsMask or (DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_ESTABLISHMENT.value or
                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_TERMINATION.value)
        notificationsMask =
            notificationsMask or DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_BARCODE.value

        // subscribe to events set in notification mask
        sdkHandler!!.dcssdkSubsribeForEvents(notificationsMask)
        sdkHandler!!.dcssdkEnableAvailableScannersDetection(true)

        getAvailableScannerList()
    }

    fun connectToScanner(scannerId: Int) {
        try {
            val scanner = availableScannerList.firstOrNull { x -> x.scannerID == scannerId }
                ?: throw Error("Scanner not available")

            if (scanner == currentScanner) {
                Log.d(TAG, "Scanner ${scanner.scannerName} already connected!")
                return
            }

            Log.d(TAG, "Connecting to scanner $scannerId")

            if (scanner.isAutoCommunicationSessionReestablishment) {
                // consider it connected as it should auto connect
                Log.d(TAG, "Scanner set to auto-connect")
            }
            if (scanner.isActive)
                throw Error("Scanner is not active")


            callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.CONNECTING) {}

            // Connect
            var result = sdkHandler!!.dcssdkEstablishCommunicationSession(scanner.scannerID)

            if (result == DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS) {
                Log.d(TAG, "Connected to scanner ${scanner.scannerName}")
            } else {
                Log.d(TAG, "Failed to connect to scanner ${scanner.scannerName}: $result")
                callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.DISCONNECTED) {}
                throw Error("Failed to connect")
            }

        } catch (e: Exception) {
            throw Error("Failed to connect to scanner")
        }
    }

    fun currentScanner(): BarcodeScanner? {
        if (currentScanner != null) {
            return BarcodeScanner(
                currentScanner!!.scannerName,
                currentScanner!!.scannerID.toLong(),
                currentScanner!!.scannerModel,
                currentScanner!!.scannerHWSerialNumber,
            )
        }
        return null
    }

    fun onDestroy() {
        try {
            if (sdkHandler != null) {
                sdkHandler = null
            }
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun dcssdkEventScannerAppeared(scanner: DCSScannerInfo?) {
        Log.d(TAG, "Scanner ${scanner?.scannerName} appeared")
        getAvailableScannerList()
    }

    override fun dcssdkEventScannerDisappeared(scannerIndex: Int) {
        val scanner = availableScannerList[scannerIndex]
        Log.d(TAG, "Scanner ${scanner?.scannerName} disappeared")
        getAvailableScannerList()
    }

    override fun dcssdkEventCommunicationSessionEstablished(scanner: DCSScannerInfo?) {
        Log.d(TAG, "Scanner connected: ${scanner?.scannerName}")
        currentScanner = scanner
        callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.CONNECTED) {}
    }

    override fun dcssdkEventCommunicationSessionTerminated(scannerId: Int) {
        Log.d(TAG, "Scanner disconnected: $scannerId")
        currentScanner = null
        callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.DISCONNECTED) {}
    }

    override fun dcssdkEventBarcode(barcodeData: ByteArray?, barcodeType: Int, scannerId: Int) {
        val barcode = Barcode(String(barcodeData!!), scannerId.toLong(), barcodeType.toLong())
        Log.d(TAG, "Barcode read ${barcode.data}")
        Handler(Looper.getMainLooper()).post {
            callbacks.onBarcodeRead(barcode) {}
        }
    }

    override fun dcssdkEventImage(p0: ByteArray?, p1: Int) {
    }

    override fun dcssdkEventVideo(p0: ByteArray?, p1: Int) {
    }

    override fun dcssdkEventBinaryData(p0: ByteArray?, p1: Int) {
    }

    override fun dcssdkEventFirmwareUpdate(p0: FirmwareUpdateEvent?) {
    }

    override fun dcssdkEventAuxScannerAppeared(p0: DCSScannerInfo?, p1: DCSScannerInfo?) {
    }

    // PRIVATE:
    private fun getAvailableScannerList() {
        sdkHandler!!.dcssdkGetAvailableScannersList(availableScannerList)
        callbacks.onAvailableScannersChanged(availableScannerList.map {
            BarcodeScanner(
                it.scannerName, it.scannerID.toLong(),
                it.scannerModel, it.scannerHWSerialNumber
            )
        }) {}
    }
}