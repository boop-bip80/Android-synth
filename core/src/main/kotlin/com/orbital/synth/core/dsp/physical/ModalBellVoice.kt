package com.orbital.synth.core.dsp.physical

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * A struck-bar / bell source built from decaying resonant modes.
 *
 * Real struck objects ring at inharmonic partials that die at different speeds; that is
 * what makes a marimba woody, a vibraphone shimmering and a bell sound like metal. Six
 * rotating phasors reproduce it:
 *
 *  * `table` morphs the partial ratios from tuned MARIMBA bars (0) through free-bar GLASS
 *    (0.5) to a minor-third BELL (1), always keeping the played note as the strong prime.
 *  * `brightness` sets how hard the object is struck (more energy in the upper modes) and
 *    velocity adds to it.
 *  * `decay` sets the ring time, shortened up the keyboard, with each higher mode dying
 *    faster than the one below it.
 *
 * Modes above 45% of the sample rate are skipped, so nothing aliases. No allocation after
 * construction; output is finite and bounded.
 */
class ModalBellVoice(val sampleRate: Int) {
    init {
        require(sampleRate in 8_000..192_000)
    }

    private val re = FloatArray(MODES)
    private val im = FloatArray(MODES)
    private val cosW = FloatArray(MODES)
    private val sinW = FloatArray(MODES)
    private val radius = FloatArray(MODES)
    private val gain = FloatArray(MODES)
    private val ratio = DoubleArray(MODES)

    private var table = 0.5f
    private var brightness = 0.6f
    private var decay = 0.5f
    private var velocity = 0.8f
    private var noteFrequency = 440.0
    private var lastFrequency = -1.0

    fun configure(table: Float, brightness: Float, decay: Float) {
        require(table.isFinite() && brightness.isFinite() && decay.isFinite())
        val t = table.coerceIn(0f, 1f)
        val b = brightness.coerceIn(0f, 1f)
        val d = decay.coerceIn(0f, 1f)
        if (t != this.table || b != this.brightness || d != this.decay) lastFrequency = -1.0
        this.table = t
        this.brightness = b
        this.decay = d
    }

    fun reset() {
        re.fill(0f)
        im.fill(0f)
    }

    /** Strike the bar: seed each mode with energy by strike hardness. */
    fun noteOn(midiNote: Int, velocity: Float) {
        this.velocity = if (velocity.isFinite()) velocity.coerceIn(0f, 1f) else 0.8f
        noteFrequency = 440.0 * 2.0.pow((midiNote.coerceIn(0, 127) - 69) / 12.0)
        lastFrequency = -1.0
        reset()
        recompute(noteFrequency)
        for (k in 0 until MODES) im[k] = gain[k]
    }

    fun retune(midiNote: Int) {
        noteFrequency = 440.0 * 2.0.pow((midiNote.coerceIn(0, 127) - 69) / 12.0)
    }

    fun nextSampleAtFrequency(frequencyHz: Double, pitchMultiplier: Double): Float {
        val f = (frequencyHz * pitchMultiplier).coerceIn(8.0, sampleRate * 0.4)
        if (lastFrequency < 0.0 || abs(f - lastFrequency) > lastFrequency * 1e-3) recompute(f)
        var out = 0f
        for (k in 0 until MODES) {
            val r = re[k]
            val i = im[k]
            val nr = radius[k] * (r * cosW[k] - i * sinW[k])
            val ni = radius[k] * (r * sinW[k] + i * cosW[k])
            re[k] = nr
            im[k] = ni
            out += nr
        }
        if (!out.isFinite()) {
            reset()
            return 0f
        }
        return (out * OUTPUT_SCALE).coerceIn(-2f, 2f)
    }

    /** Recompute mode frequencies and decay for the current pitch. */
    private fun recompute(frequency: Double) {
        lastFrequency = frequency
        val keyScale = (261.63 / frequency).pow(0.30).coerceIn(0.4, 1.9)
        val ring = (0.22 + 5.4 * decay.toDouble().pow(1.6)) * keyScale
        val strike = (0.30f + 0.70f * brightness) * (0.55f + 0.45f * velocity)
        val rolloff = 2.3 - 1.4 * strike
        for (k in 0 until MODES) {
            val partial = if (table < 0.5f) {
                lerp(MARIMBA[k], GLASS[k], table * 2.0)
            } else {
                lerp(GLASS[k], BELL[k], (table - 0.5) * 2.0)
            }
            ratio[k] = partial
            val hz = frequency * partial
            if (hz >= sampleRate * 0.45) {
                // Above the safe band: mute rather than alias.
                gain[k] = 0f
                radius[k] = 0f
                cosW[k] = 1f
                sinW[k] = 0f
                re[k] = 0f
                im[k] = 0f
                continue
            }
            val omega = 2.0 * PI * hz / sampleRate
            cosW[k] = cos(omega).toFloat()
            sinW[k] = sin(omega).toFloat()
            // Higher modes die faster: ring time falls with mode number.
            val tau = ring / (1.0 + k * (0.9 + (1.0 - strike) * 0.9))
            radius[k] = exp(-1.0 / (tau * sampleRate)).toFloat()
            gain[k] = ((1.0 / (k + 1).toDouble().pow(rolloff)) *
                (if (k == 0) 1.0 else strike.toDouble())).toFloat()
        }
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    companion object {
        const val MODES = 6

        /** Normalizes the summed modes to a healthy level (peak is bounded by the gains). */
        private const val OUTPUT_SCALE = 0.85f

        // Partial ratios relative to the played pitch. Inspired by, not measured from, real instruments.
        private val MARIMBA = doubleArrayOf(1.0, 3.99, 9.92, 17.2, 26.4, 36.9)
        private val GLASS = doubleArrayOf(1.0, 2.76, 5.40, 8.93, 13.34, 18.64)
        private val BELL = doubleArrayOf(1.0, 1.19, 1.50, 2.00, 2.57, 3.03)
    }
}
