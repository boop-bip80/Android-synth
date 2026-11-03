package com.orbital.synth.core.dsp.oscillator

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Eight independent, deterministic white/pink/brown noise streams for an additive voice.
 *
 * White is a discrete-time Nyquist-wide xorshift32 source, pink is a Voss-McCartney
 * octave-row generator (approximately -3 dB/octave), and brown is an
 * approximately -6 dB/octave leaky-integrated source above 45 Hz. [color] is
 * continuous: 0=white, 0.5=pink, 1=brown.
 *
 * Brown's 45 Hz leakage corner is expressed in *real hertz*. Pink's octave count
 * adapts to the host sample rate to cover approximately 20 Hz..Nyquist. The
 * streams are pseudo-random rather than cryptographic, and deliberately have
 * broadband noise content; this is not a band-limited tonal oscillator.
 *
 * Every sample uses primitive arithmetic and preallocated state only. This
 * object belongs exclusively to one audio worker / Voice; UI threads must not
 * call next() or reset(). Identical note/voice seeds produce identical PCM.
 */
internal class LayerNoiseBank(private val sampleRate: Int) {
    init { require(sampleRate in 8_000..192_000) }

    companion object { const val SLOT_COUNT = 8 }

    private val pinkRowCount =
        (ln(sampleRate / 20.0) / ln(2.0)).toInt().plus(1).coerceIn(8, 16)
    private val randomStates = IntArray(SLOT_COUNT)
    private val pinkRows = Array(SLOT_COUNT) { FloatArray(pinkRowCount) }
    private val pinkSums = FloatArray(SLOT_COUNT)
    private val pinkCounters = IntArray(SLOT_COUNT)
    private val brownStates = FloatArray(SLOT_COUNT)
    private val brownDecay = exp(-2.0 * PI * 45.0 / sampleRate).toFloat()
    private val brownInputGain = sqrt(1f - brownDecay * brownDecay)
    private val pinkScale = (0.61 / sqrt((pinkRowCount + 1).toDouble())).toFloat()

    init { reset(0L) }

    /**
     * Reproducible but independent lane streams on each note/retrigger. The
     * caller can include the MIDI note and voice event order in [seed] so a
     * polyphonic chord does not reproduce eight identical noise waveforms.
     */
    fun reset(seed: Long) {
        val folded = (seed xor (seed ushr 32)).toInt()
        for (slot in 0 until SLOT_COUNT) {
            // Distinct nonzero seeds even when all voices start with seed=0.
            var x = folded xor (0x6D2B79F5 * (slot + 1))
            x = x xor (x ushr 16)
            x *= 0x7FEB352D
            x = x xor (x ushr 15)
            if (x == 0) x = 0x6D2B79F5
            randomStates[slot] = x
            pinkCounters[slot] = 0
            val rows = pinkRows[slot]
            var rowSum = 0f
            for (row in rows.indices) {
                val value = nextWhite(slot)
                rows[row] = value
                rowSum += value
            }
            pinkSums[slot] = rowSum
            brownStates[slot] = nextWhite(slot) * 0.3f
        }
    }

    /**
     * One bounded audio sample in [-1, 1]. The three sources advance together,
     * allowing a continuously swept white -> pink -> brown control with no
     * abrupt generator reseeding or release of stale filter memory.
     */
    fun next(slot: Int, color: Float): Float {
        require(slot in 0 until SLOT_COUNT)
        val white = nextWhite(slot)
        val counter = pinkCounters[slot] + 1
        pinkCounters[slot] = counter
        val changedRow = Integer.numberOfTrailingZeros(counter)
        if (changedRow < pinkRowCount) {
            val rows = pinkRows[slot]
            val nextValue = nextWhite(slot)
            pinkSums[slot] += nextValue - rows[changedRow]
            rows[changedRow] = nextValue
        }
        val pink = ((pinkSums[slot] + white) * pinkScale).coerceIn(-1f, 1f)
        val brown = (
            brownStates[slot] * brownDecay + white * brownInputGain
        ).coerceIn(-2f, 2f)
        brownStates[slot] = brown
        val dark = (brown * 0.8f).coerceIn(-1f, 1f)
        val normalizedColor = if (color.isFinite()) color.coerceIn(0f, 1f) else 0f
        return if (normalizedColor <= 0.5f) {
            val blend = normalizedColor * 2f
            white + (pink - white) * blend
        } else {
            val blend = (normalizedColor - 0.5f) * 2f
            pink + (dark - pink) * blend
        }
    }

    private fun nextWhite(slot: Int): Float {
        var value = randomStates[slot]
        value = value xor (value shl 13)
        value = value xor (value ushr 17)
        value = value xor (value shl 5)
        randomStates[slot] = value
        // Arithmetic shift maps all signed 32-bit states to [-1,1) without
        // Float/Double conversion overflow or allocating a Random object.
        return (value shr 8) * (1f / 8_388_608f)
    }
}
