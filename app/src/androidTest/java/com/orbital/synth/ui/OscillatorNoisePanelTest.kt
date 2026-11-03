package com.orbital.synth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
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
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("noiseEditorScroll")) {
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
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("noiseEditorScroll")) {
                    OscillatorRackPanel(
                        slots = slots,
                        onSlotChange = { index, next ->
                            slots = slots.mapIndexed { i, old -> if (i == index) next else old }
                        },
                    )
                }
            }
        }
        val canvas = compose.fullyRevealOscillatorCanvas(
            "oscillatorNoiseCanvas", "noiseEditorScroll",
        ).assertIsDisplayed()
        val canvasBounds = canvas.fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val intended = Offset(
            canvasBounds.left + canvasBounds.width * .8f,
            canvasBounds.top + canvasBounds.height * .2f,
        )
        assertTrue("XY tap would be outside viewport; pad=$canvasBounds, root=$rootBounds, target=$intended",
            intended.x in rootBounds.left..rootBounds.right &&
                intended.y in rootBounds.top..rootBounds.bottom)

        // Distinguish an inert hit target from an off-center clipped touch.
        canvas.performTouchInput { click(center) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Even CENTER tap did not dispatch to noise pad; color=${slots[0].noiseColor}, pad=$canvasBounds, root=$rootBounds",
                slots[0].noiseColor in 0.45f..0.55f)
        }
        canvas.performTouchInput {
            click(Offset(center.x * 1.6f, center.y * .4f))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("XY color should be 0.8, was ${slots[0].noiseColor}; pad=$canvasBounds, root=$rootBounds",
                slots[0].noiseColor in 0.73f..0.87f)
            assertTrue("XY mix should be 0.8, was ${slots[0].noiseLevel}; pad=$canvasBounds, root=$rootBounds",
                slots[0].noiseLevel in 0.73f..0.87f)
        }
        compose.onNodeWithTag("oscillatorWave3").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(.75f, slots[0].waveform, 0f)
            assertTrue("Wave edit erased XY mix: ${slots[0].noiseLevel}",
                slots[0].noiseLevel > .7f)
            assertTrue("Wave edit erased XY color: ${slots[0].noiseColor}",
                slots[0].noiseColor > .7f)
        }
    }

    @Test fun continuousNoiseDragAndWaveTapBothUpdateLiveSoundDesign() {
        var slots by mutableStateOf(List(8) {
            if (it == 0) OscillatorSlot(enabled = true, level = .5f)
            else OscillatorSlot()
        })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("noiseEditorScroll")) {
                    OscillatorRackPanel(
                        slots = slots,
                        onSlotChange = { index, next ->
                            slots = slots.mapIndexed { i, old -> if (i == index) next else old }
                        },
                    )
                }
            }
        }

        // The pad must own DOWN and MOVE; the enclosing vertical scroller
        // must not steal finger sound-design gestures.
        compose.fullyRevealOscillatorCanvas("oscillatorNoiseCanvas", "noiseEditorScroll")
            .performTouchInput {
                swipe(
                    start = Offset(center.x * .2f, center.y * 1.6f),
                    end = Offset(center.x * 1.6f, center.y * .4f),
                    durationMillis = 260,
                )
            }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Noise drag X must reach color: ${slots[0].noiseColor}",
                slots[0].noiseColor in 0.72f..0.89f)
            assertTrue("Noise drag Y must reach mix: ${slots[0].noiseLevel}",
                slots[0].noiseLevel in 0.72f..0.89f)
        }

        compose.fullyRevealOscillatorCanvas("oscillatorWaveCanvas", "noiseEditorScroll")
            .performTouchInput { click(center) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Wave canvas tap X changes morph", .5f, slots[0].morph, .05f)
            assertEquals("Wave canvas tap Y changes gain", .5f, slots[0].level, .05f)
            assertTrue("Wave pad must preserve edited noise mix", slots[0].noiseLevel > .7f)
            assertTrue("Wave pad must preserve edited noise color", slots[0].noiseColor > .7f)
        }
    }

    @Test fun accessibleNoiseSlidersPublishNormalizedTouchChanges() {
        var slots by mutableStateOf(List(8) {
            if (it == 0) OscillatorSlot(enabled = true, level = .5f) else OscillatorSlot()
        })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("noiseEditorScroll")) {
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
