package com.orbital.synth.core.dsp.processing

import kotlin.math.PI
import kotlin.math.sin

enum class LfoShape { SINE, TRIANGLE, SAW_UP, SAW_DOWN, SQUARE, SAMPLE_HOLD }

/** Audio-rate capable, tempo-syncable LFO. Output range -1 .. 1. */
class TempoLfo(sampleRate: Int = 48_000, initialSeed: Int = 0x12ab34cd) {
    init { require(sampleRate in 8_000..384_000) }
    private val rate = sampleRate.toDouble()
    private var increment = 1.0 / rate
    private var phase = 0.0
    private var randomState = if (initialSeed != 0) initialSeed else 1
    private var heldValue = randomBipolar()
    var shape: LfoShape = LfoShape.SINE
        private set

    fun setShape(value: LfoShape) { shape = value }

    fun setRateHz(hertz: Float) {
        val safe = (if (hertz.isFinite()) hertz else 1f).coerceIn(0.01f, 40f)
        increment = safe.toDouble() / rate
    }

    /** A beat is a quarter-note at the selected BPM. 4 beats/cycle = one bar in 4/4. */
    fun syncToTempo(bpm: Float, beatsPerCycle: Float) {
        val safeTempo = (if (bpm.isFinite()) bpm else 120f).coerceIn(20f, 400f)
        val safeBeats = (if (beatsPerCycle.isFinite()) beatsPerCycle else 1f).coerceIn(0.0625f, 64f)
        setRateHz(safeTempo / (60f * safeBeats))
    }

    fun reset(startPhase: Float = 0f) {
        phase = (if (startPhase.isFinite()) startPhase else 0f).toDouble()
        phase -= kotlin.math.floor(phase)
        heldValue = randomBipolar()
    }

    fun next(): Float {
        val result = when (shape) {
            LfoShape.SINE -> sin(2.0 * PI * phase).toFloat()
            LfoShape.TRIANGLE -> (1.0 - 4.0 * kotlin.math.abs(phase - 0.5)).toFloat()
            LfoShape.SAW_UP -> (2.0 * phase - 1.0).toFloat()
            LfoShape.SAW_DOWN -> (1.0 - 2.0 * phase).toFloat()
            LfoShape.SQUARE -> if (phase < 0.5) 1f else -1f
            LfoShape.SAMPLE_HOLD -> heldValue
        }
        phase += increment
        if (phase >= 1.0) {
            phase -= kotlin.math.floor(phase)
            heldValue = randomBipolar()
        }
        return result.coerceIn(-1f, 1f)
    }

    /**
     * Move the oscillator clock forward without evaluating trigonometry for
     * every skipped silent sample. SAMPLE_HOLD still advances its deterministic
     * random sequence once for each elapsed period.
     */
    fun advance(samples: Int) {
        require(samples >= 0)
        if (samples == 0) return
        val total = phase + increment * samples
        val wraps = kotlin.math.floor(total).toLong()
        phase = total - wraps
        var remaining = wraps
        while (remaining > 0L) {
            heldValue = randomBipolar()
            remaining--
        }
    }

    private fun randomBipolar(): Float {
        var x = randomState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        randomState = x
        return ((x ushr 8).toDouble() / 16_777_215.0 * 2.0 - 1.0).toFloat()
    }
}
