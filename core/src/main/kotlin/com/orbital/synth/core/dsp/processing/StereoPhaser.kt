package com.orbital.synth.core.dsp.processing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.min

/**
 * Four-stage stereo allpass phaser. Coefficients come from a 257-entry
 * log-frequency table built once during construction; the audio callback
 * performs no trigonometry, exp/log, allocations, or locking.
 *
 * The parameter [depth] controls both sweep span and bounded feedback. A
 * 50/50 allpass/dry internal mix creates actual moving spectral notches even
 * when the enclosing modular rack requests 100% wet phaser output.
 *
 * Each channel has independent state and the right modulation phase may be
 * offset by 0..180 degrees. Effects and modulation intentionally create
 * sidebands; this is a creative audio processor, not an alias-free resampler.
 */
class StereoPhaser(val sampleRate: Int = 48_000) {
    init { require(sampleRate in 8_000..192_000) }

    private val stages = 4
    private val oldInput = Array(2) { FloatArray(stages) }
    private val oldOutput = Array(2) { FloatArray(stages) }
    private val feedbackOutput = FloatArray(2)
    private val coeff = FloatArray(257) { index ->
        val hi = min(5_600.0, sampleRate * 0.38)
        val hz = 80.0 * (hi / 80.0).pow(index / 256.0)
        val ratio = tan(PI * hz / sampleRate)
        ((ratio - 1.0) / (ratio + 1.0)).toFloat().coerceIn(-0.999f, 0.999f)
    }

    private var oscSin = 0.0
    private var oscCos = 1.0
    private var sinStep = 0.0
    private var cosStep = 1.0
    private var sinOffset = 0.0
    private var cosOffset = 1.0
    private var renormIn = 4_096

    fun configure(rateHz: Float, stereoPhase: Float = 0.5f) {
        val hz = safe(rateHz, 0.4f).coerceIn(0.02f, 12f).toDouble()
        val phase = safe(stereoPhase, 0.5f).coerceIn(0f, 1f) * PI
        val step = 2.0 * PI * hz / sampleRate
        sinStep = sin(step)
        cosStep = cos(step)
        sinOffset = sin(phase)
        cosOffset = cos(phase)
    }

    /**
     * [depth] may change every frame and should be smoothed by the host.
     * Buffer arguments may alias the caller's input; scalars are consumed
     * before writing either output channel.
     */
    fun process(
        left: Float, right: Float, depth: Float,
        output: FloatArray, offset: Int = 0,
    ) {
        require(offset >= 0 && offset + 1 < output.size)
        val dryL = sanitize(left)
        val dryR = sanitize(right)
        val d = safe(depth, 0f).coerceIn(0f, 1f)
        val aL = coefficient(oscSin, d)
        val aR = coefficient(oscSin * cosOffset + oscCos * sinOffset, d)
        val resonance = d * 0.68f

        val wetL = channel(0, dryL, aL, resonance)
        val wetR = channel(1, dryR, aR, resonance)
        output[offset] = ((dryL + wetL) * 0.5f).coerceIn(-8f, 8f)
        output[offset + 1] = ((dryR + wetR) * 0.5f).coerceIn(-8f, 8f)

        val nextSin = oscSin * cosStep + oscCos * sinStep
        oscCos = oscCos * cosStep - oscSin * sinStep
        oscSin = nextSin
        renormIn--
        if (renormIn == 0) {
            val norm = 1.0 / sqrt(oscSin * oscSin + oscCos * oscCos)
            oscSin *= norm
            oscCos *= norm
            renormIn = 4_096
        }
    }

    private fun coefficient(sinPhase: Double, depth: Float): Float {
        val normalized = (0.5 + sinPhase * depth * 0.49).coerceIn(0.01, 0.99)
        val position = normalized * 256.0
        val index = position.toInt().coerceIn(0, 255)
        val fraction = (position - index).toFloat()
        return coeff[index] + (coeff[index + 1] - coeff[index]) * fraction
    }

    private fun channel(channel: Int, dry: Float, a: Float, resonance: Float): Float {
        var sample = (dry + feedbackOutput[channel] * resonance).coerceIn(-4f, 4f)
        val x = oldInput[channel]
        val y = oldOutput[channel]
        for (stage in 0 until stages) {
            val processed = (a * sample + x[stage] - a * y[stage]).coerceIn(-8f, 8f)
            x[stage] = sample
            y[stage] = if (abs(processed) < 1e-18f) 0f else processed
            sample = y[stage]
        }
        feedbackOutput[channel] = sample
        return sample
    }

    fun resetForBypass() {
        for (channel in 0..1) {
            oldInput[channel].fill(0f)
            oldOutput[channel].fill(0f)
            feedbackOutput[channel] = 0f
        }
        oscSin = 0.0
        oscCos = 1.0
        renormIn = 4_096
    }

    private fun safe(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    private fun sanitize(value: Float): Float =
        (if (value.isFinite()) value else 0f).coerceIn(-8f, 8f)
}
