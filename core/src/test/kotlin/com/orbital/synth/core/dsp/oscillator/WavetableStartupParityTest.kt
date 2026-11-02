package com.orbital.synth.core.dsp.oscillator

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Independent reference of the original family-by-family / mip-by-mip generator.
 * Keep it deliberately slow: this is a numerical parity oracle, not production code.
 */
class WavetableStartupParityTest {
    private val caps = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128)
    private val tableSize = 2048

    @Test fun optimizedInitializationPreservesExactLegacyWaveformSamples() {
        val legacy = legacyTables()

        // Every family, every mip and phase positions throughout the full cycle.
        for (family in 0 until 5) {
            val wave = family / 4f
            for (level in caps.indices) {
                for (index in 0 until tableSize step 13) {
                    val phase = index.toDouble() / tableSize
                    val expected = legacySample(legacy, phase, level, wave, 1f, 0f)
                    val actual = WavetableBank.sample(phase, level, wave, 1f)
                    assertEquals(
                        "family=$family level=$level index=$index", expected, actual, 0f
                    )
                }
            }
        }

        // Exercise fractional lookup interpolation, family and morph crossfades,
        // negative/wrapped phases and mip transitions as well as grid positions.
        for (phase in listOf(-1.37, -0.001, 0.0, 0.03171, 0.173921, 0.9998, 2.73)) {
            for (level in caps.indices) {
                for (wave in listOf(0f, 0.12f, 0.25f, 0.44f, 0.5f, 0.77f, 1f)) {
                    for (morph in listOf(0f, 0.37f, 1f)) {
                        for (fade in listOf(0f, 0.37f, 1f)) {
                            val expected = legacySample(legacy, phase, level, wave, morph, fade)
                            val actual = WavetableBank.sample(phase, level, wave, morph, fade)
                            assertEquals(
                                "phase=$phase level=$level wave=$wave morph=$morph fade=$fade",
                                expected, actual, 0f
                            )
                        }
                    }
                }
            }
        }
    }

    private fun legacyTables(): Array<Array<FloatArray>> = Array(5) { family ->
        Array(caps.size) { level ->
            val cap = caps[level]
            val result = FloatArray(tableSize)
            for (i in result.indices) {
                val phase = 2.0 * PI * i / tableSize
                var value = 0.0
                for (harmonic in 1..cap) {
                    val amplitude = legacyAmplitude(family, harmonic)
                    if (amplitude != 0.0) value += amplitude * sin(phase * harmonic)
                }
                result[i] = value.toFloat()
            }
            var peak = 0f
            for (value in result) peak = max(peak, abs(value))
            if (peak > 0f) {
                val inverse = 1f / peak
                for (i in result.indices) result[i] *= inverse
            }
            result
        }
    }

    private fun legacySample(
        tables: Array<Array<FloatArray>>,
        phase: Double,
        level: Int,
        wave: Float,
        morph: Float,
        lowerMipFade: Float
    ): Float {
        val normalized = phase - floor(phase)
        val position = normalized * tableSize
        val index = position.toInt() and (tableSize - 1)
        val fraction = (position - position.toInt()).toFloat()
        val current = sampleAtLevel(tables, index, fraction, level, wave, morph)
        val blend = lowerMipFade.coerceIn(0f, 1f)
        if (level <= 0 || blend <= 0f) return current
        val safer = sampleAtLevel(tables, index, fraction, level - 1, wave, morph)
        return current + (safer - current) * blend
    }

    private fun sampleAtLevel(
        tables: Array<Array<FloatArray>>,
        index: Int,
        fraction: Float,
        level: Int,
        wave: Float,
        morph: Float
    ): Float {
        val sine = lookup(tables, 0, level, index, fraction)
        if (morph <= 0f) return sine
        val wavePosition = wave.coerceIn(0f, 1f) * 4
        val firstFamily = wavePosition.toInt().coerceIn(0, 4)
        val secondFamily = (firstFamily + 1).coerceAtMost(4)
        val familyFraction = wavePosition - firstFamily
        val first = if (firstFamily == 0) sine else
            lookup(tables, firstFamily, level, index, fraction)
        val second = if (secondFamily == 0) sine else
            lookup(tables, secondFamily, level, index, fraction)
        val selected = first + (second - first) * familyFraction
        return sine + (selected - sine) * morph.coerceIn(0f, 1f)
    }

    private fun lookup(
        tables: Array<Array<FloatArray>>,
        family: Int,
        level: Int,
        index: Int,
        fraction: Float
    ): Float {
        val table = tables[family][level]
        val first = table[index]
        return first + (table[(index + 1) and (tableSize - 1)] - first) * fraction
    }

    private fun legacyAmplitude(family: Int, n: Int): Double = when (family) {
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
