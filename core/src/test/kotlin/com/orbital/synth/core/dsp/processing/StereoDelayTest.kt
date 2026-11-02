package com.orbital.synth.core.dsp.processing

import org.junit.Assert.*
import org.junit.Test

class StereoDelayTest {
    @Test fun exactOneSampleDelayAndIndependentChannels() {
        val delay = StereoDelay(8_000, 0.1f)
        delay.configure(1f / 8_000f, feedback = 0f, wet = 1f, pingPong = 0f)
        val out = FloatArray(2)
        delay.process(1f, 0f, out, 0)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
        delay.process(0f, 0f, out, 0)
        assertEquals(1f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
        delay.process(0f, 0f, out, 0)
        assertEquals(0f, out[0], 0f)
        delay.reset()
        delay.process(0f, 0f, out, 0)
        assertEquals(0f, out[0], 0f)
    }

    @Test fun denseFeedbackNeverProducesNaN() {
        val delay = StereoDelay(48_000)
        delay.configure(Float.NaN, feedback = 100f, wet = 100f, pingPong = 1f)
        val out = FloatArray(2)
        repeat(30_000) {
            delay.process(if (it == 0) 1f else 0f, if (it == 10) -1f else 0f, out, 0)
            assertTrue(out[0].isFinite())
            assertTrue(out[1].isFinite())
            assertTrue(kotlin.math.abs(out[0]) <= 32f)
            assertTrue(kotlin.math.abs(out[1]) <= 32f)
        }
    }

    @Test fun resetNeverReplaysOldEchoesAcrossShortLongDelayChangesAndWrap() {
        val delay = StereoDelay(8_000, maxDelaySeconds = 0.01f)
        val out = FloatArray(2)
        // Poison the entire circular buffer several times, including both channels.
        delay.configure(0.001f, feedback = 0.9f, wet = 1f, pingPong = 1f)
        repeat(500) {
            delay.process(1f, -0.8f, out, 0)
        }
        for (tapSeconds in floatArrayOf(0.000125f, 0.010f, 0.002f, 0.008f)) {
            delay.reset()
            delay.configure(tapSeconds, feedback = 0.9f, wet = 1f, pingPong = 1f)
            // More than twice the full ring length, so modulo wrap cannot leak history.
            repeat(250) {
                delay.process(0f, 0f, out, 0)
                assertEquals("Old left echo escaped logical reset", 0f, out[0], 0f)
                assertEquals("Old right echo escaped logical reset", 0f, out[1], 0f)
            }
            // Re-poison before next reset, rather than testing already-zero buffers.
            repeat(200) { delay.process(0.8f, -0.6f, out, 0) }
        }
    }

    @Test fun increasingDelayAfterResetWaitsForValidFramesButRetainsNewImpulse() {
        val delay = StereoDelay(8_000, maxDelaySeconds = 0.01f)
        val out = FloatArray(2)
        delay.configure(0.000125f, feedback = 0f, wet = 1f, pingPong = 0f)
        repeat(250) { delay.process(1f, -1f, out, 0) }
        delay.reset()

        // The new impulse must not echo until the 64-sample tap reaches new history.
        delay.configure(0.008f, feedback = 0f, wet = 1f, pingPong = 0f)
        delay.process(1f, 0f, out, 0)
        assertEquals(0f, out[0], 0f)
        repeat(4) {
            delay.process(0f, 0f, out, 0)
            assertEquals(0f, out[0], 0f)
        }
        // Briefly shorten the tap; it must read new zeros, not old poisoned samples.
        delay.configure(0.000125f, feedback = 0f, wet = 1f, pingPong = 0f)
        delay.process(0f, 0f, out, 0)
        assertEquals(0f, out[0], 0f)

        delay.configure(0.008f, feedback = 0f, wet = 1f, pingPong = 0f)
        repeat(58) {
            delay.process(0f, 0f, out, 0)
            assertEquals(0f, out[0], 0f)
        }
        // At sample index 64 the original post-reset impulse is exactly 64 frames
        // old. Delay-time automation is now crossfaded, so it appears at a
        // partial gain rather than as an instantaneous full-amplitude jump.
        delay.process(0f, 0f, out, 0)
        assertTrue("Time automation lost a valid new-generation impulse", out[0] > 0.15f)
        assertTrue(out[0] <= 1f)
        assertEquals(0f, out[1], 0f)
    }


    @Test fun wetChangesUseClickResistantGlideAndReachExactEndpoints() {
        val delay = StereoDelay(8_000, 0.01f)
        val out = FloatArray(2)
        delay.configure(1f / 8_000f, feedback = 0f, wet = 0f, pingPong = 0f)
        repeat(128) { delay.process(0f, 0f, out, 0) }

        delay.configure(1f / 8_000f, feedback = 0f, wet = 1f, pingPong = 0f)
        delay.process(1f, 0f, out, 0)
        assertTrue("Wet switch muted the initial dry attack", out[0] > 0.95f)
        repeat(1_200) { delay.process(0f, 0f, out, 0) }
        delay.process(1f, 0f, out, 0)
        assertTrue("Fully wet glide did not settle", kotlin.math.abs(out[0]) < 0.05f)
        // Drain the one-frame tap from the preceding full-wet attack before
        // measuring wet->dry gain smoothing. Otherwise its legitimate echo on
        // the next sample is mistaken for a discontinuous wet-control change.
        delay.process(0f, 0f, out, 0)
        delay.process(0f, 0f, out, 0)

        delay.configure(1f / 8_000f, feedback = 0f, wet = 0f, pingPong = 0f)
        delay.process(1f, 0f, out, 0)
        assertTrue("Wet bypass changed discontinuously", kotlin.math.abs(out[0]) < 0.05f)
        repeat(1_200) { delay.process(0f, 0f, out, 0) }
        delay.process(1f, 0f, out, 0)
        assertTrue("Fully dry glide did not settle", out[0] > 0.95f)
    }

    @Test fun feedbackAutomationPreservesExistingTailForFirstFrames() {
        val delay = StereoDelay(8_000, 0.01f)
        val out = FloatArray(2)
        delay.configure(1f / 8_000f, feedback = 0.92f, wet = 1f, pingPong = 0f)
        delay.process(1f, 0f, out, 0)
        delay.configure(1f / 8_000f, feedback = 0f, wet = 1f, pingPong = 0f)
        delay.process(0f, 0f, out, 0)
        assertEquals(1f, out[0], 0f)
        delay.process(0f, 0f, out, 0)
        assertTrue("Feedback cut killed a live echo instantly", out[0] > 0.8f)
        repeat(1_200) {
            delay.process(0f, 0f, out, 0)
            assertTrue(out[0].isFinite() && out[1].isFinite())
        }
    }

    @Test fun hardBypassForgetsOldDelayTailAndRestartsWetFromDry() {
        val delay = StereoDelay(8_000, maxDelaySeconds = 0.01f)
        val out = FloatArray(2)
        delay.configure(1f / 8_000f, feedback = 0.92f, wet = 1f, pingPong = 1f)
        repeat(400) { delay.process(0.75f, -0.6f, out, 0) }

        delay.resetForBypass()
        repeat(200) {
            delay.process(0f, 0f, out, 0)
            assertEquals("Bypassed delay replayed an old echo", 0f, out[0], 0f)
            assertEquals(0f, out[1], 0f)
        }

        delay.configure(1f / 8_000f, feedback = 0.3f, wet = 1f, pingPong = 0f)
        delay.process(1f, 0f, out, 0)
        assertTrue("Wet gain inherited the previous preset", out[0] > 0.95f)
        repeat(1_200) { delay.process(0f, 0f, out, 0) }
        delay.process(1f, 0f, out, 0)
        assertTrue("Wet gain did not settle", kotlin.math.abs(out[0]) < 0.05f)
    }

    @Test fun bypassBeforeInitialConfigurePreventsAnAbruptWetJump() {
        val delay = StereoDelay(8_000, maxDelaySeconds = 0.01f)
        delay.resetForBypass()
        delay.configure(1f / 8_000f, feedback = 0f, wet = 1f)
        val out = FloatArray(2)
        delay.process(1f, 0f, out, 0)
        assertTrue(out[0] > 0.95f)
    }

    @Test fun delayTimeAutomationCrossfadesOldAndNewTapsWithoutInstantJump() {
        val delay = StereoDelay(8_000, maxDelaySeconds = 0.05f)
        val out = FloatArray(2)
        delay.configure(1f / 8_000f, feedback = 0f, wet = 1f, pingPong = 0f)
        repeat(200) { delay.process(0f, 0f, out, 0) }
        repeat(24) { delay.process(1f, 0f, out, 0) }

        // At this instant the 1-sample tap is one, while the 128-sample tap
        // reads zero. A direct tap switch would immediately drop wet audio.
        delay.configure(128f / 8_000f, feedback = 0f, wet = 1f, pingPong = 0f)
        delay.process(0f, 0f, out, 0)
        assertEquals("First sample must preserve the previous tap", 1f, out[0], 0f)
        var previous = out[0]
        repeat(8) {
            delay.process(0f, 0f, out, 0)
            assertTrue("Tap transition stepped upward", out[0] <= previous)
            previous = out[0]
        }
        assertTrue("Time did not begin fading across taps", previous < 0.95f)
        assertTrue("Transition jumped to the new tap", previous > 0.85f)
    }

    @Test fun repeatedTimeEditsDoNotRestartAnActiveFade() {
        val delay = StereoDelay(8_000, maxDelaySeconds = 0.05f)
        val out = FloatArray(2)
        delay.configure(1f / 8_000f, feedback = 0f, wet = 1f, pingPong = 0f)
        repeat(200) { delay.process(0f, 0f, out, 0) }
        repeat(24) { delay.process(1f, 0f, out, 0) }
        delay.configure(128f / 8_000f, feedback = 0f, wet = 1f)
        delay.process(0f, 0f, out, 0)
        assertEquals(1f, out[0], 0f)

        // An update while fading must be queued: restarting from the newly
        // selected (silent) tap would cause a large discontinuity on this frame.
        delay.configure(64f / 8_000f, feedback = 0f, wet = 1f)
        delay.process(0f, 0f, out, 0)
        assertTrue("A second control edit interrupted the current fade", out[0] > 0.97f)

        repeat(300) { frame ->
            if (frame % 3 == 0) {
                delay.configure(
                    if (frame % 2 == 0) 32f / 8_000f else 160f / 8_000f,
                    feedback = 0.6f, wet = 1f
                )
            }
            delay.process(0f, 0f, out, 0)
            assertTrue(out[0].isFinite() && out[1].isFinite())
            assertTrue(kotlin.math.abs(out[0]) <= 32f)
        }
    }

    @Test fun resetDuringTimeTransitionForgetsPreviousBufferGeneration() {
        val delay = StereoDelay(8_000, maxDelaySeconds = 0.05f)
        val out = FloatArray(2)
        delay.configure(1f / 8_000f, feedback = 0.92f, wet = 1f)
        repeat(500) { delay.process(1f, -1f, out, 0) }
        delay.configure(120f / 8_000f, feedback = 0.92f, wet = 1f)
        delay.process(0f, 0f, out, 0)
        delay.reset()
        repeat(600) {
            delay.process(0f, 0f, out, 0)
            assertEquals(0f, out[0], 0f)
            assertEquals(0f, out[1], 0f)
        }
    }

}
