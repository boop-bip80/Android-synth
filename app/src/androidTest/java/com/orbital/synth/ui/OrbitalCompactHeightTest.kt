package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbitalCompactHeightTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun threeHundredFiftyDpViewportKeepsKeyboardOnScreenAcrossTabs() {
        compose.setContent {
            Box(Modifier.width(360.dp).height(350.dp).testTag("heightBudgetViewport")) {
                OrbitalScreen(
                    state = OrbitalUiState(),
                    onNoteOn = {},
                    onNoteOff = {},
                    onParameterChange = { _, _ -> },
                    onModeChange = {},
                    onOctaveChange = {},
                )
            }
        }
        fun checkKeyboard() {
            val keyboard = compose.onNodeWithContentDescription(
                "Multitouch piano keyboard", substring = true,
            )
            keyboard.assertIsDisplayed()
            val bounds = keyboard.fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("heightBudgetViewport")
                .fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("Keyboard top escapes 350dp viewport", bounds.top >= viewport.top)
            assertTrue("Keyboard bottom clipped by 350dp viewport", bounds.bottom <= viewport.bottom + density)
            assertTrue("Compact keyboard became unusably short", bounds.height >= 80f * density)
        }
        checkKeyboard()
        compose.onNodeWithContentDescription("Tab FX").performClick()
        checkKeyboard()
        compose.onNodeWithContentDescription("Tab SOUND").performClick()
        checkKeyboard()
    }
}
