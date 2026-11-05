package com.orbital.synth.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.MainActivity
import com.orbital.synth.core.stage.DiatonicGrid
import com.orbital.synth.core.stage.GuideSession
import com.orbital.synth.core.stage.SongBook
import com.orbital.synth.ui.aurora.LANE_MIN_DP_LESSON
import com.orbital.synth.ui.aurora.auroraKeyOf
import com.orbital.synth.ui.aurora.fitOctaves
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genuine Android framebuffers of the living AURORA stage in the production app, with
 * Android animations switched ON so sparks, ripples and comet trails are really moving:
 *
 *  * `orbital-aurora-play.png`   - a held D-major chord on the HARP GLOW voice
 *  * `orbital-aurora-lesson.png` - a "follow the light" lesson: beacon, ribbon and combo
 *
 * The CI script `ci-aurora-gallery.sh` pulls these from the app's private files directory.
 * Nothing is rendered on the host. The render clock is advanced by hand (the same technique
 * as StageFlickPhysicalPixelsTest) because a live animation never lets Compose go idle.
 */
@RunWith(AndroidJUnit4::class)
class AuroraGalleryCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private var previousAnimatorScale = "0.0"

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }

    @Before fun enableRealAndroidMotion() {
        previousAnimatorScale = shell("settings get global animator_duration_scale")
            .toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..10f }?.toString() ?: "0.0"
        shell("settings put global animator_duration_scale 1.0")
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("orbital_aurora", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restoreAndroidMotion() {
        compose.mainClock.autoAdvance = true
        shell("settings put global animator_duration_scale $previousAnimatorScale")
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("orbital_aurora", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /** Capture the actual display, retrying until the production window really has focus. */
    private fun capture(name: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (!compose.activity.hasWindowFocus() && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(150L)
        }
        assertTrue("MainActivity must own the display to be photographed", compose.activity.hasWindowFocus())
        Thread.sleep(250L)
        val bitmap = checkNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
        ) { "UiAutomation could not capture $name" }
        try {
            assertTrue(bitmap.width >= 600 && bitmap.height >= 1000 && bitmap.width < bitmap.height)
            val target = File(compose.activity.filesDir, name)
            FileOutputStream(target).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.fd.sync()
            }
            assertTrue("screenshot suspiciously small", target.length() > 20_000L)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun capturesTheLivingStageAndALesson() {
        // A live animation never lets Compose go idle, so the clock is driven by hand from the start.
        compose.mainClock.autoAdvance = false
        advance(200)
        compose.onNodeWithTag("immersiveModeToggle").performClick()
        advance(300)
        compose.onNodeWithTag("immersiveStage").assertIsDisplayed()

        // HARP GLOW: the first stage voice after the default patch.
        compose.onNodeWithTag("auroraVoiceNext").performClick()
        advance(200)
        // The key of D, and full chords under one finger.
        compose.onNodeWithTag("auroraKey-2").performClick()
        advance(200)
        repeat(3) { compose.onNodeWithTag("auroraChord").performClick(); advance(80) }

        val canvas = compose.onNodeWithTag("immersiveCanvas")
        canvas.performTouchInput {
            down(0, Offset(width * 0.30f, height * 0.58f))
            down(1, Offset(width * 0.70f, height * 0.34f))
        }
        advance(140)
        canvas.performTouchInput {
            moveTo(0, Offset(width * 0.36f, height * 0.50f))
            moveTo(1, Offset(width * 0.64f, height * 0.30f))
        }
        advance(60)
        canvas.performTouchInput {
            moveTo(0, Offset(width * 0.42f, height * 0.44f))
            moveTo(1, Offset(width * 0.58f, height * 0.26f))
        }
        advance(420)
        capture("orbital-aurora-play.png")
        canvas.performTouchInput { up(0); up(1) }
        advance(900)

        // A lesson: the next note glows gold and the melody ribbon floats ahead of the player.
        compose.onNodeWithTag("auroraChord").performClick() // back to single notes for the lesson
        advance(80)
        compose.onNodeWithTag("auroraChord").performClick()
        advance(80)
        compose.onNodeWithTag("auroraSongs").performClick()
        advance(250)
        compose.onNodeWithTag("auroraLearn-ode").performClick()
        advance(400)
        compose.onNodeWithTag("auroraGuideHud").assertIsDisplayed()
        // Hit the glowing note for real: a gold burst, a combo, and the beacon moving on.
        val size = canvas.fetchSemanticsNode().size
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val key = auroraKeyOf(2, TouchScale.MAJOR)
        val grid = DiatonicGrid(
            key, 2, fitOctaves(3, size.height / density, key.stepsPerOctave, LANE_MIN_DP_LESSON),
        )
        val session = GuideSession(SongBook.byId("ode")!!, grid)
        val firstLane = session.target()!!.lane
        canvas.performTouchInput { down(0, Offset(width * 0.62f, grid.centerY(firstLane) * height)) }
        advance(170)
        capture("orbital-aurora-lesson.png")
        canvas.performTouchInput { up(0) }
        advance(300)
    }
}
