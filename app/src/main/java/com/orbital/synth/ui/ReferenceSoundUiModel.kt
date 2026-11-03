package com.orbital.synth.ui

import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.dsp.processing.LfoShape
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Deterministic visualization of *configured synthesis parameters*, never
 * microphone samples, a fabricated FFT, or audio output from another engine.
 *
 * Keep math pure: Android Canvas and JVM tests use exactly the same values.
 */
internal object ReferenceSoundUiModel {
    val knobOrder: List<ParameterId> = listOf(
        ParameterId.WAVE,
        ParameterId.CUTOFF,
        ParameterId.RESONANCE,
        ParameterId.MOVEMENT,
        ParameterId.FX_AMOUNT,
    )

    fun normalized(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    fun liveModulatedCount(routes: List<ModulationRouteConfig>): Int =
        routes.take(64).count {
            it.enabled &&
                it.destination in ModulationConstellationModel.liveDestinations &&
                it.depth.isFinite() && abs(it.depth) > .0001f
        }

    /**
     * Four real ADSR knobs mapped to a normalized *preview* of their envelope.
     * The preview traces the stored control shape; it is not sampled per-voice
     * audio or an assertion about a currently sounding voice.
     */
    fun envelopeAt(
        position: Float,
        attack: Float,
        decay: Float,
        sustain: Float,
        release: Float,
    ): Float {
        val t = normalized(position)
        val a = .035f + normalized(attack) * .255f
        val d = .035f + normalized(decay) * .245f
        val r = .045f + normalized(release) * .245f
        val s = normalized(sustain)
        val heldEnd = 1f - r
        return when {
            t < a -> t / a
            t < a + d -> 1f - ((t - a) / d) * (1f - s)
            t < heldEnd -> s
            else -> s * (1f - (t - heldEnd) / r)
        }.coerceIn(0f, 1f)
    }

    /** Preview of the saved LFO shape, never invented realtime LFO phase. */
    fun lfoAt(shape: LfoShape, position: Float): Float {
        val t = normalized(position)
        val cycle = t * 2f
        return when (shape) {
            LfoShape.SINE -> sin(cycle * Math.PI.toFloat())
            LfoShape.TRIANGLE -> 1f - 4f * abs(t - .5f)
            LfoShape.SAW_UP -> 2f * t - 1f
            LfoShape.SAW_DOWN -> 1f - 2f * t
            LfoShape.SQUARE -> if (t < .5f) 1f else -1f
            // Deliberate fixed, labeled configuration sketch; stochastic
            // output samples cannot be reconstructed from saved settings.
            LfoShape.SAMPLE_HOLD -> {
                val steps = intArrayOf(-1, 0, 1, 0, 1, -1, 1, -1)
                steps[floor(t * 7.9999f).toInt().coerceIn(0, steps.lastIndex)].toFloat()
            }
        }.coerceIn(-1f, 1f)
    }

    /** Parameter-driven glistening ribbons; no spectral/FFT frequency bins. */
    fun ribbonAt(
        x: Float,
        layer: Int,
        morph: Float,
        movement: Float,
    ): Float {
        val u = normalized(x)
        val m = normalized(morph)
        val v = normalized(movement)
        val phase = (u * (1.35f + m * 1.7f) + v * .33f + layer * .12f) *
            (2f * Math.PI.toFloat())
        val fine = sin(phase) * (.13f + m * .12f)
        val glimmer = sin(phase * 1.95f + layer * 1.1f) * .038f
        val offset = (layer - 2) * .057f
        return (.50f + fine + glimmer + offset).coerceIn(.04f, .96f)
    }
}
