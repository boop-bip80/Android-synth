package com.orbital.synth.ui

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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android compositor coverage: the exact visible Canvas is sampled from
 * UiAutomation rather than Compose PixelCopy, which may time out in API35
 * software graphics even while the real app remains healthy.
 */
@RunWith(AndroidJUnit4::class)
class ImmersiveVisualFieldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun realCanvas(): NativeCanvasPixelEvidence {
        val nodeBounds = compose.onNodeWithTag("immersiveVisualField")
            .assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2)
        val focused = BooleanArray(1)
        // A visible Compose node can briefly precede Android's input-focus
        // handoff on a fresh emulator Activity. Wait for genuine focus; never
        // accept a screenshot whose measured window stays unfocused.
        for (attempt in 1..8) {
            compose.runOnIdle {
                val window = compose.activity.window.decorView
                window.getLocationOnScreen(origin)
                focused[0] = window.hasWindowFocus()
            }
            if (focused[0]) break
            Thread.sleep(150L)
        }
        return NativeCanvasPixelCapture.capture(
            nodeBoundsInWindow = nodeBounds,
            decorScreenX = origin[0],
            decorScreenY = origin[1],
            expectedForegroundPackage = compose.activity.packageName,
            windowIsFocused = focused[0],
        )
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
        compose.waitForIdle()
        // A cold emulator may show a transitional Surface/AGSL frame. Wait
        // for TWO real, quiet Android display frames that agree; an unstable
        // scene remains a hard test failure rather than fake PCM evidence.
        val (silentRepeat, controlDrift) =
            NativeCanvasPixelCapture.awaitStableSilentFrame(
                initial = realCanvas(),
                takeNext = { realCanvas() },
                meaningfulChanges = { a, b -> a.countMaxChannelChanged(b) },
            )
        assertTrue(
            "Stable silent field is not genuine luminous artwork: " +
                silentRepeat.colorfulPixels() + " colorful pixels ROI=" +
                silentRepeat.regionLabel(),
            silentRepeat.colorfulPixels() >= 16,
        )

        compose.runOnIdle {
            actualRms = .8f
            actualPeak = .93f
        }
        compose.waitForIdle()
        var changed = 0
        for (attempt in 1..4) {
            val active = realCanvas()
            changed = maxOf(changed, silentRepeat.countMaxChannelChanged(active))
            if (changed >= 24 && changed > controlDrift * 3) break
            Thread.sleep(130L)
        }
        assertTrue(
            "Actual Android immersive Canvas did not visibly respond to " +
                "PCM telemetry: response=" + changed +
                ", control=" + controlDrift + " (must be >=24 and >3x)",
            changed >= 24 && changed > controlDrift * 3,
        )
        println(
            "IMMERSIVE_NATIVE_PCM_PIXEL_PROOF changed=" + changed +
                " silentDrift=" + controlDrift +
                " ROI=" + silentRepeat.regionLabel(),
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
        compose.waitForIdle()
        // A cold GPU Surface can initially appear black even while its
        // semantics node is displayed. Require genuine colored display
        // pixels within bounded frames; a persistently black Canvas fails.
        var proof = realCanvas()
        for (attempt in 1..8) {
            if (proof.colorfulPixels() >= 16) break
            Thread.sleep(150L)
            proof = realCanvas()
        }
        val lit = proof.colorfulPixels()
        assertTrue(
            "NaN/Infinity telemetry suppressed actual Canvas artwork: " +
                lit + " saturated display pixels ROI=" + proof.regionLabel(),
            lit >= 16,
        )
    }
}
