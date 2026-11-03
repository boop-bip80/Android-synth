package com.orbital.synth.ui

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
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
        steps: ModulationStepConfig? = null,
        bank: PatchGraphTargetBank = PatchGraphTargetBank.GLOBAL,
    ): PatchGraphSnapshot = PatchGraphUiModel.build(oscillators, lfos, routes, fx, steps, bank)

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
                ModDestination.OSC1_GAIN, ModDestination.OSC2_GAIN,
                ModDestination.OSC3_GAIN, ModDestination.OSC4_GAIN,
                ModDestination.OSC5_GAIN, ModDestination.OSC6_GAIN,
                ModDestination.OSC7_GAIN, ModDestination.OSC8_GAIN,
                ModDestination.OSC1_PITCH, ModDestination.OSC2_PITCH,
                ModDestination.OSC3_PITCH, ModDestination.OSC4_PITCH,
                ModDestination.OSC5_PITCH, ModDestination.OSC6_PITCH,
                ModDestination.OSC7_PITCH, ModDestination.OSC8_PITCH,
            ),
            PatchGraphUiModel.liveTargets,
        )
        assertEquals("CORE", PatchGraphUiModel.destinationEndpoint(ModDestination.MORPH))
        assertEquals("FILTER", PatchGraphUiModel.destinationEndpoint(ModDestination.CUTOFF))
        assertEquals("AMP", PatchGraphUiModel.destinationEndpoint(ModDestination.PAN))
        assertEquals("OSC 1 GAIN",
            PatchGraphUiModel.destinationEndpoint(ModDestination.OSC1_GAIN))
        assertEquals("OSC 8 PITCH",
            PatchGraphUiModel.destinationEndpoint(ModDestination.OSC8_PITCH))
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
        val routes = listOf(
            ModulationRouteConfig(true, ModSource.LFO1, ModDestination.DRIVE, .5f),
            ModulationRouteConfig(true, ModSource.LFO2, ModDestination.OSC2_PITCH, .4f),
            ModulationRouteConfig(true, ModSource.LFO3, ModDestination.PAN, .3f),
        )
        val global = snapshot(routes = routes)
        assertEquals(1, global.reservedRouteCount)
        assertEquals(2, global.liveRouteCount)
        assertEquals(1, global.hiddenLiveRouteCount)
        assertEquals(1, global.patchCables.size)
        assertEquals("BUSPAN", global.patchCables.single().to.id)
        val osc2 = snapshot(
            oscillators = List(8) {
                OscillatorSlot(enabled = it == 1, level = if (it == 1) .55f else 0f)
            },
            routes = routes,
            bank = PatchGraphTargetBank.OSC2,
        )
        assertEquals(1, osc2.patchCables.size)
        assertEquals("BUSOSC2_PITCH", osc2.patchCables.single().to.id)
        assertTrue(osc2.patchCables.single().active)
        assertEquals(1, osc2.hiddenLiveRouteCount)
        assertEquals(1, osc2.reservedRouteCount)
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
        assertEquals(
            1 to ModulationRouteConfig(
                enabled = true, source = ModSource.LFO2,
                destination = ModDestination.OSC3_GAIN, depth = .35f,
            ),
            PatchGraphUiModel.findOrCreateLiveRoute(
                bank, ModSource.LFO2, ModDestination.OSC3_GAIN,
            ),
        )
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
        // No off-canvas source can be selected by an oversized hit radius.
        assertNull(PatchGraphUiModel.hitNode(
            graph, -.001f, lfo.y, 360f, 790f, 300f,
        ))
        assertNull(PatchGraphUiModel.hitNode(
            graph, 1.001f, lfo.y, 360f, 790f, 300f,
        ))
        assertNull(PatchGraphUiModel.hitNode(
            graph, lfo.x, 1.001f, 360f, 790f, 300f,
        ))
        assertNull(PatchGraphUiModel.hitNode(
            graph, lfo.x, lfo.y, Float.NaN, 790f, 48f,
        ))
        assertNull(PatchGraphUiModel.hitNode(
            graph, lfo.x, lfo.y, 360f, 790f, Float.POSITIVE_INFINITY,
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
    @Test fun audioClockedStepNodeIsVisibleOnlyWithConfigAndHonestEnabledGate() {
        val routes = listOf(
            ModulationRouteConfig(true, ModSource.STEP, ModDestination.AMP, .7f),
            ModulationRouteConfig(true, ModSource.STEP, ModDestination.PAN, -.5f),
        )
        val config = ModulationStepConfig(enabled = true)
        val live = snapshot(routes = routes, steps = config)
        assertEquals("STEP", live.nodes.single { it.kind == PatchNodeKind.STEP }.id)
        assertEquals(2, live.stepRouteCount)
        assertEquals(0, live.dormantStepRouteCount)
        assertEquals(2, live.liveRouteCount)
        assertEquals(0, live.externalSourceRouteCount)
        assertEquals(
            setOf("BUSAMP", "BUSPAN"),
            live.patchCables.map { it.to.id }.toSet(),
        )
        assertTrue(live.patchCables.all { it.from.id == "STEP" && it.active })

        val disabled = snapshot(routes = routes, steps = config.copy(enabled = false))
        assertEquals(1, disabled.nodes.count { it.kind == PatchNodeKind.STEP })
        assertFalse(disabled.nodes.single { it.kind == PatchNodeKind.STEP }.active)
        assertEquals(2, disabled.dormantStepRouteCount)
        assertEquals(2, disabled.patchCables.size)
        assertTrue(disabled.patchCables.none { it.active })

        val missingHostState = snapshot(routes = routes)
        assertEquals(0, missingHostState.nodes.count { it.kind == PatchNodeKind.STEP })
        assertEquals(2, missingHostState.dormantStepRouteCount)
        assertEquals(0, missingHostState.patchCables.size)
        assertEquals(0, missingHostState.reservedRouteCount)
    }

    @Test fun stepCanPatchOnlyLivePcmTargetsAndLatestFxModelsRemainVisible() {
        val bank = listOf(ModulationRouteConfig(), ModulationRouteConfig())
        val patch = PatchGraphUiModel.findOrCreateLiveRoute(
            bank, ModSource.STEP, ModDestination.RESONANCE,
        )
        assertNotNull(patch)
        assertEquals(0, patch!!.first)
        assertEquals(ModSource.STEP, patch.second.source)
        assertTrue(patch.second.enabled)
        assertNull(PatchGraphUiModel.findOrCreateLiveRoute(
            bank, ModSource.STEP, ModDestination.DRIVE,
        ))

        val types = listOf(FxModuleType.REVERB, FxModuleType.PHASER, FxModuleType.BITCRUSH)
        val graph = snapshot(fx = types.map {
            FxModuleConfig(type = it, enabled = true, wet = .7f)
        })
        assertEquals(types, graph.nodes.filter {
            it.kind == PatchNodeKind.FX_SLOT
        }.map { it.fxType })
        assertEquals(listOf("REVERB", "PHASE", "CRUSH"), types.map(PatchGraphUiModel::slotName))
        assertTrue(graph.fixedAudio.any { it.from.id == "FX2" && it.to.id == "OUT" })
    }

    @Test fun allTwentyTwoRealTargetsAreReachableInNineNonoverlappingBanks() {
        val allTargets = PatchGraphTargetBank.entries.flatMap(PatchGraphUiModel::visibleTargets)
        assertEquals(22, allTargets.size)
        assertEquals(22, allTargets.toSet().size)
        assertEquals(PatchGraphUiModel.liveTargets.toSet(), allTargets.toSet())
        assertEquals(6, PatchGraphUiModel.visibleTargets(PatchGraphTargetBank.GLOBAL).size)
        PatchGraphTargetBank.entries.drop(1).forEach { bank ->
            assertEquals(2, PatchGraphUiModel.visibleTargets(bank).size)
            assertTrue(PatchGraphUiModel.visibleTargets(bank).all { target ->
                PatchGraphUiModel.bankForTarget(target) == bank
            })
        }
        assertTrue(PatchGraphTargetBank.entries.all { bank ->
            PatchGraphUiModel.visibleTargets(bank).none { it == ModDestination.DRIVE }
        })
    }

    @Test fun everyVisibleBankPortIsIndividuallyHitAt350dpWithoutWrongBankNode() {
        val oscillators = List(8) { OscillatorSlot(enabled = true, level = .55f) }
        for (bank in PatchGraphTargetBank.entries) {
            val graph = snapshot(oscillators = oscillators, bank = bank)
            val buses = graph.nodes.filter { it.kind == PatchNodeKind.MOD_BUS }
            assertEquals(PatchGraphUiModel.visibleTargets(bank).size, buses.size)
            for (i in buses.indices) {
                val bus = buses[i]
                assertEquals(
                    "At 350dp a real bus must be reachable by its unique 48dp pointer target",
                    bus,
                    PatchGraphUiModel.hitNode(graph, bus.x, bus.y, 350f, 790f, 48f),
                )
                for (j in (i + 1) until buses.size) {
                    val other = buses[j]
                    val dx = (bus.x - other.x) * 350f
                    val dy = (bus.y - other.y) * 790f
                    assertTrue(
                        "Physical 48dp bus targets must not collide: ${bus.id}/${other.id}",
                        kotlin.math.hypot(dx, dy) >= 48f,
                    )
                }
            }
            val hidden = PatchGraphUiModel.liveTargets.toSet() -
                buses.mapNotNull { it.destination }.toSet()
            assertTrue("Invisible bank terminals must never be hit-testable",
                graph.nodes.none { it.destination in hidden })
        }
    }

    @Test fun oscillatorGainPitchRoutesAreRealAndHonestAcrossBypassAndBankSwitch() {
        val route = ModulationRouteConfig(
            enabled = true,
            source = ModSource.LFO3,
            destination = ModDestination.OSC8_PITCH,
            depth = .65f,
        )
        val disabled = snapshot(
            oscillators = List(8) { OscillatorSlot(enabled = false) },
            routes = listOf(route),
            bank = PatchGraphTargetBank.OSC8,
        )
        assertEquals(1, disabled.liveRouteCount)
        assertEquals(0, disabled.reservedRouteCount)
        assertEquals(1, disabled.dormantOscillatorTargetRouteCount)
        assertEquals(0, disabled.hiddenLiveRouteCount)
        assertEquals("BUSOSC8_PITCH", disabled.patchCables.single().to.id)
        assertFalse(disabled.patchCables.single().active)

        // Gain modulation multiplies the configured slot level: zero stays
        // silent even if the OSC is enabled and the LFO is producing output.
        val zeroLevel = snapshot(
            oscillators = List(8) {
                OscillatorSlot(enabled = true, level = if (it == 7) 0f else .55f)
            },
            routes = listOf(route),
            bank = PatchGraphTargetBank.OSC8,
        )
        assertEquals(1, zeroLevel.dormantOscillatorTargetRouteCount)
        assertFalse(zeroLevel.nodes.single {
            it.id == "BUSOSC8_PITCH"
        }.active)
        assertFalse(zeroLevel.patchCables.single().active)

        val enabled = snapshot(
            oscillators = List(8) { OscillatorSlot(enabled = true, level = .55f) },
            routes = listOf(route),
            bank = PatchGraphTargetBank.OSC8,
        )
        assertTrue(enabled.patchCables.single().active)
        val global = snapshot(routes = listOf(route))
        assertEquals(1, global.hiddenLiveRouteCount)
        assertEquals(0, global.patchCables.size)
        assertEquals(1, global.liveRouteCount)
        assertEquals(0, global.reservedRouteCount)
    }

    @Test fun eightOrderedFxStagesAvoidAmbiguousSub48dpCanvasHitZones() {
        val graph = snapshot(fx = List(8) {
            FxModuleConfig(type = FxModuleType.DELAY, enabled = true, wet = .75f)
        })
        val nodes = graph.nodes.filter { it.kind == PatchNodeKind.FX_SLOT }
        assertEquals(8, nodes.size)
        for (i in nodes.indices) {
            assertEquals(
                nodes[i],
                PatchGraphUiModel.hitNode(
                    graph, nodes[i].x, nodes[i].y, 350f, 790f, 48f,
                ),
            )
            for (j in (i + 1) until nodes.size) {
                val dx = (nodes[i].x - nodes[j].x) * 350f
                val dy = (nodes[i].y - nodes[j].y) * 790f
                assertTrue("FX ports must have distinct 48dp physical centers",
                    kotlin.math.hypot(dx, dy) >= 48f)
            }
        }
        assertTrue(graph.fixedAudio.any { it.from.id == "FX6" && it.to.id == "FX7" })
        assertTrue(graph.fixedAudio.any { it.from.id == "FX7" && it.to.id == "OUT" })
    }

}
