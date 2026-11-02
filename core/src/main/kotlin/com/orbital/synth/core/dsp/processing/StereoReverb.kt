package com.orbital.synth.core.dsp.processing

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Stereo feedback-delay-network (FDN) reverb with eight mutually decorrelated
 * delay lines, a normalized Hadamard feedback matrix and one-pole high-frequency
 * damping in each feedback path.
 *
 * All buffers and scratch arrays are allocated during construction. An instance
 * belongs to one audio thread: call [configure], [process] and [reset] from that
 * thread, never concurrently. No allocations, locks or Android APIs occur in
 * the per-frame processing path.
 *
 * The normalized Hadamard matrix is orthogonal. Blending it with the identity
 * keeps the feedback transform contractive at any diffusion setting; every
 * decay coefficient remains below unity. This prevents runaway tails even at
 * maximum room time and extreme input levels.
 */
class StereoReverb(sampleRate: Int = 48_000) {
    init { require(sampleRate in 8_000..384_000) }

    private val rate = sampleRate.toDouble()
    // Non-harmonically related lengths reduce flutter and repeated tonal echoes.
    // Sample-rate scaling keeps the room's dimensions invariant.
    private val seconds = doubleArrayOf(
        0.0311, 0.0349, 0.0377, 0.0419,
        0.0451, 0.0487, 0.0523, 0.0569
    )
    private val lengths = IntArray(8) { (seconds[it] * rate).roundToInt().coerceAtLeast(2) }
    private val history = Array(8) { FloatArray(lengths[it]) }
    private val writeIndex = IntArray(8)
    private val validFrames = IntArray(8)
    private val damped = DoubleArray(8)
    private val taps = DoubleArray(8)
    private val transform = DoubleArray(8)
    private val feedback = DoubleArray(8)
    private val targetFeedback = DoubleArray(8)

    // 15 ms smoothing for performance controls: preset switches do not abruptly
    // change feedback, filtering, diffusion, width or wet gain.
    private val controlSlew = 1.0 - exp(-1.0 / (rate * 0.015))
    private var wet = 0.0
    private var targetWet = 0.0
    private var diffusion = 0.75
    private var targetDiffusion = 0.75
    private var width = 1.0
    private var targetWidth = 1.0
    private var dampingPole = 0.5
    private var targetDampingPole = 0.5
    private var configured = false

    /**
     * [decaySeconds] is the approximate T60 (0.12–12 s), [damping] ranges from
     * bright 0 to dark 1, and [wet], [diffusion], [stereoWidth] are 0..1.
     * Invalid numeric inputs fall back to safe settings.
     *
     * The first configuration takes effect immediately; later updates glide.
     * A fresh effect with wet=0 is an exact, bit-identical dry path.
     */
    fun configure(
        decaySeconds: Float,
        damping: Float,
        wet: Float,
        diffusion: Float = 0.75f,
        stereoWidth: Float = 1f
    ) {
        val t60 = safe(decaySeconds, 2.4f).coerceIn(0.12f, 12f).toDouble()
        val dark = safe(damping, 0.5f).coerceIn(0f, 1f).toDouble()
        targetWet = safe(wet, 0f).coerceIn(0f, 1f).toDouble()
        targetDiffusion = safe(diffusion, 0.75f).coerceIn(0f, 1f).toDouble()
        targetWidth = safe(stereoWidth, 1f).coerceIn(0f, 1f).toDouble()

        val cutoffHz = min(rate * 0.45, 400.0 + 18_600.0 * (1.0 - dark) * (1.0 - dark))
        targetDampingPole = 1.0 - exp(-2.0 * PI * cutoffHz / rate)
        for (line in 0 until 8) {
            // An echo that returns after "lengths[line]" frames loses 60 dB
            // after t60 seconds, irrespective of the output sample rate.
            targetFeedback[line] = exp(-6.907755278982137 * lengths[line] / (rate * t60))
        }
        if (!configured) {
            snapControlsToTargets()
            configured = true
        }
    }

    /** Process one interleaved stereo frame at [offset] and [offset + 1]. */
    fun process(left: Float, right: Float, output: FloatArray, offset: Int) {
        require(offset >= 0 && offset < output.size - 1)
        val dryL = sanitize(left)
        val dryR = sanitize(right)
        wet += (targetWet - wet) * controlSlew
        diffusion += (targetDiffusion - diffusion) * controlSlew
        width += (targetWidth - width) * controlSlew
        dampingPole += (targetDampingPole - dampingPole) * controlSlew

        for (line in 0 until 8) {
            feedback[line] += (targetFeedback[line] - feedback[line]) * controlSlew
            // O(1) generation reset: stale history is inaccessible until this
            // particular delay ring has been completely rewritten.
            val sample = if (validFrames[line] == lengths[line])
                history[line][writeIndex[line]].toDouble() else 0.0
            damped[line] += dampingPole * (sample - damped[line])
            taps[line] = damped[line]
            transform[line] = damped[line]
        }

        // Different projections of the eight delay lines yield decorrelated
        // left/right fields. Width=0 collapses the wet signal to exact mono.
        val reverbL = (taps[0] + taps[2] - taps[4] + taps[6]) * 0.5
        val reverbR = (taps[1] - taps[3] + taps[5] - taps[7]) * 0.5
        val mid = (reverbL + reverbR) * 0.5
        val wetL = mid + (reverbL - mid) * width
        val wetR = mid + (reverbR - mid) * width

        // In-place 8x8 Walsh-Hadamard butterfly. Scaling by 1/sqrt(8)
        // makes it orthogonal, so diffusion cannot add feedback gain.
        var stride = 1
        while (stride < 8) {
            var block = 0
            while (block < 8) {
                for (element in 0 until stride) {
                    val first = block + element
                    val second = first + stride
                    val a = transform[first]
                    val b = transform[second]
                    transform[first] = a + b
                    transform[second] = a - b
                }
                block += stride * 2
            }
            stride *= 2
        }

        val inputMid = (dryL + dryR) * 0.125
        val inputSide = (dryL - dryR) * 0.125
        val direct = 1.0 - diffusion
        for (line in 0 until 8) {
            val excitation = when (line % 4) {
                0 -> inputMid
                1 -> inputSide
                2 -> dryL * 0.125
                else -> dryR * 0.125
            }
            val signedExcitation = if (line < 4) excitation else -excitation
            val feedbackInput = direct * taps[line] +
                diffusion * transform[line] * 0.3535533905932738
            val next = (signedExcitation + feedback[line] * feedbackInput)
                .coerceIn(-32.0, 32.0)
            history[line][writeIndex[line]] = next.toFloat()
            writeIndex[line]++
            if (writeIndex[line] == lengths[line]) writeIndex[line] = 0
            if (validFrames[line] < lengths[line]) validFrames[line]++
        }

        val dryGain = 1.0 - wet
        output[offset] = (dryL * dryGain + wetL * wet).coerceIn(-16.0, 16.0).toFloat()
        output[offset + 1] = (dryR * dryGain + wetR * wet).coerceIn(-16.0, 16.0).toFloat()
    }

    /**
     * Invalidate all delay generations in constant time without clearing the
     * multi-second history buffers. The next zero-input process returns silence.
     */
    fun reset() {
        for (line in 0 until 8) {
            writeIndex[line] = 0
            validFrames[line] = 0
            damped[line] = 0.0
        }
        snapControlsToTargets()
    }

    /**
     * Discard old tails at exact bypass and make the next wet enable glide from
     * silence. The enclosing master bus should finish its bypass crossfade
     * before invoking this, to avoid cutting a still-audible tail.
     */
    fun resetForBypass() {
        reset()
        wet = 0.0
        targetWet = 0.0
        configured = true
    }

    private fun snapControlsToTargets() {
        wet = targetWet
        width = targetWidth
        diffusion = targetDiffusion
        dampingPole = targetDampingPole
        for (line in 0 until 8) feedback[line] = targetFeedback[line]
    }

    private fun safe(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    private fun sanitize(sample: Float): Double =
        (if (sample.isFinite()) sample else 0f).coerceIn(-8f, 8f).toDouble()
}
