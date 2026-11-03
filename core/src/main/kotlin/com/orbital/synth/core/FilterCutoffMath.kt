package com.orbital.synth.core

import kotlin.math.pow

/**
 * The exact normalized base filter cutoff requested by OrbitalSynth's audio
 * worker, in hertz. Shared with the UI so a readout never invents a different
 * frequency. The user-selected base may move dynamically under modulation.
 *
 * [sampleRate] is the synthesizer's rendering sample rate, not necessarily the
 * Android device's eventual hardware output rate. OrbitalSynth validates it.
 * Inputs to the audio worker are already finite and clamped in setParameter.
 *
 * Keep the floating-point operation order byte-for-byte equivalent to the
 * original audio worker expression; this function allocates nothing.
 */
object FilterCutoffMath {
    @JvmStatic
    fun baseHz(normalized: Float, sampleRate: Int): Double =
        (45.0 * 2.0.pow(normalized * 8.7)).coerceAtMost(sampleRate * 0.45)
}
