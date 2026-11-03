package com.orbital.synth.core.dsp.oscillator

import com.orbital.synth.core.OscillatorSlot
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * Eight independently programmable, band-limited additive layers for one Voice.
 *
 * A layer is a real oscillator, not a detuned copy of the global WAVE control:
 * it owns its own phase, waveform, morph, level and semitone/cents tuning.
 * All arrays are allocated at construction. Only the audio render thread may call
 * configure(), preparePitch(), resetPhase() or nextSample(). UI edits are published
 * as immutable OscillatorSlot snapshots by OrbitalSynth between render blocks.
 */
internal class LayeredOscillatorRack(private val sampleRate: Int) {
    companion object { const val SLOT_COUNT = 8 }

    private val applied = arrayOfNulls<OscillatorSlot>(SLOT_COUNT)
    private val phases = DoubleArray(SLOT_COUNT)
    private val wave = FloatArray(SLOT_COUNT)
    private val morph = FloatArray(SLOT_COUNT) { 1f }
    private val currentGain = FloatArray(SLOT_COUNT)
    private val targetGain = FloatArray(SLOT_COUNT)
    private val currentRatio = DoubleArray(SLOT_COUNT) { 1.0 }
    private val targetRatio = DoubleArray(SLOT_COUNT) { 1.0 }
    private val mipLevel = IntArray(SLOT_COUNT)
    private val mipFade = FloatArray(SLOT_COUNT)

    // 8 ms click-free level/wave/tuning motion; independent of audio buffer length.
    private val slew = (1.0 - exp(-1.0 / (sampleRate * 0.008))).toFloat()
    private var hasLayers = false

    var totalGain: Float = 0f
        private set

    val hasAudibleLayers: Boolean get() = hasLayers

    fun configure(index: Int, slot: OscillatorSlot) {
        if (applied[index] === slot) return
        applied[index] = slot
        wave[index] = slot.waveform
        morph[index] = slot.morph
        targetGain[index] = if (slot.enabled) slot.level else 0f
        targetRatio[index] = 2.0.pow(
            (slot.coarseSemitones.toDouble() * 100.0 + slot.fineCents.toDouble()) / 1200.0
        )
        if (targetGain[index] > 0f || currentGain[index] > 0f) hasLayers = true
    }

    /**
     * Called once per block. Reserve harmonic headroom for the highest pitch
     * reached during portamento, detune changes, bend and vibrato.
     */
    fun preparePitch(highestBaseHz: Double) {
        if (!hasLayers) return
        for (i in 0 until SLOT_COUNT) {
            if (targetGain[i] <= 0f && currentGain[i] <= 0f) continue
            val highestHz = highestBaseHz * max(currentRatio[i], targetRatio[i])
            mipLevel[i] = WavetableBank.levelForFrequency(highestHz, sampleRate)
            mipFade[i] = WavetableBank.fadeToLowerLevel(highestHz, sampleRate, mipLevel[i])
        }
    }

    /** New note starts all layers together; disabling a layer does not reset its phase. */
    fun resetPhase() {
        phases.fill(0.0)
    }

    /**
     * Return the additional oscillator signal and publish the currently smoothed
     * sum of its layer gains. Caller blends (base + extra) / (1 + totalGain)
     * to prevent a full rack from multiplying the nominal voice output by nine.
     */
    fun nextSample(fundamentalHz: Double): Float {
        if (!hasLayers) {
            totalGain = 0f
            return 0f
        }
        var extra = 0f
        var gains = 0f
        var anyRemaining = false
        for (i in 0 until SLOT_COUNT) {
            val target = targetGain[i]
            var gain = currentGain[i]
            if (abs(target - gain) > 0.00001f) {
                gain += (target - gain) * slew
            } else {
                gain = target
            }
            currentGain[i] = gain
            if (target > 0f) anyRemaining = true
            if (gain <= 0f) continue
            anyRemaining = true
            val ratio = currentRatio[i] + (targetRatio[i] - currentRatio[i]) * slew
            currentRatio[i] = ratio
            val frequency = (fundamentalHz * ratio)
                .coerceIn(0.0, sampleRate * 0.45)
            val oscillator = WavetableBank.sample(
                phases[i], mipLevel[i], wave[i], morph[i], mipFade[i]
            )
            extra += oscillator * gain
            gains += gain
            phases[i] += frequency / sampleRate
            if (phases[i] >= 1.0) phases[i] -= floor(phases[i])
        }
        hasLayers = anyRemaining
        totalGain = gains
        return extra
    }
}
