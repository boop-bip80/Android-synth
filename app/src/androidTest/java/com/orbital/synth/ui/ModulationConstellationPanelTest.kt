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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
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
