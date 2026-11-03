package com.orbital.synth.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.orbital.synth.MainActivity
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Captures actual Compose Canvas pixels with independently controlled inputs.
 * An injected host RMS value tests the renderer's meter-input response; this
 * test does NOT prove that AudioTrack delivered PCM. The host-integration
 * suite owns that separate end-to-end verification.
 */
class ReferenceKeybedGlassRiverTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun changedSamples(before: PixelMap, after: PixelMap): Int {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        var changed = 0
        val firstY = after.height / 5
        val lastY = after.height * 4 / 5
        for (y in firstY until lastY step 6) {
            for (x in after.width / 12 until after.width * 11 / 12 step 6) {
                val a = before[x, y]
                val b = after[x, y]
                val delta = abs(a.red - b.red) + abs(a.green - b.green) +
                    abs(a.blue - b.blue)
                if (delta > .045f) changed++
            }
        }
        return changed
    }

    @Test fun glassRiverPixelsRespondIndependentlyToParametersAndInjectedMeterWhileKeysRemainPlayable() {
        val morph = mutableFloatStateOf(.12f)
        val movement = mutableFloatStateOf(.18f)
        val injectedHostRms = mutableFloatStateOf(0f)
        val attacks = mutableListOf<Int>()
        val releases = mutableListOf<Int>()

        // MainActivity owns its initial content. Replace it on its actual
        // foreground Activity; AndroidComposeTestRule.setContent would throw.
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                ReferencePerformanceKeybed(
                    octave = 4,
                    onNoteOn = { attacks += it },
                    onNoteOff = { releases += it },
                    modifier = Modifier.fillMaxWidth(),
                    morph = morph.floatValue,
                    movement = movement.floatValue,
                    audioRms = injectedHostRms.floatValue,
                    preferredHeight = 108.dp,
                )
            }
        }
        val keybed = compose.onNodeWithTag("referencePerformanceKeybed")
        compose.waitForIdle()
        val quiet = keybed.captureToImage().toPixelMap()

        // Stage 1: hold stored parameters fixed, change only the injected
        // PCM-meter input. The actual host derives this from accepted AudioTrack
        // output; this renderer-only test deliberately supplies a known value.
        compose.runOnUiThread { injectedHostRms.floatValue = .16f }
        compose.waitForIdle()
        val meterOnly = keybed.captureToImage().toPixelMap()
        assertTrue(
            "Chromatic glass did not respond to its independently changed host RMS input",
            changedSamples(quiet, meterOnly) >= 36,
        )

        // Stage 2: hold RMS fixed; change only MORPH and MOVEMENT.
        compose.runOnUiThread {
            morph.floatValue = .84f
            movement.floatValue = .76f
        }
        compose.waitForIdle()
        val parameterOnly = keybed.captureToImage().toPixelMap()
        assertTrue(
            "Chromatic glass did not respond to independently changed synthesis parameters",
            changedSamples(meterOnly, parameterOnly) >= 36,
        )

        keybed.performTouchInput {
            down(center)
            up()
        }
        compose.runOnIdle {
            assertEquals("One fresh finger should attack exactly one note", 1, attacks.size)
            assertEquals("Release must match the owned attack", attacks, releases)
        }
    }
}
