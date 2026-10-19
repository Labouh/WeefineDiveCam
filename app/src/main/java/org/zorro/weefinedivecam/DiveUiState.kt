package org.zorro.weefinedivecam

data class DiscoveredDevice(
    val name: String,
    val address: String
)

enum class CameraMode {
    PHOTO, VIDEO
}

/** Battery saving levels, ordered from awake to deepest sleep. */
enum class SleepLevel {
    AWAKE,
    SCREEN_OFF, // Black screen, camera still running: wakes instantly
    CAMERA_OFF  // Black screen and camera unbound: ~1 s to restart
}

data class DiveUiState(
    val depth: Double = 0.0,
    val temperature: Double = 0.0,
    val cameraMode: CameraMode = CameraMode.PHOTO,
    val isRecording: Boolean = false,
    val isBluetoothConnected: Boolean = false,
    val housingAddress: String? = null, // Housing chosen by the user; kept across BLE dropouts
    val isScanning: Boolean = false,
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val housingBattery: Int = 100,
    val diveStartTime: Long? = null,
    val isDepthFilterEnabled: Boolean = true,
    val sleepLevel: SleepLevel = SleepLevel.AWAKE,
    val isHousingLost: Boolean = false // Disconnected longer than a short dropout: black screen
) {
    /**
     * Unbound in deep sleep and while the housing is lost, since no button could trigger it then.
     * A video being filmed keeps it running: losing the footage is worse than the battery drain.
     */
    val isCameraOn: Boolean
        get() = sleepLevel != SleepLevel.CAMERA_OFF && (!isHousingLost || isRecording)

    /** Colour correction applies under water only, when the diver hasn't switched it off. */
    val isColorCorrectionOn: Boolean
        get() = isDepthFilterEnabled && depth >= UnderwaterAutoCorrection.MIN_DEPTH_M
}
