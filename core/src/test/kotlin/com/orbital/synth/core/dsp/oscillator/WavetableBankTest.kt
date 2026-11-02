package com.orbital.synth.core.dsp.oscillator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WavetableBankTest {
    private val sampleRate = 48_000

    @Test fun everyMipBoundaryCrossesWithoutADiscontinuousWaveformStep() {
        for (cap in intArrayOf(2, 4, 8, 16, 32, 64, 128)) {
            val boundaryHz = sampleRate * 0.45 / cap
            val justBelowHz = boundaryHz * (1.0 - 0.000001)
            val justAboveHz = boundaryHz * (1.0 + 0.000001)
            for (phase in listOf(0.047, 0.173, 0.311, 0.698, 0.934)) {
                for (wave in listOf(0f, 0.23f, 0.5f, 0.78f, 1f)) {
                    val below = oscillatorSample(justBelowHz, phase, wave)
                    val above = oscillatorSample(justAboveHz, phase, wave)
                    assertTrue(
                        "mip cap $cap jumps at phase $phase, wave $wave: $below -> $above",
                        abs(below - above) < 0.002f
                    )
                }
            }
        }
    }

    @Test fun highHarmonicsAreFullyFadedBeforeCrossingTheirSafeBoundary() {
        val cap = 8
        val boundaryHz = sampleRate * 0.45 / cap
        val level = WavetableBank.levelForFrequency(boundaryHz, sampleRate)
        assertEquals(3, level)
        assertEquals(
            1f, WavetableBank.fadeToLowerLevel(boundaryHz, sampleRate, level), 0.000001f
        )

        val middleHz = sampleRate * 0.45 / (cap * 1.125)
        val middleBlend = WavetableBank.fadeToLowerLevel(
            middleHz, sampleRate, WavetableBank.levelForFrequency(middleHz, sampleRate)
        )
        assertTrue(middleBlend > 0f && middleBlend < 1f)

        val safeHz = sampleRate * 0.45 / (cap * 1.5)
        assertEquals(
            0f, WavetableBank.fadeToLowerLevel(
                safeHz, sampleRate, WavetableBank.levelForFrequency(safeHz, sampleRate)
            ), 0.000001f
        )
    }

    @Test fun lowAndExtremeInputFrequenciesAlwaysReturnFiniteBoundedSamples() {
        for (frequency in listOf(0.0, 1.0, 20.0, 440.0, 8_000.0, 30_000.0, Double.NaN)) {
            val level = WavetableBank.levelForFrequency(frequency, sampleRate)
            val fade = WavetableBank.fadeToLowerLevel(frequency, sampleRate, level)
            assertTrue(fade in 0f..1f)
            val sample = WavetableBank.sample(0.37, level, 0.88f, 1f, fade)
            assertTrue(sample.isFinite())
            assertTrue(abs(sample) <= 1.00001f)
        }
    }

    private fun oscillatorSample(frequencyHz: Double, phase: Double, wave: Float): Float {
        val level = WavetableBank.levelForFrequency(frequencyHz, sampleRate)
        val fade = WavetableBank.fadeToLowerLevel(frequencyHz, sampleRate, level)
        return WavetableBank.sample(phase, level, wave, 1f, fade)
    }
}
