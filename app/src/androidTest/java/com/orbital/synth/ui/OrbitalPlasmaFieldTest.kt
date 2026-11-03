package com.orbital.synth.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 * Real device Canvas pixels: catches an AGSL shader that compiles but only
 * paints black, or a proposed "audio reactive" renderer that never responds
 * to the real host telemetry values. No synthetic image/mock FFT involved.
 */
@RunWith(AndroidJUnit4::class)
class OrbitalPlasmaFieldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
        val orb = compose.onNodeWithTag("orbitalPlasmaField")
        orb.assertIsDisplayed()
        val quiet = orb.captureToImage().toPixelMap()
        val midX = quiet.width / 2
        val midY = quiet.height / 2
        var luminousQuiet = 0
        val stepX = (quiet.width / 55).coerceAtLeast(1)
        val stepY = (quiet.height / 55).coerceAtLeast(1)
        for (y in 0 until quiet.height step stepY) {
            for (x in 0 until quiet.width step stepX) {
                val c = quiet[x, y]
                if (maxOf(c.red, c.green, c.blue) >= .14f) luminousQuiet++
            }
        }
        assertTrue("The idle plasma orb must be colorful, not a blank placeholder",
            luminousQuiet > 15)

        compose.runOnIdle {
            rms = .88f
            peak = .94f
        }
        val active = orb.captureToImage().toPixelMap()
        var changed = 0
        for (y in 0 until active.height step stepY) {
            for (x in 0 until active.width step stepX) {
                val old = quiet[x, y]
                val now = active[x, y]
                val difference = kotlin.math.abs(now.red - old.red) +
                    kotlin.math.abs(now.green - old.green) +
                    kotlin.math.abs(now.blue - old.blue)
                if (difference >= .06f) changed++
            }
        }
        assertTrue("True PCM telemetry must alter visible shader colors", changed > 20)
        assertTrue(midX > 0 && midY > 0)
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
        val orb = compose.onNodeWithTag("orbitalPlasmaField")
        orb.assertIsDisplayed()
        assertTrue(orb.captureToImage().width > 1)
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
        val orb = compose.onNodeWithTag("orbitalPlasmaField")
        orb.assertIsDisplayed()
        val before = orb.captureToImage().toPixelMap()
        compose.runOnIdle {
            morph = .87f
            movement = .91f
            x = .92f
            y = .86f
        }
        val after = orb.captureToImage().toPixelMap()
        var changed = 0
        val stepX = (after.width / 44).coerceAtLeast(1)
        val stepY = (after.height / 44).coerceAtLeast(1)
        for (row in 0 until after.height step stepY) {
            for (column in 0 until after.width step stepX) {
                val a = before[column, row]
                val b = after[column, row]
                val d = kotlin.math.abs(a.red - b.red) +
                    kotlin.math.abs(a.green - b.green) +
                    kotlin.math.abs(a.blue - b.blue)
                if (d > .055f) changed++
            }
        }
        assertTrue(
            "Without any PCM or animation, editing real MORPH/MOVEMENT and touch" +
                " must visibly reconfigure the procedural liquid field",
            changed > 25,
        )
    }
}
