package org.zorro.weefinedivecam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.ImageProxy
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Saves a captured frame to the gallery after applying the depth colour correction at
 * full sensor resolution. Depth and water temperature go into the EXIF user comment.
 *
 * Call this from a background thread: it decodes and re-encodes a full-size JPEG.
 */
fun saveCorrectedPhoto(
    context: Context,
    image: ImageProxy,
    gains: ColorGains,
    depthMeters: Double,
    temperatureCelsius: Double
): Uri {
    val source = image.toBitmap()
    val rotationDegrees = image.imageInfo.rotationDegrees
    image.close()

    // Rotate upright and apply the gains in a single draw.
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
    matrix.mapRect(bounds)
    matrix.postTranslate(-bounds.left, -bounds.top)

    val corrected = Bitmap.createBitmap(bounds.width().toInt(), bounds.height().toInt(), Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (!gains.isIdentity) {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(gains.red, gains.green, gains.blue, 1f) })
        }
    }
    Canvas(corrected).drawBitmap(source, matrix, paint)
    source.recycle()

    val now = System.currentTimeMillis()
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_DIVE_$name")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WeefineDiveCam")
        put(MediaStore.Images.Media.DATE_TAKEN, now)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
        "MediaStore insert failed"
    }

    try {
        checkNotNull(resolver.openOutputStream(uri)).use { out ->
            corrected.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        corrected.recycle()

        checkNotNull(resolver.openFileDescriptor(uri, "rw")).use { pfd ->
            ExifInterface(pfd.fileDescriptor).apply {
                setAttribute(
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now)
                )
                setAttribute(
                    ExifInterface.TAG_USER_COMMENT,
                    String.format(Locale.US, "Depth %.1f m, water %.1f C", depthMeters, temperatureCelsius)
                )
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
