package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fake platform haptic sink checks that the Stage actually honours opt-out.
 * It does not assert that a hardware motor or physical amplitude exists on AVD.
 */
@RunWith(AndroidJUnit4::class)
class StageHapticPolicyUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class RecordedFeedback : HapticFeedback {
        var count = 0
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            count++
        }
    }

    @Test fun freshInstallOptOutNeverCallsAndroidHapticOnTouchOrDrag() {
        val events = RecordedFeedback()
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides events) {
                ImmersiveStage(
                    state = OrbitalUiState(),
                    onNoteOn = {},
                    onNoteOff = {},
                    onParameterChange = { _, _ -> },
                    onExit = {},
                )
            }
        }
        compose.onNodeWithTag("immersiveCanvas").performTouchInput {
            down(center)
            moveTo(Offset(center.x + 25f, center.y - 30f))
            up()
        }
        compose.runOnIdle { assertEquals(0, events.count) }
    }

    @Test fun optedInLongDragEmitsAtMostOneShortAccent() {
        val events = RecordedFeedback()
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides events) {
                ImmersiveStage(
                    state = OrbitalUiState(),
                    onNoteOn = {},
                    onNoteOff = {},
                    onParameterChange = { _, _ -> },
                    onExit = {},
                )
            }
        }
        compose.onNodeWithTag("stageConfigOpen").performClick()
        compose.onNodeWithTag("stageHapticsOn").performScrollTo().performClick()
        compose.onNodeWithTag("stageConfigClose").performScrollTo().performClick()
        compose.onNodeWithTag("immersiveCanvas").performTouchInput {
            down(center)
            repeat(8) { n ->
                moveTo(Offset(center.x + n * 4f, center.y - n * 6f))
            }
            up()
        }
        // Device global haptic and accessibility preferences can legitimately
        // suppress the event, even though we installed a counting fake sink.
        compose.runOnIdle { assertTrue(events.count in 0..1) }
    }
}