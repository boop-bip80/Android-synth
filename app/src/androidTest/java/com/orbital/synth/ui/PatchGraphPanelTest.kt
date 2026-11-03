package com.orbital.synth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PatchGraphPanelTest {
    @get:Rule val compose = createComposeRule()

    private fun assertVisible48Dp(tag: String) {
        val visible = compose.onNodeWithTag(tag)
            .performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        assertTrue(
            "$tag must expose a fully visible 48dp × 48dp physical tap corridor " +
                "at the real device density $density: $visible",
            visible.width >= 48f * density - 1f &&
                visible.height >= 48f * density - 1f,
        )
    }

    @Test fun talkBackAccessibleLfoButtonsPatchActualTypedLiveRouteCallbacks() {
        val routes = mutableStateOf(List(64) { ModulationRouteConfig() })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PatchGraphPanel(
                        oscillators = List(8) { OscillatorSlot() },
                        lfos = List(8) { ModulationLfoConfig(enabled = true) },
                        routes = routes.value,
                        fxSlots = List(6) { FxModuleConfig() },
                        onRouteChange = { index, config ->
                            routes.value = routes.value.toMutableList().apply { set(index, config) }
                        },
                        onRouteClear = { index ->
                            routes.value = routes.value.toMutableList().apply {
                                set(index, ModulationRouteConfig())
                            }
                        },
                        onMoveFxSlot = { _, _ -> },
                    )
                }
            }
        }
        compose.onNodeWithTag("patchGraphPanel").assertExists()
        compose.onNodeWithTag("patchGraphCanvas").assertExists()
        compose.onNodeWithTag("patchGraphTruthStatus").assertExists()
        compose.onNodeWithTag("patchLfo2").performScrollTo().performClick()
        compose.onNodeWithTag("patchBusCUTOFF").performScrollTo().performClick()
        compose.runOnIdle {
            val route = routes.value.first()
            assertTrue(route.enabled)
            assertEquals(ModSource.LFO3, route.source)
            assertEquals(ModDestination.CUTOFF, route.destination)
            assertTrue(route.depth > 0f)
        }
        compose.onNodeWithTag("patchGraphRouteEditor").performScrollTo()
        compose.onNodeWithTag("patchGraphDepthSlider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-1.5f) }
        compose.runOnIdle { assertTrue(routes.value[0].depth < -1f) }
        compose.onNodeWithTag("patchGraphToggleRoute").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(routes.value[0].enabled) }
        compose.onNodeWithTag("patchGraphClearRoute").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ModulationRouteConfig(), routes.value[0]) }
    }

    @Test fun orderedFxFallbackMoveControlsSendActualHostReorderCallbacks() {
        val stages = mutableStateOf(listOf(
            FxModuleConfig(FxModuleType.CHORUS, enabled = true, wet = .6f),
            FxModuleConfig(FxModuleType.DELAY, enabled = true, wet = .5f),
            FxModuleConfig(),
        ))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PatchGraphPanel(
                        oscillators = emptyList(),
                        lfos = List(8) { ModulationLfoConfig() },
                        routes = List(64) { ModulationRouteConfig() },
                        fxSlots = stages.value,
                        onRouteChange = { _, _ -> },
                        onRouteClear = {},
                        onMoveFxSlot = { from, to ->
                            stages.value = stages.value.toMutableList().apply {
                                add(to, removeAt(from))
                            }
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("patchGraphFxChoices").performScrollTo()
        compose.onNodeWithTag("patchFxMoveRight").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(FxModuleType.DELAY, stages.value[0].type)
            assertEquals(FxModuleType.CHORUS, stages.value[1].type)
        }
        compose.onNodeWithTag("patchFxMoveLeft").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(FxModuleType.CHORUS, stages.value[0].type)
        }
    }

    @Test fun tappingRealOscillatorCanvasNodeTogglesHostOwnedLayerOnly() {
        val layers = mutableStateOf(List(8) { OscillatorSlot() })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PatchGraphPanel(
                        oscillators = layers.value,
                        lfos = List(8) { ModulationLfoConfig() },
                        routes = List(64) { ModulationRouteConfig() },
                        fxSlots = List(6) { FxModuleConfig() },
                        onRouteChange = { _, _ -> },
                        onRouteClear = {},
                        onMoveFxSlot = { _, _ -> },
                        onOscillatorSlotChange = { index, config ->
                            layers.value = layers.value.toMutableList().apply { set(index, config) }
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("patchGraphCanvas").performTouchInput {
            click(Offset(center.x * .25f, center.y * .29f))
        }
        compose.runOnIdle {
            assertTrue(layers.value[0].enabled)
            assertTrue(layers.value[0].level > 0f)
            assertEquals(1, layers.value.count { it.enabled })
        }
        compose.onNodeWithTag("patchGraphNotice")
            .assertTextContains("engaged in the real voice mixer", substring = true)
        compose.onNodeWithTag("patchOscillator0").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(layers.value[0].enabled) }
    }

    @Test fun draggingFxNodeAcrossCanvasReordersActualHostStages() {
        val stages = mutableStateOf(listOf(
            FxModuleConfig(FxModuleType.CHORUS, true, .6f),
            FxModuleConfig(FxModuleType.DELAY, true, .5f),
            FxModuleConfig(FxModuleType.SATURATION, true, .4f),
        ))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PatchGraphPanel(
                        oscillators = emptyList(),
                        lfos = List(8) { ModulationLfoConfig() },
                        routes = List(64) { ModulationRouteConfig() },
                        fxSlots = stages.value,
                        onRouteChange = { _, _ -> },
                        onRouteClear = {},
                        onMoveFxSlot = { from, to ->
                            stages.value = stages.value.toMutableList().apply {
                                add(to, removeAt(from))
                            }
                        },
                    )
                }
            }
        }
        // Three true FX stages are centered at 1/4, 2/4, 3/4 width, y=0.55.
        compose.onNodeWithTag("patchGraphCanvas").performTouchInput {
            swipe(
                start = Offset(center.x * .5f, center.y * 1.1f),
                end = Offset(center.x * 1.5f, center.y * 1.1f),
                durationMillis = 520L,
            )
        }
        compose.runOnIdle {
            assertEquals(FxModuleType.DELAY, stages.value[0].type)
            assertEquals(FxModuleType.SATURATION, stages.value[1].type)
            assertEquals(FxModuleType.CHORUS, stages.value[2].type)
        }
    }

    @Test fun existingCableSupportsTypedSourceDestinationAndCurveWithRealCallbacks() {
        val routes = mutableStateOf(List(64) { index ->
            if (index == 0) ModulationRouteConfig(
                enabled = true,
                source = ModSource.LFO1,
                destination = ModDestination.MORPH,
                depth = .6f,
            ) else ModulationRouteConfig()
        })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PatchGraphPanel(
                        oscillators = emptyList(),
                        lfos = List(8) { ModulationLfoConfig(enabled = true) },
                        routes = routes.value,
                        fxSlots = List(6) { FxModuleConfig() },
                        onRouteChange = { index, edited ->
                            routes.value = routes.value.toMutableList().apply {
                                set(index, edited)
                            }
                        },
                        onRouteClear = {},
                        onMoveFxSlot = { _, _ -> },
                    )
                }
            }
        }
        compose.onNodeWithTag("patchGraphRouteEditor").performScrollTo()
        compose.onNodeWithTag("patchGraphRouteSource").performScrollTo().performClick()
        compose.onNodeWithTag("patchGraphRouteSourceOptionENV1").performClick()
        compose.runOnIdle {
            assertEquals(ModSource.ENV1, routes.value[0].source)
            assertTrue(routes.value[0].enabled)
        }
        // A stored ENV1->DSP configuration does not emit modulation in this
        // Android host. Keep exact count semantics without calling it LIVE.
        compose.onNodeWithText("1 CONFIGURED DSP ROUTES", substring = true)
            .assertExists()
        compose.onNodeWithText(" LIVE BUSES", substring = true)
            .assertDoesNotExist()
        compose.onNodeWithTag("patchGraphRouteDestination").performScrollTo().performClick()
        compose.onNodeWithTag("patchGraphRouteDestinationOptionRESONANCE").performClick()
        compose.runOnIdle {
            assertEquals(ModDestination.RESONANCE, routes.value[0].destination)
        }
        compose.onNodeWithTag("patchGraphRouteCurve").performScrollTo().performClick()
        compose.onNodeWithTag("patchGraphRouteCurveOption" +
            com.orbital.synth.core.dsp.processing.ModCurve.entries.last().name).performClick()
        compose.runOnIdle {
            assertEquals(
                com.orbital.synth.core.dsp.processing.ModCurve.entries.last(),
                routes.value[0].curve,
            )
            assertEquals(.6f, routes.value[0].depth)
            assertTrue(routes.value.drop(1).all { it == ModulationRouteConfig() })
        }
    }

    @Test fun audioClockedStepSourceCreatesAudibleRouteOnlyWhileSourceEnabled() {
        val step = mutableStateOf(ModulationStepConfig(enabled = true))
        val routes = mutableStateOf(List(64) { ModulationRouteConfig() })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PatchGraphPanel(
                        oscillators = emptyList(),
                        lfos = List(8) { ModulationLfoConfig() },
                        routes = routes.value,
                        fxSlots = List(6) { FxModuleConfig() },
                        onRouteChange = { index, config ->
                            routes.value = routes.value.toMutableList().apply { set(index, config) }
                        },
                        onRouteClear = {},
                        onMoveFxSlot = { _, _ -> },
                        stepConfig = step.value,
                    )
                }
            }
        }
        compose.onNodeWithTag("patchStepSource").performScrollTo().performClick()
        compose.onNodeWithTag("patchBusAMP").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ModSource.STEP, routes.value[0].source)
            assertEquals(ModDestination.AMP, routes.value[0].destination)
            assertTrue(routes.value[0].enabled)
        }
        compose.onNodeWithTag("patchGraphTruthStatus")
            .assertTextContains("1 STEP ROUTES (0 DORMANT)", substring = true)
        compose.runOnIdle { step.value = step.value.copy(enabled = false) }
        compose.onNodeWithTag("patchStepSourceStatus")
            .assertTextContains("DISABLED", substring = true)
        compose.onNodeWithTag("patchGraphTruthStatus")
            .assertTextContains("1 STEP ROUTES (1 DORMANT)", substring = true)
    }

    @Test fun pagedRoute64AndFxStageFiveRemainAccessibleWithoutNestedHorizontalScroll() {
        val routes = mutableStateOf(List(64) { ModulationRouteConfig() })
        val fx = mutableStateOf(listOf(
            FxModuleConfig(FxModuleType.CHORUS, true, .5f),
            FxModuleConfig(FxModuleType.DELAY, true, .4f),
            FxModuleConfig(FxModuleType.REVERB, true, .4f),
            FxModuleConfig(FxModuleType.PHASER, true, .4f),
            FxModuleConfig(FxModuleType.BITCRUSH, true, .7f),
            FxModuleConfig(FxModuleType.TREMOLO, true, .4f),
        ))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PatchGraphPanel(
                        oscillators = List(8) { OscillatorSlot() },
                        lfos = List(8) { ModulationLfoConfig() },
                        routes = routes.value,
                        fxSlots = fx.value,
                        onRouteChange = { index, config ->
                            routes.value = routes.value.toMutableList().apply { set(index, config) }
                        },
                        onRouteClear = {},
                        onMoveFxSlot = { from, to ->
                            fx.value = fx.value.toMutableList().apply { add(to, removeAt(from)) }
                        },
                    )
                }
            }
        }
        repeat(7) {
            compose.onNodeWithTag("patchRoutePageNext").performScrollTo().performClick()
        }
        compose.onNodeWithTag("patchRoutePageStatus")
            .assertTextContains("BANK 8 / 8", substring = true)
        compose.onNodeWithTag("patchRoute63").performScrollTo().performClick()
        compose.onNodeWithTag("patchGraphRouteEditor").performScrollTo()
        compose.onNodeWithTag("patchGraphDepthSlider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.25f) }
        compose.runOnIdle {
            assertTrue(routes.value[63].enabled)
            assertTrue(routes.value[63].depth > 1f)
            assertTrue(routes.value.take(63).all { it == ModulationRouteConfig() })
        }
        compose.onNodeWithTag("patchFx4").performScrollTo().performClick()
        compose.onNodeWithTag("patchFxMoveLeft").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(FxModuleType.BITCRUSH, fx.value[3].type)
            assertEquals(FxModuleType.PHASER, fx.value[4].type)
            assertEquals(6, fx.value.size)
        }
    }

    @Test fun compact350BankedTwentyTwoDspPortsRemainPhysicalAndActuallyPatchOsc8Pitch() {
        val routes = mutableStateOf(List(64) { ModulationRouteConfig() })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(350.dp).verticalScroll(rememberScrollState())) {
                    PatchGraphPanel(
                        oscillators = List(8) { OscillatorSlot(enabled = true, level = .5f) },
                        lfos = List(8) { ModulationLfoConfig(enabled = true) },
                        routes = routes.value,
                        fxSlots = List(8) { FxModuleConfig() },
                        onRouteChange = { index, next ->
                            routes.value = routes.value.toMutableList().apply { set(index, next) }
                        },
                        onRouteClear = { index ->
                            routes.value = routes.value.toMutableList().apply {
                                set(index, ModulationRouteConfig())
                            }
                        },
                        onMoveFxSlot = { _, _ -> },
                    )
                }
            }
        }
        // These controls have physical >=48dp semantic touch corridors in
        // a real 350dp-wide screen, no 22-node overlapping atlas fiction.
        assertVisible48Dp("patchLfo7")
        compose.onNodeWithTag("patchLfo7")
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click(center) }
        assertVisible48Dp("patchGraphBankOSC8")
        compose.onNodeWithTag("patchGraphBankOSC8")
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click(center) }
        compose.onNodeWithTag("patchGraphTargetBankStatus")
            .assertTextContains("OSC 8", substring = true)
        compose.onNodeWithTag("patchBusOSC7_GAIN").assertDoesNotExist()
        assertVisible48Dp("patchBusOSC8_GAIN")
        compose.onNodeWithTag("patchBusOSC8_GAIN").assertHeightIsAtLeast(48.dp)
        assertVisible48Dp("patchBusOSC8_PITCH")
        compose.onNodeWithTag("patchBusOSC8_PITCH")
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(ModSource.LFO8, routes.value[0].source)
            assertEquals(ModDestination.OSC8_PITCH, routes.value[0].destination)
            assertTrue(routes.value[0].enabled)
            assertTrue(routes.value[0].depth > 0f)
            assertTrue(routes.value.drop(1).all { it == ModulationRouteConfig() })
        }
        compose.onNodeWithTag("patchGraphTruthStatus")
            .assertTextContains("1 LFO/STEP CABLES DRAWN IN OSC 8", substring = true)

        // Switching banks never changes/erases the saved OSC8 cable. A global
        // tap creates a second real, different route through the SAME host API.
        assertVisible48Dp("patchGraphBankGLOBAL")
        compose.onNodeWithTag("patchGraphBankGLOBAL")
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click(center) }
        compose.onNodeWithTag("patchBusOSC8_PITCH").assertDoesNotExist()
        assertVisible48Dp("patchBusCUTOFF")
        compose.onNodeWithTag("patchBusCUTOFF")
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(ModDestination.OSC8_PITCH, routes.value[0].destination)
            assertEquals(ModSource.LFO8, routes.value[1].source)
            assertEquals(ModDestination.CUTOFF, routes.value[1].destination)
        }
        compose.onNodeWithTag("patchGraphTruthStatus")
            .assertTextContains("1 CONFIGURED DSP ROUTES IN OTHER BANKS", substring = true)
    }

}
