package com.orbital.synth.core

import com.orbital.synth.core.dsp.processing.ModDestination
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * One worker-owned, preallocated modulation frame shared by all oscillator voices.
 *
 * Eight per-layer gain buses are 0..2 (neutral 1), eight pitch buses are +/-6
 * semitones (neutral 0). The existing matrix's normalized 0..1 OSC targets
 * remain patch-compatible. All gain/pitch transitions use the same sample-clock
 * 8 ms slew, including unplugging the last cable; there is no sample-loop heap,
 * lock, exp() or pow() work.
 */
internal class OscillatorLayerModulation(sampleRate: Int) {
    companion object {
        const val LAYERS = 8
        private const val PITCH_TABLE_STEPS = 2_048
        private val GAIN_DESTINATION = ModDestination.OSC1_GAIN.ordinal
        private val PITCH_DESTINATION = ModDestination.OSC1_PITCH.ordinal
        const val MAX_UPWARD_PITCH_RATIO = 1.4142135623730951 // 2^(+6 / 12)
    }

    val gainMultipliers = FloatArray(LAYERS) { 1f }
    val pitchMultipliers = DoubleArray(LAYERS) { 1.0 }

    private val pitchSemitones = FloatArray(LAYERS)
    private val slew = (1.0 - exp(-1.0 / (sampleRate * 0.008))).toFloat()
    // Float32 can stop changing before a fixed 1e-5 cutoff is reached:
    // near +/-6 semitones the 8ms step becomes smaller than half an ULP.
    // Scale the exact-target snap to the representable signal quantum and
    // the sample-rate-dependent slew. Evaluated once at construction only,
    // never in the audio callback; <0.08 cents even at 192 kHz.
    private val gainSnapThreshold = maxOf(0.00001f,
        java.lang.Math.ulp(2f) / slew)
    private val pitchSnapThreshold = maxOf(0.00001f,
        java.lang.Math.ulp(6f) / slew)
    // Precomputed during synth construction, never on the audio worker. Linear
    // interpolation has <0.000001 relative error over the +/-6 semitone span.
    private val pitchTable = DoubleArray(PITCH_TABLE_STEPS + 1) { index ->
        2.0.pow(index.toDouble() / PITCH_TABLE_STEPS - 0.5)
    }
    private var gainRouteMask = 0
    private var pitchRouteMask = 0

    var active = false
        private set

    /** Per-slot anti-alias reservation: includes positive pitch during unplug fades. */
    var mipSafetyMask = 0
        private set

    fun configureMasks(gains: Int, pitches: Int) {
        gainRouteMask = gains and 0xff
        pitchRouteMask = pitches and 0xff
        active = gainRouteMask != 0 || pitchRouteMask != 0 || residualIsAudible()
        updateMipSafetyMask()
    }

    /**
     * Advance exactly one actual PCM sample. Passing null unwinds routes to
     * neutral if every matrix route has been cleared; still de-clicked.
     */
    fun next(matrixFrame: FloatArray?) {
        if (!active) return
        var remaining = gainRouteMask != 0 || pitchRouteMask != 0
        var safetyMask = pitchRouteMask
        for (i in 0 until LAYERS) {
            val bit = 1 shl i
            val targetGain = if (matrixFrame != null && (gainRouteMask and bit) != 0) {
                (matrixFrame[GAIN_DESTINATION + i] * 2f).coerceIn(0f, 2f)
            } else 1f
            val gain = gainMultipliers[i]
            val nextGain = gain + (targetGain - gain) * slew
            val settledGain = if (abs(targetGain - nextGain) <= gainSnapThreshold) targetGain else nextGain
            gainMultipliers[i] = settledGain

            val targetSemitones = if (matrixFrame != null && (pitchRouteMask and bit) != 0) {
                ((matrixFrame[PITCH_DESTINATION + i] - 0.5f) * 12f).coerceIn(-6f, 6f)
            } else 0f
            val pitch = pitchSemitones[i]
            val nextPitch = pitch + (targetSemitones - pitch) * slew
            val settledPitch = if (abs(targetSemitones - nextPitch) <= pitchSnapThreshold) {
                targetSemitones
            } else nextPitch
            if (settledPitch != pitch) {
                pitchSemitones[i] = settledPitch
                pitchMultipliers[i] = if (settledPitch == 0f) 1.0 else pitchRatio(settledPitch)
            }

            if (settledPitch > 0f) safetyMask = safetyMask or bit
            if (settledPitch != 0f || settledGain != 1f) remaining = true
        }
        mipSafetyMask = safetyMask
        active = remaining
    }

    /**
     * An idle synth has no oscillator/voice tail. Skip pointless idle per-sample
     * sweeps; the next note enters from neutral and slews in its routed sound.
     */
    fun resetWhileSilent() {
        for (i in 0 until LAYERS) {
            gainMultipliers[i] = 1f
            pitchMultipliers[i] = 1.0
            pitchSemitones[i] = 0f
        }
        active = gainRouteMask != 0 || pitchRouteMask != 0
        mipSafetyMask = pitchRouteMask
    }

    private fun pitchRatio(semitones: Float): Double {
        val position = ((semitones.toDouble() / 12.0 + 0.5) * PITCH_TABLE_STEPS)
            .coerceIn(0.0, PITCH_TABLE_STEPS.toDouble())
        val low = position.toInt()
        if (low == PITCH_TABLE_STEPS) return pitchTable[low]
        val fraction = position - low
        return pitchTable[low] + (pitchTable[low + 1] - pitchTable[low]) * fraction
    }

    private fun residualIsAudible(): Boolean {
        for (i in 0 until LAYERS) {
            if (gainMultipliers[i] != 1f || pitchSemitones[i] != 0f) return true
        }
        return false
    }

    private fun updateMipSafetyMask() {
        var mask = pitchRouteMask
        for (i in 0 until LAYERS) if (pitchSemitones[i] > 0f) mask = mask or (1 shl i)
        mipSafetyMask = mask
    }
}
