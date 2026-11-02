package com.orbital.synth.core.dsp.processing

enum class ModSource { LFO1, ENV1, VELOCITY, KEYTRACK, AFTERTOUCH, MOD_WHEEL, MACRO1, MACRO2 }
enum class ModDestination { PITCH, MORPH, CUTOFF, RESONANCE, PAN, DRIVE, FX_AMOUNT }

/**
 * Eight routable modulation slots, in fixed primitive arrays.
 * Configure routes on the audio thread; fill source values in [-1, 1].
 */
class ModulationMatrix(val routeCount: Int = 8) {
    init { require(routeCount in 1..64) }
    private val sourceIndex = IntArray(routeCount)
    private val destinationIndex = IntArray(routeCount)
    private val depth = FloatArray(routeCount)
    private val enabled = BooleanArray(routeCount)

    fun setRoute(slot: Int, source: ModSource, destination: ModDestination, amount: Float) {
        require(slot in 0 until routeCount)
        sourceIndex[slot] = source.ordinal
        destinationIndex[slot] = destination.ordinal
        depth[slot] = (if (amount.isFinite()) amount else 0f).coerceIn(-1f, 1f)
        enabled[slot] = true
    }

    fun clearRoute(slot: Int) {
        require(slot in 0 until routeCount)
        enabled[slot] = false
        depth[slot] = 0f
    }

    /** Copy base destination values then accumulate route contributions and clamp 0..1. */
    fun apply(base: FloatArray, sources: FloatArray, destination: FloatArray) {
        require(base.size >= ModDestination.entries.size)
        require(destination.size >= ModDestination.entries.size)
        require(sources.size >= ModSource.entries.size)
        for (i in ModDestination.entries.indices) {
            destination[i] = if (base[i].isFinite()) base[i].coerceIn(0f, 1f) else 0f
        }
        for (i in 0 until routeCount) {
            if (!enabled[i]) continue
            val value = sources[sourceIndex[i]]
            if (!value.isFinite()) continue
            val destinationId = destinationIndex[i]
            destination[destinationId] = (destination[destinationId] +
                value.coerceIn(-1f, 1f) * depth[i]).coerceIn(0f, 1f)
        }
    }
}
