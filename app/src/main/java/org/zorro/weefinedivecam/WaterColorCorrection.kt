package org.zorro.weefinedivecam

import kotlin.math.exp
import kotlin.math.pow

/** Per-channel multipliers applied to gamma-encoded (sRGB) pixel values. */
data class ColorGains(val red: Float, val green: Float, val blue: Float) {
    val isIdentity: Boolean get() = red == 1f && green == 1f && blue == 1f

    companion object {
        val IDENTITY = ColorGains(1f, 1f, 1f)
    }
}

/**
 * Depth-based colour correction for underwater images.
 *
 * Water absorbs long wavelengths first: red is mostly gone below ~5 m and orange/yellow
 * by ~15 m, leaving a blue-green cast. Light reaching the lens has travelled down from
 * the surface (depth) and then from the subject to the camera (distance), so each
 * channel is attenuated by exp(-k * path). The correction undoes this with per-channel
 * gains relative to blue (the least absorbed channel), so overall exposure stays close
 * to where auto-exposure put it.
 *
 * Gains are computed in linear light, then converted for gamma-encoded pixels: a
 * per-channel scale commutes with the power-law curve, (g * L)^(1/γ) = g^(1/γ) * L^(1/γ),
 * so the GPU shader and the photo ColorMatrix can skip linearising.
 *
 * This assumes the camera's white balance is fixed at daylight (see [applyWhiteBalance]).
 * Otherwise auto white balance also tries to cancel the blue cast and the image ends up
 * over-corrected.
 */
object WaterColorCorrection {
    // Effective attenuation (1/m) for the broadband red/green/blue camera channels in
    // clear coastal sea water. Pure water absorbs ~0.35/m at 650 nm, but the sensor's
    // red channel also picks up orange light, which travels further.
    private const val K_RED = 0.22
    private const val K_GREEN = 0.06
    private const val K_BLUE = 0.035

    // Stronger gains only amplify sensor noise: beyond ~15 m there is no red left to recover.
    private const val MAX_RED_GAIN = 4.0
    private const val MAX_GREEN_GAIN = 1.6

    /** Below this depth the housing is treated as being at the surface or out of the water. */
    private const val MIN_DEPTH_M = 0.3

    private const val GAMMA = 2.2

    /**
     * @param depthMeters depth reported by the housing.
     * @param subjectDistanceMeters typical camera-to-subject distance (horizontal water path).
     * @param strength 0 = no correction, 1 = full physical correction.
     */
    fun gains(
        depthMeters: Double,
        subjectDistanceMeters: Double = 1.0,
        strength: Double = 1.0
    ): ColorGains {
        if (depthMeters < MIN_DEPTH_M) return ColorGains.IDENTITY

        val path = depthMeters + subjectDistanceMeters
        val red = exp((K_RED - K_BLUE) * path).coerceAtMost(MAX_RED_GAIN)
        val green = exp((K_GREEN - K_BLUE) * path).coerceAtMost(MAX_GREEN_GAIN)

        return ColorGains(
            red = toEncoded(1 + (red - 1) * strength),
            green = toEncoded(1 + (green - 1) * strength),
            blue = 1f
        )
    }

    private fun toEncoded(linearGain: Double): Float = linearGain.pow(1 / GAMMA).toFloat()
}
