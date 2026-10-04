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
 * Effective attenuation (1/m) of the broadband red/green/blue camera channels.
 * Starting points to be tuned against real photos, not measurements.
 */
enum class WaterType(val label: String, val kRed: Double, val kGreen: Double, val kBlue: Double) {
    // Blue tropical/Mediterranean water: blue travels furthest, red goes first.
    // Pure water absorbs ~0.35/m at 650 nm, but the sensor's red channel also picks up orange.
    CLEAR_OCEAN("Ocean", 0.22, 0.06, 0.035),

    // Plankton and particles also absorb blue: green travels furthest, cast turns blue-green.
    COASTAL("Coastal", 0.30, 0.09, 0.12),

    // Dissolved organic matter absorbs blue strongly: green cast.
    GREEN_LAKE("Lake", 0.40, 0.15, 0.35)
}

/**
 * Depth-based colour correction for underwater images.
 *
 * Water absorbs each wavelength at its own rate. Light reaching the lens has travelled down
 * from the surface (depth) and then from the subject to the camera (distance), so each
 * channel is attenuated by exp(-k * path) (Beer-Lambert). The correction undoes this with
 * per-channel gains relative to the least absorbed channel, so overall exposure stays close
 * to where auto-exposure put it.
 *
 * Gains are computed in linear light, then converted for gamma-encoded pixels: a
 * per-channel scale commutes with the power-law curve, (g * L)^(1/γ) = g^(1/γ) * L^(1/γ),
 * so the GPU shader and the photo ColorMatrix can skip linearising.
 *
 * This assumes the camera's white balance is fixed at daylight (see [lockDaylightWhiteBalance]).
 * Otherwise auto white balance also tries to cancel the cast and the image ends up over-corrected.
 */
object WaterColorCorrection {
    // Stronger gains only amplify sensor noise: past this there is no signal left to recover.
    private const val MAX_GAIN = 4.0

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
        waterType: WaterType,
        subjectDistanceMeters: Double = 1.0,
        strength: Double = 1.0
    ): ColorGains {
        if (depthMeters < MIN_DEPTH_M) return ColorGains.IDENTITY

        val path = depthMeters + subjectDistanceMeters
        val kReference = minOf(waterType.kRed, waterType.kGreen, waterType.kBlue)

        fun channelGain(k: Double): Float {
            val linear = exp((k - kReference) * path).coerceAtMost(MAX_GAIN)
            return toEncoded(1 + (linear - 1) * strength)
        }

        return ColorGains(
            red = channelGain(waterType.kRed),
            green = channelGain(waterType.kGreen),
            blue = channelGain(waterType.kBlue)
        )
    }

    private fun toEncoded(linearGain: Double): Float = linearGain.pow(1 / GAMMA).toFloat()
}
