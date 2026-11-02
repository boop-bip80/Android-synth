package com.orbital.synth.core.dsp.digital

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * Original phase-distortion families inspired by early digital synthesis.
 * These are not ROM samples or circuit-/chip-exact emulations.
 */
enum class PhaseDistortionShape {
    SAW, PULSE, RESONANT, DOUBLE_SINE, FORMANT, GLASS
}

/**
 * A phase-distortion oscillator with ADSR, per-note phase, key tracking, and
 * four-point integration over each sample interval. The integration attenuates
 * some high-frequency images; the generated phase curves are not guaranteed
 * alias-free. All audio work is allocation-free and outputs are bounded.
 *
 * Own one instance per polyphonic voice and call it only from the audio worker.
 */
class PhaseDistortionVoice(val sampleRate: Int) {
    init {
        require(sampleRate in 8_000..192_000)
    }

    var shape: PhaseDistortionShape = PhaseDistortionShape.SAW
        private set
    var note: Int = -1
        private set
    var isActive: Boolean = false
        private set
    private var phase = 0.0
    private var frequencyHz = 440.0
    private var velocity = 1.0
    private var character = 0.6
    private var drive = 0.0
    private var envelopeStage = 0
    private var envelope = 0.0
    private var attackStep = 1.0 / (sampleRate * 0.006)
    private var decayStep = 0.3 / (sampleRate * 0.3)
    private var sustain = 0.7
    private var releaseFrames = (sampleRate * 0.18).toInt()
    private var releaseStep = 0.0

    fun configure(
        shape: PhaseDistortionShape = this.shape,
        character: Float = 0.6f,
        drive: Float = 0.15f
    ) {
        require(character.isFinite() && drive.isFinite())
        this.shape = shape
        this.character = character.coerceIn(0f, 1f).toDouble()
        this.drive = drive.coerceIn(0f, 1f).toDouble()
    }

    fun setEnvelope(
        attackSeconds: Double,
        decaySeconds: Double,
        sustain: Float,
        releaseSeconds: Double
    ) {
        require(attackSeconds.isFinite() && decaySeconds.isFinite() &&
            sustain.isFinite() && releaseSeconds.isFinite())
        attackStep = 1.0 / max(1.0, attackSeconds.coerceIn(0.0, 30.0) * sampleRate)
        this.sustain = sustain.coerceIn(0f, 1f).toDouble()
        decayStep = (1.0 - this.sustain) /
            max(1.0, decaySeconds.coerceIn(0.0, 30.0) * sampleRate)
        releaseFrames = max(1, (releaseSeconds.coerceIn(0.0, 30.0) * sampleRate).toInt())
    }

    fun noteOn(midiNote: Int, velocity: Float = 1f) {
        if (midiNote !in 0..127 || !velocity.isFinite()) return
        if (velocity <= 0f) {
            noteOff()
            return
        }
        note = midiNote
        frequencyHz = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
        this.velocity = velocity.coerceIn(0f, 1f).toDouble()
        phase = 0.0
        envelope = 0.0
        envelopeStage = 1
        isActive = true
    }

    /** Change pitch without resetting waveform or envelope phase. */
    fun retune(midiNote: Int) {
        if (midiNote !in 0..127) return
        note = midiNote
        frequencyHz = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
    }

    fun noteOff() {
        if (!isActive || envelopeStage == 4) return
        envelopeStage = 4
        releaseStep = envelope / releaseFrames
    }

    fun hardStop() {
        isActive = false
        envelopeStage = 0
        envelope = 0.0
        note = -1
    }

    fun nextSample(pitchMultiplier: Double = 1.0): Float =
        nextSampleAtFrequency(frequencyHz, pitchMultiplier)

    /**
     * Frequency override follows a parent synth voice's portamento. This function
     * is deterministic for any render-block partitioning and allocates nothing.
     */
    fun nextSampleAtFrequency(fundamentalHz: Double, pitchMultiplier: Double = 1.0): Float {
        if (!isActive) return 0f
        if (!fundamentalHz.isFinite() || !pitchMultiplier.isFinite()) return 0f
        val env = advanceEnvelope()
        if (!isActive) return 0f
        val hz = (fundamentalHz * pitchMultiplier).coerceIn(0.0, sampleRate * 0.45)
        val increment = hz / sampleRate
        // At high fundamentals, tame the sharper wavefoldings before Nyquist.
        val spectralHeadroom = ((0.45 - increment * 4.0) / 0.35).coerceIn(0.0, 1.0)
        val color = 0.5 + (character - 0.5) * spectralHeadroom
        var integrated = 0.0
        for (tap in 0..3) {
            val p = phase + increment * (tap + 0.5) * 0.25
            integrated += waveform(p - floor(p), color)
        }
        phase += increment
        if (phase >= 1.0) phase -= floor(phase)
        val averaged = integrated * 0.25
        // Smooth rational saturation (unlike hard clip) with a bounded result.
        val gain = 1.0 + drive * 3.0
        val saturated = averaged * gain / (1.0 + (gain - 1.0) * abs(averaged))
        return (saturated * velocity * env).coerceIn(-1.0, 1.0).toFloat()
    }

    private fun waveform(p: Double, color: Double): Double {
        val warped = when (shape) {
            PhaseDistortionShape.SAW -> skew(p, 0.13 + color * 0.55)
            PhaseDistortionShape.PULSE -> skew(p, 0.12 + (1.0 - color) * 0.76)
            PhaseDistortionShape.RESONANT -> p
            PhaseDistortionShape.DOUBLE_SINE -> skew(p, 0.24 + color * 0.52)
            PhaseDistortionShape.FORMANT -> skew(p, 0.25 + color * 0.48)
            PhaseDistortionShape.GLASS -> p
        }
        return when (shape) {
            PhaseDistortionShape.SAW -> DigitalSineTable.sample(warped)
            PhaseDistortionShape.PULSE -> DigitalSineTable.sample(warped + 0.25)
            PhaseDistortionShape.RESONANT -> {
                // Integer harmonic crossfade keeps both endpoints continuous.
                val harmonics = 1.0 + color * 6.0
                val bottom = floor(harmonics)
                val fraction = harmonics - bottom
                val ring = DigitalSineTable.sample(p * bottom) * (1.0 - fraction) +
                    DigitalSineTable.sample(p * (bottom + 1.0)) * fraction
                ring * (1.0 - p).pow(0.65 + color)
            }
            PhaseDistortionShape.DOUBLE_SINE -> {
                val phaseMod = DigitalSineTable.sample(p) * color * 0.22
                DigitalSineTable.sample(warped + phaseMod)
            }
            PhaseDistortionShape.FORMANT -> {
                val harmonic = 2.0 + color * 4.0
                val base = floor(harmonic)
                val frac = harmonic - base
                val formant = DigitalSineTable.sample(warped * base) * (1.0 - frac) +
                    DigitalSineTable.sample(warped * (base + 1.0)) * frac
                val window = DigitalSineTable.sample(p * 0.5)
                formant * window * window
            }
            PhaseDistortionShape.GLASS -> {
                val sine = DigitalSineTable.sample(p)
                val bright = DigitalSineTable.sample(p * 3.0)
                val shimmer = DigitalSineTable.sample(p * 7.0)
                (sine + bright * color * 0.55 + shimmer * color * 0.30) /
                    (1.0 + color * 0.85)
            }
        }
    }

    private fun skew(p: Double, knee: Double): Double {
        val safeKnee = knee.coerceIn(0.08, 0.92)
        return if (p < safeKnee) {
            0.5 * p / safeKnee
        } else {
            0.5 + 0.5 * (p - safeKnee) / (1.0 - safeKnee)
        }
    }

    private fun advanceEnvelope(): Double {
        when (envelopeStage) {
            1 -> {
                envelope = (envelope + attackStep).coerceAtMost(1.0)
                if (envelope >= 1.0) envelopeStage = 2
            }
            2 -> {
                envelope = (envelope - decayStep).coerceAtLeast(sustain)
                if (envelope <= sustain) envelopeStage = 3
            }
            3 -> Unit
            4 -> {
                envelope = (envelope - releaseStep).coerceAtLeast(0.0)
                if (envelope <= 0.000001) hardStop()
            }
        }
        return envelope
    }
}
