package com.orbital.synth.ui

import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.core.preset.SynthPatch
import com.orbital.synth.ui.aurora.LocalStagePalette
import com.orbital.synth.ui.aurora.StageThemeMode
import com.orbital.synth.ui.aurora.stagePaletteFor
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Real Book catalogue on the real, rendered AURORA stage, and the dark / normal looks.
 * Sound quality is not asserted here (the core tests render every preset through the engine);
 * this checks the flow a player takes: open songs, find a tune, PLAY it, and the stage is in
 * that tune's key with its own sound loaded. The look tests sample real framebuffer pixels.
 */
@RunWith(AndroidJUnit4::class)
class AuroraJazzInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = CopyOnWriteArrayList<Int>()
    private val applied = CopyOnWriteArrayList<SynthPatch>()
    private val dMajor = setOf(2, 4, 6, 7, 9, 11, 1)
    private val fMajor = setOf(5, 7, 9, 10, 0, 2, 4)

    private fun clearAuroraPreferences() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("orbital_aurora", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Before fun freshPreferences() = clearAuroraPreferences()

    @After fun leaveNoPreferencesBehind() = clearAuroraPreferences()

    private var mode by mutableStateOf(StageThemeMode.DARK)

    private fun render(withLookControls: Boolean = false) {
        val changeLook: (StageThemeMode) -> Unit = { chosen -> mode = chosen }
        compose.setContent {
            CompositionLocalProvider(LocalStagePalette provides stagePaletteFor(mode)) {
                ImmersiveStage(
                    state = OrbitalUiState(),
                    onNoteOn = { opened.add(it) },
                    onNoteOff = {},
                    onParameterChange = { _, _ -> },
                    onExit = {},
                    onApplyPatch = { applied.add(it) },
                    themeMode = mode,
                    onThemeChange = changeLook.takeIf { withLookControls },
                )
            }
        }
    }

    private fun openJazz() {
        compose.onNodeWithTag("auroraSongs").performClick()
        compose.onNodeWithTag("auroraTabJazz").performClick()
        compose.waitForIdle()
    }

    private fun search(text: String) {
        compose.onNodeWithTag("auroraJazzSearch").performTextInput(text)
        compose.waitForIdle()
    }

    /**
     * Plain (non-clickable) nodes inside the song sheet sit under its clickable container, which
     * merges their semantics. Address them in the unmerged tree, as the rest of this suite does;
     * clickable nodes (PLAY, the tabs, clear) remain their own nodes either way.
     */
    private fun plain(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun pitchClass(note: Int) = Math.floorMod(note, 12)

    /**
     * Waits until the playfield stops changing size. The stage deliberately re-voices a finger
     * that is already down when the grid layout changes (the lane under the finger changes), and
     * the grid depends on the playfield height, so a layout still settling after the sheet
     * closes (for instance the search keyboard animating away) would turn one touch into two
     * chords. A real player cannot touch within those few hundred milliseconds.
     */
    private fun awaitStablePlayfield(quietMillis: Long = 1_000L) {
        var lastHeight = -1
        var changedAt = SystemClock.elapsedRealtime()
        compose.waitUntil(15_000L) {
            val height = compose.onNodeWithTag("immersiveCanvas").fetchSemanticsNode().size.height
            val now = SystemClock.elapsedRealtime()
            if (height != lastHeight) {
                lastHeight = height
                changedAt = now
            }
            now - changedAt >= quietMillis
        }
    }

    private fun touchOnce(yFraction: Float) {
        awaitStablePlayfield()
        val canvas = compose.onNodeWithTag("immersiveCanvas")
        canvas.performTouchInput { down(Offset(center.x, height * yFraction)) }
        canvas.performTouchInput { up() }
    }

    @Test fun theJazzTabListsAllFourHundredTunesAndSearchNarrowsThem() {
        render()
        openJazz()
        plain("auroraJazzList").assertIsDisplayed()
        plain("auroraJazzCount").assertTextEquals("400 tunes")
        search("take five")
        plain("auroraJazz-take-five").assertIsDisplayed()
        plain("auroraJazzCount").assertTextEquals("1 of 400 tunes")
        compose.onNodeWithTag("auroraJazzSearchClear").performClick()
        compose.waitForIdle()
        plain("auroraJazzCount").assertTextEquals("400 tunes")
        search("zzzz-no-such-tune")
        plain("auroraJazzEmpty").assertIsDisplayed()
    }

    @Test fun playingATuneLoadsItsOwnSoundAndPutsTheStageInItsKeyWithJazzHarmony() {
        render()
        openJazz()
        search("wave")
        compose.onNodeWithTag("auroraJazzPlay-wave").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("auroraSongSheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, applied.size)
            assertEquals("rb1-wave", applied.single().id)
            assertEquals("Wave", applied.single().name)
            assertEquals(138f, applied.single().modulationTempoBpm, 0f)
        }
        // Wave is in D major, and the JAZZ harmony makes one finger a seventh chord.
        touchOnce(0.30f)
        compose.runOnIdle {
            assertEquals("a seventh chord is four notes: ${opened.map(::pitchClass)}", 4, opened.size)
            assertTrue("D major expected: ${opened.map(::pitchClass)}", opened.all { pitchClass(it) in dMajor })
        }
    }

    @Test fun aTuneWithNoRecordedKeyKeepsThePlayersKey() {
        render()
        compose.onNodeWithTag("auroraKey-5").performClick() // F
        compose.waitForIdle()
        openJazz()
        search("ruby")
        compose.onNodeWithTag("auroraJazzPlay-ruby-my-dear").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("rb1-ruby-my-dear", applied.single().id) }
        touchOnce(0.30f)
        compose.runOnIdle {
            assertTrue("the F major the player chose must stay: ${opened.map(::pitchClass)}", opened.all { pitchClass(it) in fMajor })
        }
    }

    @Test fun theStageIsDarkByDefaultAndPaleInNormalModeButFocusIsAlwaysBlack() {
        render(withLookControls = true)
        compose.waitForIdle()
        assertTrue("dark by default: ${medianLuminance()}", medianLuminance() < 0.25)

        // Switch with the look button in the song sheet: DARK -> NORMAL.
        compose.onNodeWithTag("auroraSongs").performClick()
        compose.onNodeWithTag("auroraTheme").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(StageThemeMode.LIGHT, mode) }
        compose.onNodeWithTag("auroraSongClose").performClick()
        compose.waitForIdle()
        assertTrue("normal mode is a pale surface: ${medianLuminance()}", medianLuminance() > 0.6)

        // FOCUS stays pure black whatever the look.
        compose.onNodeWithTag("stageFocusEnter").performClick()
        compose.waitForIdle()
        assertTrue("FOCUS must stay black in normal mode: ${medianLuminance()}", medianLuminance() < 0.1)
    }

    /** Median relative brightness of a grid of pixels (the median ignores a lane line or label). */
    private fun medianLuminance(): Float {
        val image = compose.onNodeWithTag("immersiveStage").captureToImage().toPixelMap()
        val samples = ArrayList<Float>()
        for (i in 1..5) for (j in 1..5) {
            val c: Color = image[image.width * i / 6, image.height * j / 6]
            samples += 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        }
        samples.sort()
        return samples[samples.size / 2]
    }
}
