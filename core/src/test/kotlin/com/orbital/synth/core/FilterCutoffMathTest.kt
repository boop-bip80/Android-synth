package com.orbital.synth.core

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Historical PCM-path frequency mapping: exact values, not an approximate UI curve. */
class FilterCutoffMathTest {
    @Test fun pcmBaseCutoffGoldenValuesAt48k() {
        // Preserve the legacy DSP expression as a regression oracle; if the
        // worker changes its transfer curve deliberately, update both together.
        val golden = listOf(
            0f to 45.0,
            0.25f to 203.213592865,
            0.63f to 2009.823500336,
            1f to 18714.295212048,
        )
        for ((control, hz) in golden) {
            val originalDsp = (45.0 * 2.0.pow(control * 8.7)).coerceAtMost(48_000 * .45)
            assertEquals(originalDsp, FilterCutoffMath.baseHz(control, 48_000), 0.0)
            assertEquals(hz, FilterCutoffMath.baseHz(control, 48_000), 0.001)
        }
    }

    @Test fun pcmBaseCutoffRetainsSampleRateClipping() {
        for (rate in listOf(8_000, 16_000, 32_000, 44_100, 48_000, 96_000)) {
            for (x in listOf(0f, .25f, .63f, 1f)) {
                val original = (45.0 * 2.0.pow(x * 8.7)).coerceAtMost(rate * .45)
                assertEquals(original, FilterCutoffMath.baseHz(x, rate), 0.0)
            }
        }
        assertEquals(3600.0, FilterCutoffMath.baseHz(1f, 8_000), 0.0)
        assertEquals(7200.0, FilterCutoffMath.baseHz(1f, 16_000), 0.0)
    }
}
