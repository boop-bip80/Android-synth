package com.orbital.synth.core.dsp.processing

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoReverbTest {
    @Test fun zeroWetIsAnExactDryPath() {
        val reverb = StereoReverb(8_000)
        reverb.configure(decaySeconds = 2f, damping = 0.4f, wet = 0f)
        val out = FloatArray(2)
        repeat(2_000) { frame ->
            val left = if (frame % 17 == 0) 0.63f else -0.11f
            val right = if (frame % 11 == 0) -0.87f else 0.25f
            reverb.process(left, right, out, 0)
            assertEquals(left, out[0], 0f)
            assertEquals(right, out[1], 0f)
        }
    }

    @Test fun fullyWetImpulseProducesAFiniteTwoChannelTail() {
        val reverb = StereoReverb(8_000)
        reverb.configure(2.5f, damping = 0.35f, wet = 1f, diffusion = 1f)
        val out = FloatArray(2)
        var energyL = 0.0
        var energyR = 0.0
        var lateEnergy = 0.0
        repeat(16_000) { frame ->
            reverb.process(if (frame == 0) 1f else 0f, 0f, out, 0)
            assertTrue(out[0].isFinite() && out[1].isFinite())
            assertTrue(abs(out[0]) <= 16f && abs(out[1]) <= 16f)
            energyL += out[0].toDouble() * out[0]
            energyR += out[1].toDouble() * out[1]
            if (frame > 4_000) {
                lateEnergy += out[0].toDouble() * out[0] + out[1].toDouble() * out[1]
            }
        }
        assertTrue("FDN must produce a left-channel tail", energyL > 1e-6)
        assertTrue("FDN must crossfeed into the right channel", energyR > 1e-6)
        assertTrue("tail must survive beyond an early reflection", lateEnergy > 1e-12)
    }

    @Test fun zeroWidthMakesTheWetFieldExactlyMono() {
        val reverb = StereoReverb(8_000)
        reverb.configure(3f, damping = 0.6f, wet = 1f, diffusion = 0.9f, stereoWidth = 0f)
        val out = FloatArray(2)
        repeat(4_000) { frame ->
            reverb.process(if (frame == 0) 1f else 0f, 0f, out, 0)
            assertEquals(out[0], out[1], 0f)
        }
    }

    @Test fun decayAndDampingCanBeReconfiguredWithoutNonFiniteOutput() {
        val reverb = StereoReverb(8_000)
        val out = FloatArray(2)
        repeat(48) { block ->
            reverb.configure(
                decaySeconds = if (block % 2 == 0) 12f else 0.12f,
                damping = if (block % 2 == 0) 1f else 0f,
                wet = if (block % 3 == 0) 1f else 0.4f,
                diffusion = if (block % 2 == 0) 0f else 1f,
                stereoWidth = if (block % 2 == 0) 1f else 0f
            )
            repeat(512) { sample ->
                val input = if (sample == 0) 8f else 0f
                reverb.process(input, -input, out, 0)
                assertTrue(out[0].isFinite() && out[1].isFinite())
                assertTrue(abs(out[0]) <= 16f && abs(out[1]) <= 16f)
            }
        }
        reverb.configure(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN, Float.NaN)
        repeat(200) {
            reverb.process(Float.POSITIVE_INFINITY, Float.NaN, out, 0)
            assertTrue(out[0].isFinite() && out[1].isFinite())
        }
    }

    @Test fun resetInvalidatesEveryDelayWithoutClearingBuffers() {
        val reverb = StereoReverb(8_000)
        val out = FloatArray(2)
        reverb.configure(8f, 0.2f, 1f)
        repeat(5_000) { frame ->
            reverb.process(if (frame == 0) 1f else 0f, 0f, out, 0)
        }
        reverb.reset()
        repeat(5_000) {
            reverb.process(0f, 0f, out, 0)
            assertEquals(0f, out[0], 0f)
            assertEquals(0f, out[1], 0f)
        }
    }

    @Test fun bypassResetStartsReenabledWetSignalFromDry() {
        val reverb = StereoReverb(8_000)
        val out = FloatArray(2)
        reverb.configure(6f, 0.5f, 1f)
        repeat(1_000) { frame ->
            reverb.process(if (frame == 0) 1f else 0f, 0f, out, 0)
        }
        reverb.resetForBypass()
        reverb.configure(6f, 0.5f, 1f)
        reverb.process(0.4f, -0.2f, out, 0)
        // 15 ms control glide: first frame remains predominantly dry even
        // though the target is fully wet, and old echo tails are invalidated.
        assertTrue(out[0] > 0.38f && out[0] <= 0.4f)
        assertTrue(out[1] < -0.19f && out[1] >= -0.2f)
    }

    @Test fun firstReflectionTimeTracksSampleRate() {
        val low = firstReflectionFrame(8_000) / 8_000.0
        val high = firstReflectionFrame(48_000) / 48_000.0
        assertTrue(abs(low - high) < 0.0003)
        assertTrue(low in 0.029..0.034)
    }

    private fun firstReflectionFrame(sampleRate: Int): Int {
        val reverb = StereoReverb(sampleRate)
        reverb.configure(2f, damping = 0.3f, wet = 1f)
        val out = FloatArray(2)
        for (frame in 0 until sampleRate / 2) {
            reverb.process(if (frame == 0) 1f else 0f, 0f, out, 0)
            if (abs(out[0]) + abs(out[1]) > 1e-5f) return frame
        }
        error("No early reflection within half a second")
    }
}
