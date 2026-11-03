package com.orbital.synth.ui

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LfoConfigurationGardenModelTest {
    @Test fun eightSourcesAreExactlyTheEightActualAudioRackLfoIds() {
        val expected = listOf(ModSource.LFO1, ModSource.LFO2, ModSource.LFO3, ModSource.LFO4,
            ModSource.LFO5, ModSource.LFO6, ModSource.LFO7, ModSource.LFO8)
        assertEquals(expected, (0..7).map(LfoConfigurationGardenModel::source))
    }

    @Test fun sixShapesRenderBoundedFiniteTwoCycleSourceVoltageWithoutFakeAudioPhase() {
        val traces = LfoShape.entries.map { shape ->
            LfoConfigurationGardenModel.lane(0,
                ModulationLfoConfig(enabled = true, shape = shape),
                emptyList(), 120f)
        }
        assertEquals(6, traces.size)
        for (lane in traces) {
            assertEquals(97, lane.trace.size)
            assertEquals(0f, lane.trace.first().x)
            assertEquals(1f, lane.trace.last().x)
            assertTrue(lane.trace.all { it.x.isFinite() && it.voltage.isFinite() &&
                it.voltage in -1f..1f })
        }
        assertEquals(1f, traces[0].trace[12].voltage, .0001f) // sine quarter period
        assertEquals(-1f, traces[1].trace[0].voltage, .0001f) // triangle at phase zero
        assertEquals(1f, traces[1].trace[24].voltage, .0001f)
        assertEquals(-1f, traces[2].trace[0].voltage, .0001f)
        assertEquals(1f, traces[3].trace[0].voltage, .0001f)
        assertEquals(1f, traces[4].trace[0].voltage, .0001f)
        assertEquals(-1f, traces[4].trace[24].voltage, .0001f)
        val hold = traces[5].trace
        assertTrue((0..47).all { abs(hold[it].voltage - hold[0].voltage) < .0001f })
        assertTrue((48..95).all { abs(hold[it].voltage - hold[48].voltage) < .0001f })
        assertTrue(hold[0].voltage != hold[48].voltage)
    }

    @Test fun effectiveRateUsesRealTempoClockClampsEngineLimitAndRespectsFreeHz() {
        val synced = ModulationLfoConfig(beatsPerCycle = 4f, rateHz = 7f)
        assertEquals(.5f, LfoConfigurationGardenModel.effectiveHz(synced, 120f), .0001f)
        assertEquals(1f, LfoConfigurationGardenModel.effectiveHz(synced, 240f), .0001f)
        assertEquals(.5f, LfoConfigurationGardenModel.effectiveHz(synced, Float.NaN), .0001f)
        assertEquals(40f, LfoConfigurationGardenModel.effectiveHz(
            synced.copy(beatsPerCycle = .0625f), 400f), .0001f)
        assertEquals(1f, LfoConfigurationGardenModel.effectiveHz(
            synced.copy(beatsPerCycle = 0f, rateHz = Float.NaN), 120f), .0001f)
    }

    @Test fun togglingSyncPreservesApproximateSpeedAndTouchButtonsRespectLimits() {
        val free = ModulationLfoConfig(rateHz = 1f)
        val synced = LfoConfigurationGardenModel.toggleSync(free, 120f)
        assertEquals(2f, synced.beatsPerCycle, .0001f)
        assertEquals(1f, LfoConfigurationGardenModel.effectiveHz(synced, 120f), .0001f)
        assertEquals(1f, LfoConfigurationGardenModel.toggleSync(synced, 120f).rateHz, .0001f)
        assertEquals(1f, LfoConfigurationGardenModel.faster(synced, false).rateHz, .0001f)
        assertEquals(4f, LfoConfigurationGardenModel.faster(synced, false).beatsPerCycle, .0001f)
        assertEquals(1f, LfoConfigurationGardenModel.faster(synced, true).beatsPerCycle, .0001f)
        val maxed = free.copy(rateHz = 39f)
        assertEquals(40f, LfoConfigurationGardenModel.faster(maxed, true).rateHz, .0001f)
        val minned = free.copy(rateHz = .01f)
        assertEquals(.01f, LfoConfigurationGardenModel.faster(minned, false).rateHz, .0001f)
    }

    @Test fun depthIsReadOnlyUntilActualLiveCableExistsAndReservedNeverMasqueradesAsLive() {
        val routes = List(65) { index ->
            when (index) {
                0 -> ModulationRouteConfig(true, ModSource.LFO1, ModDestination.CUTOFF, -2f)
                1 -> ModulationRouteConfig(true, ModSource.LFO1, ModDestination.OSC1_GAIN, 1f)
                2 -> ModulationRouteConfig(true, ModSource.LFO2, ModDestination.PITCH, .5f)
                3 -> ModulationRouteConfig(false, ModSource.LFO1, ModDestination.AMP, 3f)
                64 -> ModulationRouteConfig(true, ModSource.LFO1, ModDestination.PAN, 4f)
                else -> ModulationRouteConfig()
            }
        }
        val one = LfoConfigurationGardenModel.lane(0, ModulationLfoConfig(), routes, 120f)
        assertEquals(2, one.connectedCount)
        assertEquals(1, one.reservedCount)
        assertEquals(1, one.liveCables.size)
        assertEquals(0, one.liveCables[0].slot)
        assertEquals(ModDestination.CUTOFF, one.liveCables[0].destination)
        assertEquals(-2f, one.liveCables[0].depth, .0001f)
        assertEquals(2f, one.maxLiveDepth, .0001f)
        val two = LfoConfigurationGardenModel.lane(1, ModulationLfoConfig(), routes, 120f)
        assertEquals(1, two.liveCables.size)
        assertEquals(ModDestination.PITCH, two.liveCables[0].destination)
        val empty = LfoConfigurationGardenModel.lane(7, ModulationLfoConfig(), routes, 120f)
        assertTrue(empty.liveCables.isEmpty())
        assertEquals(0, empty.connectedCount)
        assertEquals(0f, empty.maxLiveDepth, .0001f)
        assertFalse(one.config.enabled)
    }
}
