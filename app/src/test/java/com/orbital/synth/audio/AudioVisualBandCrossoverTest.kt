package com.orbital.synth.audio

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVisualBandCrossoverTest {
    private val sampleRate = 48_000

    private data class Fractions(val low: Float, val mid: Float, val high: Float) {
        fun sum(): Float = low + mid + high
        fun isFinite(): Boolean = low.isFinite() && mid.isFinite() && high.isFinite()
    }

    private fun fractions(meter: AudioVisualBandCrossover): Fractions =
        Fractions(meter.lowFraction, meter.midFraction, meter.highFraction)

    private fun sine(
        frequencyHz: Int,
        amplitude: Float = .4f,
        quantizedPcm16: Boolean = false,
        rightPhaseInverted: Boolean = false,
        frames: Int = sampleRate,
    ): Fractions {
        val band = AudioVisualBandCrossover(sampleRate)
        for (i in 0 until frames) {
            val unquantized =
                (sin(2.0 * PI * frequencyHz * i / sampleRate) * amplitude).toFloat()
            val left = if (quantizedPcm16) {
                ((unquantized * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                    .toFloat() / 32768f
            } else unquantized
            val right = if (rightPhaseInverted) -left else left
            band.observe(left, right)
        }
        return fractions(band)
    }

    @Test fun equalAmplitudeTonesOwnThreeDifferentActualCrossoverBands() {
        val bass = sine(100)
        val voice = sine(1_000)
        val treble = sine(7_000)
        assertTrue("100Hz should live primarily in measured LOW, got $bass", bass.low > .65f)
        assertTrue("1kHz should live primarily in measured MID, got $voice", voice.mid > .70f)
        assertTrue("7kHz should live primarily in measured HIGH, got $treble", treble.high > .70f)
        assertTrue("LOW vs HIGH must be distinguishable at matching loudness",
            bass.low > treble.low + .60f)
        assertTrue("HIGH vs LOW must be distinguishable at matching loudness",
            treble.high > bass.high + .60f)
        for (item in listOf(bass, voice, treble)) {
            assertTrue("Real band ratios cannot be nonfinite", item.isFinite())
            assertEquals("Approximate energy fractions should normalize", 1f, item.sum(), .001f)
        }
    }

    @Test fun invertedPhaseStereoDoesNotCancelOrInventSilence() {
        val straight = sine(100, rightPhaseInverted = false)
        val antiphase = sine(100, rightPhaseInverted = true)
        assertEquals(straight.low, antiphase.low, .0001f)
        assertEquals(straight.mid, antiphase.mid, .0001f)
        assertEquals(straight.high, antiphase.high, .0001f)
        assertTrue("Stereo signals with opposing signs are still audible energy",
            antiphase.low > .65f)
    }

    @Test fun sixteenBitAcceptedPcmHasSameTimbreAsFloatPath() {
        for (frequency in listOf(100, 1_000, 7_000)) {
            val pcmFloat = sine(frequency, amplitude = .37f)
            val pcm16 = sine(frequency, amplitude = .37f, quantizedPcm16 = true)
            assertEquals("LOW float/PCM16 parity at $frequency Hz",
                pcmFloat.low, pcm16.low, .003f)
            assertEquals("MID float/PCM16 parity at $frequency Hz",
                pcmFloat.mid, pcm16.mid, .003f)
            assertEquals("HIGH float/PCM16 parity at $frequency Hz",
                pcmFloat.high, pcm16.high, .003f)
        }
    }

    @Test fun silenceStopAndMalformedInputsStayFiniteAndExactlyDark() {
        val band = AudioVisualBandCrossover(sampleRate)
        repeat(2_000) { band.observe(0f, 0f) }
        assertEquals(0f, band.lowFraction, 0f)
        assertEquals(0f, band.midFraction, 0f)
        assertEquals(0f, band.highFraction, 0f)

        band.observe(Float.NaN, Float.POSITIVE_INFINITY)
        band.observe(Float.NEGATIVE_INFINITY, Float.NaN)
        assertEquals(0f, band.lowFraction, 0f)
        assertEquals(0f, band.midFraction, 0f)
        assertEquals(0f, band.highFraction, 0f)

        repeat(500) { frame ->
            val x = sin(2.0 * PI * 1_000 * frame / sampleRate).toFloat() * .4f
            band.observe(x, x)
        }
        assertTrue(band.midFraction > 0f)
        band.resetAll() // audio worker stop/replacement
        assertEquals(0, band.framesObserved)
        assertEquals(0f, band.lowFraction, 0f)
        assertEquals(0f, band.midFraction, 0f)
        assertEquals(0f, band.highFraction, 0f)
        repeat(2_000) { band.observe(0f, 0f) }
        assertEquals("No stale filter energy after stop and fresh silent worker",
            0f, band.highFraction, 0f)
    }

    @Test fun resetWindowPreservesHistoryWithoutCarryingOldEnergy() {
        val band = AudioVisualBandCrossover(sampleRate)
        for (i in 0 until 1_600) {
            val x = sin(2.0 * PI * 100 * i / sampleRate).toFloat() * .4f
            band.observe(x, x)
        }
        val first = fractions(band)
        band.resetWindow()
        assertEquals(0, band.framesObserved)
        assertEquals(0f, band.lowFraction, 0f)
        for (i in 1_600 until 3_200) {
            val x = sin(2.0 * PI * 100 * i / sampleRate).toFloat() * .4f
            band.observe(x, x)
        }
        val next = fractions(band)
        assertTrue("Second band window must retain real bass, not synthetic reset spikes",
            first.low > .65f && next.low > .65f)
        assertTrue("Filter continuity must not drift across a 30Hz window",
            kotlin.math.abs(first.low - next.low) < .04f)
    }

    @Test fun seedableNoiseShowsBroadHighEnergyButNotFalseBass() {
        val band = AudioVisualBandCrossover(sampleRate)
        var seed = 12_345
        repeat(sampleRate) {
            seed = seed * 1_664_525 + 1_013_904_223
            val x = (seed.toUInt().toDouble() / 4_294_967_296.0 * 2.0 - 1.0)
                .toFloat() * .4f
            band.observe(x, x)
        }
        val measured = fractions(band)
        assertTrue("Wideband white noise must not look like a pure 100Hz bass",
            measured.low < .10f)
        assertTrue("Noise contains physically measured high-band energy",
            measured.high > .60f)
    }

    @Test fun amplitudesChangeLevelButNotFrequencyEnergyFractions() {
        val quiet = sine(1_000, amplitude = .1f)
        val loud = sine(1_000, amplitude = .8f)
        assertEquals(quiet.low, loud.low, .005f)
        assertEquals(quiet.mid, loud.mid, .005f)
        assertEquals(quiet.high, loud.high, .005f)
    }

    @Test fun rejectsUnstableOrNonphysicalCrossoverParameters() {
        val invalid = listOf(
            0 to (180f to 2_700f),
            7_999 to (180f to 2_700f),
            48_000 to (Float.NaN to 2_700f),
            48_000 to (180f to Float.POSITIVE_INFINITY),
            48_000 to (2_700f to 180f),
            8_000 to (180f to 4_200f),
        )
        invalid.forEach { (sr, cutoff) ->
            var rejected = false
            try {
                AudioVisualBandCrossover(sr, cutoff.first, cutoff.second)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Unstable crossover unexpectedly accepted: sr=$sr cut=$cutoff", rejected)
        }
    }
}
