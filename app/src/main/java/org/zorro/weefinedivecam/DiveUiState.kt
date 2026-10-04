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
    val waterType: WaterType = WaterType.CLEAR_OCEAN,
    val sleepLevel: SleepLevel = SleepLevel.AWAKE
) {
    val colorGains: ColorGains
        get() = if (isDepthFilterEnabled) WaterColorCorrection.gains(depth, waterType) else ColorGains.IDENTITY
}
