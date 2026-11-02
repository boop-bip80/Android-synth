package com.orbital.synth.core.dsp.processing

import kotlin.math.ceil

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
    private var feedback = 0.3f
    private var mix = 0.2f
    private var pingPong = 0.5f

    /** All parameters are clamped. Time is seconds; other values are normalized 0..1. */
    fun configure(timeSeconds: Float, feedback: Float, wet: Float, pingPong: Float = 0.5f) {
        val seconds = (if (timeSeconds.isFinite()) timeSeconds else 0.375f)
            .coerceIn(1f / effectiveSampleRate, maxSeconds)
        // Delay time is limited to the allocated buffer, in samples.
        length = (seconds * effectiveSampleRate).toInt().coerceIn(1, historySize - 2)
        this.feedback = (if (feedback.isFinite()) feedback else 0f).coerceIn(0f, 0.92f)
        mix = (if (wet.isFinite()) wet else 0f).coerceIn(0f, 1f)
        this.pingPong = (if (pingPong.isFinite()) pingPong else 0f).coerceIn(0f, 1f)
    }

    private val effectiveSampleRate = sampleRate.toFloat()

    /** Write interleaved stereo output at offset, offset+1. No per-sample allocation. */
    fun process(left: Float, right: Float, output: FloatArray, offset: Int) {
        require(offset >= 0 && offset + 1 < output.size)
        val read = (index + historySize - length) % historySize
        // A delay-time change is safe: a longer tap reads silence until enough
        // post-reset samples exist, while a shorter tap reads only recent new data.
        // No old pre-reset frame can cross this generation boundary.
        val historyValid = validFramesSinceReset >= length
        val delayedL = if (historyValid) leftHistory[read] else 0f
        val delayedR = if (historyValid) rightHistory[read] else 0f
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
    }
}
