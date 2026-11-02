package com.orbital.synth.core.dsp.processing

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FxBypassCrossfadeTest {
    @Test fun defaultAndHardResetAreExactlyDryEvenForInvalidWetSamples() {
        val fade = FxBypassCrossfade(48_000)
        val out = FloatArray(2)
        assertTrue(fade.isFullyDry)
        assertFalse(fade.requiresWetPath)
        fade.process(0.37f, -0.25f, Float.NaN, Float.POSITIVE_INFINITY, out, 0)
        assertEquals(0.37f, out[0], 0f)
        assertEquals(-0.25f, out[1], 0f)

        fade.reset(initiallyEnabled = true)
        assertEquals(1f, fade.wetFraction, 0f)
        fade.process(0f, 0f, 0.9f, -0.8f, out, 0)
        assertEquals(0.9f, out[0], 0f)
        assertEquals(-0.8f, out[1], 0f)

        fade.reset()
        fade.process(0.37f, -0.25f, Float.NaN, Float.NaN, out, 0)
        assertEquals(0.37f, out[0], 0f)
        assertEquals(-0.25f, out[1], 0f)
    }

    @Test fun tenMillisecondRampIsIndependentOfSampleRateAndReachesExactEndpoints() {
        for (rate in intArrayOf(8_000, 48_000, 96_000, 192_000)) {
            val fade = FxBypassCrossfade(rate)
            val frames = (rate / 100)
            assertEquals(frames, fade.transitionFrames)
            val out = FloatArray(2)
            fade.setEnabled(true)
            assertTrue(fade.requiresWetPath)
            repeat(frames / 2) { fade.process(0f, 0f, 1f, -1f, out, 0) }
            assertEquals(0.5f, fade.wetFraction, 0.01f)
            repeat(frames - frames / 2) { fade.process(0f, 0f, 1f, -1f, out, 0) }
            assertEquals(1f, fade.wetFraction, 0f)
            assertEquals(1f, out[0], 0f)
            assertEquals(-1f, out[1], 0f)

            fade.setEnabled(false)
            assertTrue("Wet must keep rendering during fade-out", fade.requiresWetPath)
            repeat(frames / 2) { fade.process(0f, 0f, 1f, -1f, out, 0) }
            assertEquals(0.5f, fade.wetFraction, 0.01f)
            repeat(frames - frames / 2) { fade.process(0f, 0f, 1f, -1f, out, 0) }
            assertEquals(0f, fade.wetFraction, 0f)
            assertEquals(0f, out[0], 0f)
            assertEquals(0f, out[1], 0f)
            assertTrue(fade.isFullyDry)
            assertFalse("Wet path should stop after exact endpoint", fade.requiresWetPath)
        }
    }

    @Test fun reversingMidRampIsContinuousAndRepeatedTargetsDoNotRestartFade() {
        val fade = FxBypassCrossfade(8_000)
        val out = FloatArray(2)
        fade.setEnabled(true)
        repeat(20) {
            fade.setEnabled(true) // called every block: must not restart fade
            fade.process(0f, 0f, 1f, 1f, out, 0)
        }
        assertEquals(0.25f, out[0], 0.01f)
        val beforeReverse = out[0]
        fade.setEnabled(false)
        fade.process(0f, 0f, 1f, 1f, out, 0)
        assertTrue("Reverse caused an audible step", abs(out[0] - beforeReverse) < 0.01f)
        repeat(79) { fade.process(0f, 0f, 1f, 1f, out, 0) }
        assertEquals(0f, out[0], 0f)
        assertTrue(fade.isFullyDry)

        fade.setEnabled(true)
        repeat(80) { fade.process(0f, 0f, 1f, 1f, out, 0) }
        assertEquals(1f, out[0], 0f)
    }

    @Test fun equalDryAndWetDoNotSufferCenterGainLossOrBoost() {
        val fade = FxBypassCrossfade(8_000)
        val out = FloatArray(2)
        fade.setEnabled(true)
        repeat(80) {
            fade.process(0.35f, -0.75f, 0.35f, -0.75f, out, 0)
            assertEquals(0.35f, out[0], 0.000001f)
            assertEquals(-0.75f, out[1], 0.000001f)
        }
    }

    @Test fun extremeAndInvalidInputIsFiniteBoundedAndStereoIndependent() {
        val fade = FxBypassCrossfade(48_000)
        val out = FloatArray(4)
        fade.setEnabled(true)
        repeat(1_000) { i ->
            val dryL = if (i % 2 == 0) Float.NaN else 100f
            val dryR = if (i % 3 == 0) Float.POSITIVE_INFINITY else -100f
            fade.process(dryL, dryR, -100f, Float.NEGATIVE_INFINITY, out, 2)
            assertTrue(out[2].isFinite() && out[3].isFinite())
            assertTrue(abs(out[2]) <= 1f && abs(out[3]) <= 1f)
            assertEquals(0f, out[0], 0f)
            assertEquals(0f, out[1], 0f)
        }
    }
}
