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

    // Explicitly using a solid background and high Z-index for testing
    Box(
        modifier = modifier
            .zIndex(10f)
            .padding(16.dp)
            .background(Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- Left: Depth & Temp ---
            Column {
                if (uiState.diveStartTime != null) {
                    val elapsedSeconds = (currentTime - uiState.diveStartTime) / 1000
                    val minutes = elapsedSeconds / 60
                    val seconds = elapsedSeconds % 60
                    Text(
                        text = String.format("%02d:%02d", minutes, seconds),
                        color = Color.Cyan,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "${String.format("%.1f", uiState.depth)} m",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // --- Middle: Mode ---
            Icon(
                imageVector = if (uiState.cameraMode == CameraMode.PHOTO) Icons.Default.CameraAlt else Icons.Default.Videocam,
                contentDescription = "Mode",
                tint = if (uiState.isRecording) Color.Red else Color.White,
                modifier = Modifier.size(32.dp)
            )

            // --- Right: Battery ---
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${uiState.housingBattery}%",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "BATT",
                    color = batteryColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
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
            modifier = Modifier.fillMaxSize(),
            onCameraReady = onCameraReady
        )

        // HUD is on top (Z=10)
        CameraHudOverlay(
            uiState = uiState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp) // Move down significantly for housing
                .fillMaxWidth(0.9f)
        )
    }
}
