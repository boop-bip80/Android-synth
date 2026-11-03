package com.orbital.synth.ui

import com.orbital.synth.core.dsp.processing.ModDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ModulationConstellationModelTest {
    @Test fun logarithmicRateFadersRespectWholeRange() {
        assertEquals(.01f, ModulationConstellationModel.sliderToHz(0f), .0001f)
        assertEquals(40f, ModulationConstellationModel.sliderToHz(1f), .001f)
        assertEquals(1f, ModulationConstellationModel.hzToSlider(40f), .0001f)
        assertEquals(0f, ModulationConstellationModel.hzToSlider(.01f), .0001f)
    }

    @Test fun rateRoundTripAcrossMusicalRates() {
        for (hz in listOf(.01f, .1f, .5f, 1f, 4f, 8f, 16f, 40f)) {
            val encoded = ModulationConstellationModel.hzToSlider(hz)
            val decoded = ModulationConstellationModel.sliderToHz(encoded)
            assertTrue("rate=" + hz + " decoded=" + decoded, abs(decoded - hz) < hz * .0002f + .0001f)
        }
    }

    @Test fun nonfiniteInputsNeverCreateInvalidDspSettings() {
        assertEquals(.5f, ModulationConstellationModel.sliderToHz(Float.NaN)
            .let(ModulationConstellationModel::hzToSlider), .0001f)
        assertTrue(ModulationConstellationModel.hzToSlider(Float.POSITIVE_INFINITY) in 0f..1f)
        assertEquals(120f, ModulationConstellationModel.safeTempo(Float.NEGATIVE_INFINITY))
        assertEquals(20f, ModulationConstellationModel.safeTempo(-500f))
        assertEquals(400f, ModulationConstellationModel.safeTempo(900f))
    }

    @Test fun tempoSyncStepsAreBoundedAndReversible() {
        val beats = ModulationConstellationModel.adjacentBeats(4f, -1)
        assertEquals(2f, beats)
        assertEquals(4f, ModulationConstellationModel.adjacentBeats(beats, 1))
        assertEquals(.0625f, ModulationConstellationModel.adjacentBeats(.0625f, -1))
        assertEquals(64f, ModulationConstellationModel.adjacentBeats(64f, 1))
    }

    @Test fun selectorShowsExactlySixWorkingAudioDestinationsFirst() {
        val model = ModulationConstellationModel
        val supported = listOf(
            ModDestination.PITCH, ModDestination.MORPH,
            ModDestination.CUTOFF, ModDestination.RESONANCE,
            ModDestination.AMP, ModDestination.PAN,
        )
        assertEquals(supported.toSet(), ModDestination.entries.filter(model::audibleTarget).toSet())
        assertEquals(supported, model.destinationChoices.take(supported.size))
        assertEquals(ModDestination.entries.size, model.destinationChoices.size)
        assertEquals(ModDestination.entries.toSet(), model.destinationChoices.toSet())
        for (target in supported) {
            assertTrue(model.destinationLabel(target).startsWith("LIVE"))
            assertTrue(model.destinationStatus(target).startsWith("AUDIBLE NOW"))
        }
        for (target in ModDestination.entries.filterNot(model::audibleTarget)) {
            assertFalse(model.audibleTarget(target))
            assertTrue(model.destinationLabel(target).startsWith("RESERVED"))
            assertTrue(model.destinationStatus(target).startsWith("SAVED ROUTE"))
        }
    }

    @Test fun destinationDescriptionsMatchActualSignalPath() {
        val model = ModulationConstellationModel
        assertTrue(model.destinationStatus(ModDestination.CUTOFF).contains("filter cutoff"))
        assertTrue(model.destinationStatus(ModDestination.RESONANCE).contains("filter resonance"))
        assertTrue(model.destinationStatus(ModDestination.AMP).contains("before master effects"))
        assertTrue(model.destinationStatus(ModDestination.PAN).contains("before master effects"))
        assertFalse(model.audibleTarget(ModDestination.FILTER_DRIVE))
        assertFalse(model.audibleTarget(ModDestination.OSC1_GAIN))
        assertFalse(model.audibleTarget(ModDestination.FX_AMOUNT))
    }

    @Test fun routeGridLabelsAlwaysStayWithinFiniteSlotRange() {
        assertEquals("01", ModulationConstellationModel.slotLabel(-10))
        assertEquals("01", ModulationConstellationModel.slotLabel(0))
        assertEquals("64", ModulationConstellationModel.slotLabel(63))
        assertEquals("64", ModulationConstellationModel.slotLabel(101))
    }
}
