package org.zorro.weefinedivecam

import android.Manifest
import android.content.ContentValues
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainPermissionChecker(onPermissionsGranted: @Composable () -> Unit) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    )

    if (permissionsState.allPermissionsGranted) {
        onPermissionsGranted()
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Camera & Bluetooth permissions are required.",
                    color = Color.White
                )
                Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: DiveViewModel by viewModels()

    // Full-resolution photo correction runs here, one shot at a time
    private val photoExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    MainPermissionChecker {
                        var activeImageCapture by remember { mutableStateOf<ImageCapture?>(null) }
                        var activeVideoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
                        var activeCamera by remember { mutableStateOf<Camera?>(null) }
                        var currentRecording by remember { mutableStateOf<Recording?>(null) }

                        LaunchedEffect(Unit) {
                            viewModel.cameraActionEvent.collect { event ->
                                when (event) {
                                    "TAKE_PHOTO" -> activeImageCapture?.let { capturePhoto(it) }
                                    "START_RECORDING" -> activeVideoCapture?.let { currentRecording = startVideoRecording(it) }
                                    "STOP_RECORDING" -> {
                                        currentRecording?.stop()
                                        currentRecording = null
                                    }
                                    "ZOOM_IN" -> activeCamera?.let { cam ->
                                        cam.cameraInfo.zoomState.value?.let { zoom ->
                                            cam.cameraControl.setLinearZoom((zoom.linearZoom + 0.1f).coerceAtMost(1.0f))
                                        }
                                    }
                                    "ZOOM_OUT" -> activeCamera?.let { cam ->
                                        cam.cameraInfo.zoomState.value?.let { zoom ->
                                            cam.cameraControl.setLinearZoom((zoom.linearZoom - 0.1f).coerceAtLeast(0.0f))
                                        }
                                    }
                                }
                            }
                        }

                        LaunchedEffect(activeCamera, uiState.isDepthFilterEnabled) {
                            activeCamera?.let { applyWhiteBalance(it, daylight = uiState.isDepthFilterEnabled) }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            // Camera Layer
                            DiveCameraScreen(
                                uiState = uiState,
                                onCameraReady = { boundCapture, boundVideo, boundCamera ->
                                    activeImageCapture = boundCapture
                                    activeVideoCapture = boundVideo
                                    activeCamera = boundCamera
                                }
                            )

                            // Connection Layer (on top). Only until a housing is chosen: a BLE dropout
                            // mid-dive must not cover the camera, the manager reconnects on its own.
                            if (uiState.housingAddress == null) {
                                ScanWindow(
                                    discoveredDevices = uiState.discoveredDevices,
                                    isScanning = uiState.isScanning,
                                    onStartScan = { viewModel.startScanning() },
                                    onStopScan = { viewModel.stopScanning() },
                                    onDeviceSelected = { viewModel.connectToDevice(it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun capturePhoto(imageCapture: ImageCapture) {
        // Snapshot the telemetry at shutter time, not when processing finishes
        val state = viewModel.uiState.value

        imageCapture.takePicture(
            photoExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val message = try {
                        saveCorrectedPhoto(this@MainActivity, image, state.colorGains, state.depth, state.temperature)
                        "Photo Saved!"
                    } catch (e: Exception) {
                        "Save failed: ${e.message}"
                    }
                    runOnUiThread { Toast.makeText(baseContext, message, Toast.LENGTH_SHORT).show() }
                }
                override fun onError(exc: ImageCaptureException) {
                    runOnUiThread { Toast.makeText(baseContext, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        )
    }

    private fun startVideoRecording(videoCapture: VideoCapture<Recorder>): Recording {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_DIVE_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WeefineDiveCam")
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        return videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                    PermissionChecker.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> Toast.makeText(baseContext, "Recording Started", Toast.LENGTH_SHORT).show()
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            Toast.makeText(baseContext, "Video Saved!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(baseContext, "Video Error: ${recordEvent.error}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        photoExecutor.shutdown() // Lets queued photos finish saving
    }
}
