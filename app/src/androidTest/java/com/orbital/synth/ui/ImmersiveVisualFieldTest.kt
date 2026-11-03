package com.orbital.synth.ui

import android.graphics.Color as PixelColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Canvas coverage: compares REAL Android foreground compositor pixels
 * so accidental disconnect between measured PCM telemetry and the visual field
 * is detected. This verifies visual reactivity, not design-reference parity.
 */
@RunWith(AndroidJUnit4::class)
class ImmersiveVisualFieldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()


    @Test fun equalRmsLowMidHighChangeSpatialPixelsOnRealAndroidCompositor() {
        var low by mutableFloatStateOf(.82f)
        var mid by mutableFloatStateOf(.17f)
        var high by mutableFloatStateOf(.01f)
        compose.setContent {
            ImmersiveVisualField(
                modifier = Modifier.fillMaxSize(),
                morph = .64f,
                movement = .46f,
                cutoff = .58f,
                resonance = .72f,
                touchIntensity = 0f,
                // Identical actual-output level; ONLY the measured relative
                // crossover energies differ between captures.
                audioRms = .42f,
                audioPeak = .57f,
                audioTransient = 0f,
                audioBrightness = .42f,
                audioLowBand = low,
                audioMidBand = mid,
                audioHighBand = high,
            )
        }
        val field = compose.onNodeWithTag("immersiveVisualField")
        field.assertIsDisplayed()
        val source = RealAndroidFrameCapture(compose, compose.activity)
        val sampleWidth = 64
        val sampleHeight = 64
        fun physicalPixels(label: String): IntArray = source.capture(field, label).use { real ->
            val samples = IntArray(sampleWidth * sampleHeight)
            for (y in 0 until sampleHeight) {
                for (x in 0 until sampleWidth) {
                    val px = ((x + .5f) * real.width / sampleWidth).toInt()
                        .coerceIn(0, real.width - 1)
                    val py = ((y + .5f) * real.height / sampleHeight).toInt()
                        .coerceIn(0, real.height - 1)
                    samples[y * sampleWidth + x] = real.argbAt(px, py)
                }
            }
            samples
        }
        fun changed(a: IntArray, b: IntArray): Pair<Int, Int> {
            var all = 0
            var core = 0
            for (y in 0 until sampleHeight) {
                for (x in 0 until sampleWidth) {
                    val index = y * sampleWidth + x
                    val first = a[index]
                    val second = b[index]
                    val delta = abs(PixelColor.red(first) - PixelColor.red(second)) +
                        abs(PixelColor.green(first) - PixelColor.green(second)) +
                        abs(PixelColor.blue(first) - PixelColor.blue(second))
                    if (delta >= 25) {
                        all++
                        if (x in 14..50 && y in 19..44) core++
                    }
                }
            }
            return Pair(all, core)
        }
        // The first pair measures genuine animated-PCM drift without ANY band
        // change, so a moving background alone cannot satisfy the next assertions.
        val lowA = physicalPixels("timbre-same-rms-low-a")
        val lowB = physicalPixels("timbre-same-rms-low-b")
        compose.runOnIdle {
            low = .04f
            mid = .87f
            high = .09f
        }
        val middle = physicalPixels("timbre-same-rms-mid")
        compose.runOnIdle {
            low = .01f
            mid = .16f
            high = .83f
        }
        val highFrame = physicalPixels("timbre-same-rms-high")
        val unchanged = changed(lowA, lowB)
        val changedLowMid = changed(lowB, middle)
        val changedMidHigh = changed(middle, highFrame)
        assertTrue(
            "PCM crossover must change REAL spatial light beyond same-mode temporal drift; " +
                "baseline=" + unchanged + " low-mid=" + changedLowMid,
            changedLowMid.first >= unchanged.first + 30 && changedLowMid.second >= 18,
        )
        assertTrue(
            "A 7kHz-equivalent high-band shape must differ from a 1kHz-equivalent braid " +
                "at the SAME RMS; baseline=" + unchanged + " mid-high=" + changedMidHigh,
            changedMidHigh.first >= unchanged.first + 30 && changedMidHigh.second >= 18,
        )
    }

    @Test fun stoppedFocusDrawGateLeavesMeasuredPhysicalBlackInsteadOfPhantomTimbre() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ImmersiveVisualField(
                    modifier = Modifier.fillMaxSize(),
                    morph = .5f, movement = .5f, cutoff = .5f, resonance = .5f,
                    touchIntensity = 0f,
                    audioRms = 0f, audioPeak = 0f,
                    audioLowBand = 0f, audioMidBand = 0f, audioHighBand = 0f,
                    skipArtwork = true,
                )
            }
        }
        val field = compose.onNodeWithTag("immersiveVisualField")
        field.assertIsDisplayed()
        RealAndroidFrameCapture(compose, compose.activity)
            .capture(field, "focus-post-stop-black").use { physical ->
                var brightPixels = 0
                for (iy in 0 until 44) {
                    for (ix in 0 until 44) {
                        val px = ((ix + .5f) * physical.width / 44).toInt()
                            .coerceIn(0, physical.width - 1)
                        val py = ((iy + .5f) * physical.height / 44).toInt()
                            .coerceIn(0, physical.height - 1)
                        val color = physical.argbAt(px, py)
                        if (maxOf(PixelColor.red(color), PixelColor.green(color),
                                PixelColor.blue(color)) > 10) brightPixels++
                    }
                }
                assertTrue(
                    "Stopped, fully faded FOCUS field must be actual black SurfaceFlinger pixels; " +
                        "bright=" + brightPixels,
                    brightPixels <= 2,
                )
            }
    }

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
        val actualFrame = RealAndroidFrameCapture(compose, compose.activity)
        actualFrame.capture(field, "immersive-silent").use { silent ->
            compose.runOnIdle {
                actualRms = .8f
                actualPeak = .93f
            }
            field.assertIsDisplayed()
            actualFrame.capture(field, "immersive-measured-pcm").use { active ->
                assertTrue(
                    "Real Android canvas geometry must be stable across PCM updates",
                    active.width == silent.width && active.height == silent.height,
                )
                var changed = 0
                val stepX = (active.width / 48).coerceAtLeast(1)
                val stepY = (active.height / 48).coerceAtLeast(1)
                for (y in 0 until active.height step stepY) {
                    for (x in 0 until active.width step stepX) {
                        if (active.argbAt(x, y) != silent.argbAt(x, y)) changed++
                    }
                }
                assertTrue(
                    "Measured PCM should visibly change multiple pixels in the tactile light field; " +
                        "actual SurfaceFlinger changed=" + changed,
                    changed >= 24,
                )
            }
        }
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
        RealAndroidFrameCapture(compose, compose.activity)
            .capture(field, "immersive-invalid-input").use { real ->
                assertTrue(real.width > 1)
            }
    }
}
