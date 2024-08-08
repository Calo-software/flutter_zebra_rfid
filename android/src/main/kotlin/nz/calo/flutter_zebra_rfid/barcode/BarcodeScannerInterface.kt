package nz.calo.flutter_zebra_rfid.barcode

import BarcodeScanner
import FlutterZebraBarcodeCallbacks
import android.content.Context
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

    private var scanner: DCSScannerInfo? = null

    fun updateAvailableScanners(context: Context) {
        if (sdkHandler == null)
            sdkHandler = SDKHandler(context)

//        sdkHandler!!.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_BT_NORMAL)
        sdkHandler!!.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_USB_CDC)

        sdkHandler!!.dcssdkSetDelegate(this);
        var notifications_mask = 0
        notifications_mask =
            notifications_mask or (DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_APPEARANCE.value or
                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_DISAPPEARANCE.value)
        notifications_mask =
            notifications_mask or (DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_ESTABLISHMENT.value or
                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_TERMINATION.value)
        notifications_mask =
            notifications_mask or DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_BARCODE.value

        // subscribe to events set in notification mask
        sdkHandler!!.dcssdkSubsribeForEvents(notifications_mask)
        sdkHandler!!.dcssdkEnableAvailableScannersDetection(true)

        sdkHandler!!.dcssdkGetAvailableScannersList(availableScannerList)
        callbacks.onAvailableScannersChanged(availableScannerList.map {
            BarcodeScanner(it.scannerName, it.scannerID.toLong(), it.scannerModel, it.scannerHWSerialNumber)
        }) {}
    }

    fun connectToScanner(scannerId: Int) {
        try {
            val scanner = availableScannerList.firstOrNull { x -> x.scannerID == scannerId }
                ?: throw Error("Scanner not available")
            if (scanner.isActive)
                throw Error("Scanner is not active")

            Log.d(TAG, "Connecting to scanner $scannerId")
            callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.CONNECTING) {}

            // Connect
            var result = sdkHandler!!.dcssdkEstablishCommunicationSession(scanner.scannerID)

            if(result == DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS) {
                Log.d(TAG, "Connected to scanner $scannerId")
                callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.CONNECTED) {}
            } else {
                Log.d(TAG, "Failed to connect to scanner $scannerId: $result")
                callbacks.onScannerConnectionStatusChanged(ScannerConnectionStatus.DISCONNECTED) {}
                throw Error("Failed to connect")
            }

        } catch (e: Exception) {
            throw Error("Failed to connect to scanner")
        }
    }
    fun currentScanner(): BarcodeScanner? {
        if (scanner != null) {
            return BarcodeScanner(
                scanner!!.scannerName,
                scanner!!.scannerID.toLong(),
                scanner!!.scannerModel,
                scanner!!.scannerHWSerialNumber,
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

    override fun dcssdkEventScannerAppeared(p0: DCSScannerInfo?) {
    }

    override fun dcssdkEventScannerDisappeared(p0: Int) {
    }

    override fun dcssdkEventCommunicationSessionEstablished(p0: DCSScannerInfo?) {
    }

    override fun dcssdkEventCommunicationSessionTerminated(p0: Int) {
    }

    override fun dcssdkEventBarcode(p0: ByteArray?, p1: Int, p2: Int) {
        val barcode = String(p0!!)
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
}