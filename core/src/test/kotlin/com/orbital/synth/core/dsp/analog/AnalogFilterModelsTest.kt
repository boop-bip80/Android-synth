package com.orbital.synth.core.dsp.analog

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalogFilterModelsTest {
    @Test
    fun ladderFourPoleLowPassRejectsUpperBand() {
        val low = sineRmsLadder(100.0)
        val high = sineRmsLadder(8_000.0)
        assertTrue("Low-band transfer must be audible: $low", low > 0.05)
        assertTrue("A four-pole low-pass must suppress upper band: $low vs $high", low > high * 10)
    }

    @Test
    fun semVariableModeHasDistinctLowNotchAndHighTransfer() {
        val lowModeAt100 = sineRmsSem(100.0, 0f)
        val lowModeAt8000 = sineRmsSem(8_000.0, 0f)
        val highModeAt100 = sineRmsSem(100.0, 1f)
        val highModeAt8000 = sineRmsSem(8_000.0, 1f)
        val notchAtCutoff = sineRmsSem(1_200.0, 0.5f)
        val lowAtCutoff = sineRmsSem(1_200.0, 0f)
        assertTrue(lowModeAt100 > lowModeAt8000 * 4)
        assertTrue(highModeAt8000 > highModeAt100 * 4)
        assertTrue("Notch should reject the centre frequency", notchAtCutoff < lowAtCutoff * 0.6)
    }

    @Test
    fun bothAnalogModelsStayBoundedDuringExtremeAutomation() {
        val unusual = floatArrayOf(
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            -100f, 0f, 0.72f, 1f, 90_000f,
        )
        for (sampleRate in intArrayOf(8_000, 48_000, 192_000, 384_000)) {
            val ladder = LadderFilter(sampleRate)
            val sem = SemFilter(sampleRate)
            for (index in unusual.indices) {
                val cutoff = unusual[index]
                val resonance = unusual[(index + 2) % unusual.size]
                val drive = unusual[(index + 4) % unusual.size]
                ladder.configure(cutoff, resonance, drive)
                sem.configure(cutoff, resonance, drive, unusual[(index + 5) % unusual.size])
                repeat(2_000) { frame ->
                    val input = when (frame % 7) {
                        0 -> Float.NaN
                        1 -> Float.POSITIVE_INFINITY
                        2 -> Float.NEGATIVE_INFINITY
                        3 -> 8f
                        4 -> -8f
                        else -> sin(frame * 2.0 * PI * 14_000 / sampleRate).toFloat()
                    }
                    val ladderOut = ladder.process(input)
                    val semOut = sem.process(input)
                    assertTrue("Ladder produced nonfinite output at $sampleRate", ladderOut.isFinite())
                    assertTrue("SEM produced nonfinite output at $sampleRate", semOut.isFinite())
                    assertTrue(ladderOut in -8f..8f)
                    assertTrue(semOut in -8f..8f)
                    assertTrue(sem.bandPassOutput().isFinite())
                }
            }
        }
    }

    @Test
    fun highResonanceAndDriveNeverSelfExciteWithoutBounds() {
        val ladder = LadderFilter(48_000)
        ladder.configure(5_000f, 1f, 1f)
        val sem = SemFilter(48_000)
        sem.configure(5_000f, 1f, 1f, 0.5f)
        repeat(96_000) { i ->
            val impulse = if (i == 0) 1f else 0f
            assertTrue(ladder.process(impulse).isFinite())
            assertTrue(sem.process(impulse).isFinite())
        }
    }

    @Test
    fun resetErasesChargeAndIndependentInstancesDoNotBleed() {
        val a = LadderFilter(48_000)
        val b = LadderFilter(48_000)
        val c = SemFilter(48_000)
        val d = SemFilter(48_000)
        repeat(4_000) {
            a.process(1f)
            c.process(1f)
            assertEquals(0f, b.process(0f), 0f)
            assertEquals(0f, d.process(0f), 0f)
        }
        a.reset()
        c.reset()
        repeat(16) {
            assertEquals(0f, a.process(0f), 0f)
            assertEquals(0f, c.process(0f), 0f)
            assertEquals(0f, c.bandPassOutput(), 0f)
        }
    }

    @Test
    fun driveAltersToneAndConstantControlsAreDeterministic() {
        fun ladderSteady(drive: Float): Float {
            val filter = LadderFilter(48_000)
            filter.configure(9_000f, 0f, drive)
            var output = 0f
            repeat(5_000) { output = filter.process(0.25f) }
            return output
        }
        assertTrue("Nonlinear drive should be audible", ladderSteady(1f) > ladderSteady(0f) * 1.4f)

        val first = SemFilter(48_000)
        val second = SemFilter(48_000)
        first.configure(2_000f, 0.9f, 0.8f, 0.72f)
        second.configure(2_000f, 0.9f, 0.8f, 0.72f)
        for (i in 0 until 20_000) {
            val input = sin(i * 2.0 * PI * 450.0 / 48_000.0).toFloat()
            assertEquals(first.process(input), second.process(input), 0f)
        }
        assertFalse(first.bandPassOutput().isNaN())
    }

    @Test
    fun voiceStealSnapshotCopiesFiltersWithoutSharingTheirFutureState() {
        val ladder = LadderFilter(48_000)
        val ladderTail = LadderFilter(48_000)
        val sem = SemFilter(48_000)
        val semTail = SemFilter(48_000)
        ladder.configure(1_100f, 0.72f, 0.6f)
        sem.configure(1_100f, 0.72f, 0.6f, 0.3f)
        repeat(2_000) { i ->
            val input = sin(i * 2.0 * PI * 375.0 / 48_000.0).toFloat()
            ladder.process(input)
            sem.process(input)
        }
        ladder.copyStateTo(ladderTail)
        sem.copyStateTo(semTail)
        repeat(512) { i ->
            val input = sin(i * 2.0 * PI * 830.0 / 48_000.0).toFloat()
            assertEquals(ladder.process(input), ladderTail.process(input), 0f)
            assertEquals(sem.process(input), semTail.process(input), 0f)
        }
        ladder.reset()
        sem.reset()
        assertEquals(0f, ladder.process(0f), 0f)
        assertEquals(0f, sem.process(0f), 0f)
        // The archived tail retained its own charge when the new voice reset.
        assertTrue(ladderTail.process(0f).isFinite())
        assertTrue(semTail.process(0f).isFinite())
    }

    private fun sineRmsLadder(hertz: Double): Double {
        val filter = LadderFilter(48_000)
        filter.configure(1_200f, 0f, 0f)
        var sum = 0.0
        repeat(16_000) { i ->
            val input = sin(i * hertz * 2.0 * PI / 48_000.0).toFloat() * 0.2f
            val output = filter.process(input).toDouble()
            if (i >= 4_000) sum += output * output
        }
        return sqrt(sum / 12_000.0)
    }

    private fun sineRmsSem(hertz: Double, shape: Float): Double {
        val filter = SemFilter(48_000)
        filter.configure(1_200f, 0.2f, 0f, shape)
        var sum = 0.0
        repeat(16_000) { i ->
            val input = sin(i * hertz * 2.0 * PI / 48_000.0).toFloat() * 0.2f
            val output = filter.process(input).toDouble()
            if (i >= 4_000) sum += output * output
        }
        return sqrt(sum / 12_000.0)
    }
}
