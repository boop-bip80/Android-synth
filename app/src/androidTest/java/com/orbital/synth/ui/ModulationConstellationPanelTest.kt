package com.orbital.synth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModulationConstellationPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyRouteSlotIsAccessibleAndCanEnableRealCallback() {
        var first by mutableStateOf(ModulationRouteConfig())
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModulationConstellationPanel(
                        lfos = List(8) { ModulationLfoConfig(enabled = it == 0) },
                        routes = List(64) { if (it == 0) first else ModulationRouteConfig() },
                        tempoBpm = 120f,
                        onLfoChange = { _, _ -> },
                        onRouteChange = { index, value -> if (index == 0) first = value },
                        onRouteClear = { index ->
                            if (index == 0) first = ModulationRouteConfig()
                        },
                        onTempoChange = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("modulationConstellationPanel").assertExists()
        compose.onNodeWithTag("modRouteSlot0").assertExists()
        compose.onNodeWithTag("modRouteSlot63").assertExists()
        compose.onNodeWithTag("modulationRouteEditor").performScrollTo()
        compose.onNodeWithText("ENABLE").performClick()
        compose.runOnIdle { assertTrue("enable callback reached real state owner", first.enabled) }
    }

    @Test fun liveAndReservedDestinationsReceiveDifferentHonestScreenFeedback() {
        var selected by mutableStateOf(ModulationRouteConfig(
            enabled = true, source = ModSource.TOUCH_X,
            destination = ModDestination.CUTOFF, depth = 0.5f,
        ))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModulationConstellationPanel(
                        lfos = List(8) { ModulationLfoConfig(enabled = it == 0) },
                        routes = List(64) { if (it == 0) selected else ModulationRouteConfig() },
                        tempoBpm = 120f,
                        onLfoChange = { _, _ -> },
                        onRouteChange = { index, value -> if (index == 0) selected = value },
                        onRouteClear = {},
                        onTempoChange = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("modulationRouteEditor").performScrollTo()
        compose.onNodeWithText("GESTURE-DRIVEN / TOUCH TO MODULATE").assertExists()
        compose.onNodeWithText(
            "TOUCH_X · GESTURE-DRIVEN / TOUCH REQUIRED"
        ).assertExists()
        compose.onNodeWithText(
            "DSP-CAPABLE TARGET • When driven, sweeps the per-voice filter cutoff."
        ).assertExists()
        compose.onNodeWithTag("modulationLiveDestinationsLegend").assertExists()

        compose.runOnIdle { selected = selected.copy(destination = ModDestination.OSC1_GAIN) }
        compose.onNodeWithText("GESTURE-DRIVEN / TOUCH TO MODULATE").assertExists()
        compose.onNodeWithText(
            "DSP-CAPABLE TARGET • When driven, changes the addressed oscillator layer gain (0–2×, neutral 1×)."
        ).assertExists()
        compose.onNodeWithText(ModulationConstellationModel.liveTargetsLegend).assertExists()

        compose.runOnIdle { selected = selected.copy(destination = ModDestination.FILTER_DRIVE) }
        compose.onNodeWithText("RESERVED DSP TARGET / SAVED CONFIG ONLY").assertExists()
        compose.onNodeWithText(
            "SAVED ROUTE • Reserved for a future audio engine connection."
        ).assertExists()
    }

    @Test fun actualStepPadTouchPublishesBipolarAudioConfigViaHostCallback() {
        var livePattern by mutableStateOf(ModulationStepConfig(enabled = true))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModulationConstellationPanel(
                        lfos = List(8) { ModulationLfoConfig() },
                        routes = List(64) { ModulationRouteConfig() },
                        tempoBpm = 120f,
                        onLfoChange = { _, _ -> },
                        onRouteChange = { _, _ -> },
                        onRouteClear = {},
                        onTempoChange = {},
                        stepConfig = livePattern,
                        onStepChange = { livePattern = it },
                        stepPlayhead = 3,
                    )
                }
            }
        }
        compose.onNodeWithTag("modStepRhythmPanel").assertExists()
        compose.onNodeWithTag("modStepRhythmPanel").performScrollTo()
        compose.onNodeWithTag("modStepPad0").performTouchInput {
            click(Offset(center.x, center.y * 1.8f))
        }
        compose.runOnIdle {
            assertTrue("Touching the bottom of a pad must publish negative DSP voltage",
                livePattern.values[0] < -.5f)
            assertTrue(livePattern.enabled)
        }
        compose.onNodeWithTag("modStepGlideSlider").assertExists()
        compose.onNodeWithTag("modStepSourceStatus").assertExists()
    }

    @Test fun screenExposesActualLfoParametersAndTopologicalGraph() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModulationConstellationPanel(
                        lfos = List(8) { ModulationLfoConfig() },
                        routes = List(64) { ModulationRouteConfig() },
                        tempoBpm = 120f,
                        onLfoChange = { _, _ -> },
                        onRouteChange = { _, _ -> },
                        onRouteClear = {},
                        onTempoChange = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("modulationConstellationVisual").assertExists()
        compose.onNodeWithTag("modulationLfoCarousel").assertExists()
        compose.onNodeWithTag("modLfoSlot0").assertExists()
        compose.onNodeWithTag("modLfoSlot7").assertExists()
    }
}
