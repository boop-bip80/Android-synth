package com.orbital.synth.core.dsp.analog

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Dual-integrator, 12 dB/oct, voltage-controlled state-variable model.
 *
 * This is an Oberheim SEM-inspired topology rather than a measured hardware
 * clone. Its continuously variable mode is low-pass (shape=0), notch (0.5),
 * high-pass (1); state-variable band-pass is also available via bandPassOutput() after process().
 *
 * Cutoff is in Hz, resonance/drive/shape are normalized 0..1. One instance is
 * required per independent channel or synth voice. All functions are intended
 * for the audio render thread. configure() computes TPT coefficients once per
 * block; process() and bandPassOutput() are allocation-free. All coefficient,
 * drive and mode controls slew over 3 ms without per-sample tan/tanh setup.
 *
 * The nonlinear input drive is not oversampled and can alias at high settings.
 */
class SemFilter(sampleRate: Int = 48_000) {
    init {
        require(sampleRate in 8_000..384_000) { "Unsupported sample rate" }
    }

    private val rate = sampleRate.toDouble()
    private var previousCutoff = Float.NaN
    private var previousResonance = Float.NaN
    private var previousDrive = Float.NaN
    private var previousShape = Float.NaN
    private var mode = 0.0
    private var prewarpedG = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var a3 = 0.0
    private var damping = 2.0
    private var saturationGain = 0.05
    private var saturationNorm = 1.0 / tanh(0.05)
    // Retargetable, audio-rate 3 ms slews; expensive tan/tanh stays in configure().
    private val slewLength = (rate * 0.003).toInt().coerceAtLeast(1)
    private var controlsReady = false
    private var slewRemaining = 0
    private var targetG = 0.0
    private var targetDamping = 2.0
    private var targetGain = 0.05
    private var targetNorm = 1.0 / tanh(0.05)
    private var targetMode = 0.0
    private var stepG = 0.0
    private var stepDamping = 0.0
    private var stepGain = 0.0
    private var stepNorm = 0.0
    private var stepMode = 0.0

    private var state1 = 0.0
    private var state2 = 0.0
    private var lastBandPass = 0.0

    init {
        configure(1_200f, 0.15f, 0f, 0f)
    }

    /**
     * shape=0 low-pass; 0.5 notch; 1 high-pass.
     * The shape control can be moved continuously without resetting the poles.
     */
    fun configure(
        cutoffHz: Float,
        resonance: Float,
        drive: Float = 0f,
        shape: Float = 0f,
    ) {
        val cutoff = (if (cutoffHz.isFinite()) cutoffHz else 1_200f)
            .coerceIn(20f, (rate * 0.45).toFloat())
        val q = (if (resonance.isFinite()) resonance else 0.15f).coerceIn(0f, 1f)
        val d = (if (drive.isFinite()) drive else 0f).coerceIn(0f, 1f)
        val selectedShape = (if (shape.isFinite()) shape else 0f).coerceIn(0f, 1f)
        if (cutoff == previousCutoff && q == previousResonance &&
            d == previousDrive && selectedShape == previousShape
        ) return
        previousCutoff = cutoff
        previousResonance = q
        previousDrive = d
        previousShape = selectedShape

        val g = tan(PI * cutoff / rate)
        // Damping remains positive: unlike a naive biquad, the resonant poles
        // are stable across the full supported cutoff and parameter ranges.
        val nextDamping = 2.0 - 1.85 * q
        targetG = g
        targetDamping = nextDamping
        targetGain = 0.05 + 4.95 * d
        targetNorm = 1.0 / tanh(targetGain)
        targetMode = selectedShape.toDouble()

        if (!controlsReady) {
            controlsReady = true
            prewarpedG = targetG
            damping = targetDamping
            refreshCoefficients()
            saturationGain = targetGain
            saturationNorm = targetNorm
            mode = targetMode
            return
        }
        // A rapidly repeated gesture retargets from the current ramp position.
        slewRemaining = slewLength
        stepG = (targetG - prewarpedG) / slewLength
        stepDamping = (targetDamping - damping) / slewLength
        stepGain = (targetGain - saturationGain) / slewLength
        stepNorm = (targetNorm - saturationNorm) / slewLength
        stepMode = (targetMode - mode) / slewLength
    }

    /**
     * Copy all running analog state into a separately preallocated SEM instance.
     * A stolen voice can therefore keep its old filter tail while a new note
     * reuses the primary voice without sharing the two state-variable poles.
     */
    fun copyStateTo(destination: SemFilter) {
        require(destination.rate == rate) { "Filter sample rates must match" }
        destination.previousCutoff = previousCutoff
        destination.previousResonance = previousResonance
        destination.previousDrive = previousDrive
        destination.previousShape = previousShape
        destination.controlsReady = controlsReady
        destination.slewRemaining = slewRemaining
        destination.targetG = targetG
        destination.targetDamping = targetDamping
        destination.targetGain = targetGain
        destination.targetNorm = targetNorm
        destination.targetMode = targetMode
        destination.stepG = stepG
        destination.stepDamping = stepDamping
        destination.stepGain = stepGain
        destination.stepNorm = stepNorm
        destination.stepMode = stepMode
        destination.mode = mode
        destination.prewarpedG = prewarpedG
        destination.a1 = a1
        destination.a2 = a2
        destination.a3 = a3
        destination.damping = damping
        destination.saturationGain = saturationGain
        destination.saturationNorm = saturationNorm
        destination.state1 = state1
        destination.state2 = state2
        destination.lastBandPass = lastBandPass
    }

    fun reset() {
        state1 = 0.0
        state2 = 0.0
        lastBandPass = 0.0
    }

    private fun advanceControls() {
        if (slewRemaining <= 0) return
        prewarpedG += stepG
        damping += stepDamping
        saturationGain += stepGain
        saturationNorm += stepNorm
        mode += stepMode
        slewRemaining--
        if (slewRemaining == 0) {
            prewarpedG = targetG
            damping = targetDamping
            saturationGain = targetGain
            saturationNorm = targetNorm
            mode = targetMode
        }
        // Recalculate algebraic TPT coefficients from smoothed g and damping,
        // preserving stable relationships throughout even an octave-wide jump.
        refreshCoefficients()
    }

    private fun refreshCoefficients() {
        val denominator = 1.0 + prewarpedG * (prewarpedG + damping)
        a1 = 1.0 / denominator
        a2 = prewarpedG * a1
        a3 = prewarpedG * a2
    }

    /** Allocation-free morph between low-pass, notch and high-pass. */
    fun process(input: Float): Float {
        advanceControls()
        val x = (if (input.isFinite()) input else 0f).coerceIn(-8f, 8f).toDouble()
        val driven = tanh(saturationGain * x) * saturationNorm
        val v3 = driven - state2
        val band = a1 * state1 + a2 * v3
        val low = state2 + a2 * state1 + a3 * v3
        val high = driven - damping * band - low
        state1 = bounded(2.0 * band - state1)
        state2 = bounded(2.0 * low - state2)
        lastBandPass = bounded(band)

        // SEM variable mode moves LP -> notch -> HP; the band-pass output is
        // available separately and tracks the very same state.
        val notch = low + high
        val output = if (mode <= 0.5) {
            val t = mode * 2.0
            low * (1.0 - t) + notch * t
        } else {
            val t = (mode - 0.5) * 2.0
            notch * (1.0 - t) + high * t
        }
        return bounded(output).coerceIn(-8.0, 8.0).toFloat()
    }

    /**
     * Last band-pass output from process(). Does not advance time or process a
     * second sample. Call after process() for the same sample if needed.
     */
    fun bandPassOutput(): Float = lastBandPass.coerceIn(-8.0, 8.0).toFloat()

    private fun bounded(sample: Double): Double {
        if (!sample.isFinite() || abs(sample) < 1e-25) return 0.0
        return sample.coerceIn(-16.0, 16.0)
    }
}
