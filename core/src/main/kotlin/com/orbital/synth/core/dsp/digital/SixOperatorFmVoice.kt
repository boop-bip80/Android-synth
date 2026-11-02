package com.orbital.synth.core.dsp.digital

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Eight original six-operator FM routings. These describe synthesis topologies;
 * they are not Yamaha DX7 preset data or bit-exact hardware algorithms.
 */
enum class FmAlgorithm {
    SIX_STACK, DUAL_STACK, TRIPLE_PAIR, ELECTRIC_PIANO,
    BRASS, BELL, ADDITIVE, NEBULA
}

/**
 * Shared interpolated sine oscillator. Generated once, not on an audio callback.
 * The extra endpoint makes interpolation continuous at phase wrap.
 */
internal object DigitalSineTable {
    private const val SIZE = 4096
    private val data = FloatArray(SIZE + 1) { i ->
        sin(i * (2.0 * PI / SIZE)).toFloat()
    }

    fun sample(cycles: Double): Double {
        val wrapped = cycles - floor(cycles)
        val index = wrapped * SIZE
        val left = index.toInt()
        val fraction = index - left
        return data[left] + (data[left + 1] - data[left]) * fraction
    }
}

/**
 * Audio-thread-owned six-operator phase-modulation/FM voice. All six operators have
 * independent ADSR envelopes, ratios, detunes and levels. Each routing is an acyclic
 * operator graph except for bounded self-feedback on the highest operator.
 *
 * Calling configure(), configureOperator(), and setOperatorEnvelope() on block
 * boundaries is recommended. nextSample() and nextSampleAtFrequency() never allocate.
 * The output is finite and bounded to [-1, 1]. FM sidebands are not a bit-exact
 * recreation of a hardware instrument; high-frequency modulation is conservatively
 * tapered, but this is not a proof of full alias rejection.
 */
class SixOperatorFmVoice(val sampleRate: Int) {
    init {
        require(sampleRate in 8_000..192_000)
    }

    private val phases = DoubleArray(6)
    private val ratios = doubleArrayOf(1.0, 1.0, 2.0, 3.0, 0.5, 4.0)
    private val levels = doubleArrayOf(1.0, 0.42, 0.56, 0.36, 0.50, 0.38)
    private val modulationScales = DoubleArray(6) { 1.0 }
    private val envelopeStage = IntArray(6)
    private val envelopeLevel = DoubleArray(6)
    private val attackStep = DoubleArray(6) { 1.0 / (sampleRate * 0.005) }
    private val decayStep = DoubleArray(6) { 0.3 / (sampleRate * 0.35) }
    private val sustainLevel = DoubleArray(6) { 0.7 }
    private val releaseFrames = IntArray(6) { max(1, (sampleRate * 0.20).toInt()) }
    private val releaseStep = DoubleArray(6)
    // Index: source operator * 6 + destination operator.
    private val routes = DoubleArray(36)
    private val carriers = DoubleArray(6)
    private val modulationInput = DoubleArray(6)
    private var carrierNorm = 1.0
    private var fundamentalHz = 440.0
    private var velocity = 1.0
    private var character = 0.65
    private var feedback = 0.12
    private var feedbackPrevious = 0.0
    var algorithm: FmAlgorithm = FmAlgorithm.ELECTRIC_PIANO
        private set
    var note: Int = -1
        private set
    var isActive: Boolean = false
        private set

    init {
        setAlgorithm(FmAlgorithm.ELECTRIC_PIANO)
    }

    /** Normalized timbre macro and optional high-operator self-feedback. */
    fun configure(
        algorithm: FmAlgorithm = this.algorithm,
        character: Float = 0.65f,
        feedback: Float = 0.12f
    ) {
        require(character.isFinite() && feedback.isFinite())
        if (this.algorithm != algorithm) setAlgorithm(algorithm)
        this.character = character.coerceIn(0f, 1f).toDouble()
        this.feedback = feedback.coerceIn(0f, 1f).toDouble()
    }

    /**
     * Operator 0 is normally the first carrier; operator 5 is the top modulator.
     * Ratios are relative to played pitch, level is amplitude and modulationScale
     * affects this operator's outgoing modulation edges.
     */
    fun configureOperator(
        operator: Int,
        ratio: Double,
        level: Float,
        modulationScale: Float = 1f,
        detuneCents: Float = 0f
    ) {
        require(operator in 0..5)
        require(ratio.isFinite() && ratio in 0.125..32.0)
        require(level.isFinite() && modulationScale.isFinite() && detuneCents.isFinite())
        ratios[operator] = ratio * 2.0.pow(detuneCents.coerceIn(-100f, 100f) / 1200.0)
        levels[operator] = level.coerceIn(0f, 1f).toDouble()
        modulationScales[operator] = modulationScale.coerceIn(0f, 2f).toDouble()
    }

    fun setOperatorEnvelope(
        operator: Int,
        attackSeconds: Double,
        decaySeconds: Double,
        sustain: Float,
        releaseSeconds: Double
    ) {
        require(operator in 0..5)
        require(attackSeconds.isFinite() && decaySeconds.isFinite() && releaseSeconds.isFinite())
        require(sustain.isFinite())
        attackStep[operator] = 1.0 / max(1.0, attackSeconds.coerceIn(0.0, 30.0) * sampleRate)
        sustainLevel[operator] = sustain.coerceIn(0f, 1f).toDouble()
        decayStep[operator] = (1.0 - sustainLevel[operator]) /
            max(1.0, decaySeconds.coerceIn(0.0, 30.0) * sampleRate)
        releaseFrames[operator] =
            max(1, (releaseSeconds.coerceIn(0.0, 30.0) * sampleRate).toInt())
    }

    fun setAlgorithm(next: FmAlgorithm) {
        algorithm = next
        java.util.Arrays.fill(routes, 0.0)
        java.util.Arrays.fill(carriers, 0.0)
        when (next) {
            FmAlgorithm.SIX_STACK -> {
                edge(5, 4, 3.2); edge(4, 3, 2.8); edge(3, 2, 2.4)
                edge(2, 1, 2.1); edge(1, 0, 1.9); carriers[0] = 1.0
            }
            FmAlgorithm.DUAL_STACK -> {
                edge(5, 4, 3.3); edge(4, 0, 2.2)
                edge(3, 2, 2.8); edge(2, 1, 2.1)
                carriers[0] = 0.85; carriers[1] = 0.65
            }
            FmAlgorithm.TRIPLE_PAIR -> {
                edge(5, 4, 3.4); edge(3, 2, 2.8); edge(1, 0, 2.3)
                carriers[4] = 0.50; carriers[2] = 0.65; carriers[0] = 0.95
            }
            FmAlgorithm.ELECTRIC_PIANO -> {
                edge(5, 4, 2.8); edge(4, 0, 2.1)
                edge(3, 2, 2.5); edge(2, 1, 2.0)
                carriers[0] = 1.0; carriers[1] = 0.38
            }
            FmAlgorithm.BRASS -> {
                edge(5, 3, 2.4); edge(4, 2, 2.2)
                edge(3, 0, 1.8); edge(2, 1, 1.5)
                carriers[0] = 0.95; carriers[1] = 0.70
            }
            FmAlgorithm.BELL -> {
                edge(5, 4, 5.1); edge(4, 0, 3.4)
                edge(3, 1, 4.0); edge(2, 1, 2.6)
                carriers[0] = 0.80; carriers[1] = 0.65
            }
            FmAlgorithm.ADDITIVE -> {
                carriers[0] = 1.0; carriers[1] = 0.60; carriers[2] = 0.40
                carriers[3] = 0.30; carriers[4] = 0.22; carriers[5] = 0.16
            }
            FmAlgorithm.NEBULA -> {
                edge(5, 4, 3.3); edge(5, 2, 1.7); edge(4, 1, 2.9)
                edge(3, 0, 2.6); edge(2, 0, 1.5); edge(1, 0, 0.8)
                carriers[0] = 0.90; carriers[1] = 0.25; carriers[3] = 0.15
            }
        }
        carrierNorm = carriers.sum().coerceAtLeast(1.0)
    }

    private fun edge(source: Int, destination: Int, depth: Double) {
        require(source > destination)
        routes[source * 6 + destination] = depth
    }

    /** Retrigger envelopes and phase clocks; velocity cannot be non-finite. */
    fun noteOn(midiNote: Int, velocity: Float = 1f) {
        if (midiNote !in 0..127 || !velocity.isFinite()) return
        if (velocity <= 0f) {
            noteOff()
            return
        }
        fundamentalHz = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
        note = midiNote
        this.velocity = velocity.coerceIn(0f, 1f).toDouble()
        for (i in 0..5) {
            phases[i] = 0.0
            envelopeLevel[i] = 0.0
            envelopeStage[i] = 1
        }
        feedbackPrevious = 0.0
        isActive = true
    }

    /** Legato retune changes key tracking without restarting operator envelopes. */
    fun retune(midiNote: Int) {
        if (midiNote !in 0..127) return
        note = midiNote
        fundamentalHz = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
    }

    fun noteOff() {
        if (!isActive) return
        for (i in 0..5) {
            if (envelopeStage[i] != 0 && envelopeStage[i] != 4) {
                envelopeStage[i] = 4
                releaseStep[i] = envelopeLevel[i] / releaseFrames[i]
            }
        }
    }

    fun hardStop() {
        java.util.Arrays.fill(envelopeStage, 0)
        java.util.Arrays.fill(envelopeLevel, 0.0)
        isActive = false
        note = -1
        feedbackPrevious = 0.0
    }

    fun nextSample(pitchMultiplier: Double = 1.0): Float =
        nextSampleAtFrequency(fundamentalHz, pitchMultiplier)

    /**
     * Fundamental override is useful when a parent voice implements portamento.
     * Modulation and all six operator phases follow the same glide.
     */
    fun nextSampleAtFrequency(frequencyHz: Double, pitchMultiplier: Double = 1.0): Float {
        if (!isActive) return 0f
        if (!frequencyHz.isFinite() || !pitchMultiplier.isFinite()) return 0f
        val hz = (frequencyHz * pitchMultiplier).coerceIn(0.0, sampleRate * 0.45)
        java.util.Arrays.fill(modulationInput, 0.0)
        var output = 0.0
        var live = false
        // Descending traversal guarantees every source precedes its destination.
        for (i in 5 downTo 0) {
            val envelope = advanceEnvelope(i)
            if (envelopeStage[i] != 0) live = true
            val operatorHz = hz * ratios[i]
            val fundamentalIncrement = operatorHz.coerceAtMost(sampleRate * 0.45) / sampleRate
            // Reduce modulation near Nyquist. This bounds harsh high-register sidebands;
            // exact antialiasing would require oversampled and filtered FM.
            val safety = ((0.45 - operatorHz / sampleRate) / 0.35).coerceIn(0.0, 1.0)
            val selfFeedback = if (i == 5) feedbackPrevious * feedback * 3.0 else 0.0
            val deviation = (modulationInput[i] * safety +
                selfFeedback * safety).coerceIn(-12.0, 12.0)
            val sample = DigitalSineTable.sample(phases[i] + deviation / (2.0 * PI)) *
                envelope * levels[i]
            if (i == 5) feedbackPrevious = sample
            phases[i] += fundamentalIncrement
            if (phases[i] >= 1.0) phases[i] -= floor(phases[i])
            output += sample * carriers[i]
            val scaledModulation = sample * modulationScales[i] *
                (0.18 + character * 1.75)
            for (destination in 0 until i) {
                val depth = routes[i * 6 + destination]
                if (depth != 0.0) {
                    modulationInput[destination] += scaledModulation * depth
                }
            }
        }
        if (!live) {
            hardStop()
            return 0f
        }
        // Carrier sum normalization remains valid through arbitrary operator levels.
        return (output * velocity / carrierNorm).coerceIn(-1.0, 1.0).toFloat()
    }

    private fun advanceEnvelope(i: Int): Double {
        when (envelopeStage[i]) {
            1 -> {
                envelopeLevel[i] = (envelopeLevel[i] + attackStep[i]).coerceAtMost(1.0)
                if (envelopeLevel[i] >= 1.0) envelopeStage[i] = 2
            }
            2 -> {
                envelopeLevel[i] = (envelopeLevel[i] - decayStep[i])
                    .coerceAtLeast(sustainLevel[i])
                if (envelopeLevel[i] <= sustainLevel[i]) envelopeStage[i] = 3
            }
            3 -> Unit
            4 -> {
                envelopeLevel[i] = (envelopeLevel[i] - releaseStep[i]).coerceAtLeast(0.0)
                if (envelopeLevel[i] <= 0.000001) {
                    envelopeStage[i] = 0
                    envelopeLevel[i] = 0.0
                }
            }
        }
        return envelopeLevel[i]
    }
}
