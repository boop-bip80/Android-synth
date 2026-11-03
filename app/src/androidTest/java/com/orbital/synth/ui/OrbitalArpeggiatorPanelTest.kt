package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbitalArpeggiatorPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun allMusicalChoicesDispatchToRealHostCallbacks() {
        var enabled = false
        var direction = ArpDirection.UP
        var rate = 0
        var latch = false
        compose.setContent {
            ArpeggiatorPanel(
                state = OrbitalUiState(arpEnabled = false, arpRate = 4),
                onEnabledChange = { enabled = it },
                onDirectionChange = { direction = it },
                onRateChange = { rate = it },
                onLatchChange = { latch = it },
                onSwingChange = {},
            )
        }
        compose.onNodeWithContentDescription("Turn arpeggiator on").performClick()
        compose.onNodeWithContentDescription("Arpeggiator direction DOWN").performClick()
        compose.onNodeWithContentDescription("Arpeggiator rate 8 steps per beat").performClick()
        compose.onNodeWithContentDescription("Enable arpeggiator latch").performClick()
        compose.runOnIdle {
            assertTrue(enabled)
            assertEquals(ArpDirection.DOWN, direction)
            assertEquals(8, rate)
            assertTrue(latch)
        }
    }

    @Test fun controlsWithoutHostCallbacksAreDisabledNotFake() {
        compose.setContent {
            ArpeggiatorPanel(
                state = OrbitalUiState(),
                onEnabledChange = null,
                onDirectionChange = null,
                onRateChange = null,
                onLatchChange = null,
                onSwingChange = null,
            )
        }
        compose.onNodeWithContentDescription("Turn arpeggiator on").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Arpeggiator direction RANDOM").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Arpeggiator rate 2 steps per beat").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Enable arpeggiator latch").assertIsNotEnabled()
    }
}
