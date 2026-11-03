package com.orbital.synth.ui

import android.graphics.Color as PixelColor
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genuine API35 foreground SurfaceFlinger pixels after two REAL Compose pointer
 * gestures. Same final position/pressure and zero PCM: only elapsed event-time
 * changes the visual-only flick speed. The 420ms draw animation is held at the
 * same frame by the Compose test clock, not by synthetic screenshot pixels.
 */
@RunWith(AndroidJUnit4::class)
class StageFlickPhysicalPixelsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun slowAndFastDragWithSamePressureProduceDifferentPhysicalLightShapes() {
        compose.setContent {
            ImmersiveStage(
                state = OrbitalUiState(),
                onNoteOn = {},
                onNoteOff = {},
                onParameterChange = { _, _ -> },
                onExit = {},
            )
        }
        compose.onNodeWithTag("stageFocusEnter").performClick()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        val field = compose.onNodeWithTag("immersiveVisualField")
        canvas.assertIsDisplayed()
        field.assertIsDisplayed()
        val capture = RealAndroidFrameCapture(compose, compose.activity)
        capture.capture(field, "focus-before-flick").close()

        val samplesPerAxis = 64
        fun sampledPhysicalFrame(label: String): IntArray = capture.capture(field, label).use { real ->
            IntArray(samplesPerAxis * samplesPerAxis) { sample ->
                val iy = sample / samplesPerAxis
                val ix = sample % samplesPerAxis
                val px = ((ix + .5f) * real.width / samplesPerAxis).toInt()
                    .coerceIn(0, real.width - 1)
                val py = ((iy + .5f) * real.height / samplesPerAxis).toInt()
                    .coerceIn(0, real.height - 1)
                real.argbAt(px, py)
            }
        }

        // Hold the *render clock* fixed between SurfaceFlinger captures. This
        // avoids proving only an unrelated infinite-transition phase change.
        compose.mainClock.autoAdvance = false
        try {
            val slowStart = Offset(canvas.fetchSemanticsNode().size.width * .35f,
                canvas.fetchSemanticsNode().size.height * .47f)
            val sameEndpoint = Offset(canvas.fetchSemanticsNode().size.width * .55f,
                canvas.fetchSemanticsNode().size.height * .47f)
            canvas.performTouchInput {
                down(0, slowStart)
                advanceEventTime(900L)
                moveTo(0, sameEndpoint)
            }
            compose.mainClock.advanceTimeBy(36L)
            val slowA = sampledPhysicalFrame("same-pressure-slow-flick-a")
            val slowB = sampledPhysicalFrame("same-pressure-slow-flick-b")
            canvas.performTouchInput { up(0) }
            compose.mainClock.advanceTimeBy(1_200L)

            canvas.performTouchInput {
                down(0, slowStart)
                advanceEventTime(20L)
                moveTo(0, sameEndpoint)
            }
            compose.mainClock.advanceTimeBy(36L)
            val fast = sampledPhysicalFrame("same-pressure-fast-flick")

            fun changedPixels(a: IntArray, b: IntArray): Pair<Int, Int> {
                var all = 0
                var withinOrb = 0
                for (y in 0 until samplesPerAxis) {
                    for (x in 0 until samplesPerAxis) {
                        val index = y * samplesPerAxis + x
                        val p = a[index]
                        val q = b[index]
                        val difference = abs(PixelColor.red(p) - PixelColor.red(q)) +
                            abs(PixelColor.green(p) - PixelColor.green(q)) +
                            abs(PixelColor.blue(p) - PixelColor.blue(q))
                        if (difference >= 25) {
                            all++
                            if (x in 14..50 && y in 19..44) withinOrb++
                        }
                    }
                }
                return all to withinOrb
            }
            val frozenClockDrift = changedPixels(slowA, slowB)
            val trueFlick = changedPixels(slowB, fast)
            println("ORBITAL_REAL_POINTER_FLICK_PIXELS samePressure " +
                "drift=$frozenClockDrift flick=$trueFlick")
            assertTrue(
                "A 20ms physical finger swipe should create visibly sharper " +
                    "real Android caustics than a 900ms swipe of identical " +
                    "distance/end position/pressure; drift=$frozenClockDrift " +
                    "speedPixels=$trueFlick",
                trueFlick.first > frozenClockDrift.first + 35 &&
                    trueFlick.second >= 15,
            )
        } finally {
            runCatching { canvas.performTouchInput { up(0) } }
            compose.mainClock.autoAdvance = true
        }
    }
}
