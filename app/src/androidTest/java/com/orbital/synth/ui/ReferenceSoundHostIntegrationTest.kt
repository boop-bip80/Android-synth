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
import androidx.compose.ui.test.assertIsDisplayed
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
        val marker = object : View(compose.activity) {
            private val paint = android.graphics.Paint()
            override fun onDraw(canvas: android.graphics.Canvas) {
                paint.color = PixelColor.MAGENTA
                canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
                paint.color = PixelColor.GREEN
                canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }.apply {
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
        var physicalMagentaPixels = 0
        var physicalGreenPixels = 0
        var exactMarkerVerified = false
        try {
            for (attempt in 0 until 6) {
                val frame: Bitmap? = foregroundAutomation.takeScreenshot()
                if (frame != null) {
                    try {
                        // Location is read on the Activity's actual UI thread.
                        // UiAutomation's display screenshot shares screen pixels
                        // with getLocationOnScreen, unlike Compose-local bounds.
                        val region = IntArray(4)
                        compose.activityRule.scenario.onActivity {
                            val topLeft = IntArray(2)
                            marker.getLocationOnScreen(topLeft)
                            region[0] = topLeft[0]
                            region[1] = topLeft[1]
                            region[2] = marker.width
                            region[3] = marker.height
                        }
                        val left = region[0]
                        val top = region[1]
                        val width = region[2]
                        val height = region[3]
                        if (width >= 20 && height >= 20) {
                            // Ignore edges and the two-tone seam, which can be
                            // antialiased by SurfaceFlinger or screenshot scaling.
                            val xLeftStart = maxOf(0, left + width / 10)
                            val xLeftEnd = minOf(frame.width, left + width * 4 / 10)
                            val xRightStart = maxOf(0, left + width * 6 / 10)
                            val xRightEnd = minOf(frame.width, left + width * 9 / 10)
                            val yStart = maxOf(0, top + height / 5)
                            val yEnd = minOf(frame.height, top + height * 4 / 5)
                            var leftSamples = 0
                            var rightSamples = 0
                            var magentaMatches = 0
                            var greenMatches = 0
                            for (y in yStart until yEnd step 2) {
                                for (x in xLeftStart until xLeftEnd step 2) {
                                    val pixel = frame.getPixel(x, y)
                                    leftSamples++
                                    if (PixelColor.red(pixel) >= 245 &&
                                        PixelColor.green(pixel) <= 10 &&
                                        PixelColor.blue(pixel) >= 245
                                    ) magentaMatches++
                                }
                                for (x in xRightStart until xRightEnd step 2) {
                                    val pixel = frame.getPixel(x, y)
                                    rightSamples++
                                    if (PixelColor.red(pixel) <= 10 &&
                                        PixelColor.green(pixel) >= 245 &&
                                        PixelColor.blue(pixel) <= 10
                                    ) greenMatches++
                                }
                            }
                            physicalMagentaPixels = magentaMatches
                            physicalGreenPixels = greenMatches
                            exactMarkerVerified = leftSamples >= 12 &&
                                rightSamples >= 12 &&
                                magentaMatches * 100 >= leftSamples * 80 &&
                                greenMatches * 100 >= rightSamples * 80
                            if (exactMarkerVerified) break
                        }
                    } finally {
                        frame.recycle()
                    }
                }
                Thread.sleep(180L)
            }
            assertTrue(
                "A different MainActivity owns SurfaceFlinger: the precise " +
                    "20dp magenta/green pixel signature is absent from the " +
                    "actual ActivityScenario View bounds; magenta=" +
                    "$physicalMagentaPixels green=$physicalGreenPixels " +
                    "OS=$foreground",
                exactMarkerVerified,
            )
        } finally {
            compose.activityRule.scenario.onActivity {
                (marker.parent as? ViewGroup)?.removeView(marker)
            }
        }
        println(
            "REFERENCE_HOST_INSTANCE_PIXEL_PROOF exact=true " +
                "magenta=$physicalMagentaPixels green=$physicalGreenPixels",
        )
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
            // Sample the REAL foreground SurfaceFlinger framebuffer, not
            // Compose PixelCopy. On Swangle API35, PixelCopy can time out
            // after a foreground-overlay removal even while actual UI and
            // accepted AudioTrack PCM are healthy. The magenta marker above
            // already proved this exact Activity instance owns that window.
            val orbWindow = compose.onNodeWithTag("referenceSoundHeroOrb")
                .fetchSemanticsNode().boundsInWindow
            val decorOrigin = IntArray(2)
            compose.runOnIdle {
                compose.activity.window.decorView.getLocationOnScreen(decorOrigin)
            }
            val realFrame: Bitmap = (0 until 4).firstNotNullOfOrNull {
                foregroundAutomation.takeScreenshot().also { frame ->
                    if (frame == null) Thread.sleep(170L)
                }
            } ?: throw AssertionError("Real foreground screenshot unavailable during PCM note")
            try {
                val originX = orbWindow.left + decorOrigin[0]
                val originY = orbWindow.top + decorOrigin[1]
                val orbWidth = orbWindow.width
                val orbHeight = orbWindow.height
                val left = (originX + orbWidth * .25f).toInt().coerceIn(0, realFrame.width)
                val right = (originX + orbWidth * .75f).toInt().coerceIn(0, realFrame.width)
                val top = (originY + orbHeight * .29f).toInt().coerceIn(0, realFrame.height)
                val bottom = (originY + orbHeight * .54f).toInt().coerceIn(0, realFrame.height)
                assertTrue(
                    "Measured live orb ROI is outside real foreground framebuffer",
                    right - left >= 48 && bottom - top >= 24,
                )
                var saturated = 0
                // Exclude HUD and XY. A black/broken shader cannot pass from
                // colorful text elsewhere because samples stay INSIDE orb.
                for (y in top until bottom step 7) {
                    for (x in left until right step 7) {
                        val color = realFrame.getPixel(x, y)
                        val maxChannel = maxOf(PixelColor.red(color), PixelColor.green(color), PixelColor.blue(color))
                        val minChannel = minOf(PixelColor.red(color), PixelColor.green(color), PixelColor.blue(color))
                        if (maxChannel - minChannel > 31 && maxChannel > 64) saturated++
                    }
                }
                assertTrue(
                    "Real accepted-PCM Android hero screenshot lost plasma: " +
                        "saturated=$saturated ROI=[$left,$top][$right,$bottom]",
                    saturated >= 24,
                )
                println("REFERENCE_HOST_PCM_ORB_REAL_FRAME saturation=$saturated " +
                    "ROI=[$left,$top][$right,$bottom] frame=${realFrame.width}x${realFrame.height}")
            } finally {
                realFrame.recycle()
            }
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
