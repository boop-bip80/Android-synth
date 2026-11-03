package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The illustrative UI model follows the real wavetable family's morph algebra. */
class LayerWaveDisplaySamplerTest {
    @Test fun sineAtZeroMorphIsExactlyIndependentOfSelectedFamily() {
        for (phase in 0..250) {
            val time = phase / 250f * 2f
            val baseline = LayerWaveDisplaySampler.sample(time, 0f, 0f)
            for (family in 0..4) {
                assertEquals(baseline,
                    LayerWaveDisplaySampler.sample(time, family / 4f, 0f), 0f)
            }
        }
    }

    @Test fun everyFamilyAndInterpolationRemainsFiniteAndBounded() {
        for (wave in 0..20) {
            for (morph in 0..10) {
                for (phase in 0..64) {
                    val sample = LayerWaveDisplaySampler.sample(
                        phase / 64f, wave / 20f, morph / 10f,
                    )
                    assertTrue("Wave preview cannot show nonfinite signal", sample.isFinite())
                    assertTrue("Wave preview must be normalized: $sample", abs(sample) <= 1f)
                }
            }
        }
        assertEquals(0f, LayerWaveDisplaySampler.sample(Float.NaN, .5f, .7f), 0f)
        assertEquals(0f, LayerWaveDisplaySampler.sample(.1f, Float.POSITIVE_INFINITY, .7f), 0f)
    }

    @Test fun adjacentFamilyBoundaryIsContinuousAndTimbreActuallyChanges() {
        for (boundary in listOf(.25f, .50f, .75f)) {
            for (phase in 1..60) {
                val time = phase / 64f
                val below = LayerWaveDisplaySampler.sample(time, boundary - .0001f, 1f)
                val above = LayerWaveDisplaySampler.sample(time, boundary + .0001f, 1f)
                assertTrue("Family crossfade snapped at $boundary", abs(below - above) < .01f)
            }
        }
        var difference = 0f
        for (phase in 0..255) {
            val t = phase / 256f
            difference += abs(
                LayerWaveDisplaySampler.sample(t, .5f, 1f) -
                    LayerWaveDisplaySampler.sample(t, 0f, 1f)
            )
        }
        assertTrue("Saw-family reference must not be a renamed sine", difference > 15f)
    }
}
