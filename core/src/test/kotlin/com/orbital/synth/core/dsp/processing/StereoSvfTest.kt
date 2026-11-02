package com.orbital.synth.core.dsp.processing

import org.junit.Assert.*
import org.junit.Test

class StereoSvfTest {
    @Test fun emptyInputRemainsSilentAndStereoStatesDoNotBleed() {
        val filter = StereoSvf(48_000)
        filter.setParameters(2_000f, 0.4f)
        repeat(2_000) {
            filter.processLeft(if (it == 0) 1f else 0f)
            assertEquals(0f, filter.processRight(0f), 0f)
        }
        filter.reset()
        repeat(64) {
            assertEquals(0f, filter.processLeft(0f), 0f)
            assertEquals(0f, filter.processRight(0f), 0f)
        }
    }

    @Test fun lowPassRemovesHighFrequencyMoreThanLowFrequency() {
        fun averageMagnitude(hertz: Double): Float {
            val filter = StereoSvf(48_000)
            filter.setParameters(800f, 0f)
            var acc = 0.0
            repeat(12_000) { i ->
                val input = kotlin.math.sin(i * hertz * 2.0 * Math.PI / 48_000.0).toFloat()
                val output = filter.processLeft(input)
                if (i >= 2_000) acc += output * output
            }
            return kotlin.math.sqrt(acc / 10_000.0).toFloat()
        }
        assertTrue(averageMagnitude(100.0) > averageMagnitude(8_000.0) * 8f)
    }

    @Test fun everyModeStaysFiniteUnderExtremeInputsAndParameters() {
        val filter = StereoSvf(8_000)
        val parameters = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -100f, 0f, 4_000f, 90_000f)
        for (mode in FilterMode.entries) {
            for (cutoff in parameters) {
                filter.setParameters(cutoff, Float.NaN, mode)
                repeat(4_000) { sample ->
                    val input = if (sample % 2 == 0) 16f else -16f
                    assertTrue(filter.processLeft(input).isFinite())
                    assertTrue(filter.processRight(-input).isFinite())
                }
                filter.reset()
            }
        }
    }
}
