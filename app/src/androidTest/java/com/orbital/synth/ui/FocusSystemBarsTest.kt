package com.orbital.synth.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.orbital.synth.MainActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.lifecycle.Lifecycle

/**
 * Full Android system-bar test, independent of Compose's pixel capture.
 * Requires API35 emulator. The screenshot is genuinely taken by UiAutomation.
 */
@RunWith(AndroidJUnit4::class)
class FocusSystemBarsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private data class Bars(val status: Boolean, val navigation: Boolean)

    private fun bars(): Bars? {
        var result: Bars? = null
        compose.activityRule.scenario.onActivity { activity ->
            val windowInsets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            if (windowInsets != null) {
                result = Bars(
                    windowInsets.isVisible(WindowInsetsCompat.Type.statusBars()),
                    windowInsets.isVisible(WindowInsetsCompat.Type.navigationBars()),
                )
            }
        }
        return result
    }

    private fun appHasWindowFocus(): Boolean {
        var focused = false
        compose.activityRule.scenario.onActivity {
            focused = it.window.decorView.hasWindowFocus()
        }
        return focused
    }

    private fun focusedWindowSummary(): String = runCatching {
        val pipe = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys window windows")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { stream ->
            stream.lineSequence()
                .filter { "mCurrentFocus" in it || "mFocusedApp" in it || "mTopFocused" in it }
                .take(6)
                .joinToString(" | ")
                .take(550)
        }
    }.getOrDefault("window focus dump unavailable")

    private fun behavior(): Int {
        var value = Int.MIN_VALUE
        compose.activityRule.scenario.onActivity { activity ->
            value = WindowCompat.getInsetsController(
                activity.window, activity.window.decorView,
            ).systemBarsBehavior
        }
        return value
    }

    private fun renderStage() {
        // Exercise the actual app window, not a transient generic Compose test
        // Activity that may never own SurfaceFlinger's display focus.
        compose.onNodeWithTag("immersiveModeToggle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("immersiveExit").assertIsDisplayed()
    }

    private fun enterFullScreen() {
        compose.onNodeWithTag("stageFocusEnter").performClick()
        compose.waitUntil(5_000L) { bars() == Bars(false, false) }
        compose.onNodeWithTag("immersiveCanvas").assertExists()
        compose.onNodeWithTag("immersiveExit").assertDoesNotExist()
    }

    private fun revealWithBack() {
        compose.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("immersiveExit").assertIsDisplayed()
    }

    @Test fun cleanCanvasHidesOSBarsAndRealFramebufferHasBlackEdgesThenRestores() {
        renderStage()
        compose.waitUntil(5_000L) { bars() != null }
        val before = checkNotNull(bars())
        val initialBehavior = behavior()
        // The standard stage may not mutate system bars on its own.
        assertTrue("Normal stage must not preemptively hide Android status/nav bars",
            before.status || before.navigation)

        enterFullScreen()
        Thread.sleep(1_250L) // Settled 950ms light fade plus SurfaceFlinger frame delivery.
        compose.waitForIdle()
        assertEquals(Bars(false, false), bars())

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.nanoTime() + 9_000_000_000L
        var accepted = false
        var attempts = 0
        var latestEvidence = "no captured focused framebuffer"
        // A Compose node/insets can be ready before the Activity actually owns
        // the display. Never accept a screenshot under Launcher/system focus.
        while (!accepted && attempts < 12 && System.nanoTime() < deadline) {
            attempts++
            compose.waitForIdle()
            Thread.sleep(220L)
            val focusedBeforeCapture = appHasWindowFocus()
            val barsBeforeCapture = bars()
            if (!focusedBeforeCapture || barsBeforeCapture != Bars(false, false)) {
                latestEvidence =
                    "attempt=$attempts rejected: foregroundFocus=$focusedBeforeCapture " +
                        "windowBars=$barsBeforeCapture"
                continue
            }

            val bitmap = automation.takeScreenshot()
            if (bitmap == null) {
                latestEvidence = "attempt=$attempts returned no real UiAutomation bitmap"
                continue
            }
            try {
                assertTrue("Real framebuffer must be portrait",
                    bitmap.width >= 600 && bitmap.height >= 1000 && bitmap.width < bitmap.height)

                val margin = (bitmap.height / 16).coerceAtMost(130)
                val strideX = (bitmap.width / 180).coerceAtLeast(3)
                val strideY = (margin / 12).coerceAtLeast(2)
                var topBright = 0
                var bottomBright = 0
                for (y in 0 until margin step strideY) {
                    for (x in 0 until bitmap.width step strideX) {
                        val top = bitmap.getPixel(x, y)
                        val bottom = bitmap.getPixel(x, bitmap.height - 1 - y)
                        if (maxOf(Color.red(top), Color.green(top), Color.blue(top)) > 24) topBright++
                        if (maxOf(Color.red(bottom), Color.green(bottom), Color.blue(bottom)) > 24) bottomBright++
                    }
                }
                fun rgb(x: Int, y: Int): String {
                    val pixel = bitmap.getPixel(x, y)
                    return "${Color.red(pixel)},${Color.green(pixel)},${Color.blue(pixel)}"
                }
                val centerX = bitmap.width / 2
                latestEvidence =
                    "attempt=$attempts top=$topBright bottom=$bottomBright " +
                        "topRGB=${rgb(centerX, margin / 2)} " +
                        "bottomRGB=${rgb(centerX, bitmap.height - margin / 2)} " +
                        "canvasRGB=${rgb(centerX, bitmap.height / 2)} " +
                        "focusBefore=$focusedBeforeCapture focusAfter=${appHasWindowFocus()} " +
                        "bars=${bars()} dimensions=${bitmap.width}x${bitmap.height}"
                println("ORBITAL_FOCUS_EDGE_EVIDENCE $latestEvidence")
                // The original strict requirement is retained: <=3 illuminated
                // sampled edge pixels, in a *genuinely focused* Activity frame.
                if (topBright + bottomBright <= 3) {
                    val out = File(compose.activity.filesDir, "orbital-focus-fullscreen-proof.png")
                    FileOutputStream(out).use { stream ->
                        assertTrue("Real PNG encode failed",
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                        stream.fd.sync()
                    }
                    assertTrue("Empty focus device screenshot", out.length() >= 4096L)
                    accepted = true
                }
            } finally {
                bitmap.recycle()
            }
        }
        assertTrue(
            "No valid focused black-edge Android framebuffer after $attempts attempts: " +
                "$latestEvidence; window=${focusedWindowSummary()}",
            accepted,
        )

        revealWithBack()
        compose.waitUntil(5_000L) { bars() == before }
        assertEquals("Host system-bar behavior must be restored", initialBehavior, behavior())
        compose.onNodeWithTag("immersiveExit").performClick()
        compose.onNodeWithTag("immersiveStage").assertDoesNotExist()
        assertEquals(before, bars())
    }

    @Test fun activityStopRestoresBarsAndResumeSafelyReentersFocus() {
        renderStage()
        val before = checkNotNull(bars())
        val beforeBehavior = behavior()
        enterFullScreen()

        // ON_STOP must restore normal window chrome even if Compose remains
        // mounted while the app is no longer foreground.
        val activity = compose.activity
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        var restoredBehavior = Int.MIN_VALUE
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // ActivityScenario.onActivity normally requires a resumed Activity;
            // inspect this *same* stopped window without resuming it.
            restoredBehavior = WindowCompat.getInsetsController(
                activity.window, activity.window.decorView,
            ).systemBarsBehavior
        }
        assertEquals("System-bar behavior must be restored on Activity stop",
            beforeBehavior, restoredBehavior)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000L) { bars() == Bars(false, false) }

        revealWithBack()
        compose.waitUntil(5_000L) { bars() == before }
        assertEquals(beforeBehavior, behavior())
    }
}
