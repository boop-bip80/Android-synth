package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
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
 * Physical multi-pointer Compose replay, reset-epoch overlap and real core PCM.
 * A bystander's UP must not replay a zero-speed move; an owner's UP must hand
 * its global touch sources back to the most recent still-held real sample.
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

    @Test fun lastMovedFingerUpRestoresStationarySurvivorWithoutInventingMovement() {
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
            down(0, Offset(center.x * .48f, center.y * 1.36f))
        }
        var heldA = Triple(0f, 0f, 0f)
        compose.runOnIdle { heldA = touchModulation.last() }
        canvas.performTouchInput {
            down(1, Offset(center.x * 1.44f, center.y * 1.18f))
            moveTo(1, Offset(center.x * 1.44f, center.y * .62f))
        }
        var beforeUp = 0
        var editsBeforeUp = emptyList<Pair<ParameterId, Float>>()
        compose.runOnIdle {
            assertEquals("Two distinct held contacts must attack real voices", 2, started.size)
            assertTrue("B must be the current global source before its UP",
                abs(touchModulation.last().first - heldA.first) > .2f)
            beforeUp = touchModulation.size
            editsBeforeUp = edits.toList()
        }

        // This is the opposite release ordering to oldFingerUp above:
        // B owns the global source, lifts, and A has not moved since its DOWN.
        canvas.performTouchInput { up(1) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("B-UP must deliver exactly one ownership handoff",
                beforeUp + 1, touchModulation.size)
            assertEquals("Stationary A must regain its exact X/Y/pressure sample",
                heldA, touchModulation.last())
            assertEquals("Handoff is not a zero-speed move or a parameter-knob edit",
                editsBeforeUp, edits)
            assertEquals("B's own pitch, not A's, must be released",
                listOf(started[1]), ended)
        }
        assertEquals("Touch sculpting",
            canvas.fetchSemanticsNode().config[SemanticsProperties.StateDescription])

        val pcm = FloatArray(4096)
        synth.renderStereo(pcm, 2048)
        val level = pcm.sumOf { abs(it.toDouble()) } / pcm.size
        assertTrue("Stationary A must still own audible synth PCM after B-UP, level=$level",
            level.isFinite() && level > 1e-5)

        canvas.performTouchInput { up(0) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Every admitted note still requires one release",
                started.sorted(), ended.sorted())
            assertEquals("Only the final owner's UP neutralizes the global source",
                Triple(.5f, .5f, .5f), touchModulation.last())
        }
        assertEquals("Touch idle",
            canvas.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test fun ownerHandoffChoosesLastMovedSurvivorNotLastStartedBystander() {
        val samples = mutableListOf<Triple<Float, Float, Float>>()
        compose.setContent {
            ImmersiveStage(
                state = OrbitalUiState(),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModulationTouchExpression = { x, y, pressure ->
                    samples.add(Triple(x, y, pressure))
                },
                onExit = {},
            )
        }
        compose.onNodeWithTag("stageFocusEnter").performClick()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        canvas.performTouchInput {
            down(0, Offset(center.x * .40f, center.y))
            down(1, Offset(center.x, center.y))
            down(2, Offset(center.x * 1.60f, center.y))
            // A is older than C but is the last genuinely moved survivor.
            moveTo(0, Offset(center.x * .40f, center.y * 1.30f))
        }
        var latestA = Triple(0f, 0f, 0f)
        compose.runOnIdle { latestA = samples.last() }
        canvas.performTouchInput {
            moveTo(1, Offset(center.x, center.y * .60f))
        }
        canvas.performTouchInput { up(1) }
        compose.runOnIdle {
            assertEquals("The latest real survivor sample must win, not DOWN order",
                latestA, samples.last())
        }
        var beforeBystanderUp = emptyList<Triple<Float, Float, Float>>()
        compose.runOnIdle { beforeBystanderUp = samples.toList() }
        canvas.performTouchInput { up(2) }
        compose.runOnIdle {
            assertEquals("Releasing stationary non-owner C must not rewrite A",
                beforeBystanderUp, samples)
        }
        canvas.performTouchInput { up(0) }
        compose.runOnIdle {
            assertEquals(Triple(.5f, .5f, .5f), samples.last())
        }
    }

    @Test fun overlappingHeldEpochsCannotResetNewestTouchSourcesOrVisualLease() {
        val epoch = mutableLongStateOf(0L)
        val started = mutableListOf<Int>()
        val ended = mutableListOf<Int>()
        val samples = mutableListOf<Triple<Float, Float, Float>>()
        compose.setContent {
            ImmersiveStage(
                state = OrbitalUiState(),
                onNoteOn = { started.add(it) },
                onNoteOff = { ended.add(it) },
                onParameterChange = { _, _ -> },
                onModulationTouchExpression = { x, y, pressure ->
                    samples.add(Triple(x, y, pressure))
                },
                onExit = {},
                performanceResetEpoch = epoch.longValue,
            )
        }
        compose.onNodeWithTag("stageFocusEnter").performClick()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        canvas.performTouchInput {
            down(0, Offset(center.x * .42f, center.y * 1.32f))
        }
        compose.runOnIdle { epoch.longValue++ }
        compose.waitForIdle()
        canvas.performTouchInput {
            down(1, Offset(center.x * 1.48f, center.y * .66f))
        }
        compose.runOnIdle { epoch.longValue++ }
        compose.waitForIdle()

        // Two physically held contacts belong to retired recognizers. A third
        // fresh DOWN is admitted immediately, before either obsolete UP arrives.
        canvas.performTouchInput {
            down(2, Offset(center.x * .88f, center.y * .48f))
        }
        var newestSamples = emptyList<Triple<Float, Float, Float>>()
        var releasedBeforeOldEvents = emptyList<Int>()
        compose.runOnIdle {
            assertEquals("Each epoch must admit exactly one new physical attack", 3, started.size)
            assertEquals("Each retired epoch must release its old note exactly once",
                started.take(2).sorted(), ended.sorted())
            assertTrue("Newest pressure source must remain active",
                samples.last().third > .5f)
            newestSamples = samples.toList()
            releasedBeforeOldEvents = ended.toList()
        }

        canvas.performTouchInput {
            moveTo(0, Offset(center.x * .62f, center.y * 1.02f))
            moveTo(1, Offset(center.x * 1.22f, center.y * .82f))
            up(0)
            up(1)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Old epoch MOVE/UP must not emit a neutral or stale source sample",
                newestSamples, samples)
            assertEquals("Old epoch UP must not release the newest voice",
                releasedBeforeOldEvents, ended)
        }
        assertEquals("Touch sculpting",
            canvas.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        canvas.performTouchInput { up(2) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(started.sorted(), ended.sorted())
            assertEquals(Triple(.5f, .5f, .5f), samples.last())
        }
        assertEquals("Touch idle",
            canvas.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }
}
