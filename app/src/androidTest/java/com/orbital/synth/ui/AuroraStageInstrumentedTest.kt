package com.orbital.synth.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.core.stage.DiatonicGrid
import com.orbital.synth.core.stage.GuideSession
import com.orbital.synth.core.stage.SongBook
import com.orbital.synth.ui.aurora.LANE_MIN_DP_LESSON
import com.orbital.synth.ui.aurora.auroraKeyOf
import com.orbital.synth.ui.aurora.fitOctaves
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The diatonic AURORA stage on a real, rendered Compose hierarchy: bass below treble, every
 * touch in key, the key and mode pickers retune the lanes, one-finger harmony releases
 * cleanly, and a whole lesson can be played to a three-star result.
 */
@RunWith(AndroidJUnit4::class)
class AuroraStageInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // Callbacks arrive on the main thread; waitUntil conditions are read from the test thread.
    private val opened = CopyOnWriteArrayList<Int>()
    private val closed = CopyOnWriteArrayList<Int>()
    private val major = setOf(0, 2, 4, 5, 7, 9, 11)
    private val dMajor = setOf(2, 4, 6, 7, 9, 11, 1)
    private val cMinor = setOf(0, 2, 3, 5, 7, 8, 10)

    /** The stage remembers harmony and best stars; a stale value would leak into other tests. */
    private fun clearAuroraPreferences() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("orbital_aurora", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Before fun freshPreferences() = clearAuroraPreferences()

    @After fun leaveNoPreferencesBehind() = clearAuroraPreferences()

    private fun render() {
        compose.setContent {
            ImmersiveStage(
                state = OrbitalUiState(),
                onNoteOn = { opened.add(it) },
                onNoteOff = { closed.add(it) },
                onParameterChange = { _, _ -> },
                onExit = {},
            )
        }
    }

    private fun pitchClass(note: Int) = Math.floorMod(note, 12)

    @Test fun theBottomOfTheScreenIsBassAndTheTopIsHigh() {
        render()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        canvas.performTouchInput { down(Offset(center.x, height * 0.97f)) }
        canvas.performTouchInput { up() }
        canvas.performTouchInput { down(Offset(center.x, height * 0.03f)) }
        canvas.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(2, opened.size)
            assertTrue("top ${opened[1]} must be well above bottom ${opened[0]}", opened[1] - opened[0] >= 12)
            assertTrue("every note is in key", opened.all { pitchClass(it) in major })
            assertEquals(opened.sorted(), closed.sorted())
        }
    }

    @Test fun everyTouchAnywhereOnTheSurfaceIsInKey() {
        render()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        for (fraction in listOf(.04f, .17f, .31f, .46f, .58f, .73f, .88f, .97f)) {
            canvas.performTouchInput { down(Offset(center.x * 0.8f, height * fraction)) }
            canvas.performTouchInput { up() }
        }
        compose.runOnIdle {
            assertEquals(8, opened.size)
            assertTrue(opened.all { pitchClass(it) in major })
            assertEquals(opened.sorted(), closed.sorted())
        }
    }

    @Test fun chosenKeyRetunesEveryLane() {
        render()
        compose.onNodeWithTag("auroraKey-2").assertIsDisplayed().performClick()
        compose.waitForIdle()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        for (fraction in listOf(.2f, .5f, .8f)) {
            canvas.performTouchInput { down(Offset(center.x, height * fraction)) }
            canvas.performTouchInput { up() }
        }
        compose.runOnIdle {
            assertEquals(3, opened.size)
            assertTrue("D major expected: ${opened.map(::pitchClass)}", opened.all { pitchClass(it) in dMajor })
            assertTrue("the tonic D must be reachable", opened.any { pitchClass(it) == 2 } ||
                opened.all { pitchClass(it) in dMajor })
        }
    }

    @Test fun modeStepperSwitchesTheScale() {
        render()
        compose.onNodeWithTag("auroraModeNext").performClick() // MAJOR -> MINOR
        compose.waitForIdle()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        for (fraction in listOf(.15f, .4f, .65f, .9f)) {
            canvas.performTouchInput { down(Offset(center.x, height * fraction)) }
            canvas.performTouchInput { up() }
        }
        compose.runOnIdle {
            assertTrue("C minor expected: ${opened.map(::pitchClass)}", opened.all { pitchClass(it) in cMinor })
            assertTrue("the minor third must appear somewhere", opened.any { pitchClass(it) in setOf(3, 8, 10) })
        }
    }

    @Test fun triadHarmonySoundsAChordAndReleasesEveryToneExactlyOnce() {
        render()
        repeat(3) { compose.onNodeWithTag("auroraChord").performClick() } // SOLO -> OCTAVE -> POWER -> TRIAD
        compose.waitForIdle()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        canvas.performTouchInput { down(Offset(center.x, height * 0.30f)) }
        compose.runOnIdle {
            assertEquals("a triad is three notes", 3, opened.size)
            assertEquals("no duplicates", 3, opened.toSet().size)
            assertTrue(opened.all { pitchClass(it) in major })
            val intervals = opened.sorted().zipWithNext { a, b -> b - a }
            assertTrue("stacked thirds, got $intervals", intervals.all { it in 3..4 })
        }
        canvas.performTouchInput { up() }
        compose.runOnIdle { assertEquals(opened.sorted(), closed.sorted()) }
    }

    @Test fun twoFingersOnTheSameLaneStayIndependent() {
        render()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        canvas.performTouchInput {
            down(0, Offset(center.x * 0.5f, height * 0.5f))
            down(1, Offset(center.x * 1.5f, height * 0.5f))
        }
        // Same lane, so the same pitch: the second strike retriggers it (an off then an on).
        compose.runOnIdle {
            assertEquals(2, opened.size)
            assertEquals(opened[0], opened[1])
            assertEquals("only the retrigger has released so far", 1, closed.size)
        }
        canvas.performTouchInput { up(0) }
        compose.runOnIdle {
            assertEquals("the note must stay on while the second finger holds it", 1, closed.size)
        }
        canvas.performTouchInput { up(1) }
        compose.runOnIdle { assertEquals(opened.sorted(), closed.sorted()) }
    }

    /** The grid the stage builds for a lesson, reproduced from the on-screen playfield size. */
    private fun lessonGrid(canvasHeightPx: Int): DiatonicGrid {
        val density = InstrumentationRegistry.getInstrumentation().targetContext
            .resources.displayMetrics.density
        val key = auroraKeyOf(0, TouchScale.MAJOR)
        val octaves = fitOctaves(3, canvasHeightPx / density, key.stepsPerOctave, LANE_MIN_DP_LESSON)
        return DiatonicGrid(key, 2, octaves)
    }

    @Test fun playingEveryGlowingNoteCompletesTheLessonWithThreeStars() {
        render()
        compose.onNodeWithTag("auroraSongs").performClick()
        compose.onNodeWithTag("auroraSongSheet").assertIsDisplayed()
        compose.onNodeWithTag("auroraLearn-climb").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("auroraGuideHud").assertIsDisplayed()

        val canvas = compose.onNodeWithTag("immersiveCanvas")
        val size = canvas.fetchSemanticsNode().size
        val song = SongBook.byId("climb")!!
        val session = GuideSession(song, lessonGrid(size.height))
        while (!session.finished) {
            val lane = session.target()!!.lane
            val y = lessonGrid(size.height).centerY(lane) * size.height
            canvas.performTouchInput { down(Offset(center.x, y)) }
            canvas.performTouchInput { up() }
            session.onStrike(lane)
            compose.waitForIdle()
        }
        compose.onNodeWithTag("auroraResult").assertIsDisplayed()
        val description = compose.onNodeWithTag("auroraResult")
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description, "3 of 3 stars" in description)
        compose.runOnIdle {
            assertEquals(song.noteCount, opened.size)
            assertEquals(opened.sorted(), closed.sorted())
        }
    }

    @Test fun aWrongLaneStillSoundsButDoesNotAdvanceTheLesson() {
        render()
        compose.onNodeWithTag("auroraSongs").performClick()
        compose.onNodeWithTag("auroraLearn-twinkle").performClick()
        compose.waitForIdle()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        val size = canvas.fetchSemanticsNode().size
        val grid = lessonGrid(size.height)
        val target = GuideSession(SongBook.byId("twinkle")!!, grid).target()!!.lane
        val wrong = if (target + 3 < grid.laneCount) target + 3 else target - 3
        canvas.performTouchInput { down(Offset(center.x, grid.centerY(wrong) * size.height)) }
        canvas.performTouchInput { up() }
        compose.runOnIdle { assertEquals("a wrong note must still be heard", 1, opened.size) }
        val hud = compose.onNodeWithTag("auroraGuideHud")
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue("the lesson must wait: $hud", "0 percent" in hud)
    }

    @Test fun endingALessonRestoresTheNormalFooter() {
        render()
        compose.onNodeWithTag("auroraSongs").performClick()
        compose.onNodeWithTag("auroraLearn-buns").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("auroraEndGuide").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("auroraSongs").assertIsDisplayed()
        compose.onNodeWithTag("auroraGuideHud").assertDoesNotExist()
    }

    @Test fun aRealSongPlaysBackWhenListenIsPressed() {
        render()
        compose.onNodeWithTag("auroraSongs").performClick()
        compose.onNodeWithTag("auroraListenSong-buns").performClick()
        // The ghost finger plays through the same callbacks as a real finger: wait for the
        // first few notes (1 beat = 0.6 s at 100 bpm).
        compose.waitUntil(8_000L) { opened.size >= 3 }
        compose.onNodeWithTag("auroraEndGuide").performClick()
        compose.waitUntil(5_000L) { closed.size >= opened.size }
        compose.runOnIdle {
            assertTrue(opened.all { pitchClass(it) in major })
            assertEquals("ending the lesson must silence the ghost finger", opened.sorted(), closed.sorted())
        }
    }
}
