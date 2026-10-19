package org.zorro.weefinedivecam

import android.graphics.Bitmap
import java.util.Locale
import kotlin.math.max

/**
 * Image-based colour correction for underwater photos, in the spirit of Dive+.
 *
 * Depth gains alone cannot fix deep shots: past ~20 m the sensor's red channel is close to
 * zero, and multiplying zero by any gain leaves it at zero while the green gain turns the
 * image cyan-green. So the correction is derived from the picture itself:
 * 1. red compensation (Ancuti et al., "Color Balance and Fusion for Underwater Image
 *    Enhancement", 2018): green/blue energy is transferred to red where red is weak;
 * 2. grey world: channel means are equalised to neutralise the remaining cast;
 * 3. levels: each channel is stretched between low/high percentiles to remove the haze.
 *
 * Works on gamma-encoded (sRGB) pixels.
 */
class UnderwaterAutoCorrection private constructor(
    /** Red compensation strength, (mean(ref) - mean(R)) / mean(ref). */
    private val redTransfer: Float,
    /** Per-channel out = in * scale + offset, combining grey world and levels. */
    private val scale: FloatArray,
    private val offset: FloatArray
) {

    /** Corrects [bitmap] (must be mutable ARGB_8888) in place, a band of rows at a time. */
    fun applyInPlace(bitmap: Bitmap) {
        val width = bitmap.width
        val rows = max(1, BAND_PIXELS / width)
        val pixels = IntArray(width * rows)
        var y = 0
        while (y < bitmap.height) {
            val h = minOf(rows, bitmap.height - y)
            bitmap.getPixels(pixels, 0, width, 0, y, width, h)
            for (i in 0 until width * h) pixels[i] = correctPixel(pixels[i])
            bitmap.setPixels(pixels, 0, width, 0, y, width, h)
            y += h
        }
    }

    private fun correctPixel(argb: Int): Int {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val compensatedRed = compensateRed(r, g, b)
        return (argb and 0xFF000000.toInt()) or
            (toByte(compensatedRed * scale[0] + offset[0]) shl 16) or
            (toByte(g * scale[1] + offset[1]) shl 8) or
            toByte(b * scale[2] + offset[2])
    }

    private fun compensateRed(r: Float, g: Float, b: Float): Float =
        r + redTransfer * (1 - r) * 0.5f * (g + b)

    override fun toString(): String = String.format(
        Locale.US, "auto red=%.2f scale=%.2f,%.2f,%.2f offset=%.2f,%.2f,%.2f",
        redTransfer, scale[0], scale[1], scale[2], offset[0], offset[1], offset[2]
    )

    companion object {
        private const val BAND_PIXELS = 1 shl 20
        private const val ANALYSIS_WIDTH = 320

        private const val LOW_PERCENTILE = 0.005
        private const val HIGH_PERCENTILE = 0.995

        // Keeps noisy, nearly flat channels from being blown up into posterised garbage.
        private const val MAX_SCALE = 6f

        /** Measures the image statistics on a downscaled copy of [source]. */
        fun analyze(source: Bitmap): UnderwaterAutoCorrection {
            val width = minOf(ANALYSIS_WIDTH, source.width)
            val height = max(1, source.height * width / source.width)
            val small = Bitmap.createScaledBitmap(source, width, height, true)
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            if (small !== source) small.recycle()

            val n = pixels.size
            val red = FloatArray(n)
            val green = FloatArray(n)
            val blue = FloatArray(n)
            for (i in 0 until n) {
                red[i] = (pixels[i] shr 16 and 0xFF) / 255f
                green[i] = (pixels[i] shr 8 and 0xFF) / 255f
                blue[i] = (pixels[i] and 0xFF) / 255f
            }

            val meanRed = red.average().toFloat()
            val meanRef = 0.5f * (green.average().toFloat() + blue.average().toFloat())
            val redTransfer = if (meanRef > 1e-3f) ((meanRef - meanRed) / meanRef).coerceIn(0f, 1f) else 0f
            val probe = UnderwaterAutoCorrection(redTransfer, floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 0f, 0f))
            for (i in 0 until n) red[i] = probe.compensateRed(red[i], green[i], blue[i])

            // Grey world then levels collapse into one affine map per channel: the grey-world
            // gain cancels out in the percentile stretch, so stretching the compensated channel
            // directly gives the same result.
            val channels = arrayOf(red, green, blue)
            val scale = FloatArray(3)
            val offset = FloatArray(3)
            for (c in 0..2) {
                val values = channels[c].sortedArray()
                val low = values[((n - 1) * LOW_PERCENTILE).toInt()]
                val high = values[((n - 1) * HIGH_PERCENTILE).toInt()]
                scale[c] = (1f / max(high - low, 1e-3f)).coerceAtMost(MAX_SCALE)
                offset[c] = -low * scale[c]
            }
            return UnderwaterAutoCorrection(redTransfer, scale, offset)
        }

        private fun toByte(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}
