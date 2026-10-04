package org.zorro.weefinedivecam

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DiveViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        DiveUiState(
            waterType = settings.getString(KEY_WATER_TYPE, null)
                ?.let { saved -> WaterType.entries.firstOrNull { it.name == saved } }
                ?: WaterType.CLEAR_OCEAN
        )
    )
    val uiState: StateFlow<DiveUiState> = _uiState.asStateFlow()

    // A one-shot event stream for triggering physical actions
    private val _cameraActionEvent = MutableSharedFlow<String>()
    val cameraActionEvent: SharedFlow<String> = _cameraActionEvent.asSharedFlow()

    private var bleManager: WeefineBluetoothManager? = null

    // Last housing button press (elapsedRealtime), drives the automatic sleep levels
    @Volatile
    private var lastActivityAt = SystemClock.elapsedRealtime()

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
                // Without the housing no button can wake the screen: show the camera and the warning
                if (!isConnected) wakeUp()
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

        viewModelScope.launch {
            while (true) {
                delay(1.seconds)
                updateSleepLevel()
            }
        }
    }

    private fun updateSleepLevel() {
        val state = _uiState.value
        // Never sleep while filming, before a housing is chosen, or when its buttons can't wake us
        if (state.isRecording || state.housingAddress == null || !state.isBluetoothConnected) {
            lastActivityAt = SystemClock.elapsedRealtime()
            return
        }
        val idleMillis = SystemClock.elapsedRealtime() - lastActivityAt
        val target = when {
            idleMillis >= CAMERA_OFF_AFTER.inWholeMilliseconds -> SleepLevel.CAMERA_OFF
            idleMillis >= SCREEN_OFF_AFTER.inWholeMilliseconds -> SleepLevel.SCREEN_OFF
            else -> SleepLevel.AWAKE
        }
        // Only deepen automatically; waking up is always explicit
        if (target > state.sleepLevel) {
            _uiState.update { it.copy(sleepLevel = target) }
        }
    }

    fun wakeUp() {
        lastActivityAt = SystemClock.elapsedRealtime()
        _uiState.update { it.copy(sleepLevel = SleepLevel.AWAKE) }
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

    /** Chosen before the dive for each site; remembered across launches. */
    fun setWaterType(waterType: WaterType) {
        settings.edit().putString(KEY_WATER_TYPE, waterType.name).apply()
        _uiState.update { it.copy(waterType = waterType) }
    }

    private fun handleHousingButton(code: Int) {
        lastActivityAt = SystemClock.elapsedRealtime()

        // While asleep any button only wakes up, so a press never fires a blind shot
        if (_uiState.value.sleepLevel != SleepLevel.AWAKE) {
            wakeUp()
            return
        }

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
            0x61 -> { // MF/AF: manual deep sleep. Ignored while filming so a stray press can't end a video
                if (!_uiState.value.isRecording) {
                    _uiState.update { it.copy(sleepLevel = SleepLevel.CAMERA_OFF) }
                }
            }
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

    private companion object {
        val SCREEN_OFF_AFTER = 1.minutes
        val CAMERA_OFF_AFTER = 5.minutes
        const val KEY_WATER_TYPE = "water_type"
    }

    override fun onCleared() {
        super.onCleared()
        bleManager?.stopScanning()
        bleManager?.disconnect()
    }
}