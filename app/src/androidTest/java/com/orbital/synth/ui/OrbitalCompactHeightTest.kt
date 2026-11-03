package com.orbital.synth.ui

import android.util.Log
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

private const val COMPACT_DIAGNOSTIC_TAG = "OrbitalCompactTest"

@RunWith(AndroidJUnit4::class)
class OrbitalCompactHeightTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun threeHundredFiftyDpViewportKeepsKeyboardOnScreenAcrossTabs() {
        Log.i(COMPACT_DIAGNOSTIC_TAG, "360dp: begin 350dp viewport fixture")
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
        Log.i(COMPACT_DIAGNOSTIC_TAG, "360dp: setContent returned")
        fun checkKeyboard(tab: String) {
            Log.i(COMPACT_DIAGNOSTIC_TAG, "360dp/$tab: look up keyboard semantics")
            val keyboard = compose.onNodeWithContentDescription(
                "Multitouch piano keyboard", substring = true,
            )
            val bounds = keyboard.fetchSemanticsNode().boundsInRoot
            Log.i(COMPACT_DIAGNOSTIC_TAG, "360dp/$tab: look up viewport semantics")
            val viewport = compose.onNodeWithTag("heightBudgetViewport")
                .fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            val message = "360dp/$tab keyboard=$bounds viewport=$viewport density=$density"
            Log.i(COMPACT_DIAGNOSTIC_TAG, message)
            assertTrue("$message: top escapes viewport", bounds.top >= viewport.top)
            assertTrue("$message: bottom clipped", bounds.bottom <= viewport.bottom + density)
            assertTrue("$message: keyboard unusably short", bounds.height >= 80f * density)
            Log.i(COMPACT_DIAGNOSTIC_TAG, "360dp/$tab: before assertIsDisplayed")
            keyboard.assertIsDisplayed()
            Log.i(COMPACT_DIAGNOSTIC_TAG, "360dp/$tab: keyboard visible")
        }

        checkKeyboard("SOUND-initial")
        Log.i(COMPACT_DIAGNOSTIC_TAG, "360dp: clicking Tab FX")
        compose.onNodeWithContentDescription("Tab FX").performClick()
        checkKeyboard("FX")
        Log.i(COMPACT_DIAGNOSTIC_TAG, "360dp: clicking Tab SOUND")
        compose.onNodeWithContentDescription("Tab SOUND").performClick()
        checkKeyboard("SOUND-return")
    }
}
