package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented safety contract for the optional truly chrome-free performance surface.
 * Back must always reveal a usable exit before the app can leave stage mode.
 */
@RunWith(AndroidJUnit4::class)
class CleanCanvasFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(noteOn: (Int) -> Unit = {}, noteOff: (Int) -> Unit = {}) {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = noteOn,
                onNoteOff = noteOff,
                onParameterChange = { _, _ -> },
                onModeChange = {},
                onOctaveChange = {},
            )
        }
    }

    private fun systemBack() {
        compose.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    @Test fun cleanCanvasHidesAllStageChromeButBackRevealsExitBeforeLeaving() {
        render()
        compose.onNodeWithTag("immersiveModeToggle").performClick()
        compose.onNodeWithTag("immersiveExit").assertIsDisplayed()
        compose.onNodeWithTag("stageFocusEnter").assertIsDisplayed().performClick()

        compose.onNodeWithTag("immersiveStage").assertIsDisplayed()
        compose.onNodeWithTag("immersiveCanvas").assertExists()
        compose.onNodeWithTag("immersiveExit").assertDoesNotExist()
        compose.onNodeWithTag("stageConfigOpen").assertDoesNotExist()
        compose.onNodeWithTag("stageFocusEnter").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Multitouch piano keyboard", substring = true)
            .assertCountEquals(0)
        compose.onNodeWithTag("referencePerformanceKeybed").assertDoesNotExist()
        // A HUD-free stage still exposes a TalkBack action to reveal controls.
        val revealActions = compose.onNodeWithTag("immersiveCanvas")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertTrue(
            "TalkBack must expose a reveal-controls accessibility action",
            revealActions.any { action -> action.label == "Reveal stage controls" },
        )

        systemBack() // In clean canvas, Back reveals chrome, not Activity exit.
        compose.onNodeWithTag("immersiveStage").assertIsDisplayed()
        compose.onNodeWithTag("immersiveExit").assertIsDisplayed()
        compose.onNodeWithTag("stageConfigOpen").assertIsDisplayed()
        compose.onNodeWithTag("stageFocusEnter").assertIsDisplayed()

        systemBack() // A second Back returns to the normal playable synth.
        compose.onNodeWithTag("immersiveStage").assertDoesNotExist()
        compose.onNodeWithTag("referencePerformanceKeybed").assertIsDisplayed()
    }

    @Test fun cleanCanvasKeepsSoundGesturesAndCancellationsBalanced() {
        val noteOn = mutableListOf<Int>()
        val noteOff = mutableListOf<Int>()
        render(noteOn = { noteOn += it }, noteOff = { noteOff += it })
        compose.onNodeWithTag("immersiveModeToggle").performClick()
        compose.onNodeWithTag("stageFocusEnter").performClick()
        compose.onNodeWithTag("immersiveCanvas").performTouchInput {
            down(center)
            moveTo(Offset(center.x + 30f, center.y - 25f))
            cancel()
        }
        compose.runOnIdle {
            assertTrue("A clean canvas touch must drive the real note callback", noteOn.isNotEmpty())
            assertEquals("Cancelled clean canvas gestures must release all notes",
                noteOn.sorted(), noteOff.sorted())
        }
        systemBack()
        compose.onNodeWithTag("immersiveExit").performClick()
        compose.runOnIdle {
            assertEquals("Revealing/exiting must not leave a held note", noteOn.sorted(), noteOff.sorted())
        }
    }
}
