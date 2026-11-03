package com.orbital.synth.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android compositor coverage: sample the actual visible Canvas from
 * UiAutomation's full-display framebuffer. Compose captureToImage() relies on
 * PixelCopy, which can time out in software-rendered API35 emulators even when
 * the app is visible. No synthetic Bitmap, fake accessibility screenshot or
 * exemption from a failing pixel assertion is permitted here.
 */
@RunWith(AndroidJUnit4::class)
class ImmersiveVisualFieldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private data class FieldEvidence(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val colors: IntArray,
    ) {
        fun countMeaningfullyChanged(other: FieldEvidence): Int {
            check(
                left == other.left && top == other.top &&
                    right == other.right && bottom == other.bottom &&
                    displayWidth == other.displayWidth &&
                    displayHeight == other.displayHeight &&
                    colors.size == other.colors.size,
            ) {
                "Actual framebuffer/Canvas coordinates moved between samples; " +
                    "cannot attribute pixel differences to PCM"
            }
            var changed = 0
            for (index in colors.indices) {
                val a = colors[index]
                val b = other.colors[index]
                val maxChannelChange = maxOf(
                    abs(Color.red(a) - Color.red(b)),
                    abs(Color.green(a) - Color.green(b)),
                    abs(Color.blue(a) - Color.blue(b)),
                )
                if (maxChannelChange >= 12) changed++
            }
            return changed
        }

        fun colorfulPixels(): Int = colors.count { pixel ->
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            maxOf(r, g, b) >= 64 && maxOf(r, g, b) - minOf(r, g, b) >= 31
        }
    }

    private fun captureRealVisibleField(): FieldEvidence {
        val fieldWindow = compose.onNodeWithTag("immersiveVisualField")
            .assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val decorOrigin = IntArray(2)
        compose.runOnIdle {
            compose.activity.window.decorView.getLocationOnScreen(decorOrigin)
        }

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val foregroundPackage = automation.rootInActiveWindow?.packageName?.toString()
        assertTrue(
            "Actual foreground accessibility root is not the instrumented Canvas " +
                "Activity: foreground=" + foregroundPackage +
                " activity=" + compose.activity.packageName,
            foregroundPackage == compose.activity.packageName,
        )

        // UiAutomation takes a real SurfaceFlinger frame. Bound transient
        // startup retries, then fail closed rather than drawing a fallback.
        var screenshot: Bitmap? = null
        for (attempt in 1..5) {
            screenshot = automation.takeScreenshot()
            if (screenshot != null) break
            Thread.sleep(150L)
        }
        val frame = screenshot
            ?: throw AssertionError("Android returned no real display framebuffer after five attempts")
        try {
            val left = (fieldWindow.left + decorOrigin[0]).roundToInt()
                .coerceIn(0, frame.width)
            val right = (fieldWindow.right + decorOrigin[0]).roundToInt()
                .coerceIn(0, frame.width)
            val top = (fieldWindow.top + decorOrigin[1]).roundToInt()
                .coerceIn(0, frame.height)
            val bottom = (fieldWindow.bottom + decorOrigin[1]).roundToInt()
                .coerceIn(0, frame.height)
            assertTrue(
                "Actual immersive Canvas has no usable display ROI: " +
                    "[" + left + "," + top + "][" + right + "," + bottom +
                    "] frame=" + frame.width + "x" + frame.height,
                right - left >= 64 && bottom - top >= 64,
            )

            // Sample the exact semantic Canvas bounds, never HUD, nav bar,
            // launcher or a different app. A fixed grid avoids retaining
            // large native screenshots while asserting actual rendered RGB.
            val columns = 48
            val rows = 48
            val samples = IntArray(columns * rows)
            for (y in 0 until rows) {
                val actualY = top + y * (bottom - top - 1) / (rows - 1)
                for (x in 0 until columns) {
                    val actualX = left + x * (right - left - 1) / (columns - 1)
                    samples[y * columns + x] = frame.getPixel(actualX, actualY)
                }
            }
            return FieldEvidence(
                left, top, right, bottom, frame.width, frame.height, samples,
            )
        } finally {
            frame.recycle()
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
        val silent = captureRealVisibleField()
        // Negative control: with no touch or PCM, this Canvas has no active
        // transition. If ambient or compositor drift can explain 24 changed
        // pixels, the positive response test must not be allowed to pass.
        Thread.sleep(150L)
        val silentRepeat = captureRealVisibleField()
        val baselineDrift = silent.countMeaningfullyChanged(silentRepeat)
        assertTrue(
            "Silent performance field moved by " + baselineDrift +
                " meaningful pixels before any PCM input; response cannot " +
                "be attributed to sound",
            baselineDrift < 24,
        )

        compose.runOnIdle {
            actualRms = .8f
            actualPeak = .93f
        }
        compose.waitForIdle()
        var changed = 0
        for (attempt in 1..4) {
            val active = captureRealVisibleField()
            changed = maxOf(changed, silentRepeat.countMeaningfullyChanged(active))
            if (changed >= 24 && changed > baselineDrift * 3) break
            Thread.sleep(130L)
        }
        assertTrue(
            "Actual Android framebuffer Canvas did not visibly respond to " +
                "the PCM telemetry. response=" + changed +
                " control=" + baselineDrift +
                " (requires >=24 and >3x silent drift)",
            changed >= 24 && changed > baselineDrift * 3,
        )
        println(
            "IMMERSIVE_NATIVE_PCM_PIXEL_PROOF changed=" + changed +
                " silentDrift=" + baselineDrift +
                " region=[" + silent.left + "," + silent.top + "][" +
                silent.right + "," + silent.bottom + "]",
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
        val proof = captureRealVisibleField()
        val lit = proof.colorfulPixels()
        assertTrue(
            "NaN/Infinity telemetry suppressed actual Canvas artwork; " +
                "only " + lit + " genuinely colorful native display pixels",
            lit >= 16,
        )
    }
}
