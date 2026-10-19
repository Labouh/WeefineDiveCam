package org.zorro.weefinedivecam

import android.app.Application
import android.content.Context
import android.location.Location
import android.net.Uri
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
import kotlinx.coroutines.Job
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

    private val geotagger = DiveGeotagger(application, viewModelScope) {
        _uiState.value.let { state -> if (state.isBluetoothConnected) state.depth else null }
    }

    /** Last GPS position if recent, written into videos when they start (they can't be tagged later). */
    val videoLocation: Location? get() = geotagger.recentLocation

    // Last housing button press (elapsedRealtime), drives the automatic sleep levels
    @Volatile
    private var lastActivityAt = SystemClock.elapsedRealtime()

    // Pending switch to the "housing lost" screen, cancelled if the link comes back in time
    private var housingLostJob: Job? = null

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
                // Hop to the main thread so connection events and the timer below never race
                viewModelScope.launch { onHousingConnectionChanged(isConnected) }
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

    /**
     * Once the housing has been gone for a while (switched off at the end of the dive, or out of
     * range) the screen goes black and the camera stops, so the phone doesn't drain in the housing.
     * Short underwater dropouts are ignored. The normal screen comes back on reconnection.
     */
    private fun onHousingConnectionChanged(isConnected: Boolean) {
        if (isConnected) {
            housingLostJob?.cancel()
            housingLostJob = null
            val wasLost = _uiState.value.isHousingLost
            _uiState.update { it.copy(isBluetoothConnected = true, isHousingLost = false) }
            if (wasLost) wakeUp()
        } else {
            _uiState.update { it.copy(isBluetoothConnected = false) }
            if (housingLostJob?.isActive != true) {
                housingLostJob = viewModelScope.launch {
                    delay(HOUSING_LOST_AFTER)
                    _uiState.update { it.copy(isHousingLost = true) }
                }
            }
        }
    }

    /** Tap on the black screen, on land: back to the camera, with the red warning in the HUD. */
    fun dismissHousingLost() {
        _uiState.update { it.copy(isHousingLost = false) }
        wakeUp()
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

    /** Called from the photo thread once the files are written; GPS is added when a fix is available. */
    fun onPhotosSaved(uris: List<Uri>, takenAt: Long, depth: Double) {
        viewModelScope.launch { geotagger.addPhotos(uris, takenAt, depth) }
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
        val HOUSING_LOST_AFTER = 5.seconds
        const val KEY_WATER_TYPE = "water_type"
    }

    override fun onCleared() {
        super.onCleared()
        bleManager?.stopScanning()
        bleManager?.disconnect()
        geotagger.release()
    }
}