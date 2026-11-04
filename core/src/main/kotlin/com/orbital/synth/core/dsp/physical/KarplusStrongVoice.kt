package com.orbital.synth.core.dsp.physical

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A plucked-string source: an extended Karplus-Strong waveguide.
 *
 * A burst of shaped noise rings in a delay loop whose length sets the pitch. Each pass
 * through the loop low-passes the signal, so highs die first and the tone mellows like a
 * real string. Four things make it usable as an instrument rather than a toy:
 *
 *  * Pitch is exact. The loop delay is a real number of samples, read with 4-point Lagrange
 *    interpolation, and the loop filter's phase delay at the fundamental is subtracted
 *    analytically, so notes stay in tune across the keyboard.
 *  * Sustain is a musical control: [configure]'s `decay` sets the T60 of the fundamental,
 *    shortened for high notes the way a real string behaves, with loop gain capped below
 *    unity so no setting can ring forever or blow up.
 *  * Touch matters: the pluck's brightness grows with velocity, and the pick position
 *    (a comb on the excitation) moves the tone from warm to nasal.
 *  * No allocation after construction; every output is finite and bounded.
 *
 * Own one instance per polyphonic voice and call it only from the audio worker.
 */
class KarplusStrongVoice(val sampleRate: Int) {
    init {
        require(sampleRate in 8_000..192_000)
    }

    private val maxDelay = (sampleRate / 16.0).toInt() + 16
    private val line = FloatArray(maxDelay + 8)
    private var writeIndex = 0

    private var brightness = 0.6f
    private var decay = 0.5f
    private var pickPosition = 0.18f

    // Loop state.
    private var filterState = 0f
    private var lowpassCoefficient = 0.6f
    private var loopGain = 0.995f
    private var lineDelay = 100.0
    private var dcIn = 0f
    private var dcOut = 0f

    private var noteFrequency = 440.0
    private var lastFrequency = -1.0
    private var velocity = 0.8f
    private var noiseState = 0x9E3779B9.toInt()
    private var strikeCount = 0

    /** Updated at block boundaries; changes take effect on the next pitch recalculation. */
    fun configure(brightness: Float, decay: Float, pickPosition: Float) {
        require(brightness.isFinite() && decay.isFinite() && pickPosition.isFinite())
        val b = brightness.coerceIn(0f, 1f)
        val d = decay.coerceIn(0f, 1f)
        val p = pickPosition.coerceIn(0f, 1f)
        if (b != this.brightness || d != this.decay) lastFrequency = -1.0 // retune loop filter
        this.brightness = b
        this.decay = d
        this.pickPosition = p
    }

    fun reset() {
        line.fill(0f)
        filterState = 0f
        dcIn = 0f
        dcOut = 0f
        writeIndex = 0
    }

    /** Pluck the string. Hard strikes are brighter and louder. */
    fun noteOn(midiNote: Int, velocity: Float) {
        this.velocity = if (velocity.isFinite()) velocity.coerceIn(0f, 1f) else 0.8f
        noteFrequency = 440.0 * 2.0.pow((midiNote.coerceIn(0, 127) - 69) / 12.0)
        reset()
        lastFrequency = -1.0
        retuneLoop(noteFrequency)
        strikeCount++
        exciteString()
    }

    /** Legato retune: the string keeps ringing; only its pitch moves. */
    fun retune(midiNote: Int) {
        noteFrequency = 440.0 * 2.0.pow((midiNote.coerceIn(0, 127) - 69) / 12.0)
    }

    /**
     * Next output sample. [frequencyHz] is the voice's current (glided) pitch and
     * [pitchMultiplier] carries pitch bend and vibrato.
     */
    fun nextSampleAtFrequency(frequencyHz: Double, pitchMultiplier: Double): Float {
        val f = (frequencyHz * pitchMultiplier).coerceIn(8.0, sampleRate * 0.4)
        if (lastFrequency < 0.0 || abs(f - lastFrequency) > lastFrequency * 1e-3) retuneLoop(f)

        // Read the loop at its fractional delay (4-point Lagrange).
        val total = lineDelay
        val whole = floor(total).toInt()
        val t = (total - whole).toFloat()
        val size = line.size
        val base = writeIndex - whole
        val qm1 = line[wrap(base + 1, size)]
        val q0 = line[wrap(base, size)]
        val q1 = line[wrap(base - 1, size)]
        val q2 = line[wrap(base - 2, size)]
        val delayed = qm1 * (-t * (t - 1f) * (t - 2f) / 6f) +
            q0 * ((t + 1f) * (t - 1f) * (t - 2f) / 2f) +
            q1 * (-(t + 1f) * t * (t - 2f) / 2f) +
            q2 * ((t + 1f) * t * (t - 1f) / 6f)

        // Loop filter: one-pole low-pass (the string's frequency-dependent damping) and gain.
        filterState += lowpassCoefficient * (delayed - filterState)
        var feedback = filterState * loopGain
        // A leaky DC blocker keeps rounding drift from ever accumulating in the loop.
        val blocked = feedback - dcIn + 0.9995f * dcOut
        dcIn = feedback
        dcOut = blocked
        feedback = blocked
        if (!feedback.isFinite()) {
            reset()
            return 0f
        }
        line[writeIndex] = feedback
        writeIndex = if (writeIndex + 1 >= size) 0 else writeIndex + 1
        return delayed.coerceIn(-2f, 2f)
    }

    private fun wrap(index: Int, size: Int): Int {
        var i = index % size
        if (i < 0) i += size
        return i
    }

    /** Recompute loop delay and gain for [frequency]; called only when the pitch really moves. */
    private fun retuneLoop(frequency: Double) {
        lastFrequency = frequency
        val omega = 2.0 * PI * frequency / sampleRate
        // Brightness maps to the loop low-pass: dark strings damp quickly, bright ones barely.
        val a = (0.10 + 0.88 * brightness.toDouble().pow(1.4)).coerceIn(0.05, 0.98)
        lowpassCoefficient = a.toFloat()
        // Exact phase delay (in samples) of the one-pole low-pass at the fundamental.
        val k = 1.0 - a
        val phaseDelay = atan2(k * sin(omega), 1.0 - k * cos(omega)) / omega
        val totalDelay = sampleRate / frequency
        lineDelay = (totalDelay - phaseDelay).coerceIn(3.0, maxDelay.toDouble() - 4.0)

        // T60 of the fundamental: ~0.3 s (short pluck) to ~7 s (ringing), shorter up the keyboard.
        val keyScale = (261.63 / frequency).pow(0.28).coerceIn(0.45, 1.8)
        val t60 = (0.30 + 6.7 * decay.toDouble().pow(1.5)) * keyScale
        val perPass = 10.0.pow(-3.0 * totalDelay / (t60 * sampleRate))
        // Undo the low-pass's own loss at the fundamental so `decay` means what it says...
        val lowpassGain = a / sqrt(1.0 - 2.0 * k * cos(omega) + k * k)
        // ...but never let loop gain reach unity at any frequency (DC gain is exactly gain).
        loopGain = min(0.99995, perPass / max(lowpassGain, 1e-3)).toFloat()
    }

    /** Fill the loop with a shaped, zero-mean noise burst: the pluck itself. */
    private fun exciteString() {
        val length = min(line.size - 2, max(4, ceil(lineDelay).toInt() + 3))
        // The pluck is low-passed noise: a soft, dark strike is smooth from its very first
        // sample, a hard bright one is crisp. (Brightness here is the same control that sets
        // how quickly the loop then mellows the tone.)
        val hardness = (0.04f + 0.96f * brightness.toDouble().pow(1.3).toFloat() *
            (0.4f + 0.6f * velocity)).coerceIn(0.04f, 1f)
        var previous = 0f
        var sum = 0f
        for (i in 0 until length) {
            val noise = nextNoise()
            previous += hardness * (noise - previous)
            line[i] = previous
            sum += previous
        }
        // Zero mean: a plucked string has no DC component to ring.
        val mean = sum / length
        var peak = 1e-6f
        for (i in 0 until length) {
            line[i] -= mean
        }
        // Pick position: a comb notch (nearer the bridge = brighter, more nasal tone).
        val offset = max(1, (pickPosition * 0.5f * length).toInt())
        for (i in length - 1 downTo offset) line[i] -= 0.85f * line[i - offset]
        for (i in 0 until length) peak = max(peak, abs(line[i]))
        val scale = (0.45f + 0.55f * velocity) / peak
        for (i in 0 until length) line[i] *= scale
        // The read position sits `lineDelay` behind the write head, so seat the burst there.
        writeIndex = length % line.size
    }

    private fun ceil(value: Double): Int = floor(value).toInt() + if (value > floor(value)) 1 else 0

    private fun nextNoise(): Float {
        // xorshift32: deterministic, allocation-free, uncorrelated enough for excitation.
        var x = noiseState xor (strikeCount * 0x27D4EB2D)
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        noiseState = if (x == 0) 0x9E3779B9.toInt() else x
        return (noiseState / 2147483648f)
    }
}
