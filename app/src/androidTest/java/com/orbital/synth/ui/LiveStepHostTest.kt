package com.orbital.synth.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.MainActivity
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.preset.PatchOutcome
import com.orbital.synth.preset.UserPatchRepository
import com.orbital.synth.core.preset.PatchCodec
import com.orbital.synth.core.preset.FactoryPresets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Exercises Compose gestures -> OrbitalSynth atomic worker snapshots ->
 * actual stereo PCM -> private V5 disk bank. No UI-only pretend modulator.
 */
@RunWith(AndroidJUnit4::class)
class LiveStepHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun touchProgrammingChangesActualPcmAndSavedV5PatchRestoresThePattern() {
        val synth = OrbitalSynth(sampleRate = 8_000, maxVoices = 2)
        val dry = OrbitalSynth(sampleRate = 8_000, maxVoices = 2)
        val route = ModulationRouteConfig(
            enabled = true, source = ModSource.STEP,
            destination = ModDestination.AMP, depth = 1f,
        )
        synth.setModulationRouteConfig(0, route)
        dry.setModulationRouteConfig(0, route)
        val livePattern = mutableStateOf(synth.modulationSteps)
        val liveRoutes = mutableStateOf(List(synth.modulationRouteCount) { synth.modulationRoute(it) })
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(
                    modulationLfos = List(8) { ModulationLfoConfig(enabled = it == 0) },
                    modulationRoutes = liveRoutes.value,
                    modulationSteps = livePattern.value,
                    modulationStepPlayhead = synth.modulationStepPlayhead,
                ),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
                onModulationLfoChange = { _, _ -> },
                onModulationRouteChange = { index, next ->
                    synth.setModulationRouteConfig(index, next)
                    liveRoutes.value = List(synth.modulationRouteCount) { synth.modulationRoute(it) }
                },
                onModulationRouteClear = { index ->
                    synth.clearModulationRoute(index)
                    liveRoutes.value = List(synth.modulationRouteCount) { synth.modulationRoute(it) }
                },
                onModulationTempoChange = {},
                onModulationStepsChange = { next ->
                    synth.setModulationSteps(next)
                    livePattern.value = synth.modulationSteps
                },
            )
        }
        compose.onNodeWithContentDescription("Tab FX").performClick()
        compose.onNodeWithTag("modStepRhythmPanel").assertExists()
        compose.onNodeWithContentDescription("Toggle STEP modulator")
            .performScrollTo().performClick()
        compose.onNodeWithTag("modStepPad0").performScrollTo().performTouchInput {
            click(Offset(center.x, center.y * .4f)) // 20% height: positive
        }
        compose.onNodeWithTag("modStepPad1").performScrollTo().performTouchInput {
            click(Offset(center.x, center.y * 1.6f)) // 80% height: negative
        }
        compose.runOnIdle {
            assertTrue(synth.modulationSteps.enabled)
            assertTrue(synth.modulationSteps.values[0] > .35f)
            assertTrue(synth.modulationSteps.values[1] < -.35f)
            assertEquals(route, synth.modulationRoute(0))
        }

        synth.noteOn(60)
        dry.noteOn(60)
        val activePcm = FloatArray(5_000 * 2)
        val dryPcm = FloatArray(activePcm.size)
        synth.renderStereo(activePcm, 5_000)
        dry.renderStereo(dryPcm, 5_000)
        // 5,000 samples at 8 kHz/120 BPM span five quarter-beat pads.
        // The public index comes from the audio worker, not an animated UI timer.
        val workerPlayhead = synth.modulationStepPlayhead
        assertTrue("Actual audio-clock STEP playhead never advanced: " + workerPlayhead,
            workerPlayhead in 3..7)
        val meanDifference = activePcm.indices.sumOf { index ->
            abs((activePcm[index] - dryPcm[index]).toDouble())
        } / activePcm.size
        assertTrue("Touch-painted STEP must change rendered stereo PCM: " + meanDifference,
            meanDifference > .003)

        val repository = UserPatchRepository(
            ApplicationProvider.getApplicationContext<Context>(), synth,
        )
        val saved = runBlocking { repository.saveCurrentAs("CI-Step-" + System.nanoTime()) }
        assertTrue("real Android user patch save failed: " + saved, saved is PatchOutcome.Success)
        val id = (saved as PatchOutcome.Success).value.patch.id
        try {
            val exported = runBlocking { repository.exportPatchText(id) }
            assertTrue("V5 export failed: " + exported, exported is PatchOutcome.Success)
            val text = (exported as PatchOutcome.Success).value
            assertTrue(text.startsWith("ORBITAL-PATCH/5\n"))
            assertEquals(synth.modulationSteps, PatchCodec.decode(text).modulationSteps)
            assertEquals(route, PatchCodec.decode(text).modulationRoutes[0])
            val expected = synth.modulationSteps
            // Real user-bank activation must clear V5 STEP when an older factory
            // patch has no STEP field; reactivating the saved V5 restores it.
            val factory = FactoryPresets.ALL.first()
            assertTrue("Factory migration fixture must predate V5 STEP",
                factory.modulationSteps == null)
            val legacy = runBlocking { repository.activate(factory.id) }
            assertTrue("Legacy factory activation failed: " + legacy,
                legacy is PatchOutcome.Success)
            assertTrue("Older patch leaked the previous STEP modulation",
                !synth.modulationSteps.enabled)
            val restored = runBlocking { repository.activate(id) }
            assertTrue("Disk-backed STEP recall failed: " + restored, restored is PatchOutcome.Success)
            assertEquals(expected, synth.modulationSteps)
            assertEquals(route, synth.modulationRoute(0))
        } finally {
            runBlocking { repository.deleteUser(id) }
        }
    }
}

/** Launches the actual MainActivity host, not a separately mocked screen. */
@RunWith(AndroidJUnit4::class)
class MainActivityStepHostTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realActivityShowsConnectedStepEditorAndKeepsDrawnPadsThroughStage() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription("Tab FX").performClick()
            compose.onNodeWithTag("modStepRhythmPanel").assertExists()
            compose.onNodeWithTag("modStepPad0").performScrollTo().performTouchInput {
                click(Offset(center.x, center.y * 1.6f))
            }
            val changed = compose.onNodeWithTag("modStepPad0")
                .fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
                .single()
            assertTrue("Actual MainActivity STEP touch did not update live state: " + changed,
                changed.contains("STEP pad 1") && changed.contains("-0."))
            // The connected host must also arm the real STEP clock and retain
            // that source state while the keyboardless performance view owns UI.
            compose.onNodeWithContentDescription("Toggle STEP modulator")
                .performScrollTo().performClick()
            compose.onNodeWithText("STEP CLOCK ARMED", substring = true).assertExists()
            compose.onNodeWithTag("immersiveModeToggle").performClick()
            compose.onNodeWithTag("immersiveStage").assertExists()
            compose.onNodeWithTag("immersiveExit").performClick()
            compose.onNodeWithTag("modStepRhythmPanel").assertExists()
            compose.onNodeWithText("STEP CLOCK ARMED", substring = true).assertExists()
            val returned = compose.onNodeWithTag("modStepPad0")
                .fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
                .single()
            assertEquals("Editor lost actual audio-owned STEP pattern on stage return",
                changed, returned)
        }
    }
}
