package com.orbital.synth.core.jazz

import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.preset.PatchCodec
import com.orbital.synth.core.preset.PatchParameters
import com.orbital.synth.core.preset.SynthPatch
import com.orbital.synth.core.preset.loadPatch
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests drive the real [OrbitalSynth] with each patch. They establish that every
 * jazz sound is structurally sound (finite, audible, bounded, articulate as designed,
 * distinct from its siblings). They cannot establish that it *sounds good*: that needs ears.
 */
class JazzVoicesTest {
    private val sampleRate = 48_000

    private class Rendered(val hold: FloatArray, val tail: FloatArray)

    private fun render(patch: SynthPatch, note: Int, holdSeconds: Float, tailSeconds: Float): Rendered {
        val synth = OrbitalSynth(sampleRate)
        synth.loadPatch(patch)
        synth.noteOn(note, 0.9f)
        val hold = block(synth, (holdSeconds * sampleRate).toInt())
        synth.noteOff(note)
        val tail = block(synth, (tailSeconds * sampleRate).toInt())
        return Rendered(hold, tail)
    }

    /** Left channel, rendered in 512-frame blocks like the audio thread does. */
    private fun block(synth: OrbitalSynth, frames: Int): FloatArray {
        val out = FloatArray(frames)
        var done = 0
        val tmp = FloatArray(512 * 2)
        while (done < frames) {
            val n = minOf(512, frames - done)
            synth.renderStereo(tmp, n)
            for (i in 0 until n) out[done + i] = tmp[2 * i]
            done += n
        }
        return out
    }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var sum = 0.0
        for (i in from until to) sum += x[i].toDouble() * x[i]
        return sqrt(sum / (to - from).coerceAtLeast(1))
    }

    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    private fun noteFor(voice: JazzVoice) = if (voice == JazzVoice.UPRIGHT_BASS) 40 else 60

    private fun seconds(s: Float) = (s * sampleRate).toInt()

    @Test fun thirteenVoicesOneFactoryPatchEachWithEveryParameterSet() {
        assertEquals(13, JazzVoice.entries.size)
        assertEquals(JazzVoice.entries.size, JazzVoices.ALL.size)
        assertEquals(JazzVoices.ALL.size, JazzVoices.ALL.map { it.id }.toSet().size)
        assertEquals(JazzVoice.entries.size, JazzVoice.entries.map { it.code }.toSet().size)
        for (voice in JazzVoice.entries) {
            val patch = JazzVoices.patch(voice)
            assertEquals("jazz-${voice.code}", patch.id)
            assertEquals(voice.label, patch.name)
            assertTrue("jazz" in patch.tags)
            // All 18 keys, so loading a voice fully replaces the previous sound.
            assertEquals(PatchParameters.NAMES, patch.parameters.keys)
            assertEquals(patch, PatchCodec.decode(PatchCodec.encode(patch)))
        }
    }

    @Test fun voicesUseTheEngineArchitecturesTheyAreDescribedWith() {
        fun p(v: JazzVoice, key: String) = JazzVoices.patch(v).parameters.getValue(key)
        // Physical models.
        for (v in listOf(JazzVoice.ARCHTOP_GUITAR, JazzVoice.NYLON_BOSSA, JazzVoice.UPRIGHT_BASS)) assertEquals(.5f, p(v, "SOURCE"), 0f)
        for (v in listOf(JazzVoice.VIBRAPHONE, JazzVoice.MARIMBA)) assertEquals(1f, p(v, "SOURCE"), 0f)
        // Architectures.
        for (v in listOf(JazzVoice.SUITCASE_EP, JazzVoice.STUDIO_PIANO)) assertEquals(.75f, p(v, "ENGINE"), 0f)
        for (v in listOf(JazzVoice.FLUTE, JazzVoice.CLAV_FUNK)) assertEquals(1f, p(v, "ENGINE"), 0f)
        assertEquals(.25f, p(JazzVoice.TENOR_SAX, "ENGINE"), 0f)
        // Additive drawbars, breath noise, vibrato, tremolo and tempo-synced swell.
        assertEquals(3, JazzVoices.patch(JazzVoice.DRAWBAR_ORGAN).oscillatorSlots.size)
        assertTrue(JazzVoices.patch(JazzVoice.TENOR_SAX).oscillatorSlots.any { it.noiseLevel == 1f })
        assertTrue(JazzVoices.patch(JazzVoice.VIBRAPHONE).fxSlots.any { it.type == FxModuleType.TREMOLO })
        val strings = JazzVoices.patch(JazzVoice.BALLAD_STRINGS)
        assertTrue(strings.modulationLfos.single().beatsPerCycle > 0f)
        assertEquals(ModDestination.CUTOFF, strings.modulationRoutes.single().destination)
    }

    @Test fun everyVoiceIsFiniteAudibleBoundedAndDecaysAfterNoteOff() {
        for (voice in JazzVoice.entries) {
            val patch = JazzVoices.patch(voice)
            val r = render(patch, noteFor(voice), holdSeconds = 0.6f, tailSeconds = 2.5f)
            assertTrue("${voice.code} not finite", (r.hold + r.tail).all { it.isFinite() })
            val level = rms(r.hold, seconds(0.1f), seconds(0.6f))
            assertTrue("${voice.code} is nearly silent (rms $level)", level > 0.004)
            assertTrue("${voice.code} peak ${peak(r.hold)} outside (0.02, 0.985]", peak(r.hold) in 0.02f..0.985f + 1e-4f)
            val tailEnd = rms(r.tail, seconds(2.25f), seconds(2.5f))
            assertTrue("${voice.code} does not release: tail $tailEnd vs hold $level", tailEnd < level)
        }
    }

    @Test fun strikesAndPluckDecayWhileBreathAndOrganSustain() {
        for (voice in JazzVoice.entries) {
            val r = render(JazzVoices.patch(voice), noteFor(voice), 0.9f, 0.05f)
            val early = rms(r.hold, seconds(0.04f), seconds(0.20f))
            val late = rms(r.hold, seconds(0.70f), seconds(0.90f))
            when (voice.family) {
                VoiceFamily.KEYS, VoiceFamily.PLUCK, VoiceFamily.MALLET ->
                    assertTrue("${voice.code} should decay while held: early $early late $late", late < early)
                VoiceFamily.WIND, VoiceFamily.SUSTAIN ->
                    assertTrue("${voice.code} should sustain while held: early $early late $late", late > 0.3 * early)
            }
        }
    }

    @Test fun everyVoiceSoundsDifferentFromEveryOther() {
        val renders = JazzVoice.entries.associateWith { render(JazzVoices.patch(it), 60, 0.4f, 0.05f).hold }
        val voices = JazzVoice.entries
        for (i in voices.indices) for (j in i + 1 until voices.size) {
            val a = renders.getValue(voices[i])
            val b = renders.getValue(voices[j])
            var diff = 0.0
            for (k in a.indices) diff += (a[k] - b[k]).toDouble().let { it * it }
            val relative = sqrt(diff / a.size) / maxOf(rms(a), rms(b), 1e-9)
            assertTrue("${voices[i].code} and ${voices[j].code} are too similar ($relative)", relative > 0.2)
        }
    }

    @Test fun lowVoiceSitsBelowTheBrightOnes() {
        // A crude but real brightness measure: the zero-crossing rate of a rendered note.
        fun crossings(x: FloatArray): Int = (1 until x.size).count { (x[it - 1] < 0f) != (x[it] < 0f) }
        val bass = render(JazzVoices.patch(JazzVoice.UPRIGHT_BASS), 40, 0.5f, 0.05f).hold
        val vibes = render(JazzVoices.patch(JazzVoice.VIBRAPHONE), 72, 0.5f, 0.05f).hold
        assertTrue("bass (E2) should cross zero far less often than vibes (C5)", crossings(bass) * 2 < crossings(vibes))
    }

    /** A plain sine through the voice's own vibrato route, measured by zero crossings. */
    private fun vibratoCents(voice: JazzVoice): Float {
        val patch = JazzVoices.patch(voice)
        val synth = OrbitalSynth(sampleRate)
        for (id in ParameterId.entries) synth.setParameter(id, 0f)
        synth.setParameter(ParameterId.CUTOFF, 1f)
        synth.setParameter(ParameterId.SUSTAIN, 1f)
        val lfo = patch.modulationLfos.single()
        synth.setModulationLfo(0, lfo.enabled, lfo.shape, lfo.rateHz, lfo.beatsPerCycle, lfo.retriggerOnNote)
        synth.setModulationRouteConfig(0, patch.modulationRoutes.single())
        synth.noteOn(69, 1f)
        val x = block(synth, sampleRate * 3).copyOfRange(sampleRate, sampleRate * 3)
        val crossings = ArrayList<Double>()
        for (i in 1 until x.size) if (x[i - 1] < 0f && x[i] >= 0f) crossings += i - 1 + (-x[i - 1] / (x[i] - x[i - 1])).toDouble()
        val cents = (1 until crossings.size).map { 1200.0 * log2(sampleRate / (crossings[it] - crossings[it - 1]) / 440.0) }
        return cents.maxOf { abs(it) }.toFloat()
    }

    @Test fun vibratoIsMusicalNotSeasick() {
        val sax = vibratoCents(JazzVoice.TENOR_SAX)
        val flute = vibratoCents(JazzVoice.FLUTE)
        assertTrue("sax vibrato $sax cents should be 10..30", sax in 10f..30f)
        assertTrue("flute vibrato $flute cents should be 5..20", flute in 5f..20f)
        assertTrue("flute should be gentler than sax", flute < sax)
    }

    @Test fun tailoredPatchesForEveryFeelAndVoiceAreStillFiniteAudibleAndBounded() {
        for (feel in JazzFeel.entries) for (voice in JazzVoice.entries) {
            for (key in listOf("Gm", "Bb")) {
                val tune = JazzStandard(
                    id = "t-${feel.code}-${voice.code}-$key".lowercase(), title = "Test ${feel.code} ${voice.code}",
                    composer = "Test", key = JazzKey.parse(key), feel = feel, bpm = feel.defaultBpm, voice = voice,
                )
                val patch = JazzCatalogue.defaultPatch(tune)
                val r = render(patch, noteFor(voice), 0.35f, 0.25f)
                val label = "${feel.code}/${voice.code}/$key"
                assertTrue("$label not finite", (r.hold + r.tail).all { it.isFinite() })
                assertTrue("$label silent", rms(r.hold, seconds(0.05f), seconds(0.35f)) > 0.003)
                assertTrue("$label exceeds ceiling", peak(r.hold) <= 0.985f + 1e-4f && peak(r.tail) <= 0.985f + 1e-4f)
            }
        }
    }

    @Test fun everyOneOfTheFourHundredDefaultSoundsRendersFiniteAudibleAndBounded() {
        for (tune in JazzCatalogue.REAL_BOOK_1) {
            val r = render(JazzCatalogue.defaultPatch(tune), noteFor(tune.voice), 0.3f, 0.1f)
            assertTrue("${tune.id} not finite", (r.hold + r.tail).all { it.isFinite() })
            assertTrue("${tune.id} silent", rms(r.hold, seconds(0.05f), seconds(0.3f)) > 0.003)
            assertTrue("${tune.id} exceeds ceiling", peak(r.hold) <= 0.985f + 1e-4f)
        }
    }
}
