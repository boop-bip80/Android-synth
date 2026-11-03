package com.orbital.synth.core.dsp.processing

import org.junit.Assert.*
import org.junit.Test

class ModulationRackTest {
    private fun values(initial: Float = 0.5f) = FloatArray(ModDestination.entries.size) { initial }

    @Test fun existingParameterOrdinalsStayStable() {
        assertEquals(0, ModSource.LFO1.ordinal)
        assertEquals(7, ModSource.MACRO2.ordinal)
        assertEquals(0, ModDestination.PITCH.ordinal)
        assertEquals(6, ModDestination.FX_AMOUNT.ordinal)
        assertEquals(8, ModSource.LFO2.ordinal)
        assertEquals(7, ModDestination.OSC1_GAIN.ordinal)
    }

    @Test fun expressionSourcesAreRoutableWithUpTo256Slots() {
        val rack = ModulationRack(8_000, lfoCount = 8, routeCount = 256)
        rack.setLfoEnabled(0, false)
        rack.matrix.setRoute(255, ModSource.TOUCH_X, ModDestination.CUTOFF, 0.5f)
        rack.setSource(ModSource.TOUCH_X, 0.8f)
        val base = values()
        val output = values()
        rack.next(base, output)
        assertEquals(0.9f, output[ModDestination.CUTOFF.ordinal], 1e-6f)
        assertEquals(0.5f, output[ModDestination.RESONANCE.ordinal], 0f)
    }

    @Test fun tempoSyncedSineReachesQuarterCycleAtCorrectSample() {
        val rack = ModulationRack(8_000, lfoCount = 1)
        rack.setLfoShape(0, LfoShape.SINE)
        rack.syncLfoToTempo(0, bpm = 120f, beats = 1f)
        rack.matrix.setRoute(0, ModSource.LFO1, ModDestination.CUTOFF, 0.5f)
        val base = values()
        val output = values()
        rack.next(base, output)
        assertEquals(0.5f, output[ModDestination.CUTOFF.ordinal], 1e-5f)
        repeat(999) { rack.next(base, output) }
        rack.next(base, output)
        assertEquals(1f, output[ModDestination.CUTOFF.ordinal], 0.003f)
    }

    @Test fun retriggerResetsOnlyGateResettableLfoPhases() {
        val rack = ModulationRack(8_000, lfoCount = 2)
        rack.setLfoEnabled(1, true)
        rack.setLfoShape(0, LfoShape.SINE)
        rack.setLfoShape(1, LfoShape.SINE)
        rack.setLfoRateHz(0, 10f)
        rack.setLfoRateHz(1, 10f)
        rack.setLfoRetrigger(0, true)
        rack.matrix.setRoute(0, ModSource.LFO1, ModDestination.CUTOFF, 0.25f)
        rack.matrix.setRoute(1, ModSource.LFO2, ModDestination.MORPH, 0.25f)
        val base = values()
        val output = values()
        repeat(200) { rack.next(base, output) }
        rack.retrigger()
        rack.next(base, output)
        assertEquals(0.5f, output[ModDestination.CUTOFF.ordinal], 1e-5f)
        assertTrue(output[ModDestination.MORPH.ordinal] > 0.5f)
    }

    @Test fun smoothingAvoidsAbruptTouchJumpAndCanBeDisabled() {
        val rack = ModulationRack(48_000, lfoCount = 1)
        rack.setLfoEnabled(0, false)
        rack.matrix.setRoute(0, ModSource.TOUCH_PRESSURE, ModDestination.AMP, 0.5f)
        rack.setDestinationSlewMs(ModDestination.AMP, 15f)
        val base = values()
        val output = values()
        rack.next(base, output)
        rack.setSource(ModSource.TOUCH_PRESSURE, 1f)
        rack.next(base, output)
        assertTrue(output[ModDestination.AMP.ordinal] > 0.5f)
        assertTrue(output[ModDestination.AMP.ordinal] < 0.6f)
        repeat(3000) { rack.next(base, output) }
        assertEquals(1f, output[ModDestination.AMP.ordinal], 0.01f)
        rack.setDestinationSlewMs(ModDestination.AMP, 0f)
        rack.setSource(ModSource.TOUCH_PRESSURE, -1f)
        rack.next(base, output)
        assertEquals(0f, output[ModDestination.AMP.ordinal], 0f)
    }

    @Test fun seededSampleAndHoldIsDeterministicAndBounded() {
        val a = ModulationRack(8_000, lfoCount = 1, randomSeed = 2026)
        val b = ModulationRack(8_000, lfoCount = 1, randomSeed = 2026)
        for (rack in arrayOf(a, b)) {
            rack.setLfoShape(0, LfoShape.SAMPLE_HOLD)
            rack.setLfoRateHz(0, 40f)
            rack.matrix.setRoute(0, ModSource.LFO1, ModDestination.FOLD, 0.5f)
        }
        val base = values()
        val one = values()
        val two = values()
        repeat(2000) {
            a.next(base, one)
            b.next(base, two)
            assertEquals(one[ModDestination.FOLD.ordinal], two[ModDestination.FOLD.ordinal], 0f)
            assertTrue(one[ModDestination.FOLD.ordinal] in 0f..1f)
        }
    }

    @Test fun invalidExpressionAndSlewValuesCannotPoisonOutput() {
        val rack = ModulationRack(8_000, lfoCount = 1)
        rack.matrix.setRoute(0, ModSource.TOUCH_Y, ModDestination.PAN, Float.POSITIVE_INFINITY)
        rack.setSource(ModSource.TOUCH_Y, Float.NaN)
        rack.setDestinationSlewMs(ModDestination.PAN, Float.NaN)
        val base = values()
        val output = values()
        repeat(10) {
            rack.next(base, output)
            assertEquals(0.5f, output[ModDestination.PAN.ordinal], 0f)
        }
    }
}
