package com.orbital.synth.audio

import kotlin.math.PI
import kotlin.math.exp

/**
 * Low/mid/high *approximate energy* of real interleaved stereo PCM.
 *
 * Two precomputed, stable one-pole crossovers are applied INDEPENDENTLY to
 * left/right, so opposite-phase stereo cannot disappear into a mono average:
 *   low  = LP180(x)
 *   mid  = LP2700(x) - LP180(x)
 *   high = x - LP2700(x)
 *
 * These are broad overlapping perceptual bands, NOT an FFT, calibrated
 * frequency-bin spectrum, microphone measurement, or a spectral centroid.
 * Ratios remain scale-independent and only describe samples actually passed
 * into [observe]. The audio host must feed this object *after* AudioTrack
 * accepts the complete PCM block (as its existing meter already does).
 *
 * One audio worker owns every mutable field. Construction computes the only
 * exponentials; observe(), resetWindow(), getters and resetAll() allocate no
 * objects, arrays, locks, coroutines, trigonometry, or callbacks per sample.
 * A complete reset is required at worker replacement; resetWindow deliberately
 * retains filter memory to avoid creating fake 30Hz transients.
 */
internal class AudioVisualBandCrossover(
    sampleRate: Int,
    lowCutoffHz: Float = 180f,
    highCutoffHz: Float = 2_700f,
) {
    init {
        require(sampleRate in 8_000..192_000)
        require(lowCutoffHz.isFinite() && lowCutoffHz > 0f)
        require(highCutoffHz.isFinite() && highCutoffHz > lowCutoffHz)
        require(highCutoffHz < sampleRate * .49f)
    }

    private val lowAlpha =
        (1.0 - exp(-2.0 * PI * lowCutoffHz / sampleRate)).toFloat()
    private val highAlpha =
        (1.0 - exp(-2.0 * PI * highCutoffHz / sampleRate)).toFloat()

    private var lowLeft = 0f
    private var lowRight = 0f
    private var highLeft = 0f
    private var highRight = 0f

    private var lowPower = 0.0
    private var midPower = 0.0
    private var highPower = 0.0
    private var windowFrames = 0

    val framesObserved: Int get() = windowFrames

    /** O(1) primitive-only hot path, including finite input sanitization. */
    fun observe(left: Float, right: Float) {
        val l = if (left.isFinite()) left.coerceIn(-1f, 1f) else 0f
        val r = if (right.isFinite()) right.coerceIn(-1f, 1f) else 0f

        lowLeft += lowAlpha * (l - lowLeft)
        lowRight += lowAlpha * (r - lowRight)
        highLeft += highAlpha * (l - highLeft)
        highRight += highAlpha * (r - highRight)

        val midLeft = highLeft - lowLeft
        val midRight = highRight - lowRight
        val upperLeft = l - highLeft
        val upperRight = r - highRight

        lowPower += (lowLeft * lowLeft + lowRight * lowRight).toDouble()
        midPower += (midLeft * midLeft + midRight * midRight).toDouble()
        highPower += (upperLeft * upperLeft + upperRight * upperRight).toDouble()
        windowFrames++
    }

    val lowFraction: Float get() = fraction(lowPower)
    val midFraction: Float get() = fraction(midPower)
    val highFraction: Float get() = fraction(highPower)

    private fun fraction(power: Double): Float {
        val total = lowPower + midPower + highPower
        return if (windowFrames == 0 || total <= 1e-12) 0f
        else (power / total).toFloat().coerceIn(0f, 1f)
    }

    /** Publish external immutable/quantized values first, then clear only sums. */
    fun resetWindow() {
        lowPower = 0.0
        midPower = 0.0
        highPower = 0.0
        windowFrames = 0
    }

    /** New AudioTrack worker, focus loss, stop, or patch audio-session reset. */
    fun resetAll() {
        resetWindow()
        lowLeft = 0f
        lowRight = 0f
        highLeft = 0f
        highRight = 0f
    }
}
