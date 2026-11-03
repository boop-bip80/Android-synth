package com.orbital.synth.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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

        val bitmap = checkNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ) { "No live Android screenshot in full-screen FOCUS" }
        try {
            assertTrue("Real framebuffer must be portrait",
                bitmap.width >= 600 && bitmap.height >= 1000 && bitmap.width < bitmap.height)
            val out = File(compose.activity.filesDir, "orbital-focus-fullscreen-proof.png")
            FileOutputStream(out).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.fd.sync()
            }
            assertTrue("Empty focus device screenshot", out.length() >= 4096L)

            // The true black canvas must occupy even OS-bar edge regions.
            val margin = (bitmap.height / 16).coerceAtMost(130)
            val strideX = (bitmap.width / 180).coerceAtLeast(3)
            val strideY = (margin / 12).coerceAtLeast(2)
            var brightEdges = 0
            for (y in 0 until margin step strideY) {
                for (x in 0 until bitmap.width step strideX) {
                    val top = bitmap.getPixel(x, y)
                    val bottom = bitmap.getPixel(x, bitmap.height - 1 - y)
                    if (maxOf(Color.red(top), Color.green(top), Color.blue(top)) > 24) brightEdges++
                    if (maxOf(Color.red(bottom), Color.green(bottom), Color.blue(bottom)) > 24) brightEdges++
                }
            }
            assertTrue("FOCUS framebuffer contains residual OS bar/overlay artwork at edges: $brightEdges",
                brightEdges <= 3)
        } finally {
            bitmap.recycle()
        }

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
