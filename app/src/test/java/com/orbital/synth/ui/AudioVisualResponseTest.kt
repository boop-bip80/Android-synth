package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVisualResponseTest {
    @Test fun silenceLeavesOriginalReferenceControlsUnchanged() {
        val meter = AudioVisualResponse.from(0f, 0f, 0f, 0f)
        assertEquals(0f, meter.energyBoost)
        assertEquals(0f, meter.radiusBoost)
        assertEquals(0f, meter.filamentBoost)
        assertEquals(0f, meter.transient)
        assertEquals(0f, meter.brightness)
        assertFalse(meter.shouldAnimate(0f, true))
    }

    @Test fun measuredPcmLevelIncreasesVisualEnergyAndApertureMonotonically() {
        val quiet = AudioVisualResponse.from(.15f, .35f, .03f, .2f)
        val loud = AudioVisualResponse.from(.70f, .87f, .36f, .7f)
        assertTrue(loud.energyBoost > quiet.energyBoost)
        assertTrue(loud.radiusBoost > quiet.radiusBoost)
        assertTrue(loud.filamentBoost > quiet.filamentBoost)
        assertTrue(loud.shouldAnimate(0f, true))
    }

    @Test fun invalidAndOverflowingMetersNeverEscapeNormalizedBounds() {
        val meter = AudioVisualResponse.from(Float.NaN, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, 1000f)
        assertEquals(0f, meter.rms)
        assertEquals(0f, meter.peak)
        assertEquals(0f, meter.transient)
        assertEquals(1f, meter.brightness)
        assertTrue(meter.energyBoost in 0f..1f)
        assertTrue(meter.radiusBoost in 0f..1f)
        assertTrue(meter.filamentBoost in 0f..1f)
    }

    @Test fun reducedMotionOverridesTouchAndAudioActivity() {
        val meter = AudioVisualResponse.from(1f, 1f, 1f, 1f)
        assertFalse(meter.shouldAnimate(1f, false))
        assertTrue(meter.shouldAnimate(0f, true))
        assertTrue(AudioVisualResponse.from(0f, 0f, 0f, 0f).shouldAnimate(.5f, true))
        assertFalse(AudioVisualResponse.from(0f, 0f, 0f, 0f).shouldAnimate(0f, true))
    }

    @Test fun thresholdSuppressesTinyIdleNoiseWithoutHidingAudiblePeaks() {
        assertFalse(AudioVisualResponse.from(.004f, .01f, 0f, 0f).shouldAnimate(0f, true))
        assertTrue(AudioVisualResponse.from(.02f, .04f, 0f, .5f).shouldAnimate(0f, true))
        assertTrue(AudioVisualResponse.from(0f, 0f, .14f, 0f).shouldAnimate(0f, true))
    }

    @Test fun genuineTransientAndWaveformBrightnessRemainDistinctSignals() {
        val bass = AudioVisualResponse.from(.3f, .6f, .4f, .05f)
        val bright = AudioVisualResponse.from(.3f, .6f, .4f, .9f)
        assertEquals(bass.energyBoost, bright.energyBoost)
        assertTrue(bright.filamentBoost > bass.filamentBoost)
        assertEquals(bass.transient, bright.transient)
    }
}
