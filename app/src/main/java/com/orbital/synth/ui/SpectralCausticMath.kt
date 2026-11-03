package com.orbital.synth.ui

import kotlin.math.PI
import kotlin.math.sin

/**
 * Three physically bounded, intentionally abstract caustic ribbons.
 *
 * The visual inputs are *broad relative energy fractions* from real PCM that
 * Android AudioTrack accepted. This does NOT recreate waveform samples, an FFT,
 * calibrated spectrum or audio-thread processing.
 *
 * All draw calls receive finite primitive values. No List/Path/array or other
 * object is created by these functions on each rendered point.
 */
internal object SpectralCausticMath {
    const val LANE_COUNT = 3
    const val POINTS = 48

    /** The host's RMS gate already removes subaudible residual crossover memory. */
    fun glowStrength(relativeEnergy: Float): Float =
        (unit(relativeEnergy, 0f) * 2f).coerceIn(0f, 1f)

    /** Warm bass = thicker glass, midrange = violet braid, treble = fine filament. */
    fun halfWidth(lane: Int, relativeEnergy: Float): Float {
        val e = unit(relativeEnergy, 0f)
        return when (lane) {
            0 -> .030f + e * .018f
            1 -> .018f + e * .014f
            2 -> .010f + e * .010f
            else -> 0f
        }
    }

    /**
     * Radius is expressed relative to the drawn nuclear radius. Frequencies
     * differ BY LANE; moving pressure or PCM changes amplitude and phase but
     * does not silently create a new synth oscillator or modulation route.
     */
    fun radialScale(
        lane: Int,
        angle: Float,
        phase: Float,
        morph: Float,
        movement: Float,
        band: Float,
    ): Float {
        if (lane !in 0 until LANE_COUNT) return 0f
        val a = if (angle.isFinite()) angle else 0f
        val p = unit(phase, 0f)
        val m = unit(morph, .5f)
        val v = unit(movement, .5f)
        val e = unit(band, 0f)
        val turn = (2.0 * PI).toFloat()
        val oscillations = when (lane) {
            0 -> 2.45f
            1 -> 5.15f
            else -> 8.35f
        }
        val main = sin(
            a * (oscillations + m * .85f) +
                p * turn * (.31f + lane * .12f) + v * 2.7f,
        )
        val flank = sin(
            a * (oscillations * 1.79f - v * .48f) -
                p * turn * (.18f + lane * .09f) + m * 1.8f,
        )
        return (.57f + lane * .145f +
            (main * .073f + flank * .021f) * (.44f + e * 1.07f) +
            (m - .5f) * .022f).coerceIn(.39f, 1.20f)
    }

    private fun unit(value: Float, fallback: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
}
