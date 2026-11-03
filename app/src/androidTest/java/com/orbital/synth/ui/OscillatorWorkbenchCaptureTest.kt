package com.orbital.synth.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.MainActivity
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures the actual framebuffer from the production MainActivity and its
 * long-lived audio engine. No fixture, fake bitmap, or screenshot mock is used.
 *
 * The CI harness runs this test separately after regular instrumentation and
 * extracts the PNG from the debug app's private storage via adb run-as.
 */
@RunWith(AndroidJUnit4::class)
class OscillatorWorkbenchCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun capturesRunningOscillatorEditorAfterUserConfiguration() {
        // Portrait SOUND deliberately leads with the reference hero. An OSC
        // rack only exists after a real, reachable advanced-editor tap.
        compose.onNodeWithTag("referenceSoundAdvancedToggle").performScrollTo()
            .assertIsDisplayed().performClick()
        compose.onNodeWithTag("oscillatorRack").assertExists()

        // Real factory boot may legitimately have zero enabled oscillators;
        // the workbench must permit constructing its FIRST audible source.
        compose.onNodeWithTag("oscillatorLayer0").performScrollTo().performClick()
        if (compose.onAllNodesWithContentDescription("Oscillator 1, active")
                .fetchSemanticsNodes().isEmpty()
        ) {
            compose.onNodeWithTag("oscillatorEnable").performScrollTo()
                .assertIsDisplayed().performClick()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Oscillator 1, active").assertExists()

        // Add a second truly audible oscillator and select it before wave
        // edits. This remains valid if a saved patch already has OSC2 active.
        if (compose.onAllNodesWithContentDescription("Oscillator 2, active")
                .fetchSemanticsNodes().isEmpty()
        ) {
            compose.onNodeWithTag("oscillatorAdd").performScrollTo()
                .assertIsDisplayed().performClick()
        }
        compose.onNodeWithTag("oscillatorLayer1").performScrollTo()
            .assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Oscillator 2, active").assertExists()

        // Display the real wave editor and change the actual synth parameter.
        compose.onNodeWithTag("oscillatorWave2").performScrollTo()
            .assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("oscillatorWave2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Oscillator 2, active").assertExists()

        // UiAutomation samples Android's actual display, not Compose's
        // testing render buffer; PNG bytes come from the running emulator.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Android UiAutomation did not return a genuine framebuffer"
        }
        try {
            assertTrue("Emulator screenshot must be portrait", bitmap.height > bitmap.width)
            assertTrue("Screenshot must have actual phone dimensions", bitmap.width >= 600 && bitmap.height >= 1000)
            val target = File(compose.activity.filesDir, "orbital-oscillator-workbench.png")
            FileOutputStream(target).use { output ->
                assertTrue("Failed to encode Android's real framebuffer", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.fd.sync()
            }
            assertTrue("Screenshot is empty or suspiciously small", target.length() > 4096L)
        } finally {
            bitmap.recycle()
        }
    }
}
