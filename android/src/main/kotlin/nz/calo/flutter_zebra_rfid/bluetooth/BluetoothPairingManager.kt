package nz.calo.flutter_zebra_rfid.bluetooth

import BluetoothDevice
import BluetoothScanStatus
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice as AndroidBluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BluetoothPairingManager(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    private val tag = "BluetoothPairingMgr"

    interface Callbacks {
        fun onBluetoothDeviceDiscovered(device: BluetoothDevice)
        fun onBluetoothScanStatusChanged(status: BluetoothScanStatus)
        fun onBluetoothPairingResult(device: BluetoothDevice, success: Boolean)
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val discoveredAddresses = linkedSetOf<String>()
    private var discoveryReceiverRegistered = false
    private var pairingReceiverRegistered = false
    private var pendingPairAddress: String? = null
    private var bleScanActive = false

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            emitBleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::emitBleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(tag, "BLE scan failed with errorCode=$errorCode")
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(tag, "Received ACTION_DISCOVERY_FINISHED (natural discovery completion)")
                    callbacks.onBluetoothScanStatusChanged(BluetoothScanStatus.FINISHED)
                }

                AndroidBluetoothDevice.ACTION_FOUND -> {
                    val device = intent.bluetoothDeviceExtra(AndroidBluetoothDevice.EXTRA_DEVICE) ?: return
                    val address = device.address ?: return
                    Log.d(
                        tag,
                        "Received ACTION_FOUND for ${device.name ?: "<unnamed>"} ($address), bondState=${device.bondState}",
                    )
                    if (!discoveredAddresses.add(address)) {
                        Log.d(tag, "Ignoring duplicate discovered device $address")
                        return
                    }
                    Log.d(tag, "Forwarding discovered Bluetooth device $address to Flutter")
                    callbacks.onBluetoothDeviceDiscovered(device.toPigeonDevice())
                }
            }
        }
    }

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AndroidBluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                return
            }
            val device = intent.bluetoothDeviceExtra(AndroidBluetoothDevice.EXTRA_DEVICE) ?: return
            val address = device.address ?: return
            if (pendingPairAddress != null && pendingPairAddress != address) {
                return
            }

            val newState = intent.getIntExtra(AndroidBluetoothDevice.EXTRA_BOND_STATE, AndroidBluetoothDevice.ERROR)
            val previousState = intent.getIntExtra(AndroidBluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, AndroidBluetoothDevice.ERROR)
            Log.d(tag, "Received bond state change for ${device.name ?: "<unnamed>"} ($address): $previousState -> $newState")

            when (newState) {
                AndroidBluetoothDevice.BOND_BONDED -> {
                    pendingPairAddress = null
                    callbacks.onBluetoothPairingResult(device.toPigeonDevice(), true)
                }

                AndroidBluetoothDevice.BOND_NONE -> {
                    if (previousState == AndroidBluetoothDevice.BOND_BONDING || previousState == AndroidBluetoothDevice.BOND_BONDED) {
                        pendingPairAddress = null
                        callbacks.onBluetoothPairingResult(device.toPigeonDevice(), false)
                    }
                }
            }
        }
    }

    fun startScan() {
        val adapter = requireEnabledBluetoothAdapter()
        registerDiscoveryReceiver()
        discoveredAddresses.clear()
        emitBondedDevices(adapter)
        if (adapter.isDiscovering) {
            Log.d(tag, "Cancelling in-progress discovery before restarting scan")
            adapter.cancelDiscovery()
        }
        Log.d(tag, "Starting Bluetooth discovery, adapterState=${adapter.state}, bondedCount=${adapter.bondedDevices?.size ?: 0}")
        callbacks.onBluetoothScanStatusChanged(BluetoothScanStatus.SCANNING)
        startBleScan(adapter)
        if (!adapter.startDiscovery()) {
            Log.w(tag, "Bluetooth discovery failed to start")
            callbacks.onBluetoothScanStatusChanged(BluetoothScanStatus.ERROR)
            throw IllegalStateException("Bluetooth discovery could not be started")
        }
    }

    fun stopScan() {
        val adapter = bluetoothAdapter ?: return
        stopBleScan(adapter)
        if (adapter.isDiscovering) {
            Log.d(tag, "Stopping Bluetooth discovery")
            adapter.cancelDiscovery()
        } else {
            callbacks.onBluetoothScanStatusChanged(BluetoothScanStatus.FINISHED)
        }
    }

    fun getBondedDevices(): List<BluetoothDevice> {
        val adapter = requireEnabledBluetoothAdapter()
        val devices = adapter.bondedDevices
            ?.map { it.toPigeonDevice() }
            ?.sortedWith(compareBy<BluetoothDevice>({ !(it.name?.isLikelyZebraName() ?: false) }, { it.name ?: it.address }))
            ?: emptyList()
        Log.d(tag, "getBondedDevices returning ${devices.size} device(s)")
        devices.forEach { device ->
            Log.d(tag, "Bonded device: ${device.name ?: "<unnamed>"} (${device.address}) paired=${device.isPaired}")
        }
        return devices
    }

    fun pairDevice(address: String) {
        val adapter = requireEnabledBluetoothAdapter()
        val device = adapter.getRemoteDevice(address)
        if (device.bondState == AndroidBluetoothDevice.BOND_BONDED) {
            callbacks.onBluetoothPairingResult(device.toPigeonDevice(), true)
            return
        }
        registerPairingReceiver()
        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        pendingPairAddress = address
        if (!device.createBond()) {
            pendingPairAddress = null
            callbacks.onBluetoothPairingResult(device.toPigeonDevice(), false)
            throw IllegalStateException("Bluetooth pairing could not be started for $address")
        }
    }

    fun dispose() {
        pendingPairAddress = null
        unregisterDiscoveryReceiver()
        unregisterPairingReceiver()
    }

    private fun requireEnabledBluetoothAdapter(): BluetoothAdapter {
        val adapter = bluetoothAdapter ?: throw IllegalStateException("Bluetooth is not supported on this device")
        if (!adapter.isEnabled) {
            throw IllegalStateException("Bluetooth is turned off")
        }
        return adapter
    }

    private fun emitBondedDevices(adapter: BluetoothAdapter) {
        val bondedDevices = adapter.bondedDevices
            ?.map { it.toPigeonDevice() }
            ?.sortedWith(compareBy<BluetoothDevice>({ !(it.name?.isLikelyZebraName() ?: false) }, { it.name ?: it.address }))
            ?: emptyList()
        Log.d(tag, "Emitting ${bondedDevices.size} bonded Bluetooth device(s) before discovery")
        bondedDevices.forEach { device ->
            discoveredAddresses.add(device.address)
            callbacks.onBluetoothDeviceDiscovered(device)
        }
    }

    private fun startBleScan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.d(tag, "BLE scanner unavailable; continuing with classic discovery only")
            return
        }
        if (bleScanActive) {
            Log.d(tag, "Stopping existing BLE scan before restart")
            scanner.stopScan(bleScanCallback)
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, bleScanCallback)
        bleScanActive = true
        Log.d(tag, "Started BLE scan alongside classic discovery")
    }

    private fun stopBleScan(adapter: BluetoothAdapter) {
        if (!bleScanActive) {
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner != null) {
            scanner.stopScan(bleScanCallback)
            Log.d(tag, "Stopped BLE scan")
        }
        bleScanActive = false
    }

    private fun emitBleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val deviceName = result.scanRecord?.deviceName ?: device.name
        Log.d(
            tag,
            "Received BLE scan result for ${deviceName ?: "<unnamed>"} ($address), bondState=${device.bondState}, rssi=${result.rssi}",
        )
        if (!discoveredAddresses.add(address)) {
            Log.d(tag, "Ignoring duplicate BLE device $address")
            return
        }
        Log.d(tag, "Forwarding BLE device $address to Flutter")
        callbacks.onBluetoothDeviceDiscovered(
            BluetoothDevice(
                name = deviceName,
                address = address,
                isPaired = device.bondState == AndroidBluetoothDevice.BOND_BONDED,
            ),
        )
    }

    private fun registerDiscoveryReceiver() {
        if (discoveryReceiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(AndroidBluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(
            context,
            discoveryReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        Log.d(tag, "Registered Bluetooth discovery receiver")
        discoveryReceiverRegistered = true
    }

    private fun registerPairingReceiver() {
        if (pairingReceiverRegistered) {
            return
        }
        val filter = IntentFilter(AndroidBluetoothDevice.ACTION_BOND_STATE_CHANGED)
        ContextCompat.registerReceiver(
            context,
            pairingReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        Log.d(tag, "Registered Bluetooth pairing receiver")
        pairingReceiverRegistered = true
    }

    private fun unregisterDiscoveryReceiver() {
        if (!discoveryReceiverRegistered) {
            return
        }
        runCatching { context.unregisterReceiver(discoveryReceiver) }
        Log.d(tag, "Unregistered Bluetooth discovery receiver")
        discoveryReceiverRegistered = false
    }

    private fun unregisterPairingReceiver() {
        if (!pairingReceiverRegistered) {
            return
        }
        runCatching { context.unregisterReceiver(pairingReceiver) }
        Log.d(tag, "Unregistered Bluetooth pairing receiver")
        pairingReceiverRegistered = false
    }

    private fun AndroidBluetoothDevice.toPigeonDevice(): BluetoothDevice {
        return BluetoothDevice(
            name = name,
            address = address,
            isPaired = bondState == AndroidBluetoothDevice.BOND_BONDED,
        )
    }

    private fun Intent.bluetoothDeviceExtra(key: String): AndroidBluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, AndroidBluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }

    private fun String.isLikelyZebraName(): Boolean {
        return startsWith("RFD", ignoreCase = true) ||
            startsWith("MC", ignoreCase = true) ||
            startsWith("TC", ignoreCase = true)
    }
}