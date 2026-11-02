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
        // At sample index 64, the original post-reset impulse is exactly 64 frames old.
        delay.process(0f, 0f, out, 0)
        assertEquals(1f, out[0], 0f)
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
}
