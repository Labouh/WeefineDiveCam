package org.zorro.weefinedivecam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.ImageProxy
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale

private const val CORRECTED_DIR = "Pictures/WeefineDiveCam"
private const val ORIGINALS_DIR = "Pictures/WeefineDiveCam/Original"

/**
 * Saves a captured frame to the gallery:
 * - always the untouched original (camera JPEG bytes, no re-encoding) in [ORIGINALS_DIR],
 *   so the correction can be redone later with other settings;
 * - plus, when the depth filter is on and has something to correct, a full-resolution
 *   corrected copy in [CORRECTED_DIR].
 *
 * Dive data goes into the EXIF user comment as key=value pairs for later post-processing.
 * Call this from a background thread: it decodes and re-encodes a full-size JPEG.
 *
 * @return true if a corrected copy was saved alongside the original.
 */
fun saveDivePhoto(context: Context, image: ImageProxy, state: DiveUiState): Boolean {
    val gains = state.colorGains
    val saveCorrected = state.isDepthFilterEnabled && !gains.isIdentity

    val rotationDegrees = image.imageInfo.rotationDegrees
    val jpegBytes = if (image.format == ImageFormat.JPEG) {
        val buffer = image.planes[0].buffer
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } else {
        null
    }
    val bitmap = if (jpegBytes == null || saveCorrected) image.toBitmap() else null
    image.close()

    val now = System.currentTimeMillis()
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
    val diveInfo = String.format(
        Locale.US, "WeefineDiveCam depth=%.1fm temp=%.1fC water=%s",
        state.depth, state.temperature, state.waterType.name
    )

    // Pixels are stored as the sensor delivered them; the EXIF orientation makes viewers rotate them
    saveJpeg(
        context, "IMG_DIVE_$name", ORIGINALS_DIR, now,
        exif = mapOf(
            ExifInterface.TAG_ORIENTATION to exifOrientation(rotationDegrees),
            ExifInterface.TAG_USER_COMMENT to diveInfo
        )
    ) { out ->
        if (jpegBytes != null) out.write(jpegBytes) else bitmap!!.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }

    if (!saveCorrected) {
        bitmap?.recycle()
        return false
    }

    val corrected = rotateAndCorrect(bitmap!!, rotationDegrees, gains)
    bitmap.recycle()
    try {
        saveJpeg(
            context, "IMG_DIVE_${name}_corr", CORRECTED_DIR, now,
            exif = mapOf(
                ExifInterface.TAG_USER_COMMENT to String.format(
                    Locale.US, "%s gains=%.2f,%.2f,%.2f", diveInfo, gains.red, gains.green, gains.blue
                )
            )
        ) { out -> corrected.compress(Bitmap.CompressFormat.JPEG, 95, out) }
    } finally {
        corrected.recycle()
    }
    return true
}

/** Rotates upright and applies the gains in a single draw. */
private fun rotateAndCorrect(source: Bitmap, rotationDegrees: Int, gains: ColorGains): Bitmap {
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
    matrix.mapRect(bounds)
    matrix.postTranslate(-bounds.left, -bounds.top)

    val result = Bitmap.createBitmap(bounds.width().toInt(), bounds.height().toInt(), Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(gains.red, gains.green, gains.blue, 1f) })
    }
    Canvas(result).drawBitmap(source, matrix, paint)
    return result
}

private fun exifOrientation(rotationDegrees: Int): String = when (rotationDegrees) {
    90 -> ExifInterface.ORIENTATION_ROTATE_90
    180 -> ExifInterface.ORIENTATION_ROTATE_180
    270 -> ExifInterface.ORIENTATION_ROTATE_270
    else -> ExifInterface.ORIENTATION_NORMAL
}.toString()

private fun saveJpeg(
    context: Context,
    displayName: String,
    relativePath: String,
    takenAt: Long,
    exif: Map<String, String>,
    write: (OutputStream) -> Unit
): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
        put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
        "MediaStore insert failed"
    }

    try {
        checkNotNull(resolver.openOutputStream(uri)).use(write)

        checkNotNull(resolver.openFileDescriptor(uri, "rw")).use { pfd ->
            ExifInterface(pfd.fileDescriptor).apply {
                setAttribute(
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(takenAt)
                )
                exif.forEach { (tag, value) -> setAttribute(tag, value) }
                saveAttributes()
            }
        }

        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}
