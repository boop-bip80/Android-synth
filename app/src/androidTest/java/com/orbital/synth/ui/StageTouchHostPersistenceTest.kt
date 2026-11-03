package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageTouchHostPersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun initialSettingsRestoreAndUserChangesSurviveStageReentry() {
        val saved = mutableStateOf(
            StageTouchSettings(
                tonic = 9, baseOctave = 5, octaveRange = 3, scale = TouchScale.BLUES,
            ),
        )
        val showStage = mutableStateOf(true)
        compose.setContent {
            if (showStage.value) {
                ImmersiveStage(
                    state = OrbitalUiState(octave = 4),
                    onNoteOn = {},
                    onNoteOff = {},
                    onParameterChange = { _, _ -> },
                    onExit = { showStage.value = false },
                    initialPerformanceConfig = saved.value,
                    onPerformanceConfigChange = { saved.value = it },
                )
            }
        }

        compose.onNodeWithTag("stageConfigOpen").performClick()
        compose.onNodeWithTag("stageScale-BLUES").assertIsSelected()
        // Host-restored base C5 should not be replaced by UI keyboard octave C4.
        compose.runOnIdle { assertEquals(5, saved.value.baseOctave) }
        compose.onNodeWithTag("stageScale-MAJOR").performClick()
        compose.runOnIdle { assertEquals(TouchScale.MAJOR, saved.value.scale) }
        compose.onNodeWithTag("stageConfigClose").performScrollTo().performClick()
        compose.onNodeWithTag("immersiveExit").performClick()
        compose.runOnIdle { showStage.value = true }
        compose.onNodeWithTag("stageConfigOpen").performClick()
        compose.onNodeWithTag("stageScale-MAJOR").assertIsSelected()
        compose.runOnIdle { assertEquals(5, saved.value.baseOctave) }
    }
}
