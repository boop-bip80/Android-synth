package com.orbital.synth.core.dsp.oscillator

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
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

    // Smooth note-dependent stereo placement while an old tone is still audible.
    // Otherwise legato retunes jump equal-power pan gains at a nonzero sample.
    private var targetPanLeft = panLeft
    private var targetPanRight = panRight
    private var panStepLeft = 0f
    private var panStepRight = 0f
    private var panSlewRemaining = 0

    private var stage = Stage.OFF
    private var velocity = 0f
    private var phase = 0.0
    // The two side oscillators track the whole note even while their mix is dry.
    // Enabling unison mid-note therefore never resets the oscillator clock.
    private var lowerPhase = 0.0
    private var upperPhase = 0.0
    private var unisonMix = 0f
    private var targetUnisonMix = 0f
    private var detuneDown = 1.0
    private var detuneUp = 1.0
    private var targetDetuneDown = 1.0
    private var targetDetuneUp = 1.0
    private val unisonSlewCoefficient = (1.0 - exp(-1.0 / (sampleRate * 0.010))).toFloat()
    private var frequency = 440.0
    private var targetFrequency = 440.0
    private var glideFrames = 0
    private var glideIncrement = 0.0
    private var mipLevel = 0
    private var mipFade = 0f

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
    // Cutoff/resonance automation slews coefficient changes over 2 ms to avoid
    // discontinuities while keeping expensive tan() outside the audio callback.
    private var coefficientsInitialized = false
    private var coefficientSlewRemaining = 0
    private var coefficientStepA1 = 0.0
    private var coefficientStepA2 = 0.0
    private var coefficientStepA3 = 0.0
    private var targetA1 = 1.0
    private var targetA2 = 0.0
    private var targetA3 = 0.0

    // A stolen/retriggered voice contributes a 64-frame fading tail, avoiding hard clicks.
    private var ghostSamples = 0
    private var ghostPhase = 0.0
    private var ghostLowerPhase = 0.0
    private var ghostUpperPhase = 0.0
    private var ghostIncrement = 0.0
    private var ghostLowerIncrement = 0.0
    private var ghostUpperIncrement = 0.0
    private var ghostUnisonMix = 0f
    private var ghostLevel = 0f
    private var ghostWave = 0f
    private var ghostMorph = 0f
    private var ghostMip = 0
    private var ghostMipFade = 0f

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
        val nextA1 = 1.0 / (1.0 + g * (g + k))
        val nextA2 = g * nextA1
        val nextA3 = g * nextA2
        if (!coefficientsInitialized || !active) {
            a1 = nextA1
            a2 = nextA2
            a3 = nextA3
            targetA1 = nextA1
            targetA2 = nextA2
            targetA3 = nextA3
            coefficientSlewRemaining = 0
            coefficientsInitialized = true
        } else if (nextA1 != targetA1 || nextA2 != targetA2 || nextA3 != targetA3) {
            targetA1 = nextA1
            targetA2 = nextA2
            targetA3 = nextA3
            coefficientSlewRemaining = max(1, (sampleRate * 0.002).toInt())
            coefficientStepA1 = (targetA1 - a1) / coefficientSlewRemaining
            coefficientStepA2 = (targetA2 - a2) / coefficientSlewRemaining
            coefficientStepA3 = (targetA3 - a3) / coefficientSlewRemaining
        }
    }

    /**
     * Three-oscillator unison: center plus symmetric side voices up to +/-30 cents.
     * Amount crossfades the original single oscillator into the normalized trio.
     * Ratios are calculated per render block; only inexpensive slews run per sample.
     */
    fun configureUnison(amount: Float, detune: Float) {
        targetUnisonMix = amount.coerceIn(0f, 1f)
        val cents = detune.coerceIn(0f, 1f) * 30.0
        targetDetuneUp = 2.0.pow(cents / 1200.0)
        targetDetuneDown = 2.0.pow(-cents / 1200.0)
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

        val previousToneAudible = active || ghostSamples > 0
        if (active && envelopeLevel > 0.0001f) {
            ghostSamples = 64
            ghostPhase = phase
            ghostLowerPhase = lowerPhase
            ghostUpperPhase = upperPhase
            ghostIncrement = frequency / sampleRate
            ghostLowerIncrement = min(0.45, ghostIncrement * detuneDown)
            ghostUpperIncrement = min(0.45, ghostIncrement * detuneUp)
            ghostUnisonMix = unisonMix
            ghostLevel = envelopeLevel * velocity
            ghostWave = wave
            ghostMorph = morph
            ghostMip = mipLevel
            ghostMipFade = mipFade
        }

        note = midiNote
        velocity = noteVelocity.coerceIn(0f, 1f)
        startedAt = order
        active = true
        releasing = false
        stage = Stage.ATTACK
        envelopeLevel = 0f
        phase = 0.0
        lowerPhase = 0.0
        upperPhase = 0.0
        integrator1 = 0.0
        integrator2 = 0.0

        targetFrequency = midiFrequency(midiNote)
        frequency = if (previousFrequency != null && previousFrequency.isFinite() &&
            previousFrequency > 0.0 && glideSeconds > 0.0
        ) previousFrequency else targetFrequency
        startGlide(glideSeconds)
        setPan(midiNote, smooth = previousToneAudible)
    }

    fun retune(midiNote: Int, noteVelocity: Float, glideSeconds: Double) {
        note = midiNote
        velocity = noteVelocity.coerceIn(0f, 1f)
        targetFrequency = midiFrequency(midiNote)
        startGlide(glideSeconds)
        setPan(midiNote, smooth = true)
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
        panSlewRemaining = 0
    }

    fun preparePitch(maxPitchMultiplier: Double) {
        if (active) {
            // Reserve additional mip headroom for the sharpest unison side voice.
            // Include both current and next ratios while a detune move is slewing.
            val high = max(frequency, targetFrequency) * maxPitchMultiplier *
                max(detuneUp, targetDetuneUp)
            mipLevel = WavetableBank.levelForFrequency(high, sampleRate)
            mipFade = WavetableBank.fadeToLowerLevel(high, sampleRate, mipLevel)
        }
    }

    fun nextSample(wave: Float, morph: Float, pitchMultiplier: Double): Float {
        advancePan()
        var result = 0f
        if (active) {
            val amplitude = advanceEnvelope()
            if (active) {
                if (glideFrames > 0) {
                    frequency += glideIncrement
                    glideFrames--
                    if (glideFrames == 0) frequency = targetFrequency
                }

                advanceFilterCoefficients()
                advanceUnisonControls()
                val centerSample = WavetableBank.sample(phase, mipLevel, wave, morph, mipFade)
                val waveSample = if (unisonMix <= 0.000001f) centerSample else {
                    val low = WavetableBank.sample(lowerPhase, mipLevel, wave, morph, mipFade)
                    val high = WavetableBank.sample(upperPhase, mipLevel, wave, morph, mipFade)
                    val trio = (centerSample + low + high) / 3f
                    centerSample + (trio - centerSample) * unisonMix
                }
                val increment = min(sampleRate * 0.45, frequency * pitchMultiplier) / sampleRate
                phase += increment
                lowerPhase += min(0.45, increment * detuneDown)
                upperPhase += min(0.45, increment * detuneUp)
                if (phase >= 1.0) phase -= floor(phase)
                if (lowerPhase >= 1.0) lowerPhase -= floor(lowerPhase)
                if (upperPhase >= 1.0) upperPhase -= floor(upperPhase)

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
            val center = WavetableBank.sample(
                ghostPhase, ghostMip, ghostWave, ghostMorph, ghostMipFade
            )
            val fadingWave = if (ghostUnisonMix <= 0.000001f) center else {
                val low = WavetableBank.sample(
                    ghostLowerPhase, ghostMip, ghostWave, ghostMorph, ghostMipFade
                )
                val high = WavetableBank.sample(
                    ghostUpperPhase, ghostMip, ghostWave, ghostMorph, ghostMipFade
                )
                center + ((center + low + high) / 3f - center) * ghostUnisonMix
            }
            result += fadingWave * ghostLevel * fade
            ghostPhase += ghostIncrement
            ghostLowerPhase += ghostLowerIncrement
            ghostUpperPhase += ghostUpperIncrement
            if (ghostPhase >= 1.0) ghostPhase -= floor(ghostPhase)
            if (ghostLowerPhase >= 1.0) ghostLowerPhase -= floor(ghostLowerPhase)
            if (ghostUpperPhase >= 1.0) ghostUpperPhase -= floor(ghostUpperPhase)
            ghostSamples--
        }
        return result
    }

    private fun advanceUnisonControls() {
        unisonMix += (targetUnisonMix - unisonMix) * unisonSlewCoefficient
        detuneDown += (targetDetuneDown - detuneDown) * unisonSlewCoefficient
        detuneUp += (targetDetuneUp - detuneUp) * unisonSlewCoefficient
    }

    private fun advanceFilterCoefficients() {
        if (coefficientSlewRemaining <= 0) return
        a1 += coefficientStepA1
        a2 += coefficientStepA2
        a3 += coefficientStepA3
        coefficientSlewRemaining--
        if (coefficientSlewRemaining == 0) {
            // Snap to exact targets to avoid numerical drift after many edits.
            a1 = targetA1
            a2 = targetA2
            a3 = targetA3
        }
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

    private fun setPan(midiNote: Int, smooth: Boolean) {
        val pan = (((midiNote % 12) - 5.5) / 5.5 * 0.18).coerceIn(-1.0, 1.0)
        val angle = (pan + 1.0) * (PI / 4.0)
        targetPanLeft = cos(angle).toFloat()
        targetPanRight = sin(angle).toFloat()
        if (!smooth) {
            panLeft = targetPanLeft
            panRight = targetPanRight
            panSlewRemaining = 0
        } else {
            panSlewRemaining = max(1, (sampleRate * 0.005).toInt())
            panStepLeft = (targetPanLeft - panLeft) / panSlewRemaining
            panStepRight = (targetPanRight - panRight) / panSlewRemaining
        }
    }

    private fun advancePan() {
        if (panSlewRemaining <= 0) return
        panLeft += panStepLeft
        panRight += panStepRight
        panSlewRemaining--
        if (panSlewRemaining == 0) {
            // Exact final gains preserve equal-power panning after the short ramp.
            panLeft = targetPanLeft
            panRight = targetPanRight
        }
    }

    companion object {
        fun midiFrequency(note: Int): Double =
            440.0 * 2.0.pow((note - 69) / 12.0)
    }
}
