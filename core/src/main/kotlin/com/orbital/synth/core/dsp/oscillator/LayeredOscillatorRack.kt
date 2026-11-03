package com.orbital.synth.core.dsp.oscillator

import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.OscillatorLayerModulation
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
 * Each layer can additionally mix into a continuously colored white/pink/brown
 * noise source; with all noise controls at zero the legacy PCM path is exact.
 * All arrays are allocated at construction. Only the audio render thread may call
 * configure(), preparePitch(), resetPhase() or nextSample(). UI edits are published
 * as immutable OscillatorSlot snapshots by OrbitalSynth between render blocks.
 */
internal class LayeredOscillatorRack(private val sampleRate: Int) {
    companion object { const val SLOT_COUNT = 8 }

    private val applied = arrayOfNulls<OscillatorSlot>(SLOT_COUNT)
    private val phases = DoubleArray(SLOT_COUNT)
    private val wave = FloatArray(SLOT_COUNT)
    private val targetWave = FloatArray(SLOT_COUNT)
    private val morph = FloatArray(SLOT_COUNT) { 1f }
    private val targetMorph = FloatArray(SLOT_COUNT) { 1f }
    private val currentGain = FloatArray(SLOT_COUNT)
    private val targetGain = FloatArray(SLOT_COUNT)
    private val currentRatio = DoubleArray(SLOT_COUNT) { 1.0 }
    private val targetRatio = DoubleArray(SLOT_COUNT) { 1.0 }
    private val mipCeilingRatio = DoubleArray(SLOT_COUNT) { 1.0 }
    private val mipLevel = IntArray(SLOT_COUNT)
    private val mipFade = FloatArray(SLOT_COUNT)
    private val noiseBank = LayerNoiseBank(sampleRate)
    private val noiseMix = FloatArray(SLOT_COUNT)
    private val targetNoiseMix = FloatArray(SLOT_COUNT)
    private val noiseColor = FloatArray(SLOT_COUNT)
    private val targetNoiseColor = FloatArray(SLOT_COUNT)

    // 8 ms click-free level/wave/tuning motion; independent of audio buffer length.
    private val slew = (1.0 - exp(-1.0 / (sampleRate * 0.008))).toFloat()
    private var hasLayers = false

    var totalGain: Float = 0f
        private set

    val hasAudibleLayers: Boolean get() = hasLayers

    fun configure(index: Int, slot: OscillatorSlot) {
        if (applied[index] === slot) return
        applied[index] = slot
        targetWave[index] = slot.waveform
        targetMorph[index] = slot.morph
        targetNoiseMix[index] = slot.noiseLevel
        targetNoiseColor[index] = slot.noiseColor
        targetGain[index] = if (slot.enabled) slot.level else 0f
        targetRatio[index] = 2.0.pow(
            (slot.coarseSemitones.toDouble() * 100.0 + slot.fineCents.toDouble()) / 1200.0
        )
        // Freeze a conservative harmonic ceiling for the lifetime of this edit.
        // Re-evaluating a decaying currentRatio at every render-block boundary
        // would select brighter mip tables sooner in 256-frame blocks than in a
        // single 8192-frame block, changing audio based on callback buffer size.
        mipCeilingRatio[index] = max(currentRatio[index], targetRatio[index])
        if (targetGain[index] > 0f || currentGain[index] > 0f) hasLayers = true
    }

    /**
     * Called once per block. Reserve harmonic headroom for the highest pitch
     * reached during portamento, detune changes, bend and vibrato.
     */
    fun preparePitch(highestBaseHz: Double, pitchModulationMask: Int = 0) {
        if (!hasLayers) return
        for (i in 0 until SLOT_COUNT) {
            if (targetGain[i] <= 0f && currentGain[i] <= 0f) continue
            val unmappedHighestHz = highestBaseHz * mipCeilingRatio[i]
            val highestHz = if ((pitchModulationMask and (1 shl i)) != 0) {
                // OSCn_PITCH can rise by at most +6 semitones; reserve its
                // harmonic ceiling for the whole block, separately per lane.
                unmappedHighestHz * OscillatorLayerModulation.MAX_UPWARD_PITCH_RATIO
            } else unmappedHighestHz
            mipLevel[i] = WavetableBank.levelForFrequency(highestHz, sampleRate)
            mipFade[i] = WavetableBank.fadeToLowerLevel(highestHz, sampleRate, mipLevel[i])
        }
    }

    /**
     * No sample is audible from an idle voice; prime each new patch lane at its
     * requested timbre/tuning instead of beginning a new note with a spurious
     * eight-millisecond glide from the default 0-semitone sine oscillator.
     */
    fun primeSilentSlot(index: Int) {
        currentGain[index] = targetGain[index]
        currentRatio[index] = targetRatio[index]
        wave[index] = targetWave[index]
        morph[index] = targetMorph[index]
        noiseMix[index] = targetNoiseMix[index]
        noiseColor[index] = targetNoiseColor[index]
        mipCeilingRatio[index] = targetRatio[index]
        if (currentGain[index] > 0f) hasLayers = true
    }

    /** Every new note can use its current harmonically safe mip ceiling. */
    fun resetPhase(seed: Long = 0L) {
        phases.fill(0.0)
        noiseBank.reset(seed)
        for (i in 0 until SLOT_COUNT) {
            mipCeilingRatio[i] = max(currentRatio[i], targetRatio[i])
        }
    }

    /**
     * Return the additional oscillator signal and publish the currently smoothed
     * sum of its layer gains. Caller blends (base + extra) / (1 + totalGain)
     * to prevent a full rack from multiplying the nominal voice output by nine.
     */
    fun nextSample(
        fundamentalHz: Double,
        gainMultipliers: FloatArray? = null,
        pitchMultipliers: DoubleArray? = null,
    ): Float {
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
            wave[i] += (targetWave[i] - wave[i]) * slew
            morph[i] += (targetMorph[i] - morph[i]) * slew
            val mixDiff = targetNoiseMix[i] - noiseMix[i]
            noiseMix[i] = if (abs(mixDiff) <= 0.00001f) targetNoiseMix[i]
                else (noiseMix[i] + mixDiff * slew).coerceIn(0f, 1f)
            val colorDiff = targetNoiseColor[i] - noiseColor[i]
            noiseColor[i] = if (abs(colorDiff) <= 0.00001f) targetNoiseColor[i]
                else (noiseColor[i] + colorDiff * slew).coerceIn(0f, 1f)
            val frequency = if (pitchMultipliers == null) {
                (fundamentalHz * ratio).coerceIn(0.0, sampleRate * 0.45)
            } else {
                (fundamentalHz * ratio * pitchMultipliers[i])
                    .coerceIn(0.0, sampleRate * 0.45)
            }
            // Exactly the previous wavetable PCM when noiseMix==0. No RNG
            // advances (or noise work runs) for a legacy/disabled noise layer.
            val oscillator = if (noiseMix[i] >= 0.999999f) 0f else
                WavetableBank.sample(
                    phases[i], mipLevel[i], wave[i], morph[i], mipFade[i]
                )
            val source = if (noiseMix[i] > 0f) {
                oscillator + (noiseBank.next(i, noiseColor[i]) - oscillator) * noiseMix[i]
            } else oscillator
            if (gainMultipliers == null) {
                // Untouched literal legacy arithmetic: no new route means
                // frame-by-frame, bit-for-bit identical additive PCM.
                extra += source * gain
                gains += gain
            } else {
                val routedGain = gain * gainMultipliers[i]
                extra += source * routedGain
                gains += routedGain
            }
            phases[i] += frequency / sampleRate
            if (phases[i] >= 1.0) phases[i] -= floor(phases[i])
        }
        hasLayers = anyRemaining
        totalGain = gains
        return extra
    }

}
