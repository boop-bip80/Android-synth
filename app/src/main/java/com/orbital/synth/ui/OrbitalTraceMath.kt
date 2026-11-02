package com.orbital.synth.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Coefficients independent of orbit size, morph, and movement. Building them
 * once avoids thousands of redundant trigonometric calls during Compose draws.
 */
internal data class OrbitalHarmonicSample(
    val radians: Double,
    val cosine: Double,
    val sine: Double,
    val seventhSine: Double,
    val seventhCosine: Double,
)

internal object OrbitalTraceMath {
    val samples: List<OrbitalHarmonicSample> = (0..180).map { index ->
        val radians = index * (PI * 2.0 / 180.0)
        OrbitalHarmonicSample(
            radians = radians,
            cosine = cos(radians),
            sine = sin(radians),
            seventhSine = sin(radians * 7.0),
            seventhCosine = cos(radians * 7.0),
        )
    }

    /** Equivalent to sin(a * frequency + phase) *.13 + sin(a * 7 - phase *.65) *.055. */
    fun twist(
        sample: OrbitalHarmonicSample,
        frequency: Double,
        phase: Double,
        shiftedPhaseCosine: Double,
        shiftedPhaseSine: Double,
    ): Double =
        sin(sample.radians * frequency + phase) * .13 +
            (sample.seventhSine * shiftedPhaseCosine -
                sample.seventhCosine * shiftedPhaseSine) * .055
}
