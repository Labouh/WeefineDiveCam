package org.zorro.weefinedivecam

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.UUID

@SuppressLint("MissingPermission")
class WeefineBluetoothManager(
    private val context: Context,
    private val onTelemetryReceived: (depth: Double, temp: Double) -> Unit,
    private val onButtonPressed: (buttonCode: Int) -> Unit,
    private val onConnectionChanged: (isConnected: Boolean) -> Unit,
    private val onDeviceFound: (DiscoveredDevice) -> Unit,
    private val onBatteryReceived: (Int) -> Unit
) {
    private var bluetoothGatt: BluetoothGatt? = null

    // Address of the housing to stay connected to; null once the user disconnects on purpose
    @Volatile
    private var targetAddress: String? = null

    // Characteristics whose notifications still need enabling. GATT allows only one
    // outstanding operation, so each CCCD write is chained from onDescriptorWrite.
    private val pendingNotifications = ArrayDeque<UUID>()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    // Callback that handles incoming scan results
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown Device"
            val address = device.address

            if (name.contains("WEEFINE")) {
                // Stream the clean data class upstream
                onDeviceFound(DiscoveredDevice(name, address))
            }
        }
    }

    fun startScanning() {
        bleScanner?.startScan(scanCallback)
    }

    fun stopScanning() {
        bleScanner?.stopScan(scanCallback)
    }
    companion object {
        private const val TAG = "WeefineBLE"

        val BUTTON_CHAR_UUID: UUID = UUID.fromString("00001524-1212-efde-1523-785feabcd123")
        val SENSOR_CHAR_UUID: UUID = UUID.fromString("00001625-1212-efde-1523-785feabcd123")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Standard Bluetooth SIG Battery Profile UUIDs
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_CHAR_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        // Sensor frame (every ~500 ms): uint16 LE depth, then uint16 LE temperature in 0.1 °C.
        // Out of the water nRF Connect shows 00-00-F3-00 = depth 0, 24.3 °C.
        // The depth unit is unconfirmed: 0.1 m is assumed until checked in water.
        const val DEPTH_METERS_PER_UNIT = 0.1
        const val TEMP_CELSIUS_PER_UNIT = 0.1
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onConnectionChanged(true)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onConnectionChanged(false)
                gatt.close()
                if (bluetoothGatt === gatt) bluetoothGatt = null

                // BLE through a housing underwater drops easily: keep trying in the background.
                // autoConnect = true lets the controller reconnect as soon as the housing is back in range.
                targetAddress?.let { address ->
                    Log.w(TAG, "Housing disconnected (status $status), reconnecting")
                    connectGatt(address, autoConnect = true)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            pendingNotifications.clear()
            pendingNotifications.addAll(listOf(BUTTON_CHAR_UUID, SENSOR_CHAR_UUID, BATTERY_CHAR_UUID))

            //  Services found! Start the waterfall by reading the battery first
            val batteryChar = gatt.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_CHAR_UUID)
            if (batteryChar == null || !gatt.readCharacteristic(batteryChar)) {
                enableNextNotification(gatt)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid == BATTERY_CHAR_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    value.firstOrNull()?.let { onBatteryReceived(it.toInt() and 0xFF) }
                }
                // Battery read complete (or failed)! Move to enabling notifications
                enableNextNotification(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Enabling notifications for ${descriptor.characteristic.uuid} failed: $status")
            }
            enableNextNotification(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                BUTTON_CHAR_UUID -> {
                    val code = value.firstOrNull()?.toInt()?.and(0xFF) ?: return
                    onButtonPressed(code)
                }
                SENSOR_CHAR_UUID -> {
                    Log.d(TAG, "Sensor frame: ${value.joinToString("-") { "%02X".format(it) }}")
                    if (value.size >= 4) {
                        val depthRaw = ((value[1].toInt() and 0xFF) shl 8) or (value[0].toInt() and 0xFF)
                        val tempRaw = ((value[3].toInt() and 0xFF) shl 8) or (value[2].toInt() and 0xFF)
                        onTelemetryReceived(depthRaw * DEPTH_METERS_PER_UNIT, tempRaw * TEMP_CELSIUS_PER_UNIT)
                    }
                }
                BATTERY_CHAR_UUID -> {
                    value.firstOrNull()?.let { onBatteryReceived(it.toInt() and 0xFF) }
                }
            }
        }
    }

    private fun enableNextNotification(gatt: BluetoothGatt) {
        while (pendingNotifications.isNotEmpty()) {
            val uuid = pendingNotifications.removeFirst()
            val characteristic = gatt.services.firstNotNullOfOrNull { it.getCharacteristic(uuid) } ?: continue
            val cccd = characteristic.getDescriptor(CCCD_UUID) ?: continue

            // Tell the local stack to deliver callbacks, then tell the housing to start notifying
            gatt.setCharacteristicNotification(characteristic, true)
            val result = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (result == BluetoothStatusCodes.SUCCESS) return // Wait for onDescriptorWrite
            Log.w(TAG, "writeDescriptor for $uuid rejected: $result")
        }
    }

    private fun connectGatt(address: String, autoConnect: Boolean) {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        bluetoothGatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun connect(deviceAddress: String) {
        disconnect()
        targetAddress = deviceAddress
        connectGatt(deviceAddress, autoConnect = false)
    }

    fun disconnect() {
        targetAddress = null
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
