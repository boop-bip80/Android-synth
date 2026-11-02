package com.orbital.synth.core.dsp.processing

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two-tap-per-channel, feed-forward stereo chorus with a continuously modulated
 * fractional delay. The two taps move in opposite directions; the right-hand
 * modulation can be offset by up to a quarter cycle for genuine stereo movement.
 *
 * Allocate once, then own this instance exclusively on the audio thread.
 * Configuration is block-rate and [process] makes no allocations, takes no locks,
 * and does not access Android APIs. No feedback means the effect is stable even
 * with full wet mix, extreme modulation, or an abrupt parameter change.
 */
class StereoChorus(sampleRate: Int = 48_000) {
    init { require(sampleRate in 8_000..384_000) }

    private val rate = sampleRate.toDouble()
    private val baseDelay = rate * 0.016 // 16 ms; with modulation, taps stay 7..25 ms.
    private val historySize = ceil(rate * 0.032).toInt() + 4
    private val leftHistory = FloatArray(historySize)
    private val rightHistory = FloatArray(historySize)
    private var writeIndex = 0
    private var validFrames = 0
    // Recursive quadrature oscillator avoids sin/cos calls inside every audio frame.
    // Periodic normalization bounds long-run floating-point drift.
    private var lfoSin = 0.0
    private var lfoCos = 1.0
    private var stepSin = sin(2.0 * PI * 0.38 / rate)
    private var stepCos = cos(2.0 * PI * 0.38 / rate)
    private var framesUntilNormalization = 4_096
    // A 10 ms one-pole glide avoids hard wet/depth/width edges when a touch knob moves.
    // No work is allocated on the audio thread; the rate-dependent coefficient is fixed.
    private val controlSlew = 1.0 - exp(-1.0 / (rate * 0.010))
    private var sweepSamples = rate * 0.004
    private var targetSweepSamples = sweepSamples
    private var mix = 0.0
    private var targetMix = mix
    private var offsetSin = 1.0
    private var targetOffsetSin = offsetSin
    private var offsetCos = 0.0
    private var targetOffsetCos = offsetCos
    private var hasConfiguration = false

    /**
     * [rateHz] in 0.02..8 Hz, [depth]/[wet]/[stereoWidth] in 0..1.
     * Non-finite inputs fall back to safe defaults. Initial wet=0 is an exact dry path.
     * Subsequent depth/wet/width changes are slewed over roughly 10 ms, avoiding zipper noise.
     */
    fun configure(rateHz: Float, depth: Float, wet: Float, stereoWidth: Float = 1f) {
        val hertz = (if (rateHz.isFinite()) rateHz else 0.38f).coerceIn(0.02f, 8f)
        val safeDepth = (if (depth.isFinite()) depth else 0f).coerceIn(0f, 1f)
        val safeWet = (if (wet.isFinite()) wet else 0f).coerceIn(0f, 1f)
        val width = (if (stereoWidth.isFinite()) stereoWidth else 1f).coerceIn(0f, 1f)
        val angle = 2.0 * PI * hertz.toDouble() / rate
        stepSin = sin(angle)
        stepCos = cos(angle)
        targetSweepSamples = rate * 0.009 * safeDepth
        targetMix = safeWet.toDouble()
        val offset = PI * 0.5 * width
        targetOffsetSin = sin(offset)
        targetOffsetCos = cos(offset)
        // Initial configuration must be exact: fully wet/dry presets cannot fade from
        // constructor defaults when the first note starts. Only live edits are slewed.
        if (!hasConfiguration) {
            snapControlsToTargets()
            hasConfiguration = true
        }
    }

    /** Process one interleaved stereo frame into [output] at [offset]. */
    fun process(left: Float, right: Float, output: FloatArray, offset: Int) {
        require(offset >= 0 && offset + 1 < output.size)
        mix += (targetMix - mix) * controlSlew
        sweepSamples += (targetSweepSamples - sweepSamples) * controlSlew
        offsetSin += (targetOffsetSin - offsetSin) * controlSlew
        offsetCos += (targetOffsetCos - offsetCos) * controlSlew
        val dryL = sanitize(left)
        val dryR = sanitize(right)
        leftHistory[writeIndex] = dryL
        rightHistory[writeIndex] = dryR

        if (mix == 0.0) {
            output[offset] = dryL
            output[offset + 1] = dryR
        } else {
            val leftOffset = sweepSamples * lfoSin
            val rightOffset = sweepSamples * (lfoSin * offsetCos + lfoCos * offsetSin)
            val wetL = (readTap(leftHistory, baseDelay + leftOffset) +
                readTap(leftHistory, baseDelay - leftOffset)) * 0.5
            val wetR = (readTap(rightHistory, baseDelay + rightOffset) +
                readTap(rightHistory, baseDelay - rightOffset)) * 0.5
            output[offset] = (dryL * (1.0 - mix) + wetL * mix).coerceIn(-16.0, 16.0).toFloat()
            output[offset + 1] = (dryR * (1.0 - mix) + wetR * mix).coerceIn(-16.0, 16.0).toFloat()
        }

        writeIndex++
        if (writeIndex == historySize) writeIndex = 0
        if (validFrames < historySize) validFrames++
        val nextSin = lfoSin * stepCos + lfoCos * stepSin
        lfoCos = lfoCos * stepCos - lfoSin * stepSin
        lfoSin = nextSin
        framesUntilNormalization--
        if (framesUntilNormalization == 0) {
            val invLength = 1.0 / sqrt(lfoSin * lfoSin + lfoCos * lfoCos)
            lfoSin *= invLength
            lfoCos *= invLength
            framesUntilNormalization = 4_096
        }
    }

    /** O(1) reset: invalidate all old samples without clearing the buffers. */
    fun reset() {
        writeIndex = 0
        validFrames = 0
        lfoSin = 0.0
        lfoCos = 1.0
        framesUntilNormalization = 4_096
        snapControlsToTargets()
    }

    private fun snapControlsToTargets() {
        mix = targetMix
        sweepSamples = targetSweepSamples
        offsetSin = targetOffsetSin
        offsetCos = targetOffsetCos
    }

    private fun readTap(history: FloatArray, delaySamples: Double): Double {
        val integer = delaySamples.toInt()
        val fraction = delaySamples - integer
        if (validFrames < integer || (fraction > 0.0 && validFrames <= integer)) return 0.0
        var readIndex = writeIndex - integer
        if (readIndex < 0) readIndex += historySize
        val newer = history[readIndex].toDouble()
        if (fraction == 0.0) return newer
        val olderIndex = if (readIndex == 0) historySize - 1 else readIndex - 1
        return newer + (history[olderIndex] - newer) * fraction
    }

    private fun sanitize(value: Float): Float =
        (if (value.isFinite()) value else 0f).coerceIn(-8f, 8f)
}
