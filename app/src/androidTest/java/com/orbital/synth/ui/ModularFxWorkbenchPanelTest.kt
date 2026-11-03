package com.orbital.synth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModularFxWorkbenchPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addBypassAndRemoveReachHostOwnedFxSnapshots() {
        var slots by mutableStateOf(List(6) { FxModuleConfig() })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModularFxWorkbenchPanel(
                        slots = slots,
                        onSlotChange = { index, config ->
                            slots = slots.mapIndexed { i, prior -> if (index == i) config else prior }
                        },
                        onMoveSlot = { _, _ -> },
                    )
                }
            }
        }
        compose.onNodeWithTag("modularFxSignalMap").assertExists()
        compose.onNodeWithTag("fxSlot0").assertExists()
        compose.onNodeWithTag("fxSlot5").assertExists()
        compose.onNodeWithTag("fxAddButton").performClick()
        compose.onNodeWithTag("fxAddTypeCHORUS").performClick()
        compose.runOnIdle {
            assertTrue(slots[0].enabled)
            assertEquals(FxModuleType.CHORUS, slots[0].type)
            assertTrue(slots[0].wet > 0f)
        }
        compose.onNodeWithTag("fxSelectedEditor").performScrollTo()
        compose.onNodeWithTag("fxBypassButton").performClick()
        compose.runOnIdle {
            assertFalse(slots[0].enabled)
            assertTrue(ModularFxUiModel.configured(slots[0]))
        }
        compose.onNodeWithTag("fxRemoveButton").performClick()
        compose.runOnIdle { assertEquals(FxModuleConfig(), slots[0]) }
    }

    @Test fun moveAndSaturationUnusedParametersHaveRealSemantics() {
        var slots by mutableStateOf(
            listOf(
                ModularFxUiModel.newModule(FxModuleType.SATURATION),
                ModularFxUiModel.newModule(FxModuleType.DELAY),
                FxModuleConfig(),
            )
        )
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModularFxWorkbenchPanel(
                        slots = slots,
                        onSlotChange = { index, config ->
                            slots = slots.mapIndexed { i, prior -> if (index == i) config else prior }
                        },
                        onMoveSlot = { from, to ->
                            val next = slots.toMutableList()
                            val moving = next.removeAt(from)
                            next.add(to, moving)
                            slots = next
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("fxSelectedEditor").performScrollTo()
        compose.onNodeWithTag("fxWetSlider").assertExists()
        compose.onNodeWithTag("fxDepthSlider").assertExists()
        compose.onNodeWithTag("fxRateSlider").assertIsNotEnabled()
        compose.onNodeWithTag("fxStereoSlider").assertIsNotEnabled()
        compose.onNodeWithTag("fxMoveRight").performClick()
        compose.runOnIdle {
            assertEquals(FxModuleType.SATURATION, slots[1].type)
            assertEquals(FxModuleType.DELAY, slots[0].type)
        }
        compose.onNodeWithTag("fxRateSlider").assertIsNotEnabled()
    }
}
