package org.zorro.weefinedivecam

import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.UseCaseGroup
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val FREEZE_DURATION = 1.seconds
private val FLASH_DURATION = 150.milliseconds

@Composable
fun CameraPreview(
    isColorCorrectionOn: Boolean,
    shutterCount: Int, // Incremented at each photo: flashes and freezes the frame as confirmation
    modifier: Modifier = Modifier,
    onCameraReady: (ImageCapture, VideoCapture<Recorder>, Camera) -> Unit // Callback to share the capture controllers
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // GPU colour correction shared by the preview and video streams
    val colorProcessor = remember { UnderwaterColorProcessor() }
    DisposableEffect(colorProcessor) {
        onDispose {
            // Stop the camera when the preview leaves the screen (deep sleep), not only on destroy
            if (cameraProviderFuture.isDone) cameraProviderFuture.get().unbindAll()
            colorProcessor.release()
        }
    }
    SideEffect { colorProcessor.isEnabled = isColorCorrectionOn }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView: needed for the shader output and for grabbing the frozen frame
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Shutter feedback: saving the corrected photo takes a second or two, so the "saved" toast
    // comes late. The frame on screen at the press stays frozen meanwhile, after a short flash.
    var frozenFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val flashAlpha = remember { Animatable(0f) }
    val shutterCountAtStart = remember { shutterCount } // No flash when waking up from deep sleep
    LaunchedEffect(shutterCount) {
        if (shutterCount == shutterCountAtStart) return@LaunchedEffect
        frozenFrame = previewView.bitmap?.asImageBitmap()
        launch {
            flashAlpha.snapTo(0.8f)
            flashAlpha.animateTo(0f, tween(FLASH_DURATION.inWholeMilliseconds.toInt()))
        }
        delay(FREEZE_DURATION)
        frozenFrame = null
    }

    // Create a stable reference to the ImageCapture use-case
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) // Fast capture for moving fish
            .build()
    }

    // Create a stable reference to the VideoCapture use-case
    val videoCapture = remember {
        val recorder = Recorder.Builder()
            .setExecutor(ContextCompat.getMainExecutor(context))
            .build()
        VideoCapture.withOutput(recorder)
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = {
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    // Photos bypass the GPU effect: they are corrected at full resolution in saveDivePhoto()
                    val useCases = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(imageCapture)
                        .addUseCase(videoCapture)
                        .addEffect(UnderwaterColorEffect(colorProcessor))
                        .build()

                    try {
                        cameraProvider.unbindAll()

                        // Bind preview, imageCapture, and videoCapture to the lifecycle together
                        val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCases)
                        onCameraReady(imageCapture, videoCapture, camera)
                    } catch (exc: Exception) {
                        Log.e("CameraPreview", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(context))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Same size as the view it was grabbed from, so it lines up exactly
        frozenFrame?.let { frame ->
            Image(bitmap = frame, contentDescription = "Photo taken", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().alpha(flashAlpha.value).background(Color.White))
    }
}

/**
 * Auto white balance stays off so it doesn't fight the colour correction over the blue cast,
 * which was fitted on daylight-balanced originals. It stays off even with the filter disabled
 * so the saved originals have consistent colours that post-processing can correct later.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun lockDaylightWhiteBalance(camera: Camera) {
    Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
        CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)
            .build()
    )
}
