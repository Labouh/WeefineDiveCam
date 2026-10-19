package org.zorro.weefinedivecam

import android.graphics.Bitmap
import java.util.Locale
import kotlin.math.max

/**
 * Image-based colour correction for underwater pictures, fitted on Dive+ results.
 *
 * Comparing originals with their Dive+ versions shows a global affine colour map per image:
 * green and blue are stretched between their low/high percentiles, and red, which the water
 * has removed below ~10 m, is rebuilt as G - B/2. That estimate is high where green dominates
 * (rocks, sponges, sand: they turn warm again) and close to zero where blue dominates, so open
 * water stays blue instead of being neutralised into magenta by a grey-world balance.
 * Where the sensor still sees real red (shallow water, close subjects) it is kept.
 *
 * Photos are measured and corrected at full resolution on the CPU; the preview and video
 * apply the same map on the GPU (see [UnderwaterColorProcessor]).
 * Works on gamma-encoded (sRGB) pixels.
 */
class UnderwaterAutoCorrection private constructor(
    /** Per-channel out = in * scale + offset, red being applied to the rebuilt red. */
    val scale: FloatArray,
    val offset: FloatArray
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

    /** Moves [weight] of the way towards [target], so a live stream changes colour smoothly. */
    fun blendTowards(target: UnderwaterAutoCorrection, weight: Float) = UnderwaterAutoCorrection(
        FloatArray(3) { scale[it] + (target.scale[it] - scale[it]) * weight },
        FloatArray(3) { offset[it] + (target.offset[it] - offset[it]) * weight }
    )

    private fun correctPixel(argb: Int): Int {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return (argb and 0xFF000000.toInt()) or
            (toByte(rebuildRed(r, g, b) * scale[0] + offset[0]) shl 16) or
            (toByte(g * scale[1] + offset[1]) shl 8) or
            toByte(b * scale[2] + offset[2])
    }

    override fun toString(): String = String.format(
        Locale.US, "auto scale=%.2f,%.2f,%.2f offset=%.2f,%.2f,%.2f",
        scale[0], scale[1], scale[2], offset[0], offset[1], offset[2]
    )

    companion object {
        /** Below this depth the housing is treated as being at the surface or out of the water. */
        const val MIN_DEPTH_M = 0.3

        // Fitted on 24 Dive+ pairs from 15 to 45 m (mean error 2.7 %)
        const val BLUE_IN_RED = 0.5f
        private const val CLIP_PERCENTILE = 0.003

        // Smallest input range stretched to full scale. Keeps flat channels (open blue water)
        // from being blown up into noise; red, being rebuilt, gets a gentler stretch.
        private const val MIN_RANGE_RED = 0.3f
        private const val MIN_RANGE_GREEN_BLUE = 0.2f

        private const val BAND_PIXELS = 1 shl 20
        private const val ANALYSIS_WIDTH = 320

        private fun rebuildRed(r: Float, g: Float, b: Float): Float = max(r, g - BLUE_IN_RED * b)

        /** Measures the image statistics on a downscaled copy of [source]. */
        fun analyze(source: Bitmap): UnderwaterAutoCorrection {
            val width = minOf(ANALYSIS_WIDTH, source.width)
            val height = max(1, source.height * width / source.width)
            val small = Bitmap.createScaledBitmap(source, width, height, true)
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            if (small !== source) small.recycle()
            return analyze(pixels)
        }

        /** Measures the image statistics on ARGB [pixels], typically a few thousand of them. */
        fun analyze(pixels: IntArray): UnderwaterAutoCorrection {
            val n = pixels.size
            val red = FloatArray(n)
            val green = FloatArray(n)
            val blue = FloatArray(n)
            for (i in 0 until n) {
                val g = (pixels[i] shr 8 and 0xFF) / 255f
                val b = (pixels[i] and 0xFF) / 255f
                red[i] = rebuildRed((pixels[i] shr 16 and 0xFF) / 255f, g, b)
                green[i] = g
                blue[i] = b
            }

            val channels = arrayOf(red, green, blue)
            val scale = FloatArray(3)
            val offset = FloatArray(3)
            for (c in 0..2) {
                val values = channels[c].sortedArray()
                val low = values[((n - 1) * CLIP_PERCENTILE).toInt()]
                val high = values[((n - 1) * (1 - CLIP_PERCENTILE)).toInt()]
                scale[c] = 1f / max(high - low, if (c == 0) MIN_RANGE_RED else MIN_RANGE_GREEN_BLUE)
                offset[c] = -low * scale[c]
            }
            return UnderwaterAutoCorrection(scale, offset)
        }

        private fun toByte(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}
