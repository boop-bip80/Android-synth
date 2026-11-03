package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class LiveFxWorkbenchHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * This test supplies the SAME real OrbitalSynth instance to the UI callbacks
     * as an AndroidAudioOutput would receive. It verifies actual rendered stereo
     * samples differ from a dry engine; no mock effect engine is involved.
     */
    @Test fun touchingFxWorkbenchUpdatesRealSynthPcmAndSurvivesStageToggle() {
        val synth = OrbitalSynth(sampleRate = 48_000, maxVoices = 4)
        val dry = OrbitalSynth(sampleRate = 48_000, maxVoices = 4)
        val slots = mutableStateOf(List(synth.fxSlotCount) { synth.fxSlot(it) })

        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(fxSlots = slots.value),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
                onFxSlotChange = { index, config ->
                    synth.setFxSlot(index, config)
                    slots.value = List(synth.fxSlotCount) { synth.fxSlot(it) }
                },
                onFxMoveSlot = { from, to ->
                    synth.moveFxSlot(from, to)
                    slots.value = slots.value.toMutableList().apply { add(to, removeAt(from)) }
                },
            )
        }
        compose.onNodeWithContentDescription("Tab FX").performClick()
        compose.onNodeWithTag("modularFxWorkbenchPanel").assertExists()
        compose.onNodeWithTag("fxAddButton").performScrollTo().performClick()
        compose.onNodeWithTag("fxAddTypeTREMOLO").performClick()
        compose.runOnIdle {
            assertTrue(synth.fxSlot(0).enabled)
            assertEquals(FxModuleType.TREMOLO, synth.fxSlot(0).type)
        }
        compose.onNodeWithTag("fxWetSlider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.93f) }
        compose.runOnIdle { assertTrue(synth.fxSlot(0).wet >= .89f) }

        synth.noteOn(60, 1f)
        dry.noteOn(60, 1f)
        val wetPcm = FloatArray(8192)
        val dryPcm = FloatArray(8192)
        synth.renderStereo(wetPcm, 4096)
        dry.renderStereo(dryPcm, 4096)
        val delta = wetPcm.indices.sumOf { index ->
            abs((wetPcm[index] - dryPcm[index]).toDouble())
        } / wetPcm.size
        assertTrue("FX controls must alter real stereo PCM; delta=" + delta, delta > .001)

        compose.onNodeWithTag("immersiveModeToggle").performClick()
        compose.onNodeWithTag("immersiveStage").assertExists()
        compose.onNodeWithTag("immersiveExit").performClick()
        compose.onNodeWithTag("modularFxWorkbenchPanel").assertExists()
        compose.runOnIdle { assertTrue(synth.fxSlot(0).enabled) }

        compose.onNodeWithTag("fxBypassButton").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(synth.fxSlot(0).enabled)
            assertTrue(synth.fxSlot(0).wet >= .89f)
        }
    }

    @Test fun fxMoveCallbacksReorderWorkerAfterAudioBlockAndKeepUiSelection() {
        val synth = OrbitalSynth(sampleRate = 48_000, maxVoices = 4)
        synth.setFxSlot(0, FxModuleConfig(type = FxModuleType.CHORUS, enabled = true, wet = .5f))
        synth.setFxSlot(1, FxModuleConfig(type = FxModuleType.DELAY, enabled = true, wet = .6f))
        val slots = mutableStateOf(List(synth.fxSlotCount) { synth.fxSlot(it) })
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(fxSlots = slots.value),
                onNoteOn = {}, onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {}, onOctaveChange = {},
                onFxSlotChange = { index, config ->
                    synth.setFxSlot(index, config)
                    slots.value = List(synth.fxSlotCount) { synth.fxSlot(it) }
                },
                onFxMoveSlot = { from, to ->
                    synth.moveFxSlot(from, to)
                    slots.value = slots.value.toMutableList().apply { add(to, removeAt(from)) }
                },
            )
        }
        compose.onNodeWithContentDescription("Tab FX").performClick()
        compose.onNodeWithTag("fxMoveRight").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(FxModuleType.CHORUS, slots.value[1].type)
            assertEquals(FxModuleType.DELAY, slots.value[0].type)
        }
        // Core processes the move event at the next actual audio render block.
        synth.renderStereo(FloatArray(256), 128)
        assertEquals(FxModuleType.DELAY, synth.fxSlot(0).type)
        assertEquals(FxModuleType.CHORUS, synth.fxSlot(1).type)
    }
}
