package org.zorro.weefinedivecam

import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Composable
fun CameraHudOverlay(
    uiState: DiveUiState,
    modifier: Modifier = Modifier
) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(uiState.diveStartTime) {
        if (uiState.diveStartTime != null) {
            while (true) {
                currentTime = System.currentTimeMillis()
                delay(1.seconds)
            }
        }
    }

    val batteryColor = when {
        uiState.housingBattery <= 20 -> Color.Red
        uiState.housingBattery <= 50 -> Color.Yellow
        else -> Color.Green
    }

    // Single-line strip: the app is mostly used in landscape, keep the frame clear
    Row(
        modifier = modifier
            .zIndex(10f)
            .background(Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (uiState.diveStartTime != null) {
            val elapsedSeconds = (currentTime - uiState.diveStartTime) / 1000
            Text(
                text = String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60),
                color = Color.Cyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = "${String.format("%.1f", uiState.depth)} m",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${String.format("%.1f", uiState.temperature)} °C",
            color = Color.White,
            fontSize = 14.sp
        )
        Icon(
            imageVector = if (uiState.cameraMode == CameraMode.PHOTO) Icons.Default.CameraAlt else Icons.Default.Videocam,
            contentDescription = "Mode",
            tint = if (uiState.isRecording) Color.Red else Color.White,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = if (uiState.isDepthFilterEnabled) "FILTER ON" else "FILTER OFF",
            color = if (uiState.isDepthFilterEnabled) Color(0xFFFF8A65) else Color.Gray,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        if (!uiState.isBluetoothConnected) {
            Text(
                text = "HOUSING LOST",
                color = Color.Red,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = "BATT ${uiState.housingBattery}%",
            color = batteryColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun DiveCameraScreen(
    uiState: DiveUiState,
    onCameraReady: (ImageCapture, VideoCapture<Recorder>, Camera) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera is at the bottom (Z=0)
        CameraPreview(
            colorGains = uiState.colorGains,
            modifier = Modifier.fillMaxSize(),
            onCameraReady = onCameraReady
        )

        // HUD is on top (Z=10)
        CameraHudOverlay(
            uiState = uiState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)) // Stay below the status bar
                .padding(top = 4.dp)
        )
    }
}
