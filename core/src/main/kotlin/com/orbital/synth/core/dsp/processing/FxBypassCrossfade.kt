package com.orbital.synth.core.dsp.processing

import kotlin.math.roundToInt

/**
 * Allocation-free stereo crossfade between a strictly dry master bus and a fully
 * processed FX bus. One audio thread owns this instance, including [setEnabled].
 *
 * Each toggle creates a finite 10 ms linear ramp from the *current* wet fraction,
 * not a jump to the endpoint. Toggling back during a transition reverses smoothly.
 * Identical dry/wet samples retain exactly their original amplitude; no equal-power
 * boost is introduced. Input and output PCM are bounded to -1..1.
 *
 * IMPORTANT: While [requiresWetPath] is true, the caller must continue rendering
 * both the dry and wet paths even after disabling the effect. Only when that flag
 * becomes false may the caller reset/suspend wet processors. Their last configured
 * parameters must be retained for the fade-out; new zero-valued controls should
 * not overwrite the old wet path before the fade completes.
 */
class FxBypassCrossfade(sampleRate: Int = 48_000) {
    init { require(sampleRate in 8_000..384_000) }

    val transitionFrames: Int = (sampleRate * 0.010).roundToInt().coerceAtLeast(1)

    private var wetMix = 0f
    private var targetMix = 0f
    private var increment = 0f
    private var remainingFrames = 0

    /** Normalized current FX-bus contribution. Read from the audio thread only. */
    val wetFraction: Float get() = wetMix

    /** Includes a fade-out in progress, not just the enabled target state. */
    val requiresWetPath: Boolean get() = targetMix > 0f || wetMix > 0f

    /** True only when bypass has reached the exact dry endpoint. */
    val isFullyDry: Boolean get() = remainingFrames == 0 && wetMix == 0f

    /** Change the target at a render-block boundary; repeated calls do not restart ramps. */
    fun setEnabled(enabled: Boolean) {
        val next = if (enabled) 1f else 0f
        if (next == targetMix) return
        targetMix = next
        if (wetMix == targetMix) {
            increment = 0f
            remainingFrames = 0
        } else {
            remainingFrames = transitionFrames
            increment = (targetMix - wetMix) / transitionFrames
        }
    }

    /**
     * Write one interleaved stereo output frame. May alias an input buffer: source
     * values are copied to local scalars before the output is modified.
     */
    fun process(
        dryLeft: Float,
        dryRight: Float,
        wetLeft: Float,
        wetRight: Float,
        output: FloatArray,
        offset: Int
    ) {
        require(offset >= 0 && offset < output.size - 1)
        if (remainingFrames > 0) {
            wetMix = (wetMix + increment).coerceIn(0f, 1f)
            remainingFrames--
            if (remainingFrames == 0) wetMix = targetMix
        }
        val dryL = sanitize(dryLeft)
        val dryR = sanitize(dryRight)
        // Avoid NaN * 0 corrupting an exact bypass path.
        if (wetMix == 0f) {
            output[offset] = dryL
            output[offset + 1] = dryR
            return
        }
        val wetL = sanitize(wetLeft)
        val wetR = sanitize(wetRight)
        if (wetMix == 1f) {
            output[offset] = wetL
            output[offset + 1] = wetR
            return
        }
        val dryFraction = 1f - wetMix
        output[offset] = (dryL * dryFraction + wetL * wetMix).coerceIn(-1f, 1f)
        output[offset + 1] = (dryR * dryFraction + wetR * wetMix).coerceIn(-1f, 1f)
    }

    /** Hard reset to an exact endpoint. The default and initial state are fully dry. */
    fun reset(initiallyEnabled: Boolean = false) {
        wetMix = if (initiallyEnabled) 1f else 0f
        targetMix = wetMix
        increment = 0f
        remainingFrames = 0
    }

    private fun sanitize(sample: Float): Float =
        (if (sample.isFinite()) sample else 0f).coerceIn(-1f, 1f)
}
