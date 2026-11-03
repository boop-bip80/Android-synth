package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageTouchConfigurationPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsDialogSelectsMusicalScaleAndClosesWithoutNotes() {
        val state = mutableStateOf(StageTouchSettings())
        var dismissed = false
        compose.setContent {
            StageTouchConfigurationPanel(
                settings = state.value,
                onChange = { state.value = it },
                onDismiss = { dismissed = true },
                onReset = { state.value = StageTouchSettings() },
            )
        }
        compose.onNodeWithTag("stageConfigPanel").assertExists()
        compose.onNodeWithTag("stageScale-MAJOR").performClick()
        compose.runOnIdle { assertEquals(TouchScale.MAJOR, state.value.scale) }
        // Dismissing the dialog is a UI action, never an audio note event.
        compose.onNodeWithTag("stageConfigClose").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
