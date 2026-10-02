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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

@Composable
fun CameraPreview(
    colorGains: ColorGains,
    modifier: Modifier = Modifier,
    onCameraReady: (ImageCapture, VideoCapture<Recorder>, Camera) -> Unit // Callback to share the capture controllers
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // GPU colour correction shared by the preview and video streams
    val colorProcessor = remember { UnderwaterColorProcessor() }
    DisposableEffect(colorProcessor) {
        onDispose { colorProcessor.release() }
    }
    SideEffect { colorProcessor.gains = colorGains }

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

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Photos bypass the GPU effect: they are corrected at full resolution in saveCorrectedPhoto()
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
        modifier = modifier
    )
}

/**
 * The depth filter models the colour loss against daylight, so auto white balance must be
 * off while it is active; otherwise both try to cancel the blue cast.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun applyWhiteBalance(camera: Camera, daylight: Boolean) {
    val awbMode = if (daylight) CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT else CaptureRequest.CONTROL_AWB_MODE_AUTO
    Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
        CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
            .build()
    )
}
