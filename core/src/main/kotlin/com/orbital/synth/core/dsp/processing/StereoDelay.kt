package com.orbital.synth.core.dsp.processing

import kotlin.math.ceil
import kotlin.math.exp

/**
 * Bounded stereo ping-pong delay. Preallocates its history once in the constructor.
 * Audio-thread ownership is required; never mutate controls concurrently with render.
 */
class StereoDelay(sampleRate: Int = 48_000, maxDelaySeconds: Float = 3f) {
    init {
        require(sampleRate in 8_000..384_000)
        require(maxDelaySeconds.isFinite() && maxDelaySeconds in 0.01f..8f)
    }
    private val historySize = ceil(sampleRate * maxDelaySeconds.toDouble()).toInt() + 2
    private val maxSeconds = maxDelaySeconds
    private val leftHistory = FloatArray(historySize)
    private val rightHistory = FloatArray(historySize)
    private var index = 0
    // Number of samples written since the most recent logical reset, saturated at
    // historySize. A read is valid only after its full delay interval was re-recorded.
    // This invalidates both channels in O(1), even for multi-second history buffers.
    private var validFramesSinceReset = 0
    private var length = (sampleRate * 0.375f).toInt().coerceIn(1, historySize - 2)
    // A delay-time edit crossfades two taps over 10 ms. Simply changing the read
    // offset discontinuously produces a click even with wet/feedback smoothing.
    // Keep the latest requested target while a prior fade finishes, then begin
    // the next fade without restarting or jumping between intermediate taps.
    private val timeTransitionFrames = (sampleRate * 0.010f).toInt().coerceAtLeast(1)
    private var previousLength = length
    private var targetLength = length
    private var timeTransitionRemaining = 0
    // Sample-rate-correct 10 ms slew prevents sharp feedback/wet/pan jumps when
    // changing patches or tweaking the FX control while echoes are sounding.
    private val controlSlew = (1.0 - exp(-1.0 / (sampleRate * 0.010))).toFloat()
    private var feedback = 0.3f
    private var targetFeedback = feedback
    private var mix = 0.2f
    private var targetMix = mix
    private var pingPong = 0.5f
    private var targetPingPong = pingPong
    private var hasConfiguration = false

    /** All parameters are clamped. Time is seconds; other values are normalized 0..1. */
    fun configure(timeSeconds: Float, feedback: Float, wet: Float, pingPong: Float = 0.5f) {
        val seconds = (if (timeSeconds.isFinite()) timeSeconds else 0.375f)
            .coerceIn(1f / effectiveSampleRate, maxSeconds)
        // Delay time is limited to the allocated buffer, in samples. Unlike
        // amplitude controls, time is rendered using two taps so large edits do
        // not sweep through the intervening history at an audible pitch jump.
        val requestedLength = (seconds * effectiveSampleRate).toInt().coerceIn(1, historySize - 2)
        targetLength = requestedLength
        // Immediately after a reset there is no audible echo to preserve.
        // Start on the requested time instead of fading in stale/empty taps.
        if (validFramesSinceReset == 0) {
            length = requestedLength
            previousLength = requestedLength
            timeTransitionRemaining = 0
        }
        targetFeedback = (if (feedback.isFinite()) feedback else 0f).coerceIn(0f, 0.92f)
        targetMix = (if (wet.isFinite()) wet else 0f).coerceIn(0f, 1f)
        targetPingPong = (if (pingPong.isFinite()) pingPong else 0f).coerceIn(0f, 1f)
        // The first preset should take effect immediately; only live edits glide.
        if (!hasConfiguration) {
            length = requestedLength
            previousLength = requestedLength
            snapControlsToTargets()
            hasConfiguration = true
        }
    }

    private val effectiveSampleRate = sampleRate.toFloat()

    /** Write interleaved stereo output at offset, offset+1. No per-sample allocation. */
    fun process(left: Float, right: Float, output: FloatArray, offset: Int) {
        require(offset >= 0 && offset + 1 < output.size)
        feedback += (targetFeedback - feedback) * controlSlew
        mix += (targetMix - mix) * controlSlew
        pingPong += (targetPingPong - pingPong) * controlSlew
        // Coalesce multiple controller updates while the current time crossfade
        // finishes. The next transition starts from the exact preceding target,
        // even if configure() is called repeatedly inside one 10 ms interval.
        if (timeTransitionRemaining == 0 && targetLength != length) {
            previousLength = length
            length = targetLength
            timeTransitionRemaining = timeTransitionFrames
        }
        val read = (index + historySize - length) % historySize
        // Each tap has its own post-reset validity check. A long new delay must
        // never access old buffer generations during a transition.
        val historyValid = validFramesSinceReset >= length
        var delayedL = if (historyValid) leftHistory[read] else 0f
        var delayedR = if (historyValid) rightHistory[read] else 0f
        if (timeTransitionRemaining > 0) {
            val previousRead = (index + historySize - previousLength) % historySize
            val previousValid = validFramesSinceReset >= previousLength
            val oldL = if (previousValid) leftHistory[previousRead] else 0f
            val oldR = if (previousValid) rightHistory[previousRead] else 0f
            val oldGain = timeTransitionRemaining.toFloat() / timeTransitionFrames
            val newGain = 1f - oldGain
            delayedL = oldL * oldGain + delayedL * newGain
            delayedR = oldR * oldGain + delayedR * newGain
            timeTransitionRemaining--
        }
        val dryL = if (left.isFinite()) left.coerceIn(-8f, 8f) else 0f
        val dryR = if (right.isFinite()) right.coerceIn(-8f, 8f) else 0f
        val cross = pingPong
        val nextL = dryL + feedback * (delayedL * (1f - cross) + delayedR * cross)
        val nextR = dryR + feedback * (delayedR * (1f - cross) + delayedL * cross)
        leftHistory[index] = nextL.coerceIn(-32f, 32f)
        rightHistory[index] = nextR.coerceIn(-32f, 32f)
        output[offset] = (dryL * (1f - mix) + delayedL * mix).coerceIn(-32f, 32f)
        output[offset + 1] = (dryR * (1f - mix) + delayedR * mix).coerceIn(-32f, 32f)
        index++
        if (index == historySize) index = 0
        if (validFramesSinceReset < historySize) validFramesSinceReset++
    }

    /**
     * Logical reset in constant time. Physical samples are left untouched but are
     * inaccessible until each delay tap has been replenished by post-reset writes.
     * Safe across delay reconfiguration and circular-buffer wraparound.
     */
    fun reset() {
        index = 0
        validFramesSinceReset = 0
        length = targetLength
        previousLength = length
        timeTransitionRemaining = 0
        snapControlsToTargets()
    }

    /**
     * Hard-bypass reset: discard all prior echoes and start the next [configure]
     * transition from zero wet, even if the old preset was fully wet. Unlike
     * [reset], this deliberately does not preserve the old wet gain.
     *
     * This also marks the processor configured, so an initial FX enable after
     * bypass fades wet in instead of snapping to the first preset immediately.
     */
    fun resetForBypass() {
        reset()
        mix = 0f
        targetMix = 0f
        hasConfiguration = true
    }

    private fun snapControlsToTargets() {
        feedback = targetFeedback
        mix = targetMix
        pingPong = targetPingPong
    }
}
