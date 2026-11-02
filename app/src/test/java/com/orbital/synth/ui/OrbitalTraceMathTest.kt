package com.orbital.synth.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitalTraceMathTest {
    @Test
    fun precomputedHarmonicsMatchOriginalOrbitAtEveryVertex() {
        assertEquals(181, OrbitalTraceMath.samples.size)
        for (morph in listOf(0.0, .42, 1.0)) {
            for (movement in listOf(0.0, .37, 1.0)) {
                for (layer in 0..7) {
                    val frequency = 3.0 + morph * 2.0
                    val phase = movement * PI * 2.0 + layer * .43
                    val shift = phase * .65
                    for (sample in OrbitalTraceMath.samples) {
                        val original = sin(sample.radians * frequency + phase) * .13 +
                            sin(sample.radians * 7.0 - phase * .65) * .055
                        val cached = OrbitalTraceMath.twist(
                            sample, frequency, phase, cos(shift), sin(shift),
                        )
                        assertEquals(original, cached, 1e-12)
                    }
                }
            }
        }
    }
}
