package com.orbital.synth.core.dsp.processing

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.min

/**
 * Stereo sample-rate reduction and signed 4..16-bit quantization.
 *
 * Intentionally permits spectral aliasing: hard sample-hold and low bit depth
 * are the musical point of this effect, not an oversampled mastering limiter.
 * The held sample clock is measured in real Hz, not host-buffer frames, so
 * offline block sizes and 8..192 kHz host rates do not change timing.
 *
 * Controls: depth=crushing/bit depth, rate=250..20,000 Hz hold clock,
 * stereo=opposite-channel bit depth contrast (0.5 means linked bit depths).
 * All state is preallocated; process() uses only bounded primitive operations.
 */
class StereoBitcrusher(val sampleRate: Int = 48_000) {
    init { require(sampleRate in 8_000..192_000) }

    private var captureRateHz = 4_000.0
    private var stereoContrast = 0.5f
    private var phase = 1.0
    private var heldLeft = 0f
    private var heldRight = 0f

    fun configure(rateControl: Float, stereo: Float = 0.5f) {
        val rate = safe(rateControl, 0.5f).coerceIn(0f, 1f)
        val requestedHz = 250.0 * (20_000.0 / 250.0).pow(rate.toDouble())
        captureRateHz = min(requestedHz, sampleRate.toDouble())
        stereoContrast = safe(stereo, 0.5f).coerceIn(0f, 1f)
    }

    fun process(
        left: Float, right: Float, depth: Float,
        output: FloatArray, offset: Int = 0,
    ) {
        require(offset >= 0 && offset + 1 < output.size)
        val d = safe(depth, 0f).coerceIn(0f, 1f)
        // At stereo=0.5 both channels share the same bit depth. Otherwise
        // opposite +/-2-bit offsets widen the left/right quantization texture.
        val spread = (stereoContrast - 0.5f) * 4f
        val leftBits = (16f - d * (12f + spread)).roundToInt().coerceIn(4, 16)
        val rightBits = (16f - d * (12f - spread)).roundToInt().coerceIn(4, 16)
        if (phase >= 1.0) {
            heldLeft = quantize(left, leftBits)
            heldRight = quantize(right, rightBits)
            phase -= 1.0
        }
        phase += captureRateHz / sampleRate
        output[offset] = heldLeft
        output[offset + 1] = heldRight
    }

    fun resetForBypass() {
        phase = 1.0
        heldLeft = 0f
        heldRight = 0f
    }

    private fun quantize(value: Float, bits: Int): Float {
        val sample = (if (value.isFinite()) value else 0f).coerceIn(-1f, 1f)
        val scale = 1 shl (bits - 1)
        val scaleFloat = scale.toFloat()
        // Signed N-bit PCM has codes -2^(N-1)..2^(N-1)-1: +1.0 is
        // not representable, while -1.0 must remain exact.
        val code = (sample * scaleFloat).roundToInt().coerceIn(-scale, scale - 1)
        return code.toFloat() / scaleFloat
    }

    private fun safe(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback
}
