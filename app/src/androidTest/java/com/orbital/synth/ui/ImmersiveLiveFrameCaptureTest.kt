package com.orbital.synth.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.MainActivity
import com.orbital.synth.audio.AndroidAudioOutput
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production MainActivity -> genuine finger-held note -> accepted AudioTrack
 * PCM -> Android UiAutomation framebuffer. Not a fake Compose screenshot.
 */
@RunWith(AndroidJUnit4::class)
class ImmersiveLiveFrameCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private data class PixelEvidence(val samples: Int, val visible: Int, val colorful: Int)

    private fun analyzeStage(bitmap: Bitmap): PixelEvidence {
        // Only the central performance canvas: exclude system bars and toolbars.
        val left = bitmap.width * 12 / 100
        val right = bitmap.width * 88 / 100
        val top = bitmap.height * 20 / 100
        val bottom = bitmap.height * 80 / 100
        // Near-pixel-resolution scan: the real neon filaments are only 1-3 px.
        // Row-oriented getPixels avoids thousands of per-pixel JNI read calls.
        val stepX = 3
        val stepY = 4
        val row = IntArray(bitmap.width)
        var samples = 0
        var visible = 0
        var colorful = 0
        for (y in top until bottom step stepY) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in left until right step stepX) {
                val pixel = row[x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val high = maxOf(r, g, b)
                samples++
                if (high >= 38) visible++
                if (high >= 80 && high - minOf(r, g, b) >= 27) colorful++
            }
        }
        return PixelEvidence(samples, visible, colorful)
    }

    private fun saveRealFrame(bitmap: Bitmap, name: String) {
        assertTrue("Actual framebuffer must be portrait",
            bitmap.width >= 600 && bitmap.height >= 1000 && bitmap.width < bitmap.height)
        val target = File(compose.activity.filesDir, name)
        FileOutputStream(target).use { output ->
            assertTrue("Real Android framebuffer PNG encoding failed",
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.fd.sync()
        }
        assertTrue("Real framebuffer PNG suspiciously small", target.length() > 4096L)
    }

    private fun realAudioTrack(): AndroidAudioOutput {
        // Read the one long-lived host audio worker without production test hooks.
        val field = MainActivity::class.java.getDeclaredField("audioOutput")
        field.isAccessible = true
        return field.get(compose.activity) as AndroidAudioOutput
    }

    @Test fun photographsVividStageDuringRealAudioTrackHeldNoteAndReleasesSafely() {
        val audio = realAudioTrack()
        compose.waitUntil(10_000L) { audio.isRunning }
        assertTrue("The actual Android audio worker must stream", audio.isRunning)

        compose.onNodeWithTag("immersiveModeToggle").performClick()
        compose.onNodeWithTag("immersiveStage").assertIsDisplayed()
        compose.onNodeWithTag("immersiveVisualField").assertExists()
        compose.onNodeWithTag("immersiveCanvas").assertExists()
        compose.onAllNodesWithContentDescription("Multitouch piano keyboard", substring = true)
            .assertCountEquals(0)

        // Compose semantics update before SurfaceFlinger presents the next frame.
        // Force multiple actual UI frames to settle before sampling the idle
        // stage; an immediate UiAutomation frame may still show the SOUND tab.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(180L)
        compose.waitForIdle()
        Thread.sleep(260L)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val idleBitmap = checkNotNull(automation.takeScreenshot()) {
            "Android UiAutomation could not capture the real idle stage"
        }
        val idle = try {
            saveRealFrame(idleBitmap, "orbital-stage-idle-proof.png")
            analyzeStage(idleBitmap)
        } finally {
            idleBitmap.recycle()
        }

        println("ORBITAL_STAGE_IDLE_DRAW_METRICS " + idle)
        assertTrue("Idle stage appears to be stale bright keyboard/editor instead of black performance canvas: " + idle,
            idle.visible * 5 < idle.samples * 4)
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        var held = false
        try {
            // Official Compose injection state persists across these calls:
            // the physical framebuffer capture happens while pointer is DOWN.
            canvas.performTouchInput { down(Offset(center.x * .72f, center.y * .66f)) }
            held = true
            Thread.sleep(170L)
            canvas.performTouchInput { moveTo(Offset(center.x * 1.24f, center.y * .59f)) }
            Thread.sleep(170L)
            canvas.performTouchInput { moveTo(Offset(center.x * .92f, center.y * .86f)) }

            // Real meter samples only PCM successfully submitted to AudioTrack.
            val deadline = System.nanoTime() + 6_000_000_000L
            var pcm = audio.audioVisualSnapshot
            while ((pcm.rms <= .002f || pcm.peak <= .004f) && System.nanoTime() < deadline) {
                Thread.sleep(90L)
                pcm = audio.audioVisualSnapshot
            }
            assertTrue("No non-silent PCM accepted by AudioTrack during held note",
                pcm.rms > .002f && pcm.peak > .004f)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(100L)
            compose.waitForIdle()
            Thread.sleep(220L)

            val liveBitmap = checkNotNull(automation.takeScreenshot()) {
                "Android UiAutomation failed during the live held-note gesture"
            }
            try {
                saveRealFrame(liveBitmap, "orbital-stage-held-performance.png")
                val live = analyzeStage(liveBitmap)
                println("ORBITAL_LIVE_STAGE_METRICS PCM rms=" + pcm.rms + " peak=" + pcm.peak +
                    " idle=" + idle + " live=" + live + " dimensions=" + liveBitmap.width + "x" + liveBitmap.height)
                File(compose.activity.filesDir, "orbital-stage-live-evidence.txt").writeText(
                    "audio_running=true\n" +
                    "note_held_during_capture=true\n" +
                    "audio_pcm_rms=" + pcm.rms + "\n" +
                    "audio_pcm_peak=" + pcm.peak + "\n" +
                    "audio_pcm_transient=" + pcm.transient + "\n" +
                    "idle_samples=" + idle.samples + "\n" +
                    "idle_visible=" + idle.visible + "\n" +
                    "idle_colorful=" + idle.colorful + "\n" +
                    "live_samples=" + live.samples + "\n" +
                    "live_visible=" + live.visible + "\n" +
                    "live_colorful=" + live.colorful + "\n" +
                    "live_dimensions=" + liveBitmap.width + "x" + liveBitmap.height + "\n"
                )
                assertTrue("Held-note frame must illuminate more real pixels than idle: idleVisible=" + idle.visible + " liveVisible=" + live.visible + " samples=" + live.samples,
                    live.visible >= idle.visible + maxOf(100, live.samples / 400))
                assertTrue("Held-note frame must add actual vivid chromatic filaments: idleColorful=" + idle.colorful + " liveColorful=" + live.colorful + " samples=" + live.samples,
                    live.colorful >= idle.colorful + maxOf(50, live.samples / 800))
            } finally {
                liveBitmap.recycle()
            }
        } finally {
            // Even if capture fails, actually release the injected contact.
            if (held) canvas.performTouchInput { up() }
        }

        compose.onNodeWithTag("immersiveExit").performClick()
        compose.onNodeWithTag("immersiveStage").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Multitouch piano keyboard", substring = true)
            .assertCountEquals(1)
        assertTrue("Exiting stage must leave the real audio worker alive", audio.isRunning)
    }
}
