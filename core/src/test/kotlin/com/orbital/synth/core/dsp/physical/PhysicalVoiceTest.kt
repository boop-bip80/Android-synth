package com.orbital.synth.core.dsp.physical

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SR = 48_000

private fun midiHz(note: Int) = 440.0 * 2.0.pow((note - 69) / 12.0)

private fun cents(measured: Double, expected: Double) = 1200.0 * log2(measured / expected)

private fun rms(x: FloatArray, from: Int, to: Int): Double {
    var sum = 0.0
    for (i in from until to) sum += x[i].toDouble() * x[i]
    return sqrt(sum / (to - from))
}

/** Fundamental by normalized autocorrelation with parabolic refinement around [expected]. */
private fun detectPitch(x: FloatArray, from: Int, to: Int, expected: Double): Double {
    val period = SR / expected
    val lo = (period * 0.9).toInt().coerceAtLeast(2)
    val hi = (period * 1.1).toInt() + 1
    fun corr(lag: Int): Double {
        var num = 0.0
        var den1 = 0.0
        var den2 = 0.0
        for (i in from until to - lag) {
            num += x[i].toDouble() * x[i + lag]
            den1 += x[i].toDouble() * x[i]
            den2 += x[i + lag].toDouble() * x[i + lag]
        }
        return num / sqrt(den1 * den2 + 1e-18)
    }
    var best = lo
    var bestValue = -2.0
    for (lag in lo..hi) {
        val v = corr(lag)
        if (v > bestValue) { bestValue = v; best = lag }
    }
    val a = corr(best - 1)
    val b = corr(best)
    val c = corr(best + 1)
    val shift = 0.5 * (a - c) / (a - 2 * b + c)
    return SR / (best + shift)
}

/** Magnitude of one DFT bin at [hz] over the window. */
private fun magnitudeAt(x: FloatArray, from: Int, to: Int, hz: Double): Double {
    var re = 0.0
    var im = 0.0
    val w = 2.0 * PI * hz / SR
    val n = to - from
    for (i in 0 until n) {
        val window = 0.5 - 0.5 * cos(2.0 * PI * i / n) // Hann
        re += x[from + i] * window * cos(w * i)
        im += x[from + i] * window * sin(w * i)
    }
    return sqrt(re * re + im * im) / n
}

private fun strongestPeak(x: FloatArray, from: Int, to: Int, lowHz: Int, highHz: Int, stepHz: Int = 2): Double {
    var best = lowHz.toDouble()
    var bestValue = -1.0
    var hz = lowHz
    while (hz <= highHz) {
        val m = magnitudeAt(x, from, to, hz.toDouble())
        if (m > bestValue) { bestValue = m; best = hz.toDouble() }
        hz += stepHz
    }
    return best
}

private fun renderString(
    note: Int, seconds: Float, velocity: Float = 0.8f,
    brightness: Float = 0.6f, decay: Float = 0.5f, pick: Float = 0.2f,
): FloatArray {
    val voice = KarplusStrongVoice(SR)
    voice.configure(brightness, decay, pick)
    voice.noteOn(note, velocity)
    val f = midiHz(note)
    return FloatArray((seconds * SR).toInt()) { voice.nextSampleAtFrequency(f, 1.0) }
}

private fun renderBell(
    note: Int, seconds: Float, velocity: Float = 0.8f,
    table: Float = 0.5f, brightness: Float = 0.6f, decay: Float = 0.5f,
): FloatArray {
    val voice = ModalBellVoice(SR)
    voice.configure(table, brightness, decay)
    voice.noteOn(note, velocity)
    val f = midiHz(note)
    return FloatArray((seconds * SR).toInt()) { voice.nextSampleAtFrequency(f, 1.0) }
}

class KarplusStrongVoiceTest {
    @Test fun stringIsInTuneAcrossTheKeyboard() {
        for (note in intArrayOf(36, 43, 48, 55, 60, 64, 69, 72, 79, 84)) {
            val out = renderString(note, 0.7f)
            val expected = midiHz(note)
            val measured = detectPitch(out, (0.06 * SR).toInt(), (0.5 * SR).toInt(), expected)
            val error = cents(measured, expected)
            assertTrue("note $note: ${"%.2f".format(error)} cents off (${"%.2f".format(measured)} Hz)", abs(error) < 6.0)
        }
    }

    @Test fun veryHighNotesStayWithinAQuarterTone() {
        for (note in intArrayOf(91, 96)) {
            val out = renderString(note, 0.4f, brightness = 0.9f)
            val expected = midiHz(note)
            val measured = detectPitch(out, (0.04 * SR).toInt(), (0.25 * SR).toInt(), expected)
            assertTrue("note $note off by ${cents(measured, expected)}", abs(cents(measured, expected)) < 25.0)
        }
    }

    @Test fun darkAndBrightStringsShareTheSamePitch() {
        val expected = midiHz(57)
        for (brightness in floatArrayOf(0.05f, 0.4f, 0.95f)) {
            val out = renderString(57, 0.6f, brightness = brightness)
            val measured = detectPitch(out, (0.05 * SR).toInt(), (0.4 * SR).toInt(), expected)
            assertTrue("brightness $brightness: ${cents(measured, expected)} cents", abs(cents(measured, expected)) < 8.0)
        }
    }

    @Test fun stringDecaysAndLongerDecaySettingRingsLonger() {
        val short = renderString(60, 2.5f, decay = 0.15f)
        val long = renderString(60, 2.5f, decay = 0.85f)
        fun level(x: FloatArray, a: Double, b: Double) = rms(x, (a * SR).toInt(), (b * SR).toInt())
        assertTrue(level(short, 0.05, 0.25) > level(short, 1.0, 1.2))
        assertTrue(level(long, 0.05, 0.25) > level(long, 1.8, 2.0))
        assertTrue(
            "long decay must still ring where the short one has died",
            level(long, 1.5, 1.7) > 5 * level(short, 1.5, 1.7),
        )
    }

    @Test fun brightnessIsMonotonicAndSpansAWideTonalRange() {
        // Ratio of first-difference energy to signal energy is a standard brightness proxy.
        // Measured after the first loop periods so it reflects the ringing tone, not the pluck noise.
        fun brightnessProxy(x: FloatArray): Double {
            var d = 0.0
            var s = 0.0
            for (i in (0.08 * SR).toInt() + 1 until x.size) {
                val diff = x[i] - x[i - 1]
                d += diff * diff
                s += x[i].toDouble() * x[i]
            }
            return d / s
        }
        for (note in intArrayOf(48, 60, 72)) {
            val levels = floatArrayOf(0.1f, 0.4f, 0.7f, 0.95f).map {
                brightnessProxy(renderString(note, 0.4f, brightness = it))
            }
            for (i in 1 until levels.size) {
                assertTrue("note $note not monotonic: $levels", levels[i] > levels[i - 1])
            }
            assertTrue("note $note range too narrow: $levels", levels.last() > 20 * levels.first())
        }
    }

    @Test fun harderStrikesAreLouderAndBrighter() {
        // (The synth also scales each voice by velocity, so the voice itself only needs to lean
        // the same way: harder = more energy and a crisper, brighter pluck.)
        val soft = renderString(55, 0.3f, velocity = 0.15f)
        val hard = renderString(55, 0.3f, velocity = 1f)
        val louder = rms(hard, 0, soft.size) / rms(soft, 0, soft.size)
        assertTrue("hard/soft level ratio $louder", louder > 1.15)
        fun brightnessProxy(x: FloatArray): Double {
            var d = 0.0
            var e = 0.0
            for (i in 1 until x.size) { val diff = x[i] - x[i - 1]; d += diff * diff; e += x[i].toDouble() * x[i] }
            return d / e
        }
        // A harder strike is crisper in its attack, which is where it is heard.
        val attack = (0.04 * SR).toInt()
        val crisper = brightnessProxy(hard.copyOfRange(0, attack)) / brightnessProxy(soft.copyOfRange(0, attack))
        assertTrue("hard/soft brightness ratio $crisper", crisper > 1.3)
    }

    @Test fun pickPositionChangesTheTimbreNotThePitch() {
        val near = renderString(52, 0.4f, pick = 0.05f)
        val far = renderString(52, 0.4f, pick = 0.5f)
        val expected = midiHz(52)
        assertTrue(abs(cents(detectPitch(near, 3000, 16000, expected), expected)) < 8.0)
        assertTrue(abs(cents(detectPitch(far, 3000, 16000, expected), expected)) < 8.0)
        var difference = 0.0
        for (i in 0 until 8000) difference += abs(near[i] - far[i])
        assertTrue("pick position must change the waveform", difference > 5.0)
    }

    @Test fun maximumSustainNeverGrowsOrBlowsUp() {
        val out = renderString(48, 30f, velocity = 1f, brightness = 1f, decay = 1f)
        assertTrue(out.all { it.isFinite() && abs(it) <= 2f })
        val first = rms(out, SR / 10, SR)
        val later = rms(out, 25 * SR, 30 * SR)
        assertTrue("energy must decay, first=$first later=$later", later < first)
    }

    @Test fun neverEmitsInvalidSamplesUnderHostileSettings() {
        val random = Random(77)
        val voice = KarplusStrongVoice(SR)
        repeat(300) {
            voice.configure(random.nextFloat(), random.nextFloat(), random.nextFloat())
            val note = random.nextInt(128)
            voice.noteOn(note, random.nextFloat())
            val f = midiHz(note) * (0.2 + random.nextDouble() * 3.8)
            repeat(400) {
                val pitchMultiplier = 0.25 + random.nextDouble() * 4.0
                val x = voice.nextSampleAtFrequency(f, pitchMultiplier)
                assertTrue("invalid sample $x", x.isFinite() && abs(x) <= 2f)
            }
        }
    }

    @Test fun legatoRetuneMovesThePitchWhileRinging() {
        val voice = KarplusStrongVoice(SR)
        voice.configure(0.7f, 0.8f, 0.2f)
        voice.noteOn(57, 0.9f)
        val f1 = midiHz(57)
        val a = FloatArray(SR / 2) { voice.nextSampleAtFrequency(f1, 1.0) }
        voice.retune(64)
        val f2 = midiHz(64)
        val b = FloatArray(SR / 2) { voice.nextSampleAtFrequency(f2, 1.0) }
        val first = detectPitch(a, 4000, 20000, f1)
        val second = detectPitch(b, 6000, 22000, f2)
        assertTrue(abs(cents(first, f1)) < 8.0)
        assertTrue("retuned to ${second}Hz expected $f2", abs(cents(second, f2)) < 10.0)
    }

    @Test fun twoFreshVoicesRenderIdentically() {
        assertTrue(renderString(62, 0.2f).contentEquals(renderString(62, 0.2f)))
    }
}

class ModalBellVoiceTest {
    @Test fun theStrongestPartialIsTheNotePlayed() {
        for (note in intArrayOf(48, 60, 69, 76)) {
            for (table in floatArrayOf(0f, 0.5f, 1f)) {
                val out = renderBell(note, 0.6f, table = table, brightness = 0.5f)
                val expected = midiHz(note)
                val peak = strongestPeak(out, 2000, 2000 + 16384, (expected * 0.8).toInt(), (expected * 1.25).toInt(), 1)
                assertTrue(
                    "note $note table $table: ${peak}Hz vs $expected",
                    abs(cents(peak, expected)) < 12.0,
                )
            }
        }
    }

    @Test fun marimbaTableRingsATunedFourthPartialAboveThePrime() {
        val note = 57
        val f0 = midiHz(note)
        val out = renderBell(note, 0.5f, table = 0f, brightness = 0.9f, velocity = 1f)
        val partialHz = f0 * 3.99
        val onPartial = magnitudeAt(out, 400, 400 + 8192, partialHz)
        val between = magnitudeAt(out, 400, 400 + 8192, partialHz * 0.8)
        assertTrue("partial at ${partialHz}Hz must stand out: $onPartial vs $between", onPartial > 4 * between)
    }

    @Test fun tablesProduceDifferentSpectra() {
        val f0 = midiHz(60)
        fun upperEnergy(table: Float): Double {
            val out = renderBell(60, 0.5f, table = table, brightness = 0.9f, velocity = 1f)
            return magnitudeAt(out, 300, 300 + 8192, f0 * 2.76) // free-bar partial
        }
        val glass = upperEnergy(0.5f)
        val bell = upperEnergy(1f)
        assertTrue("glass table has its 2.76 partial, bell does not: $glass vs $bell", glass > 3 * bell)
    }

    @Test fun ringTimeFollowsDecaySettingAndHigherModesDieFirst() {
        val short = renderBell(60, 3f, decay = 0.1f)
        val long = renderBell(60, 3f, decay = 0.9f)
        fun level(x: FloatArray, a: Double, b: Double) = rms(x, (a * SR).toInt(), (b * SR).toInt())
        assertTrue(level(long, 1.5, 1.7) > 8 * level(short, 1.5, 1.7))
        // Brightness proxy falls over time because upper modes decay faster.
        fun proxy(x: FloatArray, from: Int, to: Int): Double {
            var d = 0.0
            var s = 0.0
            for (i in from + 1 until to) { val diff = x[i] - x[i - 1]; d += diff * diff; s += x[i].toDouble() * x[i] }
            return d / s
        }
        val early = proxy(long, (0.01 * SR).toInt(), (0.2 * SR).toInt())
        val late = proxy(long, (1.5 * SR).toInt(), (1.7 * SR).toInt())
        assertTrue("tone must mellow as it rings: early=$early late=$late", late < early)
    }

    @Test fun velocityAndBrightnessShapeTheStrike() {
        val soft = renderBell(64, 0.3f, velocity = 0.15f, brightness = 0.2f)
        val hard = renderBell(64, 0.3f, velocity = 1f, brightness = 0.9f)
        assertTrue(rms(hard, 0, hard.size) > rms(soft, 0, soft.size))
    }

    @Test fun modesAboveTheSafeBandAreMutedInsteadOfAliasing() {
        // MIDI 120 = 8.4 kHz: the upper modes of every table lie above 21.6 kHz.
        for (table in floatArrayOf(0f, 0.5f, 1f)) {
            val out = renderBell(120, 0.2f, table = table, brightness = 1f, velocity = 1f)
            assertTrue(out.all { it.isFinite() && abs(it) <= 2f })
            assertTrue(rms(out, 0, out.size) > 0.001) // the prime still rings
        }
    }

    @Test fun neverEmitsInvalidSamplesUnderHostileSettings() {
        val random = Random(91)
        val voice = ModalBellVoice(SR)
        repeat(300) {
            voice.configure(random.nextFloat(), random.nextFloat(), random.nextFloat())
            val note = random.nextInt(128)
            voice.noteOn(note, random.nextFloat())
            val f = midiHz(note) * (0.2 + random.nextDouble() * 3.8)
            repeat(400) {
                val x = voice.nextSampleAtFrequency(f, 0.25 + random.nextDouble() * 4.0)
                assertTrue("invalid sample $x", x.isFinite() && abs(x) <= 2f)
            }
        }
    }

    @Test fun twoFreshVoicesRenderIdentically() {
        assertTrue(renderBell(65, 0.2f).contentEquals(renderBell(65, 0.2f)))
    }
}
