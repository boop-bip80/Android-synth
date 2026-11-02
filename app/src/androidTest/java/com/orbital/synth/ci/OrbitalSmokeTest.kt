package com.orbital.synth.ci

import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.MainActivity
import com.orbital.synth.core.OrbitalSynth
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbitalSmokeTest {
    @Test
    fun mainActivityLaunchesAndAttachesWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("Main activity must have a visible window", activity.window.decorView.isAttachedToWindow)
            }
        }
    }

    @Test
    fun mainActivitySurvivesBackgroundForegroundAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Three rapid focus cycles exercise AudioTrack/MIDI teardown and restart.
            // Each transition waits for the ActivityScenario lifecycle to settle.
            repeat(3) {
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    assertTrue(
                        "Foreground activity must retain its window after cycle " + it,
                        activity.window.decorView.isAttachedToWindow,
                    )
                }
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue("Recreated activity must have an attached window", activity.window.decorView.isAttachedToWindow)
            }
        }
    }

    @Test
    fun synthRendersBoundedStereoOnAndroid() {
        val synth = OrbitalSynth()
        val audio = FloatArray(512)
        synth.noteOn(69, 0.8f)
        synth.renderStereo(audio, 256)
        assertTrue("Output must be finite and bounded", audio.all { it.isFinite() && it in -1.01f..1.01f })
        assertTrue("Note-on must generate audible output", audio.any { kotlin.math.abs(it) > 0.00001f })
        synth.allNotesOff()
    }
}
