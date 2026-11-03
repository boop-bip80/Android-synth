package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Canvas coverage: compares actual rendered pixels so an accidental
 * disconnect between measured PCM telemetry and the visual field is detected.
 * This verifies visual reactivity, not photographic parity with design art.
 */
@RunWith(AndroidJUnit4::class)
class ImmersiveVisualFieldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun measuredPcmChangesActualCanvasPixels() {
        var actualRms by mutableFloatStateOf(0f)
        var actualPeak by mutableFloatStateOf(0f)
        compose.setContent {
            ImmersiveVisualField(
                modifier = Modifier.fillMaxSize(),
                morph = .76f,
                movement = .43f,
                cutoff = .65f,
                resonance = .78f,
                touchIntensity = 0f,
                audioRms = actualRms,
                audioPeak = actualPeak,
                audioBrightness = .83f,
            )
        }
        val field = compose.onNodeWithTag("immersiveVisualField")
        field.assertIsDisplayed()
        val silent = field.captureToImage().toPixelMap()

        compose.runOnIdle {
            actualRms = .8f
            actualPeak = .93f
        }
        field.assertIsDisplayed()
        val active = field.captureToImage().toPixelMap()
        var changed = 0
        val stepX = (active.width / 48).coerceAtLeast(1)
        val stepY = (active.height / 48).coerceAtLeast(1)
        for (y in 0 until active.height step stepY) {
            for (x in 0 until active.width step stepX) {
                if (active[x, y] != silent[x, y]) changed++
            }
        }
        assertTrue(
            "Measured PCM should visibly change multiple pixels in the tactile light field",
            changed >= 24,
        )
    }

    @Test fun invalidTelemetryAndGestureValuesDoNotCrashCanvas() {
        compose.setContent {
            ImmersiveVisualField(
                modifier = Modifier.fillMaxSize(),
                morph = Float.POSITIVE_INFINITY,
                movement = Float.NaN,
                cutoff = Float.NEGATIVE_INFINITY,
                resonance = Float.NaN,
                touchX = Float.NaN,
                touchY = Float.POSITIVE_INFINITY,
                touchIntensity = Float.NEGATIVE_INFINITY,
                audioRms = Float.NaN,
                audioPeak = Float.POSITIVE_INFINITY,
                audioTransient = Float.NEGATIVE_INFINITY,
                audioBrightness = Float.NaN,
            )
        }
        val field = compose.onNodeWithTag("immersiveVisualField")
        field.assertIsDisplayed()
        assertTrue(field.captureToImage().width > 1)
    }
}