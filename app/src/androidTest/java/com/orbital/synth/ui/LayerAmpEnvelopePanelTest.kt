package com.orbital.synth.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.LayerAmpEnvelope
import com.orbital.synth.core.OscillatorSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Android finger + TalkBack actions publish actual V7 slot edits, not fake DSP controls. */
@RunWith(AndroidJUnit4::class)
class LayerAmpEnvelopePanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realTapArmsVcaAndSemanticsRailsPublishFourIndependentFields() {
        var envelope by mutableStateOf(LayerAmpEnvelope())
        var pixelsPerDp = 1f
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            MaterialTheme {
                LayerAmpEnvelopePanel(
                    envelope = envelope,
                    onEnvelopeChange = { envelope = it },
                )
            }
        }
        val toggle = compose.onNodeWithTag("oscillatorAmpEnvelopeToggle")
            .assertIsDisplayed()
        toggle.performClick()
        compose.runOnIdle { assertTrue(envelope.enabled) }

        val attack = compose.onNodeWithTag("oscillatorAmpAttack").assertIsDisplayed()
        // Real physical finger touch. TalkBack SetProgress is additionally
        // tested on three distinct rails after a recomposition.
        attack.performTouchInput {
            click(Offset(center.x * 1.5f, center.y))
        }
        compose.runOnIdle {
            assertTrue("Attack must be touched on the actual slider",
                envelope.attack > .5f)
        }
        compose.onNodeWithTag("oscillatorAmpDecay").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.73f) }
        compose.onNodeWithTag("oscillatorAmpSustain").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.22f) }
        compose.onNodeWithTag("oscillatorAmpRelease").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.91f) }
        compose.runOnIdle {
            assertEquals(.73f, envelope.decay, .02f)
            assertEquals(.22f, envelope.sustain, .02f)
            assertEquals(.91f, envelope.release, .02f)
        }
        for (tag in listOf(
            "oscillatorAmpEnvelopeToggle", "oscillatorAmpAttack",
            "oscillatorAmpDecay", "oscillatorAmpSustain", "oscillatorAmpRelease",
        )) {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("TalkBack target under 48dp: $tag at $bounds",
                bounds.height / pixelsPerDp >= 47.5f)
        }
    }

    @Test fun realHorizontalDragAndRepeatedRearmsNeverLeaveStuckEnvelopeControls() {
        var envelope by mutableStateOf(LayerAmpEnvelope(
            enabled = true, attack = .2f, decay = .5f, sustain = .6f, release = .3f,
        ))
        compose.setContent {
            MaterialTheme {
                LayerAmpEnvelopePanel(envelope, onEnvelopeChange = { envelope = it })
            }
        }
        compose.onNodeWithTag("oscillatorAmpAttack").assertIsDisplayed()
            .performTouchInput {
                swipe(
                    start = Offset(center.x * .2f, center.y),
                    end = Offset(center.x * 1.65f, center.y),
                    durationMillis = 280,
                )
            }
        compose.runOnIdle { assertTrue(envelope.attack > .67f) }

        val toggle = compose.onNodeWithTag("oscillatorAmpEnvelopeToggle").assertIsDisplayed()
        toggle.performClick()
        compose.runOnIdle {
            assertTrue(!envelope.enabled)
            assertEquals(.5f, envelope.decay, .0001f)
            assertEquals(.6f, envelope.sustain, .0001f)
            assertEquals(.3f, envelope.release, .0001f)
        }
        toggle.performClick()
        compose.runOnIdle { assertTrue(envelope.enabled) }
        compose.onNodeWithTag("oscillatorAmpAttack").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.48f) }
        compose.runOnIdle { assertEquals(.48f, envelope.attack, .02f) }
    }

    @Test fun immutableSlotCallbacksRetainV6NoiseAndUnchangedTuning() {
        var slot by mutableStateOf(OscillatorSlot(
            enabled = true, waveform = .38f, morph = .82f, level = .72f,
            coarseSemitones = 12, fineCents = 11f,
            noiseLevel = .63f, noiseColor = 1f,
        ))
        compose.setContent {
            MaterialTheme {
                LayerAmpEnvelopePanel(
                    envelope = slot.ampEnvelope,
                    onEnvelopeChange = { slot = slot.copy(ampEnvelope = it) },
                    compact = true,
                )
            }
        }
        compose.onNodeWithTag("oscillatorAmpEnvelopeToggle")
            .assertIsDisplayed().performClick()
        compose.onNodeWithTag("oscillatorAmpSustain")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.4f) }
        compose.runOnIdle {
            assertTrue(slot.ampEnvelope.enabled)
            assertEquals(.4f, slot.ampEnvelope.sustain, .02f)
            assertEquals(.63f, slot.noiseLevel, 0f)
            assertEquals(1f, slot.noiseColor, 0f)
            assertEquals(12, slot.coarseSemitones)
            assertEquals(11f, slot.fineCents, 0f)
        }
        compose.onNodeWithTag("oscillatorAmpEnvelopeContour").assertIsDisplayed()
    }
}
