package com.orbital.synth.core

import com.orbital.synth.core.dsp.processing.ModDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Float32 cannot guarantee that 8ms one-pole updates cross fixed 1e-5
 * thresholds. The last sub-ULP step must not pin a live OSC cable or residual.
 */
class OscillatorLayerModulationPrecisionTest {
    @Test fun fullScaleGainPitchAndUnplugRetireExactlyAtAllSampleRates() {
        for (sampleRate in intArrayOf(8_000, 48_000, 192_000)) {
            val bus = OscillatorLayerModulation(sampleRate)
            val frame = FloatArray(ModDestination.entries.size) { .5f }
            frame[ModDestination.OSC4_GAIN.ordinal] = 1f
            frame[ModDestination.OSC4_PITCH.ordinal] = 1f
            bus.configureMasks(1 shl 3, 1 shl 3)
            bus.next(frame)
            assertTrue(bus.gainMultipliers[3] > 1f && bus.gainMultipliers[3] < 1.04f)
            assertTrue(bus.pitchMultipliers[3] > 1.0 && bus.pitchMultipliers[3] < 1.01)
            repeat(sampleRate / 4) { bus.next(frame) }
            assertEquals("Gain froze below 2x at $sampleRate",
                2f, bus.gainMultipliers[3], 0f)
            assertEquals("Pitch froze below +6 st at $sampleRate",
                2.0.pow(0.5), bus.pitchMultipliers[3], 0.0)
            assertTrue(bus.active)
            assertEquals(1 shl 3, bus.mipSafetyMask)
            for (slot in 0 until 8) if (slot != 3) {
                assertEquals(1f, bus.gainMultipliers[slot], 0f)
                assertEquals(1.0, bus.pitchMultipliers[slot], 0.0)
            }

            bus.configureMasks(0, 0)
            bus.next(null)
            assertTrue(bus.gainMultipliers[3] > 1.98f)
            assertTrue(bus.pitchMultipliers[3] > 1.40)
            repeat(sampleRate / 4) { bus.next(null) }
            assertFalse("Unplug residual never retired at $sampleRate", bus.active)
            assertEquals(1f, bus.gainMultipliers[3], 0f)
            assertEquals(1.0, bus.pitchMultipliers[3], 0.0)
            assertEquals(0, bus.mipSafetyMask)
        }
    }

    @Test fun zeroGainAndNegativeSixSemitonesAlsoConvergeAndReturnToUnity() {
        val bus = OscillatorLayerModulation(48_000)
        val frame = FloatArray(ModDestination.entries.size) { .5f }
        frame[ModDestination.OSC1_GAIN.ordinal] = 0f
        frame[ModDestination.OSC1_PITCH.ordinal] = 0f
        bus.configureMasks(1, 1)
        repeat(12_000) { bus.next(frame) }
        assertEquals(0f, bus.gainMultipliers[0], 0f)
        assertEquals(2.0.pow(-0.5), bus.pitchMultipliers[0], 0.0)
        bus.configureMasks(0, 0)
        repeat(12_000) { bus.next(null) }
        assertFalse(bus.active)
        assertEquals(1f, bus.gainMultipliers[0], 0f)
        assertEquals(1.0, bus.pitchMultipliers[0], 0.0)
    }

    @Test fun eightRapidlyRepluggedSlotsHaveIndependentExactRetirement() {
        val bus = OscillatorLayerModulation(48_000)
        val frame = FloatArray(ModDestination.entries.size) { .5f }
        repeat(8) { slot ->
            val bit = 1 shl slot
            frame[ModDestination.OSC1_GAIN.ordinal + slot] = 1f
            frame[ModDestination.OSC1_PITCH.ordinal + slot] = 1f
            bus.configureMasks(bit, bit)
            repeat(4_800) { bus.next(frame) }
            assertEquals(2f, bus.gainMultipliers[slot], 0f)
            assertEquals(2.0.pow(0.5), bus.pitchMultipliers[slot], 0.0)
            bus.configureMasks(0, 0)
            repeat(4_800) { bus.next(null) }
            assertFalse("OSC" + (slot + 1) + " residual never retired", bus.active)
            assertEquals(1f, bus.gainMultipliers[slot], 0f)
            assertEquals(1.0, bus.pitchMultipliers[slot], 0.0)
            assertEquals(0, bus.mipSafetyMask)
        }
    }
}
