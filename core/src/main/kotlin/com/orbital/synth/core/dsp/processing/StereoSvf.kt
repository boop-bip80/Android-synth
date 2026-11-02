package com.orbital.synth.core.dsp.processing

import kotlin.math.PI
import kotlin.math.tan

/** Topology-preserving transform (zero-delay-feedback) state-variable filter. */
enum class FilterMode { LOW_PASS, BAND_PASS, HIGH_PASS }

/**
 * Two completely independent filter states, one for each stereo channel.
 *
 * Control changes recompute coefficients outside the sample loop. Instances are owned by
 * the audio thread; call setParameters from that thread, not concurrently with process.
 */
class StereoSvf(sampleRate: Int = 48_000) {
    init { require(sampleRate in 8_000..384_000) { "Unsupported sample rate" } }

    private val rate = sampleRate.toDouble()
    private var mode: FilterMode = FilterMode.LOW_PASS
    private var a1 = 0.0
    private var a2 = 0.0
    private var a3 = 0.0
    private var k = 1.0
    private var leftIc1 = 0.0
    private var leftIc2 = 0.0
    private var rightIc1 = 0.0
    private var rightIc2 = 0.0

    init { setParameters(1_200f, 0.15f) }

    /** resonance: 0 (gentle) .. 1 (high-Q); cutoff expressed in hertz. */
    fun setParameters(cutoffHz: Float, resonance: Float, mode: FilterMode = this.mode) {
        val cutoff = (if (cutoffHz.isFinite()) cutoffHz else 1_200f)
            .coerceIn(15f, (rate * 0.45).toFloat()).toDouble()
        val r = (if (resonance.isFinite()) resonance else 0.15f).coerceIn(0f, 1f)
        val q = 0.5 + 19.5 * r
        val nextK = 1.0 / q
        val g = tan(PI * cutoff / rate)
        val denominator = 1.0 + g * (g + nextK)
        k = nextK
        a1 = 1.0 / denominator
        a2 = g * a1
        a3 = g * a2
        this.mode = mode
    }

    fun reset() {
        leftIc1 = 0.0; leftIc2 = 0.0
        rightIc1 = 0.0; rightIc2 = 0.0
    }

    /** Process one left-channel sample. Allocation-free. */
    fun processLeft(input: Float): Float {
        val x = sanitize(input)
        val v3 = x - leftIc2
        val band = a1 * leftIc1 + a2 * v3
        val low = leftIc2 + a2 * leftIc1 + a3 * v3
        leftIc1 = boundState(2.0 * band - leftIc1)
        leftIc2 = boundState(2.0 * low - leftIc2)
        return output(x, band, low)
    }

    /** Process one right-channel sample. Never shares state with the left channel. */
    fun processRight(input: Float): Float {
        val x = sanitize(input)
        val v3 = x - rightIc2
        val band = a1 * rightIc1 + a2 * v3
        val low = rightIc2 + a2 * rightIc1 + a3 * v3
        rightIc1 = boundState(2.0 * band - rightIc1)
        rightIc2 = boundState(2.0 * low - rightIc2)
        return output(x, band, low)
    }

    private fun output(x: Double, band: Double, low: Double): Float {
        val sample = when (mode) {
            FilterMode.LOW_PASS -> low
            FilterMode.BAND_PASS -> band
            FilterMode.HIGH_PASS -> x - k * band - low
        }
        return (if (sample.isFinite()) sample else 0.0).coerceIn(-32.0, 32.0).toFloat()
    }

    private fun sanitize(input: Float) =
        (if (input.isFinite()) input else 0f).coerceIn(-16f, 16f).toDouble()

    private fun boundState(value: Double) =
        (if (value.isFinite()) value else 0.0).coerceIn(-64.0, 64.0)
}
