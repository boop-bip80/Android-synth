package com.orbital.synth.core.dsp.processing

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class StereoChorusTest {
    @Test fun zeroWetIsExactDryEvenWithModulationAndInvalidAudio() {
        val chorus = StereoChorus(8_000)
        chorus.configure(rateHz = 8f, depth = 1f, wet = 0f)
        val out = FloatArray(2)
        repeat(3_000) { i ->
            val sample = sin(i * 0.03).toFloat()
            chorus.process(sample, -sample, out, 0)
            assertEquals(sample, out[0], 0f)
            assertEquals(-sample, out[1], 0f)
        }
        chorus.process(Float.NaN, Float.POSITIVE_INFINITY, out, 0)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
    }

    @Test fun zeroDepthProducesExactSixteenMillisecondDelay() {
        val chorus = StereoChorus(8_000)
        chorus.configure(rateHz = 0.5f, depth = 0f, wet = 1f)
        val out = FloatArray(2)
        chorus.process(1f, -0.75f, out, 0)
        assertEquals(0f, out[0], 0f)
        repeat(127) {
            chorus.process(0f, 0f, out, 0)
            assertEquals(0f, out[0], 0f)
            assertEquals(0f, out[1], 0f)
        }
        chorus.process(0f, 0f, out, 0)
        assertEquals(1f, out[0], 0f)
        assertEquals(-0.75f, out[1], 0f)
        chorus.process(0f, 0f, out, 0)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
    }

    @Test fun leftAndRightChannelsAreIndependentAndWidthCreatesStereoMovement() {
        val chorus = StereoChorus(8_000)
        chorus.configure(rateHz = 4f, depth = 1f, wet = 1f, stereoWidth = 1f)
        val out = FloatArray(2)
        repeat(8_000) { i ->
            val wave = sin(i * 0.17).toFloat()
            chorus.process(wave, wave, out, 0)
            assertTrue(out[0].isFinite() && out[1].isFinite())
        }
        var difference = 0.0
        repeat(2_000) { i ->
            val wave = sin(i * 0.17).toFloat()
            chorus.process(wave, wave, out, 0)
            difference += abs((out[0] - out[1]).toDouble())
        }
        assertTrue("Wide stereo should create channel differences", difference > 10.0)

        chorus.reset()
        repeat(5_000) { i ->
            chorus.process(if (i == 0) 1f else 0f, 0f, out, 0)
            assertEquals("A left impulse must never leak to right", 0f, out[1], 0f)
        }
    }

    @Test fun logicalResetDoesNotReplayOldWetAudioAfterCircularWrapOrParameterChange() {
        val chorus = StereoChorus(8_000)
        val out = FloatArray(2)
        chorus.configure(rateHz = 8f, depth = 1f, wet = 1f)
        repeat(4_000) { chorus.process(1f, -1f, out, 0) }
        chorus.reset()
        chorus.configure(rateHz = Float.NaN, depth = Float.POSITIVE_INFINITY,
            wet = Float.NaN, stereoWidth = Float.NaN)
        // Invalid wet is dry; reconfigure at full wet after the reset.
        chorus.configure(rateHz = 0.02f, depth = 1f, wet = 1f, stereoWidth = 0f)
        repeat(5_000) {
            chorus.process(0f, 0f, out, 0)
            assertEquals(0f, out[0], 0f)
            assertEquals(0f, out[1], 0f)
        }
    }

    @Test fun aggressiveModulationAndControlsRemainFiniteAndBounded() {
        val chorus = StereoChorus(48_000)
        val out = FloatArray(2)
        chorus.configure(rateHz = Float.POSITIVE_INFINITY, depth = 4f,
            wet = 3f, stereoWidth = -2f)
        repeat(100_000) { i ->
            val left = if (i % 137 == 0) Float.NaN else sin(i * 0.05).toFloat() * 8f
            chorus.process(left, -left, out, 0)
            assertTrue(out[0].isFinite() && out[1].isFinite())
            assertTrue(abs(out[0]) <= 16f && abs(out[1]) <= 16f)
        }
    }

    @Test fun deterministicLfoRestartsAndStaysStableAcrossLongRenders() {
        val chorus = StereoChorus(8_000)
        chorus.configure(rateHz = 7.5f, depth = 1f, wet = 0.6f, stereoWidth = 1f)
        val out = FloatArray(2)
        val first = FloatArray(512 * 2)
        val warmup = 2_000
        repeat(warmup) { i -> chorus.process(sin(i * 0.03).toFloat(), 0f, out, 0) }
        repeat(512) { i ->
            chorus.process(sin((warmup + i) * 0.03).toFloat(), 0f, out, 0)
            first[i * 2] = out[0]
            first[i * 2 + 1] = out[1]
        }

        chorus.reset()
        repeat(warmup) { i -> chorus.process(sin(i * 0.03).toFloat(), 0f, out, 0) }
        repeat(512) { i ->
            chorus.process(sin((warmup + i) * 0.03).toFloat(), 0f, out, 0)
            assertEquals(first[i * 2], out[0], 0f)
            assertEquals(first[i * 2 + 1], out[1], 0f)
        }
    }

    @Test fun liveWetEditsSlewInsteadOfHardSwitchingAndEventuallyReachTargets() {
        val chorus = StereoChorus(8_000)
        val out = FloatArray(2)
        chorus.configure(rateHz = 0.6f, depth = 1f, wet = 0f)
        repeat(256) { chorus.process(0f, 0f, out, 0) }

        // Turning on 100% chorus should preserve almost all of the first dry attack,
        // instead of instantly replacing it with an empty 16 ms wet history.
        chorus.configure(rateHz = 0.6f, depth = 1f, wet = 1f)
        chorus.process(1f, 0f, out, 0)
        assertTrue("Wet jump removed the dry attack", out[0] > 0.95f)
        repeat(1_800) { chorus.process(0f, 0f, out, 0) }
        chorus.process(1f, 0f, out, 0)
        assertTrue("Wet glide did not settle", abs(out[0]) < 0.05f)

        chorus.configure(rateHz = 0.6f, depth = 1f, wet = 0f)
        chorus.process(1f, 0f, out, 0)
        assertTrue("Bypass jumped immediately", abs(out[0]) < 0.05f)
        repeat(1_800) { chorus.process(0f, 0f, out, 0) }
        chorus.process(1f, 0f, out, 0)
        assertTrue("Dry glide did not settle", out[0] > 0.95f)
    }

    @Test fun hardBypassDiscardsOldChorusHistoryAndRestartsWetFromDry() {
        val chorus = StereoChorus(8_000)
        val out = FloatArray(2)
        chorus.configure(rateHz = 0.5f, depth = 0f, wet = 1f)
        repeat(1_000) { chorus.process(1f, -1f, out, 0) }

        chorus.resetForBypass()
        repeat(400) {
            chorus.process(0f, 0f, out, 0)
            assertEquals("Bypassed chorus replayed old audio", 0f, out[0], 0f)
            assertEquals(0f, out[1], 0f)
        }

        // Fresh 100% wet target must fade in from zero rather than mute the
        // initial dry attack, even though the preceding preset was 100% wet.
        chorus.configure(rateHz = 0.5f, depth = 0f, wet = 1f)
        chorus.process(1f, 0f, out, 0)
        assertTrue("Wet gain inherited old preset instead of entering dry", out[0] > 0.95f)
        repeat(1_800) { chorus.process(0f, 0f, out, 0) }
        chorus.process(1f, 0f, out, 0)
        assertTrue("Wet fade did not settle", abs(out[0]) < 0.05f)
    }

    @Test fun bypassBeforeFirstConfigurationAlsoFadesIn() {
        val chorus = StereoChorus(8_000)
        chorus.resetForBypass()
        chorus.configure(rateHz = 1f, depth = 0f, wet = 1f)
        val out = FloatArray(2)
        chorus.process(1f, 0f, out, 0)
        assertTrue(out[0] > 0.95f)
    }
}
