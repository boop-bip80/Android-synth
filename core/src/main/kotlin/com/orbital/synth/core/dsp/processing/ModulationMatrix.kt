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

    /**
     * Copy base destination values, accumulate every route, then clamp once.
     *
     * Clamping after individual routes makes the result depend on slot order:
     * a +0.8 route followed by -0.8 cannot cancel if the first is prematurely
     * clipped at 1.0. A final clamp is both cheaper and musically predictable.
     * [sources] must be a separate array from [destination].
     */
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
            destination[destinationId] += value.coerceIn(-1f, 1f) * depth[i]
        }
        for (i in ModDestination.entries.indices) {
            destination[i] = destination[i].coerceIn(0f, 1f)
        }
    }
}
