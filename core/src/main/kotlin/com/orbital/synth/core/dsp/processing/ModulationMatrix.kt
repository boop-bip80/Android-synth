package com.orbital.synth.core.dsp.processing

/**
 * Stable ordinal IDs: append new sources, do not reorder the original eight.
 * Sources are bipolar (-1..1), except they may be unipolar where meaningful.
 */
enum class ModSource {
    LFO1, ENV1, VELOCITY, KEYTRACK, AFTERTOUCH, MOD_WHEEL, MACRO1, MACRO2,
    LFO2, LFO3, LFO4, LFO5, LFO6, LFO7, LFO8,
    ENV2, ENV3, TOUCH_X, TOUCH_Y, TOUCH_PRESSURE, RANDOM, GATE, STEP, NOTE_PITCH
}

/** Stable ordinal IDs: the initial seven retain their old positions. */
enum class ModDestination {
    PITCH, MORPH, CUTOFF, RESONANCE, PAN, DRIVE, FX_AMOUNT,
    OSC1_GAIN, OSC2_GAIN, OSC3_GAIN, OSC4_GAIN,
    OSC5_GAIN, OSC6_GAIN, OSC7_GAIN, OSC8_GAIN,
    OSC1_PITCH, OSC2_PITCH, OSC3_PITCH, OSC4_PITCH,
    OSC5_PITCH, OSC6_PITCH, OSC7_PITCH, OSC8_PITCH,
    FILTER_DRIVE, AMP, FOLD, LFO_RATE
}

/** Curves shape the incoming source before multiplying by the route depth. */
enum class ModCurve { LINEAR, EXPONENTIAL, SOFT, EIGHT_STEPS }

/**
 * Fixed-capacity, allocation-free modulation routing.
 *
 * Each route is independently enableable, uses an up-to-4x bipolar depth, and
 * accumulates into its destination before one final 0..1 clamp. Configure
 * routes on the audio/render thread or hand changes through the host's event
 * queue; the primitive arrays themselves are not thread-safe.
 *
 * Existing four-argument setRoute and apply APIs remain supported.
 */
class ModulationMatrix(val routeCount: Int = 8) {
    init { require(routeCount in 1..256) }
    private val sourceIndex = IntArray(routeCount)
    private val destinationIndex = IntArray(routeCount)
    private val depth = FloatArray(routeCount)
    private val enabled = BooleanArray(routeCount)
    private val curve = IntArray(routeCount)

    fun setRoute(
        slot: Int,
        source: ModSource,
        destination: ModDestination,
        amount: Float,
        shaping: ModCurve = ModCurve.LINEAR,
    ) {
        require(slot in 0 until routeCount)
        sourceIndex[slot] = source.ordinal
        destinationIndex[slot] = destination.ordinal
        depth[slot] = sanitizedDepth(amount)
        curve[slot] = shaping.ordinal
        enabled[slot] = true
    }

    fun setRouteAmount(slot: Int, amount: Float) {
        require(slot in 0 until routeCount)
        depth[slot] = sanitizedDepth(amount)
    }

    fun setRouteEnabled(slot: Int, value: Boolean) {
        require(slot in 0 until routeCount)
        enabled[slot] = value
    }

    fun setRouteCurve(slot: Int, shaping: ModCurve) {
        require(slot in 0 until routeCount)
        curve[slot] = shaping.ordinal
    }

    fun isRouteEnabled(slot: Int): Boolean {
        require(slot in 0 until routeCount)
        return enabled[slot]
    }

    fun clearRoute(slot: Int) {
        require(slot in 0 until routeCount)
        enabled[slot] = false
        depth[slot] = 0f
    }

    /** Sources must not alias destination; base and destination may alias. */
    fun apply(base: FloatArray, sources: FloatArray, destination: FloatArray) {
        require(base.size >= ModDestination.entries.size)
        require(destination.size >= ModDestination.entries.size)
        require(sources.size >= ModSource.entries.size)
        for (i in ModDestination.entries.indices) {
            destination[i] = if (base[i].isFinite()) base[i].coerceIn(0f, 1f) else 0f
        }
        for (i in 0 until routeCount) {
            if (!enabled[i]) continue
            val source = sources[sourceIndex[i]]
            if (!source.isFinite()) continue
            val x = source.coerceIn(-1f, 1f)
            val shaped = when (curve[i]) {
                ModCurve.EXPONENTIAL.ordinal -> ModulationCurves.exponential(x)
                ModCurve.SOFT.ordinal -> ModulationCurves.soft(x)
                ModCurve.EIGHT_STEPS.ordinal -> ModulationCurves.eightSteps(x)
                else -> x
            }
            destination[destinationIndex[i]] += shaped * depth[i]
        }
        for (i in ModDestination.entries.indices) {
            destination[i] = destination[i].coerceIn(0f, 1f)
        }
    }

    private fun sanitizedDepth(value: Float): Float =
        (if (value.isFinite()) value else 0f).coerceIn(-4f, 4f)
}
