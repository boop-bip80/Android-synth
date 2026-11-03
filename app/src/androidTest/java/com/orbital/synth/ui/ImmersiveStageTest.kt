package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ParameterId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against a genuinely rendered Compose stage on the Android emulator.
 * It checks that keyboardless mode is interactive and does not leave held notes.
 * A screenshot captured by ci-immersive-stage.sh is separate evidence of appearance.
 */
@RunWith(AndroidJUnit4::class)
class ImmersiveStageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(
        noteOn: (Int) -> Unit = {},
        noteOff: (Int) -> Unit = {},
        parameter: (ParameterId, Float) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = noteOn,
                onNoteOff = noteOff,
                onParameterChange = parameter,
                onModeChange = {},
                onOctaveChange = {},
            )
        }
    }

    @Test fun immersiveStageHidesKeyboardAndExitRestoresIt() {
        render()
        compose.onAllNodesWithContentDescription("Multitouch piano keyboard", substring = true)
            .assertCountEquals(0)
        compose.onAllNodesWithContentDescription(
            "Twelve live chromatic liquid-glass keys", substring = true,
        ).assertCountEquals(1)
        compose.onNodeWithTag("immersiveModeToggle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("immersiveStage").assertIsDisplayed()
        compose.onNodeWithTag("immersiveCanvas").assertExists()
        compose.onNodeWithTag("immersiveExit").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Multitouch piano keyboard", substring = true)
            .assertCountEquals(0)
        compose.onAllNodesWithContentDescription(
            "Twelve live chromatic liquid-glass keys", substring = true,
        ).assertCountEquals(0)

        compose.onNodeWithTag("immersiveExit").performClick()
        compose.onNodeWithTag("immersiveStage").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Multitouch piano keyboard", substring = true)
            .assertCountEquals(0)
        compose.onAllNodesWithContentDescription(
            "Twelve live chromatic liquid-glass keys", substring = true,
        ).assertCountEquals(1)
    }

    @Test fun realStageDragEmitsBalancedNotesAndBoundedModulation() {
        val opened = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        val controls = mutableListOf<Pair<ParameterId, Float>>()
        render(
            noteOn = { opened.add(it) },
            noteOff = { closed.add(it) },
            parameter = { id, value -> controls.add(id to value) },
        )
        compose.onNodeWithTag("immersiveModeToggle").performClick()
        compose.onNodeWithTag("immersiveCanvas").performTouchInput {
            down(center)
            moveTo(Offset(center.x + 25f, center.y - 40f))
            up()
        }
        compose.runOnIdle {
            assertTrue("Touching the sound field must trigger audible note callbacks", opened.isNotEmpty())
            assertEquals("Every sound-field note-on must be released", opened.sorted(), closed.sorted())
            assertTrue("Dragging must drive the synthesis parameter callbacks", controls.isNotEmpty())
            assertTrue("All gesture control values must remain finite and normalized",
                controls.all { (_, value) -> value.isFinite() && value in 0f..1f })
            assertTrue("Each emitted MIDI pitch must be legal", opened.all { it in 0..127 })
        }
        compose.onNodeWithTag("immersiveExit").performClick()
        compose.runOnIdle { assertEquals(opened.sorted(), closed.sorted()) }
    }

    @Test fun leavingStageDuringHeldTouchDoesNotKeepNotesPlaying() {
        val opened = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        render(noteOn = { opened.add(it) }, noteOff = { closed.add(it) })
        compose.onNodeWithTag("immersiveModeToggle").performClick()
        // OS gesture cancellation must be equivalent to releasing held contacts.
        compose.onNodeWithTag("immersiveCanvas").performTouchInput {
            down(center)
            cancel()
        }
        compose.runOnIdle {
            assertTrue("Cancellation test did not create a note", opened.isNotEmpty())
            assertEquals("Cancellation must release every held note", opened.sorted(), closed.sorted())
        }
        compose.onNodeWithTag("immersiveExit").performClick()
        compose.runOnIdle { assertEquals(opened.sorted(), closed.sorted()) }
    }
}
