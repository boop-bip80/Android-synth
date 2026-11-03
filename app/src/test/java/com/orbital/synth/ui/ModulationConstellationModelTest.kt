package com.orbital.synth.ui

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
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

    @Test fun selectorShowsExactlyTwentyTwoWorkingAudioDestinationsFirst() {
        val model = ModulationConstellationModel
        val supported = listOf(
            ModDestination.PITCH, ModDestination.MORPH,
            ModDestination.CUTOFF, ModDestination.RESONANCE,
            ModDestination.AMP, ModDestination.PAN,
            ModDestination.OSC1_GAIN, ModDestination.OSC2_GAIN,
            ModDestination.OSC3_GAIN, ModDestination.OSC4_GAIN,
            ModDestination.OSC5_GAIN, ModDestination.OSC6_GAIN,
            ModDestination.OSC7_GAIN, ModDestination.OSC8_GAIN,
            ModDestination.OSC1_PITCH, ModDestination.OSC2_PITCH,
            ModDestination.OSC3_PITCH, ModDestination.OSC4_PITCH,
            ModDestination.OSC5_PITCH, ModDestination.OSC6_PITCH,
            ModDestination.OSC7_PITCH, ModDestination.OSC8_PITCH,
        )
        assertEquals(22, supported.size)
        assertTrue(model.liveTargetsLegend.contains("6 GLOBAL"))
        assertTrue(model.liveTargetsLegend.contains("16 LAYER TARGETS"))
        assertTrue(model.liveTargetsLegend.contains("OSC 1–8 GAIN"))
        assertTrue(model.liveTargetsLegend.contains("OSC 1–8 PITCH"))
        assertTrue(model.liveTargetsLegend.contains("remain reserved"))
        assertEquals(supported.toSet(), ModDestination.entries.filter(model::audibleTarget).toSet())
        assertEquals(supported, model.destinationChoices.take(supported.size))
        assertEquals(ModDestination.entries.size, model.destinationChoices.size)
        assertEquals(ModDestination.entries.toSet(), model.destinationChoices.toSet())
        for (target in supported) {
            assertTrue(model.destinationLabel(target).startsWith("LIVE"))
            assertTrue(model.destinationStatus(target).startsWith("DSP-CAPABLE TARGET"))
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
        assertTrue(model.audibleTarget(ModDestination.OSC1_GAIN))
        assertTrue(model.audibleTarget(ModDestination.OSC8_GAIN))
        assertTrue(model.audibleTarget(ModDestination.OSC1_PITCH))
        assertTrue(model.audibleTarget(ModDestination.OSC8_PITCH))
        assertTrue(model.destinationStatus(ModDestination.OSC7_GAIN).contains("0–2×"))
        assertTrue(model.destinationStatus(ModDestination.OSC4_PITCH).contains("±6 semitones"))
        for (reserved in listOf(
            ModDestination.DRIVE, ModDestination.FX_AMOUNT,
            ModDestination.FILTER_DRIVE, ModDestination.FOLD,
            ModDestination.LFO_RATE,
        )) {
            assertFalse(model.audibleTarget(reserved))
            assertTrue(model.destinationLabel(reserved).startsWith("RESERVED"))
            assertTrue(model.destinationStatus(reserved).startsWith("SAVED ROUTE"))
        }
    }

    @Test fun perOscillatorCapabilitiesDoNotFakeAPlayingSource() {
        val destination = ModDestination.OSC3_GAIN
        val route = ModulationRouteConfig(
            enabled = true, source = ModSource.LFO2,
            destination = destination, depth = .5f,
        )
        val paused = List(8) { ModulationLfoConfig(enabled = false) }
        val active = paused.toMutableList().apply { set(1, ModulationLfoConfig(enabled = true)) }
        assertTrue(ModulationConstellationModel.audibleTarget(destination))
        assertEquals("SOURCE PAUSED / ENABLE GENERATOR",
            ModulationSourceAvailability.routeStatus(route, paused, null))
        assertFalse(ModulationSourceAvailability.showConfiguredPhoton(route, paused, null))
        assertEquals("DSP GENERATOR CONNECTED / SIGNAL DEPENDENT",
            ModulationSourceAvailability.routeStatus(route, active, null))
        assertTrue(ModulationSourceAvailability.showConfiguredPhoton(route, active, null))

        val noDepth = route.copy(depth = 0f)
        assertEquals("ZERO DEPTH / NO MODULATION",
            ModulationSourceAvailability.routeStatus(noDepth, active, null))
        assertFalse(ModulationSourceAvailability.showConfiguredPhoton(noDepth, active, null))
        val unhosted = route.copy(source = ModSource.ENV1, destination = ModDestination.OSC8_PITCH)
        assertEquals("UNWIRED SOURCE / CONFIG ONLY IN THIS APP",
            ModulationSourceAvailability.routeStatus(unhosted, active, null))
        assertFalse(ModulationSourceAvailability.showConfiguredPhoton(unhosted, active, null))
        val reserved = route.copy(destination = ModDestination.FILTER_DRIVE)
        assertEquals("RESERVED DSP TARGET / SAVED CONFIG ONLY",
            ModulationSourceAvailability.routeStatus(reserved, active, null))
        assertFalse(ModulationSourceAvailability.showConfiguredPhoton(reserved, active, null))
    }

    @Test fun routeGridLabelsAlwaysStayWithinFiniteSlotRange() {
        assertEquals("01", ModulationConstellationModel.slotLabel(-10))
        assertEquals("01", ModulationConstellationModel.slotLabel(0))
        assertEquals("64", ModulationConstellationModel.slotLabel(63))
        assertEquals("64", ModulationConstellationModel.slotLabel(101))
    }
}
