package com.orbital.synth.core.dsp.oscillator

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * One audio-thread-owned synthesizer voice. Note events and parameter changes are delivered
 * only at render-block boundaries by OrbitalSynth. Inner sample processing is allocation free.
 */
internal class Voice(private val sampleRate: Int) {
    private enum class Stage { OFF, ATTACK, DECAY, SUSTAIN, RELEASE }

    var note: Int = -1
        private set
    var active: Boolean = false
        private set
    var releasing: Boolean = false
        private set
    var startedAt: Long = 0L
        private set
    var envelopeLevel: Float = 0f
        private set
    var panLeft: Float = 0.70710677f
        private set
    var panRight: Float = 0.70710677f
        private set

    val hasTail: Boolean get() = ghostSamples > 0

    private var stage = Stage.OFF
    private var velocity = 0f
    private var phase = 0.0
    private var frequency = 440.0
    private var targetFrequency = 440.0
    private var glideFrames = 0
    private var glideIncrement = 0.0
    private var mipLevel = 0

    private var attackStep = 1f
    private var decayStep = 0.01f
    private var sustain = 0.7f
    private var releaseFrames = 4800
    private var releaseStep = 0.001f

    // Stable zero-delay-feedback state-variable low-pass filter (per voice).
    private var integrator1 = 0.0
    private var integrator2 = 0.0
    private var a1 = 1.0
    private var a2 = 0.0
    private var a3 = 0.0

    // A stolen/retriggered voice contributes a 64-frame fading tail, avoiding hard clicks.
    private var ghostSamples = 0
    private var ghostPhase = 0.0
    private var ghostIncrement = 0.0
    private var ghostLevel = 0f
    private var ghostWave = 0f
    private var ghostMorph = 0f
    private var ghostMip = 0

    fun configure(
        attackSeconds: Double,
        decaySeconds: Double,
        sustainLevel: Float,
        releaseSeconds: Double,
        cutoffHz: Double,
        resonance: Float
    ) {
        attackStep = (1.0 / max(1.0, attackSeconds * sampleRate)).toFloat()
        sustain = sustainLevel.coerceIn(0f, 1f)
        decayStep = ((1.0 - sustain) / max(1.0, decaySeconds * sampleRate)).toFloat()
        releaseFrames = max(1, (releaseSeconds * sampleRate).toInt())

        val safeCutoff = cutoffHz.coerceIn(20.0, sampleRate * 0.45)
        val g = tan(PI * safeCutoff / sampleRate)
        val k = 2.0 - 1.80 * resonance.coerceIn(0f, 1f)
        a1 = 1.0 / (1.0 + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun begin(
        midiNote: Int,
        noteVelocity: Float,
        order: Long,
        previousFrequency: Double?,
        glideSeconds: Double,
        wave: Float,
        morph: Float,
        legato: Boolean = false
    ) {
        if (legato && active) {
            retune(midiNote, noteVelocity, glideSeconds)
            return
        }

        if (active && envelopeLevel > 0.0001f) {
            ghostSamples = 64
            ghostPhase = phase
            ghostIncrement = frequency / sampleRate
            ghostLevel = envelopeLevel * velocity
            ghostWave = wave
            ghostMorph = morph
            ghostMip = mipLevel
        }

        note = midiNote
        velocity = noteVelocity.coerceIn(0f, 1f)
        startedAt = order
        active = true
        releasing = false
        stage = Stage.ATTACK
        envelopeLevel = 0f
        phase = 0.0
        integrator1 = 0.0
        integrator2 = 0.0

        targetFrequency = midiFrequency(midiNote)
        frequency = if (previousFrequency != null && previousFrequency.isFinite() &&
            previousFrequency > 0.0 && glideSeconds > 0.0
        ) previousFrequency else targetFrequency
        startGlide(glideSeconds)
        setPan(midiNote)
    }

    fun retune(midiNote: Int, noteVelocity: Float, glideSeconds: Double) {
        note = midiNote
        velocity = noteVelocity.coerceIn(0f, 1f)
        targetFrequency = midiFrequency(midiNote)
        startGlide(glideSeconds)
        setPan(midiNote)
    }

    fun noteOff() {
        if (!active || stage == Stage.RELEASE) return
        releasing = true
        stage = Stage.RELEASE
        releaseStep = envelopeLevel / releaseFrames
        if (releaseStep < 0.00000001f) releaseStep = 0.00000001f
    }

    fun hardStop() {
        stage = Stage.OFF
        active = false
        releasing = false
        note = -1
        envelopeLevel = 0f
        integrator1 = 0.0
        integrator2 = 0.0
        ghostSamples = 0
    }

    fun preparePitch(maxPitchMultiplier: Double) {
        if (active) {
            val high = max(frequency, targetFrequency) * maxPitchMultiplier
            mipLevel = WavetableBank.levelForFrequency(high, sampleRate)
        }
    }

    fun nextSample(wave: Float, morph: Float, pitchMultiplier: Double): Float {
        var result = 0f
        if (active) {
            val amplitude = advanceEnvelope()
            if (active) {
                if (glideFrames > 0) {
                    frequency += glideIncrement
                    glideFrames--
                    if (glideFrames == 0) frequency = targetFrequency
                }

                val waveSample = WavetableBank.sample(phase, mipLevel, wave, morph)
                val frequencyNow = min(sampleRate * 0.45, frequency * pitchMultiplier)
                phase += frequencyNow / sampleRate
                if (phase >= 1.0) phase -= floor(phase)

                val input = (waveSample * velocity * amplitude).toDouble()
                val v3 = input - integrator2
                val v1 = a1 * integrator1 + a2 * v3
                val v2 = integrator2 + a2 * integrator1 + a3 * v3
                integrator1 = 2.0 * v1 - integrator1
                integrator2 = 2.0 * v2 - integrator2
                result = if (v2.isFinite()) v2.coerceIn(-8.0, 8.0).toFloat() else {
                    integrator1 = 0.0
                    integrator2 = 0.0
                    0f
                }
            }
        }

        if (ghostSamples > 0) {
            val fade = ghostSamples / 64f
            result += WavetableBank.sample(ghostPhase, ghostMip, ghostWave, ghostMorph) *
                ghostLevel * fade
            ghostPhase += ghostIncrement
            if (ghostPhase >= 1.0) ghostPhase -= floor(ghostPhase)
            ghostSamples--
        }
        return result
    }

    private fun advanceEnvelope(): Float {
        when (stage) {
            Stage.ATTACK -> {
                envelopeLevel = min(1f, envelopeLevel + attackStep)
                if (envelopeLevel >= 1f) stage = Stage.DECAY
            }
            Stage.DECAY -> {
                envelopeLevel -= decayStep
                if (envelopeLevel <= sustain) {
                    envelopeLevel = sustain
                    stage = Stage.SUSTAIN
                }
            }
            Stage.SUSTAIN -> {
                // Slew moving sustain values; parameter automation must not create a click.
                envelopeLevel += (sustain - envelopeLevel) * 0.004f
            }
            Stage.RELEASE -> {
                envelopeLevel -= releaseStep
                if (envelopeLevel <= 0.00001f) {
                    envelopeLevel = 0f
                    note = -1
                    stage = Stage.OFF
                    active = false
                    releasing = false
                }
            }
            Stage.OFF -> return 0f
        }
        return envelopeLevel
    }

    private fun startGlide(glideSeconds: Double) {
        glideFrames = if (glideSeconds <= 0.0) 0 else
            max(1, (glideSeconds * sampleRate).toInt())
        glideIncrement = if (glideFrames > 0)
            (targetFrequency - frequency) / glideFrames else 0.0
        if (glideFrames == 0) frequency = targetFrequency
    }

    private fun setPan(midiNote: Int) {
        val pan = (((midiNote % 12) - 5.5) / 5.5 * 0.18).coerceIn(-1.0, 1.0)
        val angle = (pan + 1.0) * (PI / 4.0)
        panLeft = cos(angle).toFloat()
        panRight = sin(angle).toFloat()
    }

    companion object {
        fun midiFrequency(note: Int): Double =
            440.0 * 2.0.pow((note - 69) / 12.0)
    }
}
