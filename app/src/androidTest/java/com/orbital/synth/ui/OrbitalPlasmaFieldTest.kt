package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Android display pixels: catches a black AGSL renderer, an audio
 * reactivity disconnect and inert gesture controls. Uses true foreground
 * UiAutomation frames, NOT Compose captureToImage()/PixelCopy, which has
 * timed out on API35 software GPU despite a healthy emulator.
 */
@RunWith(AndroidJUnit4::class)
class OrbitalPlasmaFieldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun realOrb(columns: Int = 56, rows: Int = 56): NativeCanvasPixelEvidence {
        val bounds = compose.onNodeWithTag("orbitalPlasmaField")
            .assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2)
        compose.runOnIdle {
            compose.activity.window.decorView.getLocationOnScreen(origin)
        }
        return NativeCanvasPixelCapture.capture(
            nodeBoundsInWindow = bounds,
            decorScreenX = origin[0],
            decorScreenY = origin[1],
            expectedForegroundPackage = compose.activity.packageName,
            columns = columns,
            rows = rows,
        )
    }

    @Test fun orbHasLuminousColorAndPcmTelemetryAltersActualPixels() {
        var rms by mutableFloatStateOf(0f)
        var peak by mutableFloatStateOf(0f)
        compose.setContent {
            OrbitalPlasmaField(
                morph = .64f, movement = .32f,
                audioRms = rms, audioPeak = peak,
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.waitForIdle()
        val quiet = realOrb()
        val luminous = quiet.luminousPixels(minChannel = 36)
        assertTrue(
            "Idle plasma must have genuinely luminous visible Android pixels; " +
                "count=" + luminous + " ROI=" + quiet.regionLabel(),
            luminous > 15,
        )
        // Negative control: no PCM or finger, so production AGSL must stop
        // scheduling animation; a drifting static framebuffer cannot fake
        // the PCM-response threshold.
        Thread.sleep(150L)
        val quietAgain = realOrb()
        val quietDrift = quiet.countRgbSumChanged(quietAgain, threshold = 16)
        assertTrue(
            "Plasma changed " + quietDrift +
                " meaningful RGB pixels without PCM/touch",
            quietDrift < 24,
        )

        compose.runOnIdle {
            rms = .88f
            peak = .94f
        }
        var changed = 0
        for (attempt in 1..4) {
            val active = realOrb()
            changed = maxOf(changed, quietAgain.countRgbSumChanged(active, threshold = 16))
            if (changed > 20 && changed > quietDrift * 3) break
            Thread.sleep(130L)
        }
        assertTrue(
            "Real AudioTrack PCM telemetry must alter plasma shader colors; " +
                "changed=" + changed + " quiet drift=" + quietDrift,
            changed > 20 && changed > quietDrift * 3,
        )
        println(
            "PLASMA_NATIVE_PCM_PIXEL_PROOF luminous=" + luminous +
                " changed=" + changed + " control=" + quietDrift +
                " ROI=" + quiet.regionLabel(),
        )
    }

    @Test fun invalidSignalsCannotCauseNonfiniteShaderUniforms() {
        compose.setContent {
            OrbitalPlasmaField(
                morph = Float.NaN,
                movement = Float.NEGATIVE_INFINITY,
                audioRms = Float.POSITIVE_INFINITY,
                audioPeak = Float.NaN,
                touchX = Float.NaN,
                touchY = Float.POSITIVE_INFINITY,
                pressed = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val visible = realOrb()
        val luminous = visible.luminousPixels(minChannel = 36)
        assertTrue(
            "Invalid telemetry made the real AGSL Canvas blank or nonrendering; " +
                "luminous=" + luminous + " ROI=" + visible.regionLabel(),
            luminous > 15,
        )
    }

    @Test fun morphMovementAndTouchCoordinatesActuallyAlterVolumetricArtwork() {
        var morph by mutableFloatStateOf(.14f)
        var movement by mutableFloatStateOf(.17f)
        var x by mutableFloatStateOf(.10f)
        var y by mutableFloatStateOf(.11f)
        compose.setContent {
            OrbitalPlasmaField(
                morph = morph, movement = movement,
                touchX = x, touchY = y,
                audioRms = 0f, audioPeak = 0f, pressed = false,
                modifier = Modifier.size(300.dp),
            )
        }
        compose.waitForIdle()
        val before = realOrb(columns = 44, rows = 44)
        // Same static no-PCM, no-touch negative control as the real PCM test.
        Thread.sleep(150L)
        val control = realOrb(columns = 44, rows = 44)
        val drift = before.countRgbSumChanged(control, threshold = 15)
        assertTrue(
            "The silent 300dp liquid shader drifted without controls; " +
                "changed=" + drift,
            drift < 24,
        )

        compose.runOnIdle {
            morph = .87f
            movement = .91f
            x = .92f
            y = .86f
        }
        var changed = 0
        for (attempt in 1..4) {
            val after = realOrb(columns = 44, rows = 44)
            changed = maxOf(changed, control.countRgbSumChanged(after, threshold = 15))
            if (changed > 25 && changed > drift * 3) break
            Thread.sleep(130L)
        }
        assertTrue(
            "Editing MORPH/MOVEMENT/touch must reconfigure actual 300dp " +
                "liquid artwork without PCM: changed=" + changed +
                " control=" + drift,
            changed > 25 && changed > drift * 3,
        )
        println(
            "PLASMA_NATIVE_CONTROL_PIXEL_PROOF changed=" + changed +
                " controlDrift=" + drift + " ROI=" + before.regionLabel(),
        )
    }
}
