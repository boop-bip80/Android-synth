package com.orbital.synth.ui

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No enum migration, audio-thread source invention, or pretend spectrum. */
class ModulationSourceAvailabilityTest {
    private val enabledLfos = List(8) { ModulationLfoConfig(enabled = true) }
    private val disabledLfos = List(8) { ModulationLfoConfig() }

    private fun route(
        source: ModSource,
        depth: Float = .75f,
        destination: ModDestination = ModDestination.CUTOFF,
    ) = ModulationRouteConfig(enabled = true, source = source,
        destination = destination, depth = depth)

    @Test fun everyOneOf24SerializedSourcesHasExactlyOneTruthfulAvailability() {
        val sources = ModSource.entries.toList()
        assertEquals("Historical source IDs must remain present", 24, sources.size)
        val groups = sources.groupBy(ModulationSourceAvailability::kind)
        assertEquals(8, groups[ModulationSourceKind.LFO]?.size)
        assertEquals(listOf(ModSource.STEP), groups[ModulationSourceKind.STEP])
        assertEquals(
            setOf(ModSource.TOUCH_X, ModSource.TOUCH_Y, ModSource.TOUCH_PRESSURE),
            groups[ModulationSourceKind.TOUCH]?.toSet(),
        )
        assertEquals(
            setOf(
                ModSource.ENV1, ModSource.ENV2, ModSource.ENV3,
                ModSource.VELOCITY, ModSource.KEYTRACK, ModSource.AFTERTOUCH,
                ModSource.MOD_WHEEL, ModSource.MACRO1, ModSource.MACRO2,
                ModSource.RANDOM, ModSource.GATE, ModSource.NOTE_PITCH,
            ),
            groups[ModulationSourceKind.UNWIRED]?.toSet(),
        )
        assertTrue(sources.all {
            ModulationSourceAvailability.sourceLabel(it).contains(it.name)
        })
        assertTrue(ModulationSourceAvailability.sourceLabel(ModSource.ENV1)
            .contains("UNWIRED / CONFIG ONLY"))
        assertTrue(ModulationSourceAvailability.pickerLabel(ModSource.ENV1)
            .contains("UNWIRED"))
        assertTrue(ModulationSourceAvailability.pickerLabel(ModSource.TOUCH_Y)
            .contains("GESTURE"))
    }

    @Test fun sourceGenerationRequiresEnabledInternalLfoOrStep() {
        for (source in ModulationSourceAvailability.lfoSources) {
            assertTrue(ModulationSourceAvailability.generatorEnabled(source, enabledLfos, null))
            assertFalse(ModulationSourceAvailability.generatorEnabled(source, disabledLfos, null))
        }
        assertTrue(ModulationSourceAvailability.generatorEnabled(ModSource.STEP,
            disabledLfos, ModulationStepConfig(enabled = true)))
        assertFalse(ModulationSourceAvailability.generatorEnabled(ModSource.STEP,
            enabledLfos, ModulationStepConfig(enabled = false)))
        assertFalse(ModulationSourceAvailability.generatorEnabled(ModSource.STEP,
            enabledLfos, null))
        for (source in ModSource.entries.filter {
            ModulationSourceAvailability.kind(it) == ModulationSourceKind.TOUCH ||
                ModulationSourceAvailability.kind(it) == ModulationSourceKind.UNWIRED
        }) {
            assertFalse("No fake on-screen source clock for " + source,
                ModulationSourceAvailability.generatorEnabled(source, enabledLfos,
                    ModulationStepConfig(enabled = true)))
        }
    }

    @Test fun decorativePhotonNeedsWiredDestinationEnabledPeriodicSourceAndSignedDepth() {
        for (depth in listOf(.001f, .75f, -2.5f, -4f)) {
            assertTrue("Inverse signed depth is still a real modulation cable",
                ModulationSourceAvailability.showConfiguredPhoton(
                    route(ModSource.LFO1, depth), enabledLfos, null))
        }
        assertFalse(ModulationSourceAvailability.showConfiguredPhoton(
            route(ModSource.LFO1, 0f), enabledLfos, null))
        assertFalse(ModulationSourceAvailability.showConfiguredPhoton(
            route(ModSource.LFO1, Float.NaN), enabledLfos, null))
        assertFalse(ModulationSourceAvailability.showConfiguredPhoton(
            route(ModSource.LFO1, 1f), disabledLfos, null))
        assertFalse(ModulationSourceAvailability.showConfiguredPhoton(
            route(ModSource.LFO1, 1f, ModDestination.OSC1_GAIN),
            enabledLfos, null))
        assertFalse(ModulationSourceAvailability.showConfiguredPhoton(
            route(ModSource.LFO1, 1f).copy(enabled = false), enabledLfos, null))
        assertTrue(ModulationSourceAvailability.showConfiguredPhoton(
            route(ModSource.STEP), disabledLfos,
            ModulationStepConfig(enabled = true)))
        assertFalse(ModulationSourceAvailability.showConfiguredPhoton(
            route(ModSource.STEP), disabledLfos,
            ModulationStepConfig(enabled = false)))

        for (source in ModSource.entries.filter {
            ModulationSourceAvailability.kind(it) == ModulationSourceKind.TOUCH ||
                ModulationSourceAvailability.kind(it) == ModulationSourceKind.UNWIRED
        }) {
            assertFalse("Gesture/unwired voltage is not a periodic UI animation",
                ModulationSourceAvailability.showConfiguredPhoton(
                    route(source), enabledLfos, ModulationStepConfig(enabled = true)))
        }
    }

    @Test fun labelsDistinguishDspConnectedFromAudibleAndUnwired() {
        assertTrue(ModulationSourceAvailability.routeStatus(
            route(ModSource.ENV1), enabledLfos, null).contains("UNWIRED SOURCE"))
        assertTrue(ModulationSourceAvailability.routeStatus(
            route(ModSource.TOUCH_PRESSURE), enabledLfos, null)
            .contains("GESTURE-DRIVEN"))
        assertTrue(ModulationSourceAvailability.routeStatus(
            route(ModSource.LFO1), disabledLfos, null).contains("SOURCE PAUSED"))
        assertTrue(ModulationSourceAvailability.routeStatus(
            route(ModSource.LFO1), enabledLfos, null).contains("SIGNAL DEPENDENT"))
        assertTrue(ModulationSourceAvailability.routeStatus(
            route(ModSource.LFO1, 0f), enabledLfos, null).contains("NO MODULATION"))
        assertTrue(ModulationSourceAvailability.routeStatus(
            route(ModSource.LFO1, 1f).copy(enabled = false), enabledLfos, null)
            .contains("BYPASSED"))
        assertTrue(ModulationSourceAvailability.routeStatus(
            route(ModSource.LFO1, 1f, ModDestination.OSC1_GAIN),
            enabledLfos, null).contains("RESERVED"))
    }

    @Test fun signedSavedRouteFieldsStayUntouchedAndUnwiredCableTalkBackCannotClaimAudio() {
        val saved = route(ModSource.VELOCITY, -1.75f)
        val trace = PatchCableTraceModel.describe(63, saved, enabledLfos, null)
        assertEquals(PatchCableTraceStatus.UNWIRED_SOURCE, trace.status)
        assertEquals(ModSource.VELOCITY, trace.source)
        assertEquals(ModDestination.CUTOFF, trace.destination)
        assertEquals(-1.75f, trace.depth, 0f)
        assertTrue(trace.accessibleLabel.contains("unwired / config only"))
        assertFalse(trace.accessibleLabel.contains("patched / signal dependent"))
        val touch = PatchCableTraceModel.describe(0,
            route(ModSource.TOUCH_X, -1f), enabledLfos, null)
        assertEquals(PatchCableTraceStatus.GESTURE_DRIVEN, touch.status)
        assertTrue(touch.accessibleLabel.contains("gesture-driven"))
        val dry = PatchCableTraceModel.describe(1,
            route(ModSource.ENV1, 0f), enabledLfos, null)
        assertEquals(PatchCableTraceStatus.ZERO_DEPTH, dry.status)
    }
}
