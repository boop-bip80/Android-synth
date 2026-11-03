package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.ParameterId
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical two-pointer Compose replay and real core OrbitalSynth PCM rendering.
 * The unrelated old finger's UP cannot reset the remaining finger's speed FX
 * or synth expression by delivering its stationary position a second time.
 */
@RunWith(AndroidJUnit4::class)
class FocusPointerExpressionDedupeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun oldFingerUpDoesNotRewriteHeldFingerExpressionAndPcmRemainsAudible() {
        val synth = OrbitalSynth()
        val started = mutableListOf<Int>()
        val ended = mutableListOf<Int>()
        val edits = mutableListOf<Pair<ParameterId, Float>>()
        val touchModulation = mutableListOf<Triple<Float, Float, Float>>()
        compose.setContent {
            ImmersiveStage(
                state = OrbitalUiState(),
                onNoteOn = { note -> started.add(note); synth.noteOn(note) },
                onNoteOff = { note -> ended.add(note); synth.noteOff(note) },
                onParameterChange = { id, value ->
                    edits.add(id to value)
                    synth.setParameter(id, value)
                },
                onModulationTouchExpression = { x, y, pressure ->
                    touchModulation.add(Triple(x, y, pressure))
                },
                onExit = {},
            )
        }
        compose.onNodeWithTag("stageFocusEnter").performClick()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        canvas.performTouchInput {
            down(0, Offset(center.x * .52f, center.y * .93f))
            down(1, Offset(center.x * 1.43f, center.y * .93f))
        }
        canvas.performTouchInput {
            moveTo(1, Offset(center.x * 1.43f, center.y * .63f))
        }
        compose.waitForIdle()

        var editsBeforeUp = emptyList<Pair<ParameterId, Float>>()
        var modulationBeforeUp = emptyList<Triple<Float, Float, Float>>()
        compose.runOnIdle {
            assertEquals("Two admitted fingers must own two real voice attacks", 2, started.size)
            assertTrue("At least one glide/drag must reach real DSP controls", edits.isNotEmpty())
            editsBeforeUp = edits.toList()
            modulationBeforeUp = touchModulation.toList()
        }

        // Owner B has not moved; this event belongs ONLY to A's release.
        // The old implementation incorrectly re-delivered B with speed=0,
        // audibly collapsing MOVEMENT and FX_AMOUNT while B was held.
        canvas.performTouchInput { up(0) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                "Bystander A-UP must not rewrite any of B's unchanged DSP expression",
                editsBeforeUp, edits,
            )
            assertEquals(
                "A-UP must not rebroadcast a stationary B modulation sample",
                modulationBeforeUp, touchModulation,
            )
            assertEquals("A released while B remains down", 1, ended.size)
            assertTrue("B's velocity-dependent space must remain active",
                synth.parameter(ParameterId.FX_AMOUNT) in 0f..1f)
        }

        // A genuinely new B movement still must edit the instrument;
        // the dedupe is a per-pointer sample gate, never a gesture lockout.
        canvas.performTouchInput {
            moveTo(1, Offset(center.x * 1.43f, center.y * .45f))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Fresh B movement must continue expressive control after A-UP",
                edits.size > editsBeforeUp.size)
        }

        // This is actual platform-independent synthesizer PCM, not a
        // synthetic visual RMS readout or a screenshot-based audio claim.
        val pcm = FloatArray(4096)
        synth.renderStereo(pcm, 2048)
        val level = pcm.sumOf { abs(it.toDouble()) } / pcm.size
        assertTrue("Fresh B must keep genuine PCM audible after A-UP, level=$level",
            level.isFinite() && level > 1e-5)

        canvas.performTouchInput { up(1) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Both physically admitted note-ons must eventually release",
                started.sorted(), ended.sorted())
            assertTrue("All emitted DSP controls must be bounded and finite",
                edits.all { (_, value) -> value.isFinite() && value in 0f..1f })
        }
    }
}
