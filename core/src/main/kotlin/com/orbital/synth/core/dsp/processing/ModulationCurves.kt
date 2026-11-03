package com.orbital.synth.core.dsp.processing

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** Numerically bounded, deterministic shaping for bipolar modulation. */
object ModulationCurves {
    fun linear(value: Float): Float = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
    fun exponential(value: Float): Float { val x = linear(value); return x * abs(x) }
    fun soft(value: Float): Float { val x = linear(value); return if (x >= 0f) sqrt(x) else -sqrt(-x) }
    fun eightSteps(value: Float): Float = (linear(value) * 8f).roundToInt() / 8f
}
