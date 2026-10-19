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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    // Optional: without the microphone videos are silent, without location photos aren't geotagged,
    // so they don't block the app. Asked separately so installs that already granted the others get the prompt.
    val optionalPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    if (permissionsState.allPermissionsGranted) {
        LaunchedEffect(Unit) {
            if (!optionalPermissions.allPermissionsGranted) optionalPermissions.launchMultiplePermissionRequest()
        }
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

        // Full screen: the white status/navigation bars would glow over the black sleep screen.
        // A swipe from the edge shows them briefly.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val phoneBattery = rememberPhoneBatteryLevel()

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    MainPermissionChecker {
                        var activeImageCapture by remember { mutableStateOf<ImageCapture?>(null) }
                        var activeVideoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
                        var activeCamera by remember { mutableStateOf<Camera?>(null) }
                        var currentRecording by remember { mutableStateOf<Recording?>(null) }
                        var shutterCount by remember { mutableIntStateOf(0) }

                        LaunchedEffect(Unit) {
                            viewModel.cameraActionEvent.collect { event ->
                                when (event) {
                                    "TAKE_PHOTO" -> activeImageCapture?.let {
                                        capturePhoto(it)
                                        shutterCount++
                                    }
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

                        // The unbound use cases are unusable once the camera stops; fresh ones arrive via onCameraReady
                        LaunchedEffect(uiState.isCameraOn) {
                            if (!uiState.isCameraOn) {
                                activeImageCapture = null
                                activeVideoCapture = null
                                activeCamera = null
                            }
                        }

                        LaunchedEffect(activeCamera) {
                            activeCamera?.let { lockDaylightWhiteBalance(it) }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            // Camera Layer
                            DiveCameraScreen(
                                uiState = uiState,
                                phoneBattery = phoneBattery,
                                shutterCount = shutterCount,
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

                            // Battery saver: black screen over everything
                            if (uiState.isHousingLost) {
                                SleepOverlay(
                                    message = "Housing connection lost",
                                    phoneBattery = phoneBattery,
                                    onTap = { viewModel.dismissHousingLost() }
                                )
                            } else if (uiState.sleepLevel != SleepLevel.AWAKE) {
                                SleepOverlay(
                                    message = String.format(Locale.getDefault(), "%.1f m   %.1f °C", uiState.depth, uiState.temperature),
                                    phoneBattery = phoneBattery,
                                    onTap = { viewModel.wakeUp() }
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
        val takenAt = System.currentTimeMillis()

        imageCapture.takePicture(
            photoExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val message = try {
                        val uris = saveDivePhoto(this@MainActivity, image, state)
                        viewModel.onPhotosSaved(uris, takenAt, state.depth)
                        if (uris.size > 1) "Photo saved (+ corrected)" else "Photo saved"
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
            .apply { viewModel.videoLocation?.let { setLocation(it) } }
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
