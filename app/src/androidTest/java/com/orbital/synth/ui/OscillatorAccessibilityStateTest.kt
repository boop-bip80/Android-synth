package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.OscillatorSlot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screen-reader contract for the live editor: selection must be announced as
 * state, not only communicated through a neon tint or visual preview.
 * The production rack/pointer editors run unchanged in this real Compose UI.
 */
@RunWith(AndroidJUnit4::class)
class OscillatorAccessibilityStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun announcesEditorSelectionAndPerLayerWaveformWithoutChangingOtherLayers() {
        val slots = mutableStateOf(
            listOf(
                OscillatorSlot(enabled = true, waveform = 0f),
                OscillatorSlot(enabled = true, waveform = .5f),
            ),
        )
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    OscillatorRackPanel(
                        slots = slots.value,
                        onSlotChange = { index, next ->
                            slots.value = slots.value.toMutableList().apply { set(index, next) }
                        },
                    )
                }
            }
        }

        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        val notSelected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

        compose.onNodeWithTag("oscillatorLayer0")
            .assert(button)
            .assert(selected)
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "Selected editor layer",
            ))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription, listOf("Oscillator 1, active"),
            ))
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("oscillatorLayer1")
            .assert(notSelected)
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "Unselected editor layer",
            ))
        compose.onNodeWithTag("oscillatorWave0")
            .performScrollTo()
            .assert(selected)
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription, listOf("SINE waveform"),
            ))
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("oscillatorWave2").assert(notSelected)

        // Selecting a different editor changes the spoken selected layer,
        // and the waveform controls truthfully reflect that layer's stored data.
        compose.onNodeWithTag("oscillatorLayer1").performClick()
        compose.onNodeWithTag("oscillatorLayer0").assert(notSelected)
        compose.onNodeWithTag("oscillatorLayer1").assert(selected)
        compose.onNodeWithTag("oscillatorWave2")
            .performScrollTo()
            .assert(selected)
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "Current waveform",
            ))
        compose.onNodeWithTag("oscillatorWave4")
            .performScrollTo()
            .assert(notSelected)
            .assert(button)
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription, listOf("GLASS waveform"),
            ))
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("oscillatorWave4")
            .assert(selected)
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "Current waveform",
            ))
        compose.onNodeWithTag("oscillatorWave2")
            .assert(notSelected)
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "Not selected waveform",
            ))
        compose.runOnIdle {
            assertEquals(0f, slots.value[0].waveform)
            assertEquals(1f, slots.value[1].waveform)
        }

        compose.onNodeWithTag("oscillatorLayer0").performClick()
        compose.onNodeWithTag("oscillatorWave0").performScrollTo().assert(selected)
        compose.onNodeWithTag("oscillatorLayer1").performClick()
        compose.onNodeWithTag("oscillatorWave4").performScrollTo().assert(selected)

        // Removing the currently selected oscillator must announce its empty
        // source state and remove its waveform selection controls.
        compose.onNodeWithTag("oscillatorRemove").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("oscillatorLayer1")
            .assert(selected)
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription, listOf("Oscillator 2, empty"),
            ))
        compose.onNodeWithTag("oscillatorWave4").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(false, slots.value[1].enabled)
            assertEquals(true, slots.value[0].enabled)
        }
    }
}
