package com.orbital.synth.ui

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Deterministic, bounded geometry for the tactile light field. These are visual
 * metaphors informed by actual PCM RMS/peak/transient/brightness and finger
 * coordinates, NEVER reconstructed waveform/FFT bins or microphone sampling.
 *
 * No random generator, mutable particle simulation or per-frame allocation is
 * needed to produce the same sound-to-light mapping on different phones.
 */
internal object ImmersiveLightMath {
    const val MAX_EMBERS = 48
    const val PETAL_COUNT = 12
    const val CONDUIT_STRANDS = 9

    data class Ember(
        val angle: Float,
        val velocity: Float,
        val radiusScale: Float,
        val phase: Float,
        val hueLane: Int,
    )

    val embers: Array<Ember> = Array(MAX_EMBERS) { index ->
        Ember(
            angle = ((index * 2.399963229728653) % (2.0 * PI)).toFloat(),
            velocity = .58f + ((index * 17) % 11) * .055f,
            radiusScale = .7f + (index % 6) * .11f,
            phase = ((index * 23) % MAX_EMBERS).toFloat() / MAX_EMBERS,
            hueLane = index % 4,
        )
    }

    fun safeUnit(value: Float, fallback: Float = 0f): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else fallback.coerceIn(0f, 1f)

    /**
     * How strongly the touch physically deforms the orbital ribbons. Measured
     * PCM adds energy even when a held chord is sounded from an external keyboard.
     */
    fun conduitEnergy(pressure: Float, pcmRms: Float, pcmPeak: Float): Float =
        (.69f * safeUnit(pressure) +
            .23f * safeUnit(pcmRms) +
            .08f * safeUnit(pcmPeak)).coerceIn(0f, 1f)

    /**
     * A transient emits little gold/cyan motes in addition to a finger tap.
     * At zero audio/gesture energy this is exactly zero and draws no particles.
     */
    fun burstEnergy(pressure: Float, transient: Float): Float =
        (.62f * safeUnit(pressure) + .38f * safeUnit(transient)).coerceIn(0f, 1f)

    fun burstOpacity(progress: Float, intensity: Float): Float {
        val decay = 1f - safeUnit(progress)
        return (safeUnit(intensity) * decay.pow(1.9f)).coerceIn(0f, 1f)
    }

    fun emberDisplacement(
        seed: Ember,
        progress: Float,
        intensity: Float,
        shortEdge: Float,
    ): Float {
        val extent = if (shortEdge.isFinite()) shortEdge.coerceAtLeast(0f) else 0f
        val phase = safeUnit(progress)
        return extent * (.012f + phase * .28f) * seed.velocity *
            seed.radiusScale * (.4f + .6f * safeUnit(intensity))
    }

    /** Ignore subpixel finger jitter to keep the 24-vertex touch trace meaningful. */
    fun shouldRecordTrailPoint(
        lastX: Float,
        lastY: Float,
        nextX: Float,
        nextY: Float,
        minDistance: Float = .004f,
    ): Boolean {
        if (!lastX.isFinite() || !lastY.isFinite()) return true
        if (!nextX.isFinite() || !nextY.isFinite()) return false
        val threshold = if (minDistance.isFinite()) minDistance.coerceIn(0f, 1f) else .004f
        return hypot(lastX - nextX, lastY - nextY) >= threshold
    }

    /**
     * Sonically bright output shifts energy toward cyan/ice, mellow output
     * toward violet/gold. Brightness is a PCM-slope proxy, not spectral FFT.
     */
    fun chromaBlend(brightness: Float, cutoff: Float): Float =
        (.68f * safeUnit(brightness) + .32f * safeUnit(cutoff, .5f)).coerceIn(0f, 1f)

    /** Bounded petal flare: responds smoothly to the physical and PCM dynamic. */
    fun petalFlare(pressure: Float, rms: Float, transient: Float): Float =
        (.28f + .31f * safeUnit(pressure) + .25f * safeUnit(rms) +
            .16f * safeUnit(transient)).coerceIn(0f, 1f)
}
