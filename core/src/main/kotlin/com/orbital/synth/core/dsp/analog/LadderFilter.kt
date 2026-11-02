package com.orbital.synth.core.dsp.analog

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Four-pole, zero-delay-feedback ladder-style low-pass model.
 *
 * This is a deliberately new, topology-inspired instrument filter, not a calibrated
 * replica of a particular Minimoog or any other physical unit. A bilinear/TPT
 * four-integrator ladder is closed through a saturating feedback equation. Three
 * bounded Newton refinements solve the implicit nonlinearity per sample, avoiding
 * the one-sample feedback delay and the explosive Q of a naive four-pole cascade.
 *
 * Cutoff is in Hz. Resonance and drive are normalized 0..1; drive increases the
 * input/feedback saturation. Input and output are normalized audio signals.
 * Parameters should be updated at an audio block boundary (not from another thread).
 * A separate instance is required for each voice/channel: state is not shared.
 *
 * No dynamic allocation or tan()/tanh() coefficient setup occurs inside process();
 * its 3 ms audio-rate coefficient slew uses inexpensive multiplications, while
 * the saturating solve does evaluate tanh three times per sample. This model
 * does not oversample; high drive at high pitch may alias.
 */
class LadderFilter(sampleRate: Int = 48_000) {
    init {
        require(sampleRate in 8_000..384_000) { "Unsupported sample rate" }
    }

    private val rate = sampleRate.toDouble()
    private var previousCutoff = Float.NaN
    private var previousResonance = Float.NaN
    private var previousDrive = Float.NaN

    private var alpha = 0.0
    private var beta = 1.0
    private var alphaSquared = 0.0
    private var alphaCubed = 0.0
    private var alphaFourth = 0.0
    private var feedback = 0.0
    private var saturationGain = 0.05
    private var saturationNorm = 1.0 / tanh(0.05)

    // Retargetable 3 ms linear slews smooth cutoff, resonance and drive changes.
    // All expensive tan/tanh coefficient setup stays on the block-level configure path.
    private val slewLength = (rate * 0.003).toInt().coerceAtLeast(1)
    private var controlsReady = false
    private var slewRemaining = 0
    private var targetAlpha = 0.0
    private var targetFeedback = 0.0
    private var targetGain = 0.05
    private var targetNorm = 1.0 / tanh(0.05)
    private var alphaStep = 0.0
    private var feedbackStep = 0.0
    private var gainStep = 0.0
    private var normStep = 0.0

    private var state1 = 0.0
    private var state2 = 0.0
    private var state3 = 0.0
    private var state4 = 0.0

    init {
        configure(1_200f, 0.15f, 0f)
    }

    /** Safe for nonfinite control values; no internal state is cleared by automation. */
    fun configure(cutoffHz: Float, resonance: Float, drive: Float = 0f) {
        val cutoff = (if (cutoffHz.isFinite()) cutoffHz else 1_200f)
            .coerceIn(20f, (rate * 0.45).toFloat())
        val q = (if (resonance.isFinite()) resonance else 0.15f).coerceIn(0f, 1f)
        val d = (if (drive.isFinite()) drive else 0f).coerceIn(0f, 1f)
        if (cutoff == previousCutoff && q == previousResonance && d == previousDrive) return
        previousCutoff = cutoff
        previousResonance = q
        previousDrive = d

        val prewarped = tan(PI * cutoff / rate)
        val nextAlpha = prewarped / (1.0 + prewarped)
        val nextFeedback = 3.92 * q
        // Near-zero distortion at d=0; increasingly compressed at d=1.
        val nextGain = 0.05 + 4.95 * d
        val nextNorm = 1.0 / tanh(nextGain)
        targetAlpha = nextAlpha
        targetFeedback = nextFeedback
        targetGain = nextGain
        targetNorm = nextNorm

        if (!controlsReady) {
            // Constructor setup starts at the exact requested coefficient.
            controlsReady = true
            alpha = nextAlpha
            feedback = nextFeedback
            saturationGain = nextGain
            saturationNorm = nextNorm
            refreshPowers()
            return
        }
        // New automation can retarget a running ramp from its actual current
        // coefficient, including before the previous 3 ms transition finishes.
        slewRemaining = slewLength
        alphaStep = (targetAlpha - alpha) / slewLength
        feedbackStep = (targetFeedback - feedback) / slewLength
        gainStep = (targetGain - saturationGain) / slewLength
        normStep = (targetNorm - saturationNorm) / slewLength
    }

    /**
     * Snapshot the complete running filter into another preallocated instance.
     * Useful for a 64-frame voice-stealing tail: the old tone can finish through
     * its original nonlinear filter while the recycled voice starts a fresh one.
     * No copy allocation occurs on the audio thread.
     */
    fun copyStateTo(destination: LadderFilter) {
        require(destination.rate == rate) { "Filter sample rates must match" }
        destination.previousCutoff = previousCutoff
        destination.previousResonance = previousResonance
        destination.previousDrive = previousDrive
        destination.alpha = alpha
        destination.beta = beta
        destination.alphaSquared = alphaSquared
        destination.alphaCubed = alphaCubed
        destination.alphaFourth = alphaFourth
        destination.feedback = feedback
        destination.saturationGain = saturationGain
        destination.saturationNorm = saturationNorm
        destination.controlsReady = controlsReady
        destination.slewRemaining = slewRemaining
        destination.targetAlpha = targetAlpha
        destination.targetFeedback = targetFeedback
        destination.targetGain = targetGain
        destination.targetNorm = targetNorm
        destination.alphaStep = alphaStep
        destination.feedbackStep = feedbackStep
        destination.gainStep = gainStep
        destination.normStep = normStep
        destination.state1 = state1
        destination.state2 = state2
        destination.state3 = state3
        destination.state4 = state4
    }

    /** Explicitly clear stored charge, e.g. when a voice is recycled. */
    fun reset() {
        state1 = 0.0
        state2 = 0.0
        state3 = 0.0
        state4 = 0.0
    }

    private fun refreshPowers() {
        beta = 1.0 - alpha
        alphaSquared = alpha * alpha
        alphaCubed = alphaSquared * alpha
        alphaFourth = alphaSquared * alphaSquared
    }

    private fun advanceControls() {
        if (slewRemaining <= 0) return
        alpha += alphaStep
        feedback += feedbackStep
        saturationGain += gainStep
        saturationNorm += normStep
        slewRemaining--
        if (slewRemaining == 0) {
            alpha = targetAlpha
            feedback = targetFeedback
            saturationGain = targetGain
            saturationNorm = targetNorm
        }
        refreshPowers()
    }

    /** Allocation-free single-sample low-pass processing. */
    fun process(input: Float): Float {
        advanceControls()
        val x = (if (input.isFinite()) input else 0f).coerceIn(-8f, 8f).toDouble()
        // Four-stage TPT cascade has y4 = alpha^4 * u + history.
        val history = beta * (
            alphaCubed * state1 + alphaSquared * state2 +
                alpha * state3 + state4
            )
        // A linear zero-delay solution is the Newton starting point.
        var u = ((x - feedback * history) / (1.0 + feedback * alphaFourth))
            .coerceIn(-8.0, 8.0)

        // Solve u = tanh(gain * (x - k*y4)) / tanh(gain).
        // Derivative stays >= 1, even at the highest resonance.
        repeat(3) {
            val argument = saturationGain * (x - feedback * (alphaFourth * u + history))
            val t = tanh(argument)
            val shaped = saturationNorm * t
            val slope = saturationNorm * saturationGain * (1.0 - t * t)
            u = (u - (u - shaped) / (1.0 + feedback * alphaFourth * slope))
                .coerceIn(-8.0, 8.0)
        }

        val y1 = alpha * u + beta * state1
        val y2 = alpha * y1 + beta * state2
        val y3 = alpha * y2 + beta * state3
        val y4 = alpha * y3 + beta * state4
        state1 = bounded(2.0 * y1 - state1)
        state2 = bounded(2.0 * y2 - state2)
        state3 = bounded(2.0 * y3 - state3)
        state4 = bounded(2.0 * y4 - state4)
        return bounded(y4).coerceIn(-8.0, 8.0).toFloat()
    }

    private fun bounded(sample: Double): Double {
        if (!sample.isFinite() || abs(sample) < 1e-25) return 0.0
        return sample.coerceIn(-16.0, 16.0)
    }
}
