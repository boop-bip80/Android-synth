package com.orbital.synth.core.dsp.processing

import kotlin.math.min

/** Stable on-disk names; never reorder these playback modes. */
enum class StepDirection { FORWARD, REVERSE, PING_PONG, RANDOM }

/**
 * Immutable sixteen-pad pattern for the bipolar STEP modulation source.
 * A shorter length simply loops the first N pads; the rest remain editable.
 * Glide is the fraction of each step spent interpolating from the previous pad.
 */
data class ModulationStepConfig(
    val enabled: Boolean = false,
    val values: List<Float> = listOf(
        1f, -.25f, .35f, -.65f, .70f, 0f, -.45f, .40f,
        .85f, -.50f, .25f, -.8f, .6f, -.1f, .4f, -.3f,
    ),
    val length: Int = 16,
    val beatsPerStep: Float = .25f,
    val glide: Float = .12f,
    val direction: StepDirection = StepDirection.FORWARD,
    val retriggerOnNote: Boolean = false,
) {
    init {
        require(values.size == 16 && values.all { it.isFinite() && it in -1f..1f }) {
            "Step pattern must have exactly sixteen finite bipolar pads"
        }
        require(length in 1..16) { "Step length must be 1..16" }
        require(beatsPerStep.isFinite() && beatsPerStep in .0625f..8f) {
            "Step duration must be between 1/16 and 8 beats"
        }
        require(glide.isFinite() && glide in 0f..1f) { "Step glide must be normalized" }
    }

    /** UI/editor helper; copies the entire pad list before publishing. */
    fun withStep(index: Int, value: Float): ModulationStepConfig {
        require(index in 0..15) { "Step index must be 0..15" }
        require(value.isFinite()) { "Step pad must be finite" }
        val next = values.toMutableList()
        next[index] = value.coerceIn(-1f, 1f)
        return copy(values = next)
    }
}

/**
 * Audio-thread-only 16-step clock. O(1) per sample; silent fast-forward is
 * O(1), including deterministic random and ping-pong traversal.
 *
 * No allocation, locks, trigonometry or hidden system clock in [next] or
 * [advance]. Every block/chunk size produces the same sequence. This source
 * is bipolar and can feed any of the 64 modulation matrix cables.
 */
class TempoStepSequencer(val sampleRate: Int = 48_000, private val randomSeed: Int = 0x13ac75) {
    init { require(sampleRate in 8_000..384_000) }
    private var configuration = ModulationStepConfig()
    private var tempoBpm = 120f
    private var phase = 0.0
    private var stepNumber = 0L
    private var previousValue = configuration.values[0]

    val enabled: Boolean get() = configuration.enabled
    val playheadIndex: Int get() = indexAt(stepNumber)
    val config: ModulationStepConfig get() = configuration

    fun configure(config: ModulationStepConfig) {
        val previous = configuration
        configuration = config
        if ((!previous.enabled && config.enabled) ||
            previous.direction != config.direction || previous.length != config.length
        ) reset()
    }

    fun setTempoBpm(bpm: Float) {
        if (bpm.isFinite()) tempoBpm = bpm.coerceIn(20f, 400f)
    }

    /** Re-enter the first pad on a configured MIDI note trigger. */
    fun onNoteOn() {
        if (enabled && configuration.retriggerOnNote) reset()
    }

    fun reset() {
        phase = 0.0
        stepNumber = 0L
        previousValue = configuration.values[indexAt(0L)]
    }

    fun next(): Float {
        if (!enabled) return 0f
        val current = configuration.values[playheadIndex]
        val fraction = if (configuration.glide == 0f) 1f
            else min(1f, (phase / configuration.glide).toFloat())
        val output = previousValue + (current - previousValue) * fraction
        advance(1)
        return output.coerceIn(-1f, 1f)
    }

    fun advance(samples: Int) {
        require(samples >= 0)
        if (!enabled || samples == 0) return
        val increment = tempoBpm.toDouble() /
            (60.0 * configuration.beatsPerStep * sampleRate)
        val total = phase + increment * samples
        val crossed = total.toLong()
        phase = total - crossed
        if (crossed > 0L) {
            previousValue = configuration.values[indexAt(stepNumber + crossed - 1L)]
            stepNumber += crossed
        }
    }

    private fun indexAt(number: Long): Int {
        val length = configuration.length
        return when (configuration.direction) {
            StepDirection.FORWARD -> (number % length).toInt()
            StepDirection.REVERSE -> length - 1 - (number % length).toInt()
            StepDirection.PING_PONG -> {
                if (length == 1) 0 else {
                    val period = 2 * (length - 1)
                    val index = (number % period).toInt()
                    if (index < length) index else period - index
                }
            }
            StepDirection.RANDOM -> {
                // Pad selection is a hash of the absolute step number, not
                // mutable RNG state; skipping idle time cannot desynchronize it.
                var hash = (number xor (number ushr 32)).toInt() xor randomSeed
                hash = hash xor (hash ushr 16)
                hash = hash * 1664525 + 1013904223
                hash = hash xor (hash ushr 13)
                (hash ushr 1) % length
            }
        }
    }
}
