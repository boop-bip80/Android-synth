package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.ParameterId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbitalScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tabsAndKnobsAreRealSemanticsNodes() {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = {},
                onNoteOff = {},
                onParameterChange = { _: ParameterId, _: Float -> },
                onModeChange = {},
                onOctaveChange = {},
            )
        }
        compose.onNodeWithText("O R B I T A L").assertExists()
        compose.onNodeWithContentDescription("CUTOFF").assertExists()
        compose.onNodeWithContentDescription("Tab SEQ").performClick()
        compose.onNodeWithText("16-STEP ORBIT").assertExists()
        compose.onNodeWithContentDescription("Tab FX").performClick()
        compose.onNodeWithText("ENVELOPES  /  EFFECTS").assertExists()
        compose.onNodeWithContentDescription("Tab PLAY").performClick()
        compose.onNodeWithText("PERFORMANCE").assertExists()
    }

    @Test fun performanceKeyboardStaysVisibleAcrossTabs() {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = {},
                onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {},
                onOctaveChange = {},
            )
        }
        val keyboard = compose.onNodeWithContentDescription(
            "Multitouch piano keyboard",
            substring = true,
        )
        keyboard.assertIsDisplayed()
        listOf("Tab FX", "Tab SEQ", "Tab PLAY", "Tab SOUND").forEach { tab ->
            compose.onNodeWithContentDescription(tab).performClick()
            keyboard.assertIsDisplayed()
        }
    }

    @Test fun presetArrowsAreAccessibleAndCallNavigation() {
        var previous = 0
        var next = 0
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(
                    patchName = "HORIZON",
                    presetIndex = 1,
                    presetCount = 6,
                ),
                onNoteOn = {},
                onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {},
                onOctaveChange = {},
                onPreviousPatch = { previous++ },
                onNextPatch = { next++ },
            )
        }
        compose.onNodeWithContentDescription("Previous factory preset").performClick()
        compose.onNodeWithContentDescription("Next factory preset").performClick()
        compose.onNodeWithText("PATCH 2 / 6").assertExists()
        compose.runOnIdle {
            assertEquals(1, previous)
            assertEquals(1, next)
        }
    }

    @Test fun patchArrowsDoNotPretendToWorkWithoutCallbacks() {
        compose.setContent {
            OrbitalScreen(
                state = OrbitalUiState(),
                onNoteOn = {},
                onNoteOff = {},
                onParameterChange = { _, _ -> },
                onModeChange = {},
                onOctaveChange = {},
            )
        }
        compose.onNodeWithContentDescription("Previous factory preset").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next factory preset").assertIsNotEnabled()
    }

    @Test fun keyboardHitTestingMatchesBlackAndWhiteGeometry() {
        assertEquals(60, keyboardHitNote(25f, 150f, 800f, 160f, 8, 4))
        assertEquals(61, keyboardHitNote(100f, 12f, 800f, 160f, 8, 4))
        assertEquals(62, keyboardHitNote(100f, 140f, 800f, 160f, 8, 4))
        assertEquals(72, keyboardHitNote(799f, 140f, 800f, 160f, 8, 4))
        assertNull(keyboardHitNote(-1f, 10f, 800f, 160f, 8, 4))
        assertNull(keyboardHitNote(900f, 10f, 800f, 160f, 8, 4))
    }
}
