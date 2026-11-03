package com.orbital.synth.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.roundToInt
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.MainActivity
import com.orbital.synth.audio.AndroidAudioOutput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end visual and audio-host proof for the actual production Activity.
 * Isolated plasma/knob/keybed tests cannot prove that SOUND ever mounts them.
 * GitHub Actions separately exports the emulator's real screen framebuffers.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceSoundHostIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun liveOutput(): AndroidAudioOutput {
        val field = MainActivity::class.java.getDeclaredField("audioOutput")
        field.isAccessible = true
        return field.get(compose.activity) as AndroidAudioOutput
    }

    @Test fun realActivityShowsPlasmaControlsAndNotesWithLivePcmThenKeepsOtherModesPlayable() {
        val audio = liveOutput()
        compose.waitUntil(10_000L) { audio.isRunning }
        // Actual Android window focus is required before treating this as
        // genuine visible-app pixel proof (Compose nodes alone are insufficient).
        val foregroundAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var foreground = awaitMainActivityForeground(foregroundAutomation)
        if (!foreground.isRealMainActivityForeground) {
            // An unrelated Pixel Launcher ANR is the ONLY recoverable overlay.
            // The OS focus and active accessibility window must BOTH identify
            // our actual MainActivity before any audio/pixel checks can pass.
            val focused = foregroundShell(foregroundAutomation, "dumpsys window")
                .lineSequence().firstOrNull { it.contains("mCurrentFocus=") }.orEmpty()
            if (!focused.contains("com.orbital.synth.MainActivity") &&
                focused.contains("Application Not Responding: com.google.android.apps.nexuslauncher")
            ) {
                foregroundShell(foregroundAutomation, "input keyevent KEYCODE_BACK")
                Thread.sleep(450L)
                if (!probeMainActivityForeground(foregroundAutomation)
                        .isRealMainActivityForeground
                ) {
                    val afterBack = foregroundShell(foregroundAutomation, "dumpsys window")
                    if (afterBack.contains(
                        "Application Not Responding: com.google.android.apps.nexuslauncher",
                    )) foregroundShell(
                        foregroundAutomation,
                        "am force-stop com.google.android.apps.nexuslauncher",
                    )
                }
                compose.activity.runOnUiThread {
                    compose.activity.startActivity(
                        Intent(compose.activity, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        },
                    )
                }
                foreground = awaitMainActivityForeground(foregroundAutomation, timeoutMs = 8_000L)
            }
        }
        assertTrue(
            "Production MainActivity lacks OS-authoritative foreground focus; " +
                "decorFocus=${compose.activity.hasWindowFocus()} observed=$foreground",
            foreground.isRealMainActivityForeground,
        )
        // Prove this exact ActivityScenario instance owns the *foreground*
        // window, not merely another MainActivity from an earlier test. A
        // nonfocusable View adds a tiny unique pixel probe to the existing
        // production Compose window; no setContent(), no fake audio, no second
        // synth. After real UiAutomation screenshot verification it is removed
        // before any SOUND gestures or screenshot assertions run.
        val marker = View(compose.activity).apply {
            setBackgroundColor(PixelColor.MAGENTA)
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val markerPx = (20f * compose.activity.resources.displayMetrics.density)
            .roundToInt().coerceAtLeast(20)
        compose.activityRule.scenario.onActivity { activity ->
            activity.addContentView(
                marker,
                FrameLayout.LayoutParams(
                    markerPx, markerPx, Gravity.END or Gravity.BOTTOM,
                ),
            )
        }
        var physicalMarkerPixels = 0
        try {
            for (attempt in 0 until 6) {
                val frame: Bitmap? = foregroundAutomation.takeScreenshot()
                if (frame != null) {
                    var matched = 0
                    for (y in (frame.height * .78f).toInt() until frame.height step 2) {
                        for (x in (frame.width * .82f).toInt() until frame.width step 2) {
                            val pixel = frame.getPixel(x, y)
                            if (PixelColor.red(pixel) >= 245 &&
                                PixelColor.green(pixel) <= 10 &&
                                PixelColor.blue(pixel) >= 245
                            ) matched++
                        }
                    }
                    frame.recycle()
                    physicalMarkerPixels = maxOf(physicalMarkerPixels, matched)
                    if (physicalMarkerPixels >= 50) break
                }
                Thread.sleep(180L)
            }
            assertTrue(
                "A different MainActivity owns SurfaceFlinger: this exact " +
                    "AudioTrack-backed test Activity marker absent from real " +
                    "bottom-right pixels; count=$physicalMarkerPixels OS=$foreground",
                physicalMarkerPixels >= 50,
            )
        } finally {
            compose.activityRule.scenario.onActivity {
                (marker.parent as? ViewGroup)?.removeView(marker)
            }
        }
        println("REFERENCE_HOST_INSTANCE_PIXEL_PROOF count=$physicalMarkerPixels")
        compose.onNodeWithTag("referenceSoundHero").assertExists()
        compose.onNodeWithTag("referenceSoundHeroOrb").assertIsDisplayed()
        compose.onNodeWithTag("orbitalPlasmaField").assertExists()
        compose.onNodeWithTag("referenceExpressionRibbon", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("referenceKnobCUTOFF").assertExists()
        compose.onNodeWithTag("referenceMiniSignals").assertExists()
        // Initially one visible hero; advanced engine/8-osc workbench and
        // honest patch graph remain explicitly accessible without duplicate UI.
        compose.onNodeWithTag("referenceSoundAdvancedToggle").assertExists()
        compose.onNodeWithTag("referenceSoundGraphToggle").assertExists()
        compose.onNodeWithTag("engineArchitecturePanel").assertDoesNotExist()
        compose.onNodeWithTag("referencePerformanceKeybed").assertIsDisplayed()
        val editor = compose.onNodeWithTag("soundEditorScroll")
            .fetchSemanticsNode().boundsInRoot
        val tiles = compose.onNodeWithTag("referenceMiniSignals")
            .fetchSemanticsNode().boundsInRoot
        val liveKeys = compose.onNodeWithTag("referencePerformanceKeybed")
            .fetchSemanticsNode().boundsInRoot
        val displayDensity = compose.activity.resources.displayMetrics.density
        assertTrue(
            "Real MainActivity first-screen ENV/LFO/EXPR/MOD plot tiles are clipped: " +
                "tiles=$tiles editor=$editor keys=$liveKeys",
            tiles.height >= 60f * displayDensity &&
                tiles.top >= editor.top && tiles.bottom <= editor.bottom - 2f * displayDensity &&
                tiles.bottom <= liveKeys.top - 2f * displayDensity,
        )

        val keys = compose.onNodeWithTag("referencePerformanceKeybed")
        keys.performTouchInput { down(center) }
        try {
            // The chromatic reference keybed really drives the one AudioTrack
            // engine: a held note must register in post-write PCM telemetry.
            compose.waitUntil(8_000L) { audio.audioVisualSnapshot.rms > .0005f }
            val pixels = compose.onNodeWithTag("referenceSoundHeroOrb")
                .captureToImage().toPixelMap()
            var saturated = 0
            // Validate the orb's interior, excluding the cyan HUD text at the
            // top and XY ribbon at the bottom. A black/broken shader cannot
            // pass simply because the interface's labels are colorful.
            val left = (pixels.width * .25f).toInt()
            val right = (pixels.width * .75f).toInt()
            val top = (pixels.height * .29f).toInt()
            val bottom = (pixels.height * .54f).toInt()
            for (y in top until bottom step 7) {
                for (x in left until right step 7) {
                    val color = pixels[x, y]
                    val spread = maxOf(color.red, color.green, color.blue) -
                        minOf(color.red, color.green, color.blue)
                    if (spread > .12f && maxOf(color.red, color.green, color.blue) > .25f) {
                        saturated++
                    }
                }
            }
            assertTrue(
                "Actual running Android hero framebuffer lost its colorful plasma",
                saturated >= 24,
            )
        } finally {
            keys.performTouchInput { up() }
        }

        // A live sound designer can reveal all 8 oscillator slots and the
        // single-host cable graph after playing, without losing anchored keys.
        compose.onNodeWithTag("referenceSoundAdvancedToggle")
            .performScrollTo().performClick()
        compose.onNodeWithTag("engineArchitecturePanel").assertExists()
        compose.onNodeWithTag("oscillatorRack").assertExists()
        compose.onNodeWithTag("referenceSoundGraphToggle")
            .performScrollTo().performClick()
        compose.onNodeWithTag("patchGraphPanel").assertExists()
        compose.onNodeWithTag("referencePerformanceKeybed").assertIsDisplayed()

        compose.onNodeWithTag("immersiveModeToggle").performClick()
        compose.onNodeWithTag("immersiveStage").assertIsDisplayed()
        compose.onNodeWithTag("referencePerformanceKeybed").assertDoesNotExist()
        compose.onNodeWithTag("immersiveExit").performClick()
        compose.onNodeWithTag("referencePerformanceKeybed").assertIsDisplayed()

        compose.onNodeWithContentDescription("Tab FX").performClick()
        compose.onNodeWithTag("modulationConstellationPanel").assertExists()
        compose.onNodeWithTag("modularFxWorkbenchPanel").assertExists()
        compose.onNodeWithContentDescription("Tab PLAY").performClick()
        compose.onNodeWithContentDescription(
            "Multitouch piano keyboard", substring = true,
        ).assertIsDisplayed()
        assertTrue("Audio worker stopped during UI navigation", audio.isRunning)
    }
}
