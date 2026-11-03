package com.orbital.synth.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.MainActivity
import androidx.compose.ui.unit.dp
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.dsp.processing.ModDestination
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A truthful, standalone 360dp component screenshot for engineering audit.
 * This is NOT a claim that the SOUND app already hosts the new hero. The
 * bitmap is Android UiAutomation's real API35 compositor framebuffer, with
 * saved parameter snapshots driving the actual production Composables.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceSoundHeroCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun markerPixels(bitmap: Bitmap): Int {
        var found = 0
        val row = IntArray(bitmap.width)
        // Sample low-cost 4px grid across the REAL full-screen framebuffer.
        for (y in 0 until bitmap.height step 4) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width step 4) {
                val pixel = row[x]
                if (PixelColor.red(pixel) > 240 &&
                    PixelColor.green(pixel) < 24 &&
                    PixelColor.blue(pixel) > 185
                ) found++
            }
        }
        return found
    }

    @Test fun captureActualAgslPlasmaAndLiveWidgetLayoutBeforeHostIntegration() {
        val parameters = mapOf(
            ParameterId.MORPH to .47f,
            ParameterId.MOVEMENT to .64f,
            ParameterId.WAVE to .42f,
            ParameterId.CUTOFF to .73f,
            ParameterId.RESONANCE to .35f,
            ParameterId.FX_AMOUNT to .78f,
            ParameterId.ATTACK to .15f,
            ParameterId.DECAY to .30f,
            ParameterId.SUSTAIN to .54f,
            ParameterId.RELEASE to .70f,
        )
        // Run the isolated hero pixel fixture in the truly foregrounded
        // production MainActivity window. ComponentActivity test windows on
        // this emulator remain background/focus=false even with valid Compose
        // nodes, which cannot prove actual SurfaceFlinger user-facing pixels.
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF01050F))) {
                ReferenceSoundHero(
                    parameters = parameters,
                    lfos = List(8) { ModulationLfoConfig(enabled = it == 0) },
                    routes = listOf(ModulationRouteConfig(
                        enabled = true,
                        destination = ModDestination.CUTOFF,
                        depth = .52f,
                    )) + List(63) { ModulationRouteConfig() },
                    onParameterChange = { _, _ -> },
                    compact = true,
                    patchName = "AURORA",
                    engineName = "ORBITAL WT",
                    modifier = Modifier.fillMaxWidth(),
                )
                // Diagnostic-only SurfaceFlinger sentinel. This small magenta
                // marker CANNOT exist on the original MainActivity SOUND
                // screen; it catches an unrelated foreground-frame capture.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 35.dp)
                        .size(20.dp)
                        .background(Color(0xFFF708DE))
                        .testTag("heroPixelFixtureSentinel"),
                )
            }
            }
        }
        compose.onNodeWithTag("orbitalPlasmaField").assertExists()
        val stageBounds = compose.onNodeWithTag("referenceSoundHeroOrb",
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val ribbonBounds = compose.onNodeWithTag("referenceExpressionRibbon",
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Real XY overlay must be inside the plasma hero",
            ribbonBounds.top > stageBounds.top &&
                ribbonBounds.bottom <= stageBounds.bottom + 2f &&
                ribbonBounds.height > 48f)
        compose.onNodeWithTag("referenceKnobFX_AMOUNT").assertExists()
        compose.onNodeWithTag("referenceMiniMod").assertExists()
        compose.onNodeWithTag("heroPixelFixtureSentinel").assertExists()
        compose.waitForIdle()
        val foregroundAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var foreground = awaitMainActivityForeground(foregroundAutomation)
        if (!foreground.isRealMainActivityForeground) {
            // Recover ONLY a foregrounded Pixel Launcher ANR, never a synth ANR.
            // WindowManager focus AND the active accessibility root must agree
            // on our real displayed MainActivity before any screenshot is valid.
            fun externalLauncherAnr(): Boolean {
                val dump = foregroundShell(foregroundAutomation, "dumpsys window")
                val current = dump.lineSequence()
                    .firstOrNull { it.contains("mCurrentFocus=") }.orEmpty()
                return dump.contains(
                    "Application Not Responding: com.google.android.apps.nexuslauncher",
                ) && !current.contains("com.orbital.synth.MainActivity")
            }
            if (externalLauncherAnr()) {
                println("REFERENCE_HERO_EXTERNAL_PIXEL_LAUNCHER_ANR_RECOVERY_BACK")
                foregroundShell(foregroundAutomation, "input keyevent KEYCODE_BACK")
                Thread.sleep(500L)
                if (!probeMainActivityForeground(foregroundAutomation).isRealMainActivityForeground &&
                    externalLauncherAnr()
                ) {
                    println("REFERENCE_HERO_EXTERNAL_PIXEL_LAUNCHER_ANR_RECOVERY_STOP")
                    foregroundShell(
                        foregroundAutomation,
                        "am force-stop com.google.android.apps.nexuslauncher",
                    )
                }
                // Reorder the EXISTING MainActivity: a replacement lacking
                // the magenta sentinel still fails the real-frame pixel check.
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
        if (!foreground.isRealMainActivityForeground) {
            throw AssertionError(
                "Real MainActivity screenshot fixture lacks OS-authoritative focus; " +
                    "decorFocus=" + compose.activity.hasWindowFocus() +
                    " decorShown=" + compose.activity.window.decorView.isShown +
                    " width=" + compose.activity.window.decorView.width +
                    " observed=" + foreground,
            )
        }
        println("REFERENCE_HERO_SCREENSHOT_FOCUS_VERIFIED MainActivity")

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        compose.mainClock.advanceTimeBy(300L)
        compose.waitForIdle()
        Thread.sleep(450L) // SurfaceFlinger presents AFTER Compose semantics.
        // Pixel sentinel is part of THIS fixture's actual Android framebuffer.
        // Compose nodes alone can exist while another Activity is onscreen;
        // accepting such a screenshot would falsify reference parity.
        val automation = instrumentation.uiAutomation
        var verifiedFrame: Bitmap? = null
        var verifiedMarkers = 0
        for (attempt in 1..16) {
            val frame = automation.takeScreenshot()
            if (frame != null) {
                val seen = markerPixels(frame)
                println("REFERENCE_HERO_SCREENSHOT_FRAME_ATTEMPT attempt=$attempt markerPixels=$seen")
                if (seen >= 50) {
                    verifiedFrame = frame
                    verifiedMarkers = seen
                    break
                }
                frame.recycle()
            }
            Thread.sleep(260L)
        }
        val bitmap = checkNotNull(verifiedFrame) {
            "UiAutomation captured a DIFFERENT foreground window than the real " +
                "ReferenceSoundHero fixture: magenta marker never reached SurfaceFlinger"
        }
        println("REFERENCE_HERO_SCREENSHOT_PROVENANCE_VERIFIED markerPixels=$verifiedMarkers")
        try {
            assertTrue("Real emulator screenshot must be portrait",
                bitmap.height > bitmap.width)
            assertTrue("Actual emulator phone dimensions expected",
                bitmap.width >= 600 && bitmap.height >= 1000)
            val file = File(compose.activity.filesDir, "orbital-reference-hero-standalone.png")
            FileOutputStream(file).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.fd.sync()
            }
            assertTrue("Missing visual comparison artifact", file.length() > 4096L)
        } finally {
            bitmap.recycle()
        }
    }
}
