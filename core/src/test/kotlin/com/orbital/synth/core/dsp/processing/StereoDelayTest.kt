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
}
