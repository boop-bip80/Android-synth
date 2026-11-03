package com.orbital.synth.ui

/**
 * Strictly UI-side response to *actual* AudioTrack PCM metering.
 *
 * [brightness] is a normalized waveform-slope proxy supplied by AudioVisualTelemetry,
 * not an FFT spectral centroid. All values are sanitized before reaching Canvas.
 * With absent telemetry the boosts are exactly zero, preserving the original
 * control-driven rendering and older callers byte-for-byte at the input boundary.
 */
internal data class AudioVisualResponse(
    val rms: Float,
    val peak: Float,
    val transient: Float,
    val brightness: Float,
    val energyBoost: Float,
    val radiusBoost: Float,
    val filamentBoost: Float,
) {
    companion object {
        fun from(
            rms: Float,
            peak: Float,
            transient: Float,
            brightness: Float,
        ): AudioVisualResponse {
            fun safe(value: Float): Float =
                if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

            val r = safe(rms)
            val p = safe(peak)
            val hit = safe(transient)
            val bright = safe(brightness)
            return AudioVisualResponse(
                rms = r,
                peak = p,
                transient = hit,
                brightness = bright,
                energyBoost = .13f * r + .09f * p,
                radiusBoost = .08f * r + .035f * p,
                filamentBoost = .032f * r + .020f * bright,
            )
        }
    }

    /** Stop visual animation entirely when the surface is untouched and silent. */
    fun shouldAnimate(touchIntensity: Float, animatorsEnabled: Boolean): Boolean =
        animatorsEnabled && (
            (touchIntensity.isFinite() && touchIntensity > .01f) ||
                rms > .015f || peak > .025f || transient > .03f
        )
}
