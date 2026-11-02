package com.orbital.synth.core.dsp.oscillator

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Band-limited, phase-continuous wavetable families. Every table contains only harmonics
 * below its mip level's upper limit, so higher notes select spectrally smaller tables.
 *
 * All tables are generated once; [sample] does not allocate or evaluate transcendental math.
 */
internal object WavetableBank {
    private const val TABLE_SIZE = 2048
    private const val TABLE_MASK = TABLE_SIZE - 1
    private const val WAVE_FAMILIES = 5
    private val harmonicCaps = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128)

    private val tables: Array<Array<FloatArray>> = Array(WAVE_FAMILIES) { family ->
        Array(harmonicCaps.size) { level ->
            val cap = harmonicCaps[level]
            val result = FloatArray(TABLE_SIZE)
            for (i in result.indices) {
                val phase = 2.0 * PI * i / TABLE_SIZE
                var value = 0.0
                for (harmonic in 1..cap) {
                    val amplitude = harmonicAmplitude(family, harmonic)
                    if (amplitude != 0.0) value += amplitude * sin(phase * harmonic)
                }
                result[i] = value.toFloat()
            }
            normalize(result)
            result
        }
    }

    /** Largest safe table index for the fundamental frequency, reserving a Nyquist margin. */
    fun levelForFrequency(frequencyHz: Double, sampleRate: Int): Int {
        val frequency = if (frequencyHz.isFinite() && frequencyHz > 0.0) frequencyHz else 1.0
        val allowed = (sampleRate * 0.45 / frequency).toInt().coerceAtLeast(1)
        var result = 0
        for (level in 1 until harmonicCaps.size) {
            if (harmonicCaps[level] > allowed) break
            result = level
        }
        return result
    }

    /**
     * [wave] chooses sine -> triangle -> saw -> pulse -> glass (continuous crossfade);
     * [morph] blends from a pure sine to the selected waveform.
     */
    fun sample(phase: Double, level: Int, wave: Float, morph: Float): Float {
        val normalized = phase - floor(phase)
        val tablePosition = normalized * TABLE_SIZE
        val firstIndex = tablePosition.toInt() and TABLE_MASK
        val fraction = (tablePosition - tablePosition.toInt()).toFloat()

        val sine = lookup(0, level, firstIndex, fraction)
        if (morph <= 0f) return sine

        val wavePosition = wave.coerceIn(0f, 1f) * (WAVE_FAMILIES - 1)
        val firstFamily = wavePosition.toInt().coerceIn(0, WAVE_FAMILIES - 1)
        val secondFamily = (firstFamily + 1).coerceAtMost(WAVE_FAMILIES - 1)
        val familyFraction = wavePosition - firstFamily
        val first = if (firstFamily == 0) sine else lookup(firstFamily, level, firstIndex, fraction)
        val second = if (secondFamily == 0) sine else lookup(secondFamily, level, firstIndex, fraction)
        val selected = first + (second - first) * familyFraction
        return sine + (selected - sine) * morph.coerceIn(0f, 1f)
    }

    private fun lookup(family: Int, level: Int, index: Int, fraction: Float): Float {
        val table = tables[family][level.coerceIn(0, harmonicCaps.lastIndex)]
        val first = table[index]
        return first + (table[(index + 1) and TABLE_MASK] - first) * fraction
    }

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

    private fun normalize(table: FloatArray) {
        var peak = 0f
        for (value in table) peak = max(peak, abs(value))
        if (peak > 0f) {
            val inverse = 1f / peak
            for (i in table.indices) table[i] *= inverse
        }
    }
}
