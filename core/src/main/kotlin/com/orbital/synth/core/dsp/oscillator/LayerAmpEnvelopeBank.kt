package com.orbital.synth.core.dsp.oscillator

import com.orbital.synth.core.LayerAmpEnvelope
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Worker-owned per-layer amplitude envelopes, one bank per already-allocated voice.
 *
 * Each slot adds its own gated ADSR inside the shared master voice VCA. The master
 * voice release may end earlier than an individual layer's release: this is
 * intentional; this class never creates hidden voices or unbounded FX tails.
 *
 * All state lives in eight primitive lanes. Configuring a slot is done at an
 * audio-block boundary; [nextGain] allocates nothing and never calls pow/exp.
 */
internal class LayerAmpEnvelopeBank(sampleRate: Int) {
    private companion object {
        const val LANES = 8
        const val OFF = 0
        const val ATTACK = 1
        const val DECAY = 2
        const val SUSTAIN = 3
        const val RELEASE = 4
    }

    private val applied = arrayOfNulls<LayerAmpEnvelope>(LANES)
    private val enabled = BooleanArray(LANES)
    private val stages = IntArray(LANES)
    private val levels = FloatArray(LANES)
    private val bypassMix = FloatArray(LANES)
    private val targetMix = FloatArray(LANES)
    private val attackStep = FloatArray(LANES)
    private val decayStep = FloatArray(LANES)
    private val releaseFrames = IntArray(LANES) { 1 }
    private val releaseStep = FloatArray(LANES)
    private val targetSustain = FloatArray(LANES) { 1f }
    private val sampleRateHz = sampleRate
    private val slew = (1.0 - exp(-1.0 / (sampleRate * 0.008))).toFloat()

    fun configure(slot: Int, config: LayerAmpEnvelope) {
        if (applied[slot] === config) return
        val wasEnabled = enabled[slot]
        applied[slot] = config
        enabled[slot] = config.enabled
        targetMix[slot] = if (config.enabled) 1f else 0f
        targetSustain[slot] = config.sustain

        val attackSeconds = 0.001 + config.attack.toDouble() * config.attack * 4.0
        val decaySeconds = 0.005 + config.decay.toDouble() * config.decay * 3.0
        val releaseSeconds = 0.005 + config.release.toDouble() * config.release * 4.0
        attackStep[slot] = 1f / max(1, (sampleRateHz * attackSeconds).toInt())
        decayStep[slot] = (1f - config.sustain) /
            max(1, (sampleRateHz * decaySeconds).toInt())
        releaseFrames[slot] = max(1, (sampleRateHz * releaseSeconds).toInt())
        if (stages[slot] == RELEASE) {
            releaseStep[slot] = levels[slot] / releaseFrames[slot]
        }

        // An envelope switched ON while a note is already held begins at its
        // own attack, then its wet VCA is crossfaded in over eight milliseconds.
        // Idle voices are primed by the owner before their next note gate.
        if (config.enabled && !wasEnabled) {
            stages[slot] = ATTACK
            levels[slot] = 0f
        }
    }

    fun primeSilentSlot(slot: Int) {
        bypassMix[slot] = targetMix[slot]
        stages[slot] = OFF
        levels[slot] = 0f
    }

    /** Repeated/stealing polyphonic note gates restart each layer; legato does not. */
    fun noteOn() {
        for (slot in 0 until LANES) {
            if (enabled[slot] || bypassMix[slot] > 0f) {
                stages[slot] = ATTACK
                levels[slot] = 0f
            }
        }
    }

    fun noteOff() {
        for (slot in 0 until LANES) {
            if (stages[slot] != OFF && stages[slot] != RELEASE) {
                stages[slot] = RELEASE
                releaseStep[slot] = levels[slot] / releaseFrames[slot]
            }
        }
    }

    fun hardStop() {
        for (slot in 0 until LANES) {
            stages[slot] = OFF
            levels[slot] = 0f
            bypassMix[slot] = targetMix[slot]
        }
    }

    fun isEngaged(slot: Int): Boolean = targetMix[slot] > 0f || bypassMix[slot] > 0f

    /** 0..1 VCA multiplier with a click-free bypass; exact 1 when unused. */
    fun nextGain(slot: Int): Float {
        if (!isEngaged(slot)) return 1f
        val mixTarget = targetMix[slot]
        var mix = bypassMix[slot] + (mixTarget - bypassMix[slot]) * slew
        if (abs(mixTarget - mix) < 0.00001f) mix = mixTarget
        bypassMix[slot] = mix

        var level = levels[slot]
        when (stages[slot]) {
            ATTACK -> {
                level = min(1f, level + attackStep[slot])
                if (level >= 1f) stages[slot] = DECAY
            }
            DECAY -> {
                if (level <= targetSustain[slot]) {
                    // Raising sustain mid-decay must not jump the envelope up
                    // instantaneously; the same 8 ms slew owns the edit.
                    stages[slot] = SUSTAIN
                    level += (targetSustain[slot] - level) * slew
                } else {
                    level = max(targetSustain[slot], level - decayStep[slot])
                    if (level <= targetSustain[slot]) stages[slot] = SUSTAIN
                }
            }
            SUSTAIN -> {
                level += (targetSustain[slot] - level) * slew
            }
            RELEASE -> {
                level = max(0f, level - releaseStep[slot])
                if (level <= 0.00001f) {
                    level = 0f
                    stages[slot] = OFF
                }
            }
            else -> level = 0f
        }
        levels[slot] = level
        return 1f + (level - 1f) * mix
    }
}
