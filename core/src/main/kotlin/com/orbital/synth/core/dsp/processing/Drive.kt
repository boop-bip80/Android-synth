package com.orbital.synth.core.dsp.processing

import kotlin.math.abs

/**
 * Stable, branch-light, symmetric soft clipping. amount 0 is a perfect dry pass;
 * amount 1 saturates quickly. Scalar method makes it usable per voice or on master.
 */
object Drive {
    fun process(input: Float, amount: Float): Float {
        val x = (if (input.isFinite()) input else 0f).coerceIn(-16f, 16f)
        val a = (if (amount.isFinite()) amount else 0f).coerceIn(0f, 1f)
        if (a == 0f) return x
        val gain = 1f + a * 15f
        val z = x * gain
        val saturated = z / (1f + abs(z))
        val levelCompensation = (1f + gain) / gain
        return ((1f - a) * x + a * saturated * levelCompensation).coerceIn(-16f, 16f)
    }
}
