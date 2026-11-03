package com.orbital.synth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
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

/**
 * Tests genuine pointer delivery to the drawn Canvas, not direct fake callback
 * invocations. The complete immutable V6 slot is the publication contract.
 */
@RunWith(AndroidJUnit4::class)
class LuminousOscillatorWaveEditorTest {
    @get:Rule val compose = createComposeRule()

    private fun originalSlot() = OscillatorSlot(
        enabled = true, waveform = .5f, morph = .33f, level = .44f,
        coarseSemitones = -7, fineCents = 12f,
        noiseLevel = .76f, noiseColor = .65f,
    )

    @Test fun physicallyTappingHolographicWavePublishesBothAxesAndKeepsV6Noise() {
        var state by mutableStateOf(originalSlot())
        var callbacks = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    LuminousOscillatorWaveEditor(
                        slot = state,
                        onSlotChange = { next ->
                            callbacks++
                            state = next
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("oscillatorWaveCanvas").assertIsDisplayed()
            .performTouchInput {
                click(Offset((center.x * 2f) * .82f, (center.y * 2f) * .18f))
            }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Canvas tap must reach callback", callbacks > 0)
            assertEquals(.82f, state.morph, .07f)
            assertEquals(.82f, state.level, .07f)
            assertEquals(.5f, state.waveform, 0f)
            assertEquals(.76f, state.noiseLevel, 0f)
            assertEquals(.65f, state.noiseColor, 0f)
            assertEquals(-7, state.coarseSemitones)
            assertEquals(12f, state.fineCents, 0f)
        }
    }

    @Test fun dragReleaseThenFreshTapCannotLeaveStuckWaveGestureOrScroller() {
        var state by mutableStateOf(originalSlot())
        var callbacks = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    LuminousOscillatorWaveEditor(
                        slot = state,
                        onSlotChange = {
                            callbacks++
                            state = it
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("oscillatorWaveCanvas").assertIsDisplayed()
            .performTouchInput {
                swipe(
                    start = Offset((center.x * 2f) * .12f, (center.y * 2f) * .84f),
                    end = Offset((center.x * 2f) * .88f, (center.y * 2f) * .12f),
                    durationMillis = 310,
                )
            }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Pointer drag must deliver continuous wave edits", callbacks >= 2)
            assertTrue("Drag X must reach 88% blend: ${state.morph}",
                state.morph in .79f..94f)
            assertTrue("Drag Y must reach 88% level: ${state.level}",
                state.level in .79f..94f)
        }

        val afterDrag = callbacks
        compose.onNodeWithTag("oscillatorWaveCanvas").performTouchInput {
            click(Offset((center.x * 2f) * .21f, (center.y * 2f) * .72f))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("UP must retire previous pointer, fresh tap must fire",
                callbacks > afterDrag)
            assertEquals(.21f, state.morph, .06f)
            assertEquals(.28f, state.level, .06f)
            assertEquals(.76f, state.noiseLevel, 0f)
            assertEquals(.65f, state.noiseColor, 0f)
        }
    }

    @Test fun accessibleSeventyDpRailsUseTalkBackProgressAndRealFingerTaps() {
        var state by mutableStateOf(originalSlot())
        compose.setContent {
            MaterialTheme {
                Column {
                    LuminousOscillatorWaveEditor(slot = state, onSlotChange = { state = it })
                }
            }
        }
        compose.onNodeWithTag("luminousMorphControl").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.64f) }
        compose.onNodeWithTag("luminousLevelControl").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.91f) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(.64f, state.morph, .005f)
            assertEquals(.91f, state.level, .005f)
            assertEquals(.76f, state.noiseLevel, 0f)
        }

        compose.onNodeWithTag("luminousLevelControl").performTouchInput {
            click(Offset((center.x * 2f) * .25f, (center.y * 2f) * .75f))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Rail must accept genuine touch X as normalized level",
                .25f, state.level, .06f)
            assertEquals(.64f, state.morph, .005f)
        }
    }
}
