package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVisualResponseTest {
    @Test fun equalLevelBassMiddleTrebleHaveDifferentSpatialVisualResponses() {
        val bass = AudioVisualResponse.from(.42f, .57f, 0f, .45f, .82f, .17f, .01f)
        val middle = AudioVisualResponse.from(.42f, .57f, 0f, .45f, .04f, .87f, .09f)
        val treble = AudioVisualResponse.from(.42f, .57f, 0f, .45f, .01f, .16f, .83f)
        assertEquals(bass.energyBoost, treble.energyBoost)
        assertEquals(bass.radiusBoost, middle.radiusBoost)
        assertTrue("LOW exposes amber outer aperture", bass.lowGold > .75f)
        assertTrue("MID braids purple ribbons", middle.midViolet > .80f)
        assertTrue("HIGH sharpens cyan filaments", treble.highCyan > .75f)
        assertTrue(bass.lowGold > middle.lowGold + .5f)
        assertTrue(middle.midViolet > treble.midViolet + .5f)
        assertTrue(treble.highCyan > bass.highCyan + .5f)
    }

    @Test fun absentOrSilentBandTelemetryNeverInventsSpectralArtwork() {
        val legacy = AudioVisualResponse.from(.55f, .8f, .2f, .6f)
        assertEquals(0f, legacy.lowGold)
        assertEquals(0f, legacy.midViolet)
        assertEquals(0f, legacy.highCyan)
        val silent = AudioVisualResponse.from(0f, 0f, 0f, .8f, 1f, 0f, 0f)
        assertEquals(0f, silent.lowGold)
        assertEquals(0f, silent.midViolet)
        assertEquals(0f, silent.highCyan)
        val malformed = AudioVisualResponse.from(
            .5f, .6f, 0f, .2f,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
        )
        assertEquals(0f, malformed.lowGold)
        assertEquals(0f, malformed.midViolet)
        assertEquals(0f, malformed.highCyan)
    }

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


    @Test fun silentTouchReleaseAllowsVisualClockToPauseImmediately() {
        val silence = AudioVisualResponse.from(0f, 0f, 0f, 0f)
        assertTrue(silence.shouldAnimate(.011f, true))
        assertFalse(silence.shouldAnimate(.01f, true))
        assertFalse(silence.shouldAnimate(0f, true))
    }

    @Test fun brightnessProxyAloneDoesNotImplyAudibleAnimationOrInventFftMotion() {
        val dcSlope = AudioVisualResponse.from(0f, 0f, 0f, 1f)
        assertFalse(dcSlope.shouldAnimate(0f, true))
        assertEquals(0f, dcSlope.energyBoost)
        assertEquals(0f, dcSlope.radiusBoost)
        assertTrue(dcSlope.filamentBoost > 0f)
    }

    @Test fun genuineTransientAndWaveformBrightnessRemainDistinctSignals() {
        val bass = AudioVisualResponse.from(.3f, .6f, .4f, .05f)
        val bright = AudioVisualResponse.from(.3f, .6f, .4f, .9f)
        assertEquals(bass.energyBoost, bright.energyBoost)
        assertTrue(bright.filamentBoost > bass.filamentBoost)
        assertEquals(bass.transient, bright.transient)
    }
}
