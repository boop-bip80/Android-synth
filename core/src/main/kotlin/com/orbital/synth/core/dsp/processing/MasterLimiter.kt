package com.orbital.synth.core.dsp.processing

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * The last stage before the speakers: a stereo-linked, zero-latency peak limiter.
 *
 * Dense chords through a user's effects chain (saturation, delay feedback, resonant filters)
 * can exceed full scale, and the converter would then hard-clip them into harsh distortion.
 * This limiter makes the output ceiling absolute: gain drops instantly the moment a sample
 * would pass [ceiling], then recovers smoothly over [releaseSeconds], so loud passages stay
 * clean instead of cracking.
 *
 * Properties the tests rely on:
 *  * Transparent: while the signal stays at or below the ceiling and the gain is at unity,
 *    the output is bit-identical to the input (multiplication by exactly 1f).
 *  * Absolute: no output sample ever exceeds [ceiling].
 *  * Linked: both channels share one gain, so a loud left channel never skews the stereo image.
 *  * Safe: non-finite input becomes silence, and the gain state can never become non-finite.
 *
 * Allocation-free; call only from the audio worker.
 */
class MasterLimiter(
    sampleRate: Int,
    val ceiling: Float = 0.985f,
    releaseSeconds: Float = 0.14f,
) {
    init {
        require(sampleRate in 8_000..192_000)
        require(ceiling in 0.1f..1f)
        require(releaseSeconds in 0.005f..2f)
    }

    private val releaseCoefficient = 1.0 - exp(-1.0 / (sampleRate * releaseSeconds))

    // Double precision on purpose: near unity a float increment is smaller than half an ULP and
    // would be rounded away, stalling recovery just short of 1.0 and leaving quiet audio
    // permanently (if inaudibly) attenuated instead of bit-exact.
    private var gain = 1.0

    /** Current gain reduction as a linear multiplier (1 = none). For metering and tests. */
    val currentGain: Float get() = gain.toFloat()

    fun reset() { gain = 1.0 }

    /** Limit one interleaved stereo frame in place at [offset]. */
    fun process(buffer: FloatArray, offset: Int) {
        var left = buffer[offset]
        var right = buffer[offset + 1]
        if (!left.isFinite()) left = 0f
        if (!right.isFinite()) right = 0f
        val peak = max(abs(left), abs(right))
        if (peak > ceiling) {
            // Instant attack: take exactly the gain that lands this frame on the ceiling.
            val needed = ceiling.toDouble() / peak
            if (needed < gain) gain = needed
        } else if (gain < 1.0) {
            gain += (1.0 - gain) * releaseCoefficient
            if (gain > 0.99999) gain = 1.0
        }
        if (gain == 1.0) {
            // Transparent: untouched audio stays bit-identical (|x| <= ceiling is guaranteed here
            // only when the frame was below it; clamp covers the attack frame's rounding).
            buffer[offset] = left.coerceIn(-ceiling, ceiling)
            buffer[offset + 1] = right.coerceIn(-ceiling, ceiling)
        } else {
            buffer[offset] = (left * gain).toFloat().coerceIn(-ceiling, ceiling)
            buffer[offset + 1] = (right * gain).toFloat().coerceIn(-ceiling, ceiling)
        }
    }
}
