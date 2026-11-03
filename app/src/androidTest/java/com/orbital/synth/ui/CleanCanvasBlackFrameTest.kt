package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose-owned pixel snapshots verify the pure-black quiet canvas and touch
 * wake in an isolated host. PR #107 independently verifies the genuine Android
 * framebuffer / AudioTrack path, which this test intentionally does not claim.
 */
@RunWith(AndroidJUnit4::class)
class CleanCanvasBlackFrameTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun sampledBrightPixelCount(): Int {
        val image = compose.onNodeWithTag("immersiveStage").captureToImage()
        val pixels = image.toPixelMap()
        var bright = 0
        // Sample a broad center area to avoid aliasing against thin neon edges.
        val xStep = (image.width / 150).coerceAtLeast(2)
        val yStep = (image.height / 180).coerceAtLeast(2)
        for (y in image.height / 10 until image.height * 9 / 10 step yStep) {
            for (x in image.width / 10 until image.width * 9 / 10 step xStep) {
                val color = pixels[x, y]
                if (maxOf(color.red, color.green, color.blue) >= .15f) bright++
            }
        }
        return bright
    }

    @Test fun aSettledFocusCanvasIsBlackButFingerContactIlluminatesIt() {
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

        compose.onNodeWithTag("immersiveModeToggle").performClick()
        compose.onNodeWithTag("stageFocusEnter").performClick()
        // Let the intended 950 ms fade complete before examining quiet pixels.
        Thread.sleep(1_150L)
        compose.waitForIdle()
        val idleBright = sampledBrightPixelCount()
        assertTrue("A settled silent FOCUS field must be visually blank black; samples=$idleBright",
            idleBright <= 2)

        val canvas = compose.onNodeWithTag("immersiveCanvas")
        try {
            canvas.performTouchInput { down(center) }
            Thread.sleep(210L)
            compose.waitForIdle()
            val activeBright = sampledBrightPixelCount()
            assertTrue("Finger pressure must wake visible neon artwork: quiet=$idleBright active=$activeBright",
                activeBright >= idleBright + 40)
        } finally {
            canvas.performTouchInput { up() }
        }

        Thread.sleep(1_150L)
        compose.waitForIdle()
        val settledBright = sampledBrightPixelCount()
        assertTrue("After sound ends and the trail fades, FOCUS should return to black",
            settledBright <= 2)
    }
}
