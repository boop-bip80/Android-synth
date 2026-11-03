package com.orbital.synth.ui

import android.graphics.Color as PixelColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Real device compositor pixels: catches an AGSL shader that compiles but only
 * paints black, or a proposed "audio reactive" renderer that never responds
 * to real input telemetry. No synthetic image/mock FFT or PixelCopy fallback.
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
        val actualFrame = RealAndroidFrameCapture(compose, compose.activity)
        actualFrame.capture(orb, "orb-quiet-pcm").use { quiet ->
            val midX = quiet.width / 2
            val midY = quiet.height / 2
            var luminousQuiet = 0
            val stepX = (quiet.width / 55).coerceAtLeast(1)
            val stepY = (quiet.height / 55).coerceAtLeast(1)
            for (y in 0 until quiet.height step stepY) {
                for (x in 0 until quiet.width step stepX) {
                    val c = quiet.argbAt(x, y)
                    if (maxOf(PixelColor.red(c), PixelColor.green(c), PixelColor.blue(c)) /
                        255f >= .14f
                    ) luminousQuiet++
                }
            }
            assertTrue(
                "The idle plasma orb must be colorful, not a blank placeholder; " +
                    "real android luminous=" + luminousQuiet,
                luminousQuiet > 15,
            )

            compose.runOnIdle {
                rms = .88f
                peak = .94f
            }
            actualFrame.capture(orb, "orb-active-pcm").use { active ->
                assertTrue(
                    "Real Android orb geometry must be stable across PCM updates",
                    active.width == quiet.width && active.height == quiet.height,
                )
                var changed = 0
                for (y in 0 until active.height step stepY) {
                    for (x in 0 until active.width step stepX) {
                        val old = quiet.argbAt(x, y)
                        val now = active.argbAt(x, y)
                        val difference =
                            (abs(PixelColor.red(now) - PixelColor.red(old)) +
                                abs(PixelColor.green(now) - PixelColor.green(old)) +
                                abs(PixelColor.blue(now) - PixelColor.blue(old))) / 255f
                        if (difference >= .06f) changed++
                    }
                }
                assertTrue(
                    "True PCM telemetry must alter visible shader colors; " +
                        "real Android changed=" + changed,
                    changed > 20,
                )
            }
            assertTrue(midX > 0 && midY > 0)
        }
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
        RealAndroidFrameCapture(compose, compose.activity)
            .capture(orb, "orb-invalid-finite-uniforms").use { real ->
                assertTrue(real.width > 1)
            }
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
        val actualFrame = RealAndroidFrameCapture(compose, compose.activity)
        actualFrame.capture(orb, "orb-quiet-touch").use { before ->
            compose.runOnIdle {
                morph = .87f
                movement = .91f
                x = .92f
                y = .86f
            }
            actualFrame.capture(orb, "orb-altered-touch").use { after ->
                assertTrue(
                    "Real Android orb geometry must be stable across gestures",
                    after.width == before.width && after.height == before.height,
                )
                var changed = 0
                val stepX = (after.width / 44).coerceAtLeast(1)
                val stepY = (after.height / 44).coerceAtLeast(1)
                for (row in 0 until after.height step stepY) {
                    for (column in 0 until after.width step stepX) {
                        val a = before.argbAt(column, row)
                        val b = after.argbAt(column, row)
                        val difference =
                            (abs(PixelColor.red(a) - PixelColor.red(b)) +
                                abs(PixelColor.green(a) - PixelColor.green(b)) +
                                abs(PixelColor.blue(a) - PixelColor.blue(b))) / 255f
                        if (difference > .055f) changed++
                    }
                }
                assertTrue(
                    "Without PCM or animation, real MORPH/MOVEMENT and touch " +
                        "must visibly reconfigure the procedural liquid field; " +
                        "real Android changed=" + changed,
                    changed > 25,
                )
            }
        }
    }
}
