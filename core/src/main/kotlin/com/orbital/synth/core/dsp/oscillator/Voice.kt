package com.orbital.synth.core.dsp.oscillator

import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.dsp.analog.LadderFilter
import com.orbital.synth.core.dsp.analog.SemFilter
import com.orbital.synth.core.dsp.digital.FmAlgorithm
import com.orbital.synth.core.dsp.digital.PhaseDistortionShape
import com.orbital.synth.core.dsp.digital.PhaseDistortionVoice
import com.orbital.synth.core.dsp.digital.SixOperatorFmVoice
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
    private var targetVelocity = 0f
    // Retuning a held legato note to a new velocity must not step its amplitude
    // at nonzero envelope level. The smoothing time is constant across rates.
    private val velocitySlewCoefficient = (1.0 - exp(-1.0 / (sampleRate * 0.005))).toFloat()
    private val sustainSlewCoefficient = (1.0 - exp(-1.0 / (sampleRate * 0.005))).toFloat()
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
    // A stolen voice must keep the *filtered* sound alive for its tail. Replaying
    // the dry wavetable here creates a sharp transient at low cutoffs/resonance.
    private var ghostIntegrator1 = 0.0
    private var ghostIntegrator2 = 0.0
    private var ghostA1 = 1.0
    private var ghostA2 = 0.0
    private var ghostA3 = 0.0
    // Snapshot the actual oscillator increments/controls from the last sample:
    // frequency alone ignores bend, vibrato and the voice's Nyquist clamp.
    private var lastIncrement = 0.0
    private var lastLowerIncrement = 0.0
    private var lastUpperIncrement = 0.0
    private var lastWave = 0f
    private var lastMorph = 0f

    // Independent oscillator/filter architectures share the existing note ownership,
    // glide, ADSR, velocity slew, pan and master effects path. Each voice owns its
    // filter charges and digital operator phases; nothing allocates per sample.
    // 0 wavetable | 1 nonlinear ladder | 2 SEM multimode | 3 six-op FM | 4 phase distortion.
    private var engineModel = 0
    // Held by the audio worker for live filter retargets between block setups.
    private var filterCharacter = 0f
    private var ghostEngineModel = 0
    private var engineSwitchSamples = 0
    private var engineSwitchSeed = 0f
    private var lastRenderedSample = 0f
    private var ghostLastSample = 0f
    private var ghostHadRack = false
    // Eight independent, band-limited oscillator phases owned by this voice.
    // Shared patch edits arrive only from the audio worker at block boundaries.
    private val oscillatorRack = LayeredOscillatorRack(sampleRate)
    private val ladder = LadderFilter(sampleRate)
    private val sem = SemFilter(sampleRate)
    private val ghostLadder = LadderFilter(sampleRate)
    private val ghostSem = SemFilter(sampleRate)
    private val fm = SixOperatorFmVoice(sampleRate)
    private val phaseDistortion = PhaseDistortionVoice(sampleRate)

    init {
        // ORBITAL's main ADSR owns note amplitude, including slow analog pads.
        // Digital operator envelopes remain sustained until the parent ADSR ends.
        for (operator in 0..5) {
            fm.setOperatorEnvelope(operator, 0.0, 0.01, 1f, 0.05)
        }
        phaseDistortion.setEnvelope(0.0, 0.01, 1f, 0.05)
    }

    fun configureOscillatorSlot(index: Int, slot: OscillatorSlot) {
        oscillatorRack.configure(index, slot)
        if (!active && !hasTail) oscillatorRack.primeSilentSlot(index)
    }

    /** Called once per render block on the audio worker, never on the UI thread. */
    fun configureEngine(
        model: Int, character: Float, cutoffHz: Float, resonance: Float,
        wave: Float, morph: Float, updateFilter: Boolean = true
    ) {
        val requestedModel = model.coerceIn(0, 4)
        if (requestedModel != engineModel) {
            if (active) {
                // A short crossfade protects live held notes from an abrupt topology
                // switch (including old filter charges and operator phase changes).
                engineSwitchSamples = max(1, (sampleRate * 0.008).toInt())
                engineSwitchSeed = lastRenderedSample
                // Inactive analog filter charges must not leak into a new held-note
                // topology after temporarily playing FM or phase distortion.
                if (requestedModel == 1) ladder.reset()
                if (requestedModel == 2) sem.reset()
                if (requestedModel == 3) fm.noteOn(note, 1f)
                if (requestedModel == 4) phaseDistortion.noteOn(note, 1f)
            }
            engineModel = requestedModel
        }
        val tone = character.coerceIn(0f, 1f)
        filterCharacter = tone
        if (updateFilter) {
            ladder.configure(cutoffHz, resonance, tone * 0.9f)
            sem.configure(cutoffHz, resonance, tone * 0.5f, tone)
        }
        val fmChoice = (wave.coerceIn(0f, 1f) * (FmAlgorithm.entries.size - 1) + 0.5f)
            .toInt().coerceIn(0, FmAlgorithm.entries.lastIndex)
        fm.configure(FmAlgorithm.entries[fmChoice],
            character = (tone * 0.72f + morph.coerceIn(0f, 1f) * 0.28f),
            feedback = (tone * 0.8f + morph.coerceIn(0f, 1f) * 0.2f))
        val pdChoice = (wave.coerceIn(0f, 1f) *
            (PhaseDistortionShape.entries.size - 1) + 0.5f)
            .toInt().coerceIn(0, PhaseDistortionShape.entries.lastIndex)
        phaseDistortion.configure(
            shape = PhaseDistortionShape.entries[pdChoice],
            character = (morph.coerceIn(0f, 1f) * 0.70f + tone * 0.30f),
            drive = tone,
        )
    }

    fun configure(
        attackSeconds: Double,
        decaySeconds: Double,
        sustainLevel: Float,
        releaseSeconds: Double,
        cutoffHz: Double,
        resonance: Float,
        updateFilter: Boolean = true,
    ) {
        attackStep = (1.0 / max(1.0, attackSeconds * sampleRate)).toFloat()
        sustain = sustainLevel.coerceIn(0f, 1f)
        decayStep = ((1.0 - sustain) / max(1.0, decaySeconds * sampleRate)).toFloat()
        releaseFrames = max(1, (releaseSeconds * sampleRate).toInt())
        if (updateFilter) retargetSvf(cutoffHz, resonance)
    }

    /**
     * Audio-worker-only live filter cutoff and Q automation. Called at a bounded
     * control cadence; each physical model slews its poles per audio sample.
     * This never restarts an envelope, phase, analog filter charge or note.
     */
    fun retargetFilter(cutoffHz: Double, resonance: Float) {
        val safeCutoff = cutoffHz.coerceIn(20.0, sampleRate * 0.45)
        val safeResonance = resonance.coerceIn(0f, 1f)
        retargetSvf(safeCutoff, safeResonance)
        ladder.configure(safeCutoff.toFloat(), safeResonance, filterCharacter * 0.9f)
        sem.configure(safeCutoff.toFloat(), safeResonance, filterCharacter * 0.5f,
            filterCharacter)
    }

    private fun retargetSvf(cutoffHz: Double, resonance: Float) {
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
            ghostIncrement = lastIncrement
            ghostLowerIncrement = lastLowerIncrement
            ghostUpperIncrement = lastUpperIncrement
            ghostUnisonMix = unisonMix
            ghostLevel = envelopeLevel * velocity
            ghostWave = lastWave
            ghostMorph = lastMorph
            ghostMip = mipLevel
            ghostMipFade = mipFade
            ghostIntegrator1 = integrator1
            ghostIntegrator2 = integrator2
            ghostA1 = a1
            ghostA2 = a2
            ghostA3 = a3
            ghostEngineModel = engineModel
            ghostHadRack = oscillatorRack.hasAudibleLayers
            ghostLastSample = lastRenderedSample
            if (engineModel == 1) ladder.copyStateTo(ghostLadder)
            if (engineModel == 2) sem.copyStateTo(ghostSem)
        }

        note = midiNote
        velocity = noteVelocity.coerceIn(0f, 1f)
        targetVelocity = velocity
        startedAt = order
        active = true
        releasing = false
        stage = Stage.ATTACK
        envelopeLevel = 0f
        phase = 0.0
        lowerPhase = 0.0
        upperPhase = 0.0
        // Deterministically decorrelate each voice's eight noise lanes across
        // MIDI notes and allocation/retrigger order without any audio allocation.
        oscillatorRack.resetPhase((midiNote.toLong() shl 32) xor order)
        oscillatorRack.noteOn()
        integrator1 = 0.0
        integrator2 = 0.0
        ladder.reset()
        sem.reset()
        fm.noteOn(midiNote, 1f)
        phaseDistortion.noteOn(midiNote, 1f)
        engineSwitchSamples = 0
        lastRenderedSample = 0f

        targetFrequency = midiFrequency(midiNote)
        frequency = if (previousFrequency != null && previousFrequency.isFinite() &&
            previousFrequency > 0.0 && glideSeconds > 0.0
        ) previousFrequency else targetFrequency
        startGlide(glideSeconds)
        setPan(midiNote, smooth = previousToneAudible)
    }

    fun retune(midiNote: Int, noteVelocity: Float, glideSeconds: Double) {
        note = midiNote
        targetVelocity = noteVelocity.coerceIn(0f, 1f)
        targetFrequency = midiFrequency(midiNote)
        fm.retune(midiNote)
        phaseDistortion.retune(midiNote)
        startGlide(glideSeconds)
        setPan(midiNote, smooth = true)
    }

    fun noteOff() {
        if (!active || stage == Stage.RELEASE) return
        releasing = true
        stage = Stage.RELEASE
        releaseStep = envelopeLevel / releaseFrames
        if (releaseStep < 0.00000001f) releaseStep = 0.00000001f
        oscillatorRack.noteOff()
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
        oscillatorRack.resetPhase()
        oscillatorRack.hardStopAmp()
        ladder.reset()
        sem.reset()
        ghostLadder.reset()
        ghostSem.reset()
        fm.hardStop()
        phaseDistortion.hardStop()
        engineSwitchSamples = 0
        lastRenderedSample = 0f
        panSlewRemaining = 0
    }

    fun preparePitch(
        maxPitchMultiplier: Double,
        oscillatorPitchMipMask: Int = 0,
    ) {
        if (active) {
            // Reserve additional mip headroom for the sharpest unison side voice.
            // Include both current and next ratios while a detune move is slewing.
            val high = max(frequency, targetFrequency) * maxPitchMultiplier *
                max(detuneUp, targetDetuneUp)
            mipLevel = WavetableBank.levelForFrequency(high, sampleRate)
            mipFade = WavetableBank.fadeToLowerLevel(high, sampleRate, mipLevel)
            oscillatorRack.preparePitch(
                max(frequency, targetFrequency) * maxPitchMultiplier,
                oscillatorPitchMipMask,
            )
        }
    }

    fun nextSample(
        wave: Float,
        morph: Float,
        pitchMultiplier: Double,
        oscillatorGainMultipliers: FloatArray? = null,
        oscillatorPitchMultipliers: DoubleArray? = null,
    ): Float {
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
                velocity += (targetVelocity - velocity) * velocitySlewCoefficient
                val waveSample = if (engineModel <= 2) {
                    val center = WavetableBank.sample(phase, mipLevel, wave, morph, mipFade)
                    if (unisonMix <= 0.000001f) center else {
                        val low = WavetableBank.sample(lowerPhase, mipLevel, wave, morph, mipFade)
                        val high = WavetableBank.sample(upperPhase, mipLevel, wave, morph, mipFade)
                        center + ((center + low + high) / 3f - center) * unisonMix
                    }
                } else 0f
                val increment = min(sampleRate * 0.45, frequency * pitchMultiplier) / sampleRate
                val lowerIncrement = min(0.45, increment * detuneDown)
                val upperIncrement = min(0.45, increment * detuneUp)
                lastIncrement = increment
                lastLowerIncrement = lowerIncrement
                lastUpperIncrement = upperIncrement
                lastWave = wave
                lastMorph = morph
                phase += increment
                lowerPhase += lowerIncrement
                upperPhase += upperIncrement
                if (phase >= 1.0) phase -= floor(phase)
                if (lowerPhase >= 1.0) lowerPhase -= floor(lowerPhase)
                if (upperPhase >= 1.0) upperPhase -= floor(upperPhase)

                val oscillator = when (engineModel) {
                    3 -> fm.nextSampleAtFrequency(frequency, pitchMultiplier)
                    4 -> phaseDistortion.nextSampleAtFrequency(frequency, pitchMultiplier)
                    else -> waveSample
                }
                // The configured rack is audible in ALL five synthesis architectures.
                // Eight independently-tuned, wavetable-mip-safe oscillators mix
                // alongside the original engine, with bounded normalization.
                val extraOscillators = oscillatorRack.nextSample(
                    frequency * pitchMultiplier,
                    oscillatorGainMultipliers,
                    oscillatorPitchMultipliers,
                )
                val mixedOscillator = (oscillator + extraOscillators) /
                    (1f + oscillatorRack.totalGain)
                val input = (mixedOscillator * velocity * amplitude).toDouble()
                val filtered = when (engineModel) {
                    1 -> ladder.process(input.toFloat())
                    2 -> sem.process(input.toFloat())
                    else -> {
                        val v3 = input - integrator2
                        val v1 = a1 * integrator1 + a2 * v3
                        val v2 = integrator2 + a2 * integrator1 + a3 * v3
                        integrator1 = 2.0 * v1 - integrator1
                        integrator2 = 2.0 * v2 - integrator2
                        if (v2.isFinite()) v2.coerceIn(-8.0, 8.0).toFloat() else {
                            integrator1 = 0.0
                            integrator2 = 0.0
                            0f
                        }
                    }
                }
                result = filtered
                if (engineSwitchSamples > 0) {
                    val duration = max(1, (sampleRate * 0.008).toInt())
                    val oldWeight = engineSwitchSamples.toFloat() / duration
                    result = engineSwitchSeed * oldWeight + result * (1f - oldWeight)
                    engineSwitchSamples--
                }
                lastRenderedSample = result
            }
        }

        if (ghostSamples > 0) {
            val fade = ghostSamples / 64f
            if (ghostEngineModel >= 3 || ghostHadRack) {
                // FM/PD oscillator graphs are independent and cannot be copied cheaply.
                // A 64-sample tail of the actual last output avoids falling back to the
                // wrong wavetable family when stealing a digital voice.
                result += ghostLastSample * fade
            } else {
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
                val ghostInput = (fadingWave * ghostLevel).toDouble()
                val ghostFiltered = when (ghostEngineModel) {
                    1 -> ghostLadder.process(ghostInput.toFloat())
                    2 -> ghostSem.process(ghostInput.toFloat())
                    else -> {
                        val ghostV3 = ghostInput - ghostIntegrator2
                        val ghostV1 = ghostA1 * ghostIntegrator1 + ghostA2 * ghostV3
                        val ghostV2 = ghostIntegrator2 + ghostA2 * ghostIntegrator1 + ghostA3 * ghostV3
                        ghostIntegrator1 = 2.0 * ghostV1 - ghostIntegrator1
                        ghostIntegrator2 = 2.0 * ghostV2 - ghostIntegrator2
                        ghostV2.toFloat()
                    }
                }
                if (ghostFiltered.isFinite() &&
                    ghostIntegrator1.isFinite() && ghostIntegrator2.isFinite()
                ) {
                    result += ghostFiltered.coerceIn(-8f, 8f) * fade
                } else {
                    ghostSamples = 1 // safely retire a pathological stolen tail
                }
                ghostPhase += ghostIncrement
                ghostLowerPhase += ghostLowerIncrement
                ghostUpperPhase += ghostUpperIncrement
                if (ghostPhase >= 1.0) ghostPhase -= floor(ghostPhase)
                if (ghostLowerPhase >= 1.0) ghostLowerPhase -= floor(ghostLowerPhase)
                if (ghostUpperPhase >= 1.0) ghostUpperPhase -= floor(ghostUpperPhase)
            }
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
                if (envelopeLevel <= sustain) {
                    // Raising sustain above the current decay level must not
                    // jump directly to the new target at an arbitrary phase.
                    stage = Stage.SUSTAIN
                    envelopeLevel += (sustain - envelopeLevel) * sustainSlewCoefficient
                } else {
                    envelopeLevel = max(sustain, envelopeLevel - decayStep)
                    if (envelopeLevel <= sustain) stage = Stage.SUSTAIN
                }
            }
            Stage.SUSTAIN -> {
                // Sample-rate independent smoothing also handles automation after decay.
                envelopeLevel += (sustain - envelopeLevel) * sustainSlewCoefficient
            }
            Stage.RELEASE -> {
                envelopeLevel -= releaseStep
                if (envelopeLevel <= 0.00001f) {
                    envelopeLevel = 0f
                    note = -1
                    stage = Stage.OFF
                    active = false
                    releasing = false
                    // Digital operators use sustained internal envelopes; the parent
                    // ADSR owns release, so retire operator state only once silent.
                    fm.hardStop()
                    phaseDistortion.hardStop()
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
