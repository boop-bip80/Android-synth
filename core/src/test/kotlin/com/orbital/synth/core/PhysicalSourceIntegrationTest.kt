package com.orbital.synth.core

import com.orbital.synth.core.dsp.processing.MasterLimiter
import com.orbital.synth.core.preset.FactoryPatches
import com.orbital.synth.core.preset.PatchCodec
import com.orbital.synth.core.preset.loadPatch
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SR = 48_000
private const val CEILING = 0.985f

private fun midiHz(note: Int) = 440.0 * 2.0.pow((note - 69) / 12.0)

/** Renders [frames] frames in 256-frame blocks and returns the interleaved stereo result. */
private fun OrbitalSynth.render(frames: Int): FloatArray {
    val out = FloatArray(frames * 2)
    val block = FloatArray(512)
    var done = 0
    while (done < frames) {
        val n = minOf(256, frames - done)
        renderStereo(block, n)
        System.arraycopy(block, 0, out, done * 2, n * 2)
        done += n
    }
    return out
}

private fun left(stereo: FloatArray) = FloatArray(stereo.size / 2) { stereo[it * 2] }

private fun rms(x: FloatArray, from: Int, to: Int): Double {
    var s = 0.0
    for (i in from until to) s += x[i].toDouble() * x[i]
    return sqrt(s / (to - from))
}

private fun detectPitch(x: FloatArray, from: Int, to: Int, expected: Double): Double {
    val period = SR / expected
    fun corr(lag: Int): Double {
        var num = 0.0; var a = 0.0; var b = 0.0
        for (i in from until to - lag) { num += x[i].toDouble() * x[i + lag]; a += x[i].toDouble() * x[i]; b += x[i + lag].toDouble() * x[i + lag] }
        return num / sqrt(a * b + 1e-18)
    }
    var best = (period * 0.9).toInt().coerceAtLeast(2)
    var bestValue = -2.0
    for (lag in best..(period * 1.1).toInt() + 1) { val v = corr(lag); if (v > bestValue) { bestValue = v; best = lag } }
    val l = corr(best - 1); val m = corr(best); val r = corr(best + 1)
    return SR / (best + 0.5 * (l - r) / (l - 2 * m + r))
}

private fun magnitudeAt(x: FloatArray, from: Int, to: Int, hz: Double): Double {
    var re = 0.0; var im = 0.0
    val w = 2.0 * PI * hz / SR
    val n = to - from
    for (i in 0 until n) {
        val window = 0.5 - 0.5 * cos(2.0 * PI * i / n)
        re += x[from + i] * window * cos(w * i); im += x[from + i] * window * sin(w * i)
    }
    return sqrt(re * re + im * im) / n
}

class MasterLimiterTest {
    @Test fun belowTheCeilingTheOutputIsBitIdentical() {
        val limiter = MasterLimiter(SR)
        val random = Random(5)
        val buffer = FloatArray(2)
        repeat(20_000) {
            buffer[0] = (random.nextFloat() * 2f - 1f) * 0.95f
            buffer[1] = (random.nextFloat() * 2f - 1f) * 0.95f
            val a = buffer[0]; val b = buffer[1]
            limiter.process(buffer, 0)
            assertEquals(a, buffer[0], 0f)
            assertEquals(b, buffer[1], 0f)
        }
    }

    @Test fun noSampleEverExceedsTheCeiling() {
        val limiter = MasterLimiter(SR)
        val random = Random(9)
        val buffer = FloatArray(2)
        var worst = 0f
        repeat(100_000) { n ->
            val loud = if ((n / 5_000) % 2 == 0) 6f else 0.3f
            buffer[0] = (sin(n * 0.05) * loud).toFloat() + (random.nextFloat() - .5f)
            buffer[1] = (random.nextFloat() * 2f - 1f) * loud
            limiter.process(buffer, 0)
            worst = maxOf(worst, abs(buffer[0]), abs(buffer[1]))
        }
        assertTrue("peak $worst", worst <= limiter.ceiling + 1e-6f)
    }

    @Test fun gainRecoversSmoothlyAfterALoudBurst() {
        val limiter = MasterLimiter(SR)
        val buffer = FloatArray(2)
        repeat(SR / 5) { buffer[0] = 3f; buffer[1] = 3f; limiter.process(buffer, 0) }
        assertTrue(limiter.currentGain < 0.4f)
        var previous = limiter.currentGain
        repeat(SR) {
            buffer[0] = 0.2f; buffer[1] = 0.2f
            limiter.process(buffer, 0)
            assertTrue("gain must never fall while quiet", limiter.currentGain >= previous - 1e-7f)
            previous = limiter.currentGain
        }
        // The release is exponential (0.14 s time constant): almost home after one second...
        assertTrue("gain ${limiter.currentGain}", limiter.currentGain > 0.999f)
        // ...and snaps to exact unity (so quiet audio is bit-identical again) soon after.
        repeat(SR * 2) {
            buffer[0] = 0.2f; buffer[1] = 0.2f
            limiter.process(buffer, 0)
        }
        assertEquals(1f, limiter.currentGain, 0f)
        buffer[0] = 0.2f; buffer[1] = -0.2f
        limiter.process(buffer, 0)
        assertEquals(0.2f, buffer[0], 0f)
    }

    @Test fun channelsShareOneGainSoTheStereoImageHolds() {
        val limiter = MasterLimiter(SR)
        val buffer = floatArrayOf(2f, 0.5f)
        limiter.process(buffer, 0)
        assertEquals(limiter.ceiling, buffer[0], 1e-6f)
        assertEquals(0.5f * limiter.ceiling / 2f, buffer[1], 1e-6f)
    }

    @Test fun nonFiniteInputBecomesSilenceAndNeverPoisonsTheGain() {
        val limiter = MasterLimiter(SR)
        val buffer = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY)
        limiter.process(buffer, 0)
        assertEquals(0f, buffer[0], 0f)
        assertEquals(0f, buffer[1], 0f)
        assertTrue(limiter.currentGain.isFinite())
        buffer[0] = 0.3f; buffer[1] = 0.3f
        limiter.process(buffer, 0)
        assertEquals(0.3f, buffer[0], 0f)
    }
}

class PhysicalSourceIntegrationTest {
    private fun synthWith(source: Float, engine: Float = 0f, extra: Map<ParameterId, Float> = emptyMap()) =
        OrbitalSynth(SR).also {
            it.setParameter(ParameterId.SOURCE, source)
            it.setParameter(ParameterId.ENGINE, engine)
            it.setParameter(ParameterId.ATTACK, 0f)
            it.setParameter(ParameterId.SUSTAIN, 0.95f)
            it.setParameter(ParameterId.FX_AMOUNT, 0f)
            extra.forEach { (id, value) -> it.setParameter(id, value) }
        }

    @Test fun theStringSourcePlaysTheNoteThatWasPressed() {
        for (engine in floatArrayOf(0f, .25f, .5f)) {
            val synth = synthWith(0.5f, engine, mapOf(ParameterId.CUTOFF to 0.85f, ParameterId.WAVE to 0.6f))
            synth.noteOn(57, 0.9f)
            val out = left(synth.render(SR))
            val expected = midiHz(57)
            val measured = detectPitch(out, (0.08 * SR).toInt(), (0.5 * SR).toInt(), expected)
            val cents = 1200.0 * log2(measured / expected)
            assertTrue("engine $engine: ${"%.1f".format(cents)} cents (${"%.1f".format(measured)} Hz vs ${"%.1f".format(expected)})", abs(cents) < 20.0)
        }
    }

    /** The criterion that matters musically: a plucked or struck voice sits at a similar level to the oscillator. */
    @Test fun physicalSourcesAreInTheSameLoudnessLeagueAsTheOscillator() {
        fun level(source: Float, engine: Int, note: Int): Double {
            val synth = synthWith(source, engine / 4f, mapOf(ParameterId.CUTOFF to 0.85f,
                ParameterId.WAVE to 0.55f, ParameterId.CHARACTER to 0.45f))
            synth.noteOn(note, 0.85f)
            val out = left(synth.render(SR / 2))
            return rms(out, 1500, 16000)
        }
        for (engine in 0..4) {
            for (note in intArrayOf(45, 57, 69)) {
                val oscillator = level(0f, engine, note)
                for (source in floatArrayOf(0.5f, 1f)) {
                    val ratio = level(source, engine, note) / oscillator
                    assertTrue(
                        "engine $engine note $note source $source: ${"%.3f".format(ratio)}x the oscillator",
                        ratio in 0.2..3.0,
                    )
                }
            }
        }
    }

    @Test fun theGlassSourceRingsItsPrimeAtTheNotePlayed() {
        val synth = synthWith(1f, extra = mapOf(ParameterId.CUTOFF to 0.9f, ParameterId.WAVE to 0.5f,
            ParameterId.CHARACTER to 0.5f))
        synth.noteOn(60, 0.9f)
        val out = left(synth.render(SR))
        val f0 = midiHz(60)
        val prime = magnitudeAt(out, 3000, 3000 + 16384, f0)
        val away = magnitudeAt(out, 3000, 3000 + 16384, f0 * 1.45)
        assertTrue("prime $prime vs $away", prime > 5 * away)
    }

    @Test fun switchingTheSourceOnAHeldNoteIsClickFree() {
        val synth = synthWith(0f, extra = mapOf(ParameterId.CUTOFF to 0.8f))
        synth.noteOn(52, 0.8f)
        val before = left(synth.render(SR / 4))
        synth.setParameter(ParameterId.SOURCE, 0.5f)
        val after = left(synth.render(SR / 4))
        fun maxJump(x: FloatArray, from: Int, to: Int): Float {
            var m = 0f
            for (i in from + 1 until to) m = maxOf(m, abs(x[i] - x[i - 1]))
            return m
        }
        val natural = maxOf(maxJump(before, 4000, before.size), maxJump(after, 4000, after.size))
        val atSwitch = maxJump(after, 0, 600) // the first 12 ms after the flip
        assertTrue("switch jump $atSwitch vs natural $natural", atSwitch <= natural * 2.5f + 0.02f)
    }

    @Test fun everyEngineWithEverySourceStaysFiniteAndUnderTheCeiling() {
        for (engine in 0..4) {
            for (source in floatArrayOf(0f, 0.5f, 1f)) {
                val synth = synthWith(source, engine / 4f, mapOf(ParameterId.FX_AMOUNT to 0.6f))
                for (note in intArrayOf(36, 48, 55, 64, 72, 79, 91)) synth.noteOn(note, 1f)
                val out = synth.render(SR / 2)
                assertTrue(
                    "engine $engine source $source",
                    out.all { it.isFinite() && abs(it) <= CEILING + 1e-5f },
                )
            }
        }
    }

    @Test fun legacySourceZeroRendersIdenticallyWhetherOrNotTheParameterIsTouched() {
        fun run(touch: Boolean): FloatArray {
            val synth = OrbitalSynth(SR)
            if (touch) synth.setParameter(ParameterId.SOURCE, 0f)
            synth.noteOn(60, 0.8f); synth.noteOn(67, 0.8f)
            return synth.render(SR / 4)
        }
        assertArrayEquals(run(false), run(true), 0f)
    }

    @Test fun patchesCarryTheSourceAndOlderPatchesResetIt() {
        val harp = FactoryPatches.byId("harp-glow")!!
        assertEquals(0.5f, harp.parameters.getValue("SOURCE"), 0f)
        val decoded = PatchCodec.decode(PatchCodec.encode(harp))
        assertEquals(harp, decoded)
        val synth = OrbitalSynth(SR)
        synth.loadPatch(harp)
        assertEquals(0.5f, synth.parameter(ParameterId.SOURCE), 0f)
        // A patch without the key (every pre-existing saved file) must not inherit the source.
        val legacy = harp.copy(id = "legacy", parameters = harp.parameters - "SOURCE")
        synth.loadPatch(legacy)
        assertEquals(0f, synth.parameter(ParameterId.SOURCE), 0f)
        synth.loadPatch(FactoryPatches.DEFAULT)
        assertEquals(0f, synth.parameter(ParameterId.SOURCE), 0f)
    }

    @Test fun everyFactoryPatchIsAudibleBoundedAndFiniteWithADenseChord() {
        for (patch in FactoryPatches.ALL) {
            val synth = OrbitalSynth(SR)
            synth.loadPatch(patch)
            for (note in intArrayOf(48, 55, 60, 64, 67, 72, 76, 79)) synth.noteOn(note, 1f)
            val out = synth.render(SR / 2)
            assertTrue("${patch.id} produced invalid or clipping output",
                out.all { it.isFinite() && abs(it) <= CEILING + 1e-5f })
            assertTrue("${patch.id} is silent", rms(left(out), 0, out.size / 2) > 0.004)
        }
    }

    @Test fun everyPhysicalPatchRingsInTune() {
        val physical = FactoryPatches.ALL.filter { (it.parameters["SOURCE"] ?: 0f) == 0.5f }
        assertTrue(physical.size >= 4)
        for (patch in physical) {
            val synth = OrbitalSynth(SR)
            synth.loadPatch(patch)
            synth.noteOn(57, 0.85f)
            val out = left(synth.render(SR))
            val expected = midiHz(57)
            val measured = detectPitch(out, (0.1 * SR).toInt(), (0.5 * SR).toInt(), expected)
            val cents = 1200.0 * log2(measured / expected)
            assertTrue("${patch.id}: ${"%.1f".format(cents)} cents", abs(cents) < 35.0)
        }
    }

    @Test fun libraryNowCarriesTheNewInstrumentFamilies() {
        val ids = FactoryPatches.ALL.map { it.id }.toSet()
        assertEquals(FactoryPatches.ALL.size, ids.size)
        assertTrue(FactoryPatches.ALL.size >= 36)
        for (id in listOf("harp-glow", "crystal-marimba", "cathedral-bell", "velvet-keys", "moon-bass", "sunbeam-lead")) {
            assertTrue("missing $id", id in ids)
        }
        assertTrue(FactoryPatches.ALL.any { "stage" in it.tags })
    }
}
