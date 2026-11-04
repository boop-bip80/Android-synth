package com.orbital.synth.ui.aurora

import com.orbital.synth.core.stage.ChordAssist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraLayoutTest {
    @Test fun tallScreensShowTheWishedForRange() {
        // 686dp of playfield, seven-note scale: 22 lanes of 31dp fit three octaves.
        assertEquals(3, fitOctaves(wanted = 3, heightDp = 686f, stepsPerOctave = 7))
        assertEquals(2, fitOctaves(wanted = 2, heightDp = 900f, stepsPerOctave = 7))
    }

    @Test fun shorterScreensShowFewerOctavesInsteadOfUnhittableLanes() {
        assertEquals(2, fitOctaves(wanted = 3, heightDp = 586f, stepsPerOctave = 7))
        assertEquals(1, fitOctaves(wanted = 3, heightDp = 300f, stepsPerOctave = 7))
        assertEquals(1, fitOctaves(wanted = 4, heightDp = 120f, stepsPerOctave = 7))
    }

    @Test fun everyShownLaneStaysAtLeastTheMinimumHeight() {
        for (steps in intArrayOf(5, 6, 7, 8, 12)) {
            for (height in 150..1000 step 25) {
                val octaves = fitOctaves(wanted = 4, heightDp = height.toFloat(), stepsPerOctave = steps)
                val lanes = octaves * steps + 1
                // One octave is always allowed so something is playable on any screen.
                if (octaves > 1) assertTrue(
                    "steps=$steps height=$height lanes=$lanes",
                    height.toFloat() / lanes >= LANE_MIN_DP - 0.001f,
                )
            }
        }
    }

    @Test fun pentatonicScalesFitMoreOctavesInTheSameSpace() {
        val heptatonic = fitOctaves(4, 700f, 7)
        val pentatonic = fitOctaves(4, 700f, 5)
        assertTrue(pentatonic >= heptatonic)
    }

    @Test fun lessonsAllowDenserLanesSoEverySongFits() {
        // 8 lanes cannot hold the widest tunes (9 lanes); two octaves can.
        assertEquals(1, fitOctaves(3, 300f, 7, LANE_MIN_DP))
        assertEquals(2, fitOctaves(3, 380f, 7, LANE_MIN_DP_LESSON))
    }

    @Test fun invalidHeightsFallBackToTheWish() {
        assertEquals(3, fitOctaves(3, 0f, 7))
        assertEquals(3, fitOctaves(3, Float.NaN, 7))
        assertEquals(4, fitOctaves(99, -5f, 7))
        assertEquals(1, fitOctaves(-7, 0f, 7))
        assertEquals(2, fitOctaves(2, 500f, 0))
    }

    @Test fun preferencesWorkWithoutAnAndroidContext() {
        val prefs = AuroraPrefs()
        assertEquals(ChordAssist.OFF, prefs.chord)
        prefs.chord = ChordAssist.TRIAD
        assertEquals(ChordAssist.TRIAD, prefs.chord)
        assertEquals(0, prefs.bestStars("ode"))
        assertTrue(prefs.recordStars("ode", 2))
        assertTrue(!prefs.recordStars("ode", 1)) // a worse run is never recorded
        assertTrue(prefs.recordStars("ode", 3))
        assertEquals(3, prefs.bestStars("ode"))
        assertEquals(3, prefs.bestStars("ode"))
        assertEquals(0, prefs.bestStars("lamb"))
    }
}
