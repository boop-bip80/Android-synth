package com.orbital.synth.ui

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchGraphUiModelTest {
    private fun snapshot(
        oscillators: List<OscillatorSlot> = emptyList(),
        lfos: List<ModulationLfoConfig> = List(8) { ModulationLfoConfig(enabled = true) },
        routes: List<ModulationRouteConfig> = emptyList(),
        fx: List<FxModuleConfig> = List(6) { FxModuleConfig() },
    ): PatchGraphSnapshot = PatchGraphUiModel.build(oscillators, lfos, routes, fx)

    @Test fun physicalBackboneContainsOnlyRealFixedAudioEdges() {
        val graph = snapshot()
        assertEquals(8, graph.nodes.count { it.kind == PatchNodeKind.OSC_LAYER })
        assertEquals(8, graph.nodes.count { it.kind == PatchNodeKind.LFO })
        assertEquals(6, graph.nodes.count { it.kind == PatchNodeKind.MOD_BUS })
        assertEquals(6, graph.nodes.count { it.kind == PatchNodeKind.FX_SLOT })
        assertTrue(graph.fixedAudio.any { it.from.id == "CORE" && it.to.id == "MIX" })
        assertTrue(graph.fixedAudio.any { it.from.id == "OSC7" && it.to.id == "MIX" })
        assertTrue(graph.fixedAudio.any { it.from.id == "MIX" && it.to.id == "FILTER" })
        assertTrue(graph.fixedAudio.any { it.from.id == "FILTER" && it.to.id == "AMP" })
        assertTrue(graph.fixedAudio.any { it.from.id == "AMP" && it.to.id == "MASTER" })
        assertTrue(graph.fixedAudio.any { it.from.id == "MASTER" && it.to.id == "FX0" })
        assertTrue(graph.fixedAudio.any { it.from.id == "FX5" && it.to.id == "OUT" })
        assertEquals(0, graph.patchCables.size)
    }

    @Test fun disabledLayerDoesNotClaimAudibleSignalButCoreStillFeedsVoice() {
        val graph = snapshot(oscillators = listOf(
            OscillatorSlot(enabled = true, level = .45f),
            OscillatorSlot(enabled = false, level = 1f),
        ))
        assertTrue(graph.nodes.first { it.id == "OSC0" }.active)
        assertFalse(graph.nodes.first { it.id == "OSC1" }.active)
        assertTrue(graph.fixedAudio.first { it.from.id == "OSC0" }.active)
        assertFalse(graph.fixedAudio.first { it.from.id == "OSC1" }.active)
        assertTrue(graph.fixedAudio.first { it.from.id == "CORE" }.active)
    }

    @Test fun realModulationBusesExactlyMatchCoreProcessingNotReservedMetadata() {
        assertEquals(
            listOf(
                ModDestination.PITCH, ModDestination.MORPH,
                ModDestination.CUTOFF, ModDestination.RESONANCE,
                ModDestination.AMP, ModDestination.PAN,
            ),
            PatchGraphUiModel.liveTargets,
        )
        assertEquals("CORE", PatchGraphUiModel.destinationEndpoint(ModDestination.MORPH))
        assertEquals("FILTER", PatchGraphUiModel.destinationEndpoint(ModDestination.CUTOFF))
        assertEquals("AMP", PatchGraphUiModel.destinationEndpoint(ModDestination.PAN))
        assertNull(PatchGraphUiModel.destinationEndpoint(ModDestination.OSC1_GAIN))
        assertNull(PatchGraphUiModel.destinationEndpoint(ModDestination.DRIVE))
    }

    @Test fun lfoOrdinalMappingIsExplicitAndEightSourcesAreNotContiguous() {
        assertEquals(ModSource.LFO1, PatchGraphUiModel.lfoSource(0))
        assertEquals(ModSource.LFO2, PatchGraphUiModel.lfoSource(1))
        assertEquals(ModSource.LFO8, PatchGraphUiModel.lfoSource(7))
        assertEquals(6, PatchGraphUiModel.lfoIndex(ModSource.LFO7))
        assertEquals(-1, PatchGraphUiModel.lfoIndex(ModSource.TOUCH_X))
        assertTrue(ModSource.LFO2.ordinal > ModSource.LFO1.ordinal + 1)
    }

    @Test fun visibleLfoCableUsesActualSourceAndDestinationOnly() {
        val route = ModulationRouteConfig(
            enabled = true, source = ModSource.LFO3,
            destination = ModDestination.CUTOFF, depth = .75f,
        )
        val graph = snapshot(routes = listOf(route))
        assertEquals(1, graph.liveRouteCount)
        assertEquals(1, graph.patchCables.size)
        assertEquals("LFO2", graph.patchCables.single().from.id)
        assertEquals("BUSCUTOFF", graph.patchCables.single().to.id)
        assertEquals(0, graph.patchCables.single().routeSlot)
        assertTrue(graph.patchCables.single().active)
    }

    @Test fun reservedTargetsNeverProduceFictitiousAudioCable() {
        val graph = snapshot(routes = listOf(
            ModulationRouteConfig(true, ModSource.LFO1, ModDestination.DRIVE, .5f),
            ModulationRouteConfig(true, ModSource.LFO2, ModDestination.OSC2_PITCH, .4f),
            ModulationRouteConfig(true, ModSource.LFO3, ModDestination.PAN, .3f),
        ))
        assertEquals(2, graph.reservedRouteCount)
        assertEquals(1, graph.liveRouteCount)
        assertEquals(1, graph.patchCables.size)
        assertEquals("BUSPAN", graph.patchCables.single().to.id)
    }

    @Test fun nonLfoSourceCanBeLiveWithoutPretendingItComesFromAnLfo() {
        val graph = snapshot(routes = listOf(
            ModulationRouteConfig(true, ModSource.TOUCH_X, ModDestination.PITCH, .5f),
            ModulationRouteConfig(true, ModSource.MOD_WHEEL, ModDestination.AMP, .5f),
        ))
        assertEquals(2, graph.liveRouteCount)
        assertEquals(2, graph.externalSourceRouteCount)
        assertEquals(0, graph.patchCables.size)
    }

    @Test fun disabledLfoLeavesSavedCableButShowsItsDormantState() {
        val graph = snapshot(
            lfos = List(8) { ModulationLfoConfig(enabled = it != 0) },
            routes = listOf(ModulationRouteConfig(true, ModSource.LFO1, ModDestination.MORPH, .5f)),
        )
        assertEquals(1, graph.dormantLfoRouteCount)
        assertFalse(graph.patchCables.single().active)
    }

    @Test fun newLivePatchesFillOnlyTrulyEmptySlotsAndNeverOverwriteDisabledMetadata() {
        val savedBypassed = ModulationRouteConfig(
            enabled = false, source = ModSource.LFO4,
            destination = ModDestination.AMP, depth = .8f,
        )
        val bank = listOf(savedBypassed, ModulationRouteConfig(), ModulationRouteConfig())
        val route = PatchGraphUiModel.findOrCreateLiveRoute(
            bank, ModSource.LFO2, ModDestination.PAN,
        )
        assertNotNull(route)
        assertEquals(1, route!!.first)
        assertTrue(route.second.enabled)
        assertEquals(ModDestination.PAN, route.second.destination)
        assertEquals(.35f, route.second.depth)
        assertEquals(-1, PatchGraphUiModel.firstEmptyRoute(List(64) { savedBypassed }))
    }

    @Test fun selectingExistingCableDoesNotAccidentallyStackDuplicateRouting() {
        val active = ModulationRouteConfig(true, ModSource.LFO2, ModDestination.CUTOFF, .83f)
        val bank = listOf(active, ModulationRouteConfig())
        val found = PatchGraphUiModel.findOrCreateLiveRoute(
            bank, ModSource.LFO2, ModDestination.CUTOFF,
        )
        assertEquals(0 to active, found)
        assertNull(PatchGraphUiModel.findOrCreateLiveRoute(
            bank, ModSource.LFO2, ModDestination.OSC3_GAIN,
        ))
        assertNull(PatchGraphUiModel.findOrCreateLiveRoute(
            bank, ModSource.TOUCH_X, ModDestination.CUTOFF,
        ))
    }

    @Test fun touchHitDetectionUsesMinimumPhysicalSizeAndDoesNotLeakOutsideGraph() {
        val graph = snapshot()
        val lfo = graph.nodes.first { it.kind == PatchNodeKind.LFO }
        val found = PatchGraphUiModel.hitNode(
            graph, lfo.x, lfo.y, widthPx = 360f,
            heightPx = 790f, touchDiameterPx = 48f,
        )
        assertEquals(lfo.id, found?.id)
        assertNull(PatchGraphUiModel.hitNode(
            graph, -9f, -9f, 360f, 790f, 48f,
        ))
        assertNull(PatchGraphUiModel.hitNode(
            graph, Float.NaN, .5f, 360f, 790f, 48f,
        ))
        assertNull(PatchGraphUiModel.hitNode(
            graph, lfo.x, lfo.y, 0f, 790f, 48f,
        ))
    }

    @Test fun orderedBypassedEffectsStillFormExactSerialDspSignalChain() {
        val fx = listOf(
            FxModuleConfig(FxModuleType.DELAY, true, .7f),
            FxModuleConfig(FxModuleType.CHORUS, false, .3f),
            FxModuleConfig(),
        )
        val graph = snapshot(fx = fx)
        assertEquals(3, graph.nodes.count { it.kind == PatchNodeKind.FX_SLOT })
        assertEquals("DELAY", graph.nodes.first { it.id == "FX0" }.fxType?.name)
        assertFalse(graph.nodes.first { it.id == "FX1" }.active)
        assertTrue(graph.fixedAudio.any { it.from.id == "FX0" && it.to.id == "FX1" })
        assertTrue(graph.fixedAudio.any { it.from.id == "FX1" && it.to.id == "FX2" })
        assertTrue(graph.fixedAudio.any { it.from.id == "FX2" && it.to.id == "OUT" })
    }

    @Test fun invalidDepthAndRouteOverflowAreBounded() {
        assertEquals(0f, PatchGraphUiModel.safeDepth(Float.NaN))
        assertEquals(-4f, PatchGraphUiModel.safeDepth(-100f))
        assertEquals(4f, PatchGraphUiModel.safeDepth(100f))
        val active = ModulationRouteConfig(true, ModSource.LFO1, ModDestination.MORPH, .5f)
        val graph = snapshot(routes = List(70) { active })
        assertEquals(64, graph.liveRouteCount)
        assertEquals(64, graph.patchCables.size)
    }
}
