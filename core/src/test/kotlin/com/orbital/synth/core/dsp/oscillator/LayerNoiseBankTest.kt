package com.orbital.synth.core.dsp.oscillator

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** Offline signal-level regressions for eight independent colored noise sources. */
class LayerNoiseBankTest {
    @Test fun identicalSeedAndColorAutomationProduceBitIdenticalPcm() {
        val one = LayerNoiseBank(48_000).apply { reset(0x1ACB72L) }
        val two = LayerNoiseBank(48_000).apply { reset(0x1ACB72L) }
        val unrelated = LayerNoiseBank(48_000).apply { reset(0x1ACB73L) }
        var differentSeedSamples = 0
        repeat(20_000) { index ->
            val slot = index % 8
            val color = (index % 311) / 310f
            val expected = one.next(slot, color)
            val actual = two.next(slot, color)
            assertEquals(expected, actual, 0f)
            if (expected != unrelated.next(slot, color)) differentSeedSamples++
            assertTrue(actual.isFinite() && actual in -1f..1f)
        }
        assertTrue("Distinct voice note seeds must not correlate perfectly",
            differentSeedSamples > 19_000)
    }

    @Test fun eightLayersHaveIndependentNoiseSequences() {
        val bank = LayerNoiseBank(48_000).apply { reset(0L) }
        var unequal = 0
        repeat(4_096) {
            if (bank.next(0, 0f) != bank.next(1, 0f)) unequal++
        }
        assertTrue("Noise in different slots must be independently seeded", unequal > 4_000)
    }

    @Test fun noiseColorsHaveDistinctPowerAndSpectralTiltAcrossHostRates() {
        for (sampleRate in intArrayOf(8_000, 48_000, 192_000)) {
            val bank = LayerNoiseBank(sampleRate).apply { reset(73L) }
            val previous = FloatArray(3)
            val changes = DoubleArray(3)
            val amplitudes = DoubleArray(3)
            val powers = DoubleArray(3)
            val colors = floatArrayOf(0f, .5f, 1f)
            repeat(48_000) { frame ->
                for (color in 0..2) {
                    val sample = bank.next(color, colors[color])
                    if (frame >= 4_096) {
                        changes[color] += abs((sample - previous[color]).toDouble())
                        amplitudes[color] += abs(sample.toDouble())
                        powers[color] += sample.toDouble() * sample
                    }
                    previous[color] = sample
                    assertTrue("Nonfinite noise at $sampleRate Hz, color=$color",
                        sample.isFinite() && abs(sample) <= 1f)
                }
            }
            val differenceRatio = DoubleArray(3) { changes[it] / amplitudes[it] }
            val rms = DoubleArray(3) { sqrt(powers[it] / (48_000 - 4_096)) }
            assertTrue("White should have broad high-frequency power at $sampleRate",
                differenceRatio[0] > 1.1)
            assertTrue("Pink should be darker than white at $sampleRate",
                differenceRatio[1] < differenceRatio[0] * 0.7)
            assertTrue("Brown should be substantially darker than pink at $sampleRate",
                differenceRatio[2] < differenceRatio[1] * 0.6)
            for (level in rms) assertTrue("Colors must stay useful yet controlled",
                level in 0.18..0.80)
        }
    }

    @Test fun restoringVoiceStateClearsPriorColoredNoiseHistory() {
        val reused = LayerNoiseBank(48_000).apply { reset(111L) }
        repeat(24_000) { reused.next(it % 8, .85f) }
        reused.reset(222L)
        val fresh = LayerNoiseBank(48_000).apply { reset(222L) }
        repeat(8_192) { frame ->
            val lane = frame % 8
            assertEquals(fresh.next(lane, .6f), reused.next(lane, .6f), 0f)
        }
    }

    @Test fun noiseGenerationIsIndependentOfBufferPartition() {
        val whole = LayerNoiseBank(48_000).apply { reset(8L) }
        val chunked = LayerNoiseBank(48_000).apply { reset(8L) }
        val one = FloatArray(8_192)
        val many = FloatArray(one.size)
        for (sample in one.indices) {
            one[sample] = whole.next(sample % 8, .65f)
        }
        for (start in 0 until many.size step 137) {
            for (sample in start until minOf(start + 137, many.size)) {
                many[sample] = chunked.next(sample % 8, .65f)
            }
        }
        assertArrayEquals(one, many, 0f)
    }

    @Test fun sixteenVoicesWithEightActiveNoiseLayersRemainBoundedAndFinite() {
        val banks = Array(16) { index ->
            LayerNoiseBank(48_000).apply { reset((index + 1).toLong()) }
        }
        var nonZero = 0
        repeat(2_048) { frame ->
            var mixed = 0f
            for (bank in banks) {
                for (layer in 0 until 8) {
                    mixed += bank.next(layer, if (layer % 2 == 0) .5f else 1f) / 8f
                }
            }
            mixed /= 4f // 1/sqrt(16), as in a polyphonic master mix.
            if (abs(mixed) > 0.001f) nonZero++
            assertTrue(mixed.isFinite() && abs(mixed) < 4f)
        }
        assertTrue("Stack must generate actual varying audio", nonZero > 2_000)
    }
}
