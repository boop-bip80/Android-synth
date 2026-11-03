package com.orbital.synth.ui

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
        val deadline = SystemClock.uptimeMillis() + 12_000L
        while (!compose.activity.hasWindowFocus() &&
            SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(120L)
        }
        if (!compose.activity.hasWindowFocus()) {
            // Do not confuse an unrelated Pixel Launcher ANR overlay with a
            // broken synth window, or accept unfocused Compose pixel captures.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            fun shell(command: String): String {
                val descriptor = automation.executeShellCommand(command)
                return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    .bufferedReader().use { it.readText() }
            }
            val focused = shell("dumpsys window").lineSequence()
                .firstOrNull { it.contains("mCurrentFocus=") }.orEmpty()
            if (!focused.contains("com.orbital.synth.MainActivity") &&
                focused.contains("Application Not Responding: com.google.android.apps.nexuslauncher")
            ) {
                shell("input keyevent KEYCODE_BACK")
                Thread.sleep(450L)
                if (!compose.activity.hasWindowFocus()) {
                    val afterBack = shell("dumpsys window")
                    if (afterBack.contains(
                        "Application Not Responding: com.google.android.apps.nexuslauncher",
                    )) shell("am force-stop com.google.android.apps.nexuslauncher")
                }
                compose.activity.runOnUiThread {
                    compose.activity.startActivity(
                        Intent(compose.activity, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        },
                    )
                }
                val recoveryDeadline = SystemClock.uptimeMillis() + 8_000L
                while (!compose.activity.hasWindowFocus() &&
                    SystemClock.uptimeMillis() < recoveryDeadline) Thread.sleep(120L)
            }
        }
        assertTrue("Production MainActivity never acquired real window focus",
            compose.activity.hasWindowFocus())
        compose.onNodeWithTag("referenceSoundHero").assertExists()
        compose.onNodeWithTag("referenceSoundHeroOrb").assertIsDisplayed()
        compose.onNodeWithTag("orbitalPlasmaField").assertExists()
        compose.onNodeWithTag("referenceExpressionRibbon").assertExists()
        compose.onNodeWithTag("referenceKnobCUTOFF").assertExists()
        compose.onNodeWithTag("referenceMiniSignals").assertExists()
        // Initially one visible hero; advanced engine/8-osc workbench and
        // honest patch graph remain explicitly accessible without duplicate UI.
        compose.onNodeWithTag("referenceSoundAdvancedToggle").assertExists()
        compose.onNodeWithTag("referenceSoundGraphToggle").assertExists()
        compose.onNodeWithTag("engineArchitecturePanel").assertDoesNotExist()
        compose.onNodeWithTag("referencePerformanceKeybed").assertIsDisplayed()

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
