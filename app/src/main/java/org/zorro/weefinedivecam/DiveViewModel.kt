package org.zorro.weefinedivecam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class DiveViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DiveUiState())
    val uiState: StateFlow<DiveUiState> = _uiState.asStateFlow()

    // A one-shot event stream for triggering physical actions
    private val _cameraActionEvent = MutableSharedFlow<String>()
    val cameraActionEvent: SharedFlow<String> = _cameraActionEvent.asSharedFlow()

    private var bleManager: WeefineBluetoothManager? = null

    init {
        bleManager = WeefineBluetoothManager(
            context = application,
            onTelemetryReceived = { depth, temp ->
                _uiState.update { state ->
                    val newStartTime = if (depth > 0.1 && state.diveStartTime == null) {
                        System.currentTimeMillis()
                    } else if (depth <= 0.1) {
                        null
                    } else {
                        state.diveStartTime
                    }
                    state.copy(depth = depth, temperature = temp, diveStartTime = newStartTime)
                }
            },
            onButtonPressed = { buttonCode ->
                handleHousingButton(buttonCode)
            },
            onConnectionChanged = { isConnected ->
                _uiState.update { it.copy(isBluetoothConnected = isConnected) }
            },
            onDeviceFound = { newDevice ->
                _uiState.update { state ->
                    // Only add the device if we haven't seen its MAC address yet
                    if (state.discoveredDevices.none { it.address == newDevice.address }) {
                        state.copy(discoveredDevices = state.discoveredDevices + newDevice)
                    } else {
                        state
                    }
                }
            },
            onBatteryReceived= { batteryPercentage ->
                // Feed the fresh battery metrics directly into the state
                _uiState.update { it.copy(housingBattery = batteryPercentage) }
            }
        )

    }

    fun startScanning() {
        _uiState.update { it.copy(isScanning = true, discoveredDevices = emptyList()) }
        bleManager?.startScanning()
    }

    fun stopScanning() {
        _uiState.update { it.copy(isScanning = false) }
        bleManager?.stopScanning()
    }

    fun connectToDevice(macAddress: String) {
        stopScanning()
        _uiState.update { it.copy(housingAddress = macAddress) }
        bleManager?.connect(macAddress)
    }

    fun toggleDepthFilter() {
        _uiState.update { it.copy(isDepthFilterEnabled = !it.isDepthFilterEnabled) }
    }

    private fun handleHousingButton(code: Int) {
        when (code) {
            0x20 -> { // Shutter Button clicked on Weefine housing
                viewModelScope.launch {
                    val currentState = _uiState.value
                    if (currentState.cameraMode == CameraMode.PHOTO) {
                        _cameraActionEvent.emit("TAKE_PHOTO")
                    } else {
                        if (currentState.isRecording) {
                            _cameraActionEvent.emit("STOP_RECORDING")
                            _uiState.update { it.copy(isRecording = false) }
                        } else {
                            _cameraActionEvent.emit("START_RECORDING")
                            _uiState.update { it.copy(isRecording = true) }
                        }
                    }
                }
            }
            0x61 -> { /* TODO: MF/AF */ }
            0x10 -> { // Mode / Back
                viewModelScope.launch {
                    val currentState = _uiState.value
                    if (currentState.isRecording) {
                        _cameraActionEvent.emit("STOP_RECORDING")
                    }
                    _uiState.update { state ->
                        val nextMode = if (state.cameraMode == CameraMode.PHOTO) CameraMode.VIDEO else CameraMode.PHOTO
                        state.copy(cameraMode = nextMode, isRecording = false)
                    }
                }
            }
            0x40 -> { // Zoom / +
                viewModelScope.launch {
                    _cameraActionEvent.emit("ZOOM_IN")
                }
            }
            0x30 -> toggleDepthFilter() // Menu / OK
            0x50 -> { // Filter / -
                viewModelScope.launch {
                    _cameraActionEvent.emit("ZOOM_OUT")
                }
            }
        }
    }

    fun startHousingConnection(macAddress: String) {
        connectToDevice(macAddress)
    }

    override fun onCleared() {
        super.onCleared()
        bleManager?.stopScanning()
        bleManager?.disconnect()
    }
}