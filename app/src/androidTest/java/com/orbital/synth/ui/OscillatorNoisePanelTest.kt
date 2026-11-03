package com.orbital.synth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.OscillatorSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OscillatorNoisePanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun colorChipsEnableRealNoiseBlendViaImmutableSlotCallback() {
        var slots by mutableStateOf(List(8) { OscillatorSlot() })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OscillatorRackPanel(
                        slots = slots,
                        onSlotChange = { index, next ->
                            slots = slots.mapIndexed { i, old -> if (i == index) next else old }
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("oscillatorEnable").performClick()
        compose.onNodeWithTag("oscillatorNoisePINK").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(slots[0].enabled)
            assertEquals(.5f, slots[0].noiseColor, 0f)
            assertEquals(.55f, slots[0].noiseLevel, 0f)
            assertEquals(0f, slots[1].noiseLevel, 0f)
        }
        compose.onNodeWithTag("oscillatorNoiseBROWN").performClick()
        compose.runOnIdle {
            assertEquals(1f, slots[0].noiseColor, 0f)
            assertEquals(.55f, slots[0].noiseLevel, 0f)
        }
        compose.onNodeWithTag("oscillatorNoiseWHITE").performClick()
        compose.runOnIdle { assertEquals(0f, slots[0].noiseColor, 0f) }
    }

    @Test fun xySpectrumTouchesAndLegacyWaveChipsPreserveNoiseSettings() {
        var slots by mutableStateOf(List(8) {
            if (it == 0) OscillatorSlot(enabled = true, level = .5f) else OscillatorSlot()
        })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OscillatorRackPanel(
                        slots = slots,
                        onSlotChange = { index, next ->
                            slots = slots.mapIndexed { i, old -> if (i == index) next else old }
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("oscillatorNoiseCanvas").performScrollTo()
            .performTouchInput {
                click(Offset(size.width * .8f, size.height * .2f))
            }
        compose.runOnIdle {
            assertTrue(slots[0].noiseColor in 0.73f..0.87f)
            assertTrue(slots[0].noiseLevel in 0.73f..0.87f)
        }
        compose.onNodeWithTag("oscillatorWave3").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(.75f, slots[0].waveform, 0f)
            assertTrue(slots[0].noiseLevel > .7f)
            assertTrue(slots[0].noiseColor > .7f)
        }
    }

    @Test fun accessibleNoiseSlidersPublishNormalizedTouchChanges() {
        var slots by mutableStateOf(List(8) {
            if (it == 0) OscillatorSlot(enabled = true, level = .5f) else OscillatorSlot()
        })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OscillatorRackPanel(
                        slots = slots,
                        onSlotChange = { index, next ->
                            slots = slots.mapIndexed { i, old -> if (i == index) next else old }
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("oscillatorNoiseLevel").performScrollTo().assertExists()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.92f) }
        compose.onNodeWithTag("oscillatorNoiseColor").performScrollTo().assertExists()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
        compose.runOnIdle {
            assertTrue(slots[0].noiseLevel in 0.88f..0.96f)
            assertEquals(.5f, slots[0].noiseColor, .01f)
        }
    }
}
