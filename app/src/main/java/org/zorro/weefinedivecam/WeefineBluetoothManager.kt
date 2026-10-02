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

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    // Callback that handles incoming scan results
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown Device"
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
        val BUTTON_CHAR_UUID: UUID = UUID.fromString("00001524-1212-efde-1523-785feabcd123")
        val SENSOR_CHAR_UUID: UUID = UUID.fromString("00001625-1212-efde-1523-785feabcd123")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Standard Bluetooth SIG Battery Profile UUIDs
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_CHAR_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onConnectionChanged(true)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onConnectionChanged(false)
            }
        }

        // Helper method to look through services and enable the Button notification first
        private fun enableNotificationsForCustomServices(gatt: BluetoothGatt) {
            for (service in gatt.services) {
                val buttonChar = service.getCharacteristic(BUTTON_CHAR_UUID)
                if (buttonChar != null) {
                    // Tell Android local system to listen
                    gatt.setCharacteristicNotification(buttonChar, true)

                    // Tell physical housing hardware to stream
                    val descriptor = buttonChar.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                    return // Exit early! We wait for onDescriptorWrite before setting up sensors
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                //  Services found! Start the waterfall by reading the battery first
                val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
                val batteryChar = batteryService?.getCharacteristic(BATTERY_CHAR_UUID)

                if (batteryChar != null) {
                    gatt.readCharacteristic(batteryChar)
                } else {
                    // If no battery service, skip straight to setting up buttons
                    enableNotificationsForCustomServices(gatt)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Button notifications are officially active! Now safely enable Sensors
            if (descriptor.characteristic.uuid == BUTTON_CHAR_UUID) {
                for (service in gatt.services) {
                    val sensorChar = service.getCharacteristic(SENSOR_CHAR_UUID)
                    if (sensorChar != null) {
                        gatt.setCharacteristicNotification(sensorChar, true)

                        val sensorDescriptor = sensorChar.getDescriptor(CCCD_UUID)
                        if (sensorDescriptor != null) {
                            @Suppress("DEPRECATION")
                            sensorDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(sensorDescriptor)
                        }
                        break
                    }
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_CHAR_UUID) {
                val batteryLevel = value.firstOrNull()?.toInt() ?: return
                onBatteryReceived(batteryLevel)

                // Battery read complete! Move to enabling notifications
                enableNotificationsForCustomServices(gatt)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // We use the 'value' variable directly now!
            when (characteristic.uuid) {
                BUTTON_CHAR_UUID -> {
                    val code = value.firstOrNull()?.toInt() ?: return
                    onButtonPressed(code)
                }
                SENSOR_CHAR_UUID -> {
                    if (value.size >= 4) {
                        val depthRaw = ((value[1].toInt() and 0xFF) shl 8) or (value[0].toInt() and 0xFF)
                        val tempRaw = ((value[3].toInt() and 0xFF) shl 8) or (value[2].toInt() and 0xFF)
                        onTelemetryReceived(depthRaw / 10.0, tempRaw / 10.0)
                    }
                }
            }
        }
    }

    fun connect(deviceAddress: String) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bluetoothManager.adapter.getRemoteDevice(deviceAddress)
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}