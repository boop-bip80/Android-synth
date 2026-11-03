package com.orbital.synth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PatchGraphPanelTest {
    @get:Rule val compose = createComposeRule()

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
            click(Offset(size.width * .125f, size.height * .145f))
        }
        compose.runOnIdle {
            assertTrue(layers.value[0].enabled)
            assertTrue(layers.value[0].level > 0f)
            assertEquals(1, layers.value.count { it.enabled })
        }
        compose.onNodeWithTag("patchGraphNotice")
            .assertTextContains("engaged in the real voice mixer", substring = true)
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
                start = Offset(size.width * .25f, size.height * .55f),
                end = Offset(size.width * .75f, size.height * .55f),
                durationMillis = 520L,
            )
        }
        compose.runOnIdle {
            assertEquals(FxModuleType.DELAY, stages.value[0].type)
            assertEquals(FxModuleType.SATURATION, stages.value[1].type)
            assertEquals(FxModuleType.CHORUS, stages.value[2].type)
        }
    }

}
