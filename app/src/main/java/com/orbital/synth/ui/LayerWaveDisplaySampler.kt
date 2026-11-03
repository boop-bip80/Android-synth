package com.orbital.synth.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Allocation-free, deterministic reference waveform for the OSC constellation
 * renderer. The five Fourier families and their coefficients are deliberately
 * matched to core WavetableBank's 32-harmonic A4-level table. Like the audible
 * engine, adjacent families interpolate and MORPH blends from a pure sine.
 *
 * This is a stable reference-cycle drawing, NOT live AudioTrack PCM, an FFT, a
 * microphone spectrum, or a claim that every pitch's anti-aliased mip is drawn
 * exactly. The real DSP independently chooses its mip based on played pitch.
 *
 * Tables allocate once at class initialization; sample() allocates nothing and
 * uses no trigonometric functions or loops over harmonics at draw time.
 */
internal object LayerWaveDisplaySampler {
    private const val SIZE = 512
    private const val MASK = SIZE - 1
    private const val HARMONICS = 32
    private const val FAMILIES = 5

    private val tables = Array(FAMILIES) { family ->
        FloatArray(SIZE) { index ->
            val angle = 2.0 * PI * index / SIZE
            var value = 0.0
            for (harmonic in 1..HARMONICS) {
                val amplitude = harmonicAmplitude(family, harmonic)
                if (amplitude != 0.0) value += amplitude * sin(angle * harmonic)
            }
            value.toFloat()
        }.also { table ->
            var peak = 0f
            for (value in table) peak = max(peak, abs(value))
            if (peak > 0f) {
                val inverse = 1f / peak
                for (index in table.indices) table[index] *= inverse
            }
        }
    }

    fun sample(phase: Float, waveform: Float, morph: Float): Float {
        if (!phase.isFinite() || !waveform.isFinite() || !morph.isFinite()) return 0f
        val wrapped = (phase - floor(phase.toDouble()).toFloat()).coerceIn(0f, .99999994f)
        val position = wrapped * SIZE
        val index = position.toInt() and MASK
        val fraction = position - position.toInt()
        val sine = lookup(0, index, fraction)
        if (morph <= 0f) return sine

        val wavePosition = waveform.coerceIn(0f, 1f) * (FAMILIES - 1)
        val first = wavePosition.toInt().coerceIn(0, FAMILIES - 1)
        val second = (first + 1).coerceAtMost(FAMILIES - 1)
        val mix = wavePosition - first
        val selected = lookup(first, index, fraction) +
            (lookup(second, index, fraction) - lookup(first, index, fraction)) * mix
        return (sine + (selected - sine) * morph.coerceIn(0f, 1f)).coerceIn(-1f, 1f)
    }

    private fun lookup(family: Int, index: Int, fraction: Float): Float {
        val table = tables[family]
        return table[index] + (table[(index + 1) and MASK] - table[index]) * fraction
    }

    // Source-matched coefficients from core.dsp.oscillator.WavetableBank.
    private fun harmonicAmplitude(family: Int, n: Int): Double = when (family) {
        0 -> if (n == 1) 1.0 else 0.0
        1 -> if ((n and 1) == 0) 0.0 else
            (if (((n - 1) / 2 and 1) == 0) 1.0 else -1.0) / (n.toDouble() * n)
        2 -> (if ((n and 1) == 0) -1.0 else 1.0) / n
        3 -> if ((n and 1) == 0) 0.0 else 1.0 / n
        4 -> when (n) {
            1 -> 0.55
            2 -> 0.32
            3 -> 0.21
            5 -> -0.19
            7 -> 0.14
            11 -> -0.09
            else -> 0.0
        }
        else -> 0.0
    }
}
