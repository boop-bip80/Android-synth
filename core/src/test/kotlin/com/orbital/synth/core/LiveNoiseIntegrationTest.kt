package com.orbital.synth.core

import com.orbital.synth.core.preset.FactoryPatches
import com.orbital.synth.core.preset.PatchCodec
import com.orbital.synth.core.preset.loadPatch
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** PCM regressions for genuine per-voice, per-slot noise-morph oscillator audio. */
class LiveNoiseIntegrationTest {
    private fun synth(voices: Int = 4): OrbitalSynth =
        OrbitalSynth(sampleRate = 8_000, maxVoices = voices).apply {
            setParameter(ParameterId.ATTACK, 0f)
            setParameter(ParameterId.SUSTAIN, 1f)
            setParameter(ParameterId.RELEASE, 0f)
            setParameter(ParameterId.CUTOFF, 1f)
            setParameter(ParameterId.FX_AMOUNT, 0f)
        }

    private fun noiseSlot(level: Float, color: Float) = OscillatorSlot(
        enabled = true, waveform = .42f, morph = .8f, level = .85f,
        coarseSemitones = -12, fineCents = 7f,
        noiseLevel = level, noiseColor = color,
    )

    @Test fun whitePinkBrownAreAudibleAcrossAllFiveRealSynthesisEngines() {
        for (engine in 0..4) {
            for (color in floatArrayOf(0f, .5f, 1f)) {
                val dry = synth()
                val designed = synth()
                for (candidate in listOf(dry, designed)) {
                    candidate.setParameter(ParameterId.ENGINE, engine / 4f)
                    candidate.setParameter(ParameterId.CHARACTER, .5f)
                }
                dry.setOscillatorSlot(0, noiseSlot(0f, color))
                designed.setOscillatorSlot(0, noiseSlot(1f, color))
                dry.noteOn(69)
                designed.noteOn(69)
                val plain = FloatArray(4_096 * 2)
                val wet = FloatArray(plain.size)
                dry.renderStereo(plain, 4_096)
                designed.renderStereo(wet, 4_096)
                var pcmDifference = 0.0
                for (i in plain.indices) {
                    pcmDifference += abs((plain[i] - wet[i]).toDouble())
                    assertTrue("Nonfinite engine $engine color $color",
                        wet[i].isFinite() && abs(wet[i]) <= 1f)
                }
                assertTrue("Colored oscillator must genuinely alter engine $engine PCM",
                    pcmDifference > 5.0)
            }
        }
    }

    @Test fun legacyToneWithNoNoisePreservesBitIdenticalOutputAndExistingControls() {
        val existing = synth()
        val v6 = synth()
        val tone = noiseSlot(0f, 1f)
        existing.setOscillatorSlot(0, tone.enabled, tone.waveform, tone.level,
            tone.coarseSemitones, tone.fineCents, tone.morph)
        v6.setOscillatorSlot(0, tone)
        existing.noteOn(64)
        v6.noteOn(64)
        val a = FloatArray(8_192)
        val b = FloatArray(a.size)
        existing.renderStereo(a, a.size / 2)
        v6.renderStereo(b, b.size / 2)
        assertArrayEquals("NoiseLevel=0 must cost no wavetable PCM difference", a, b, 0f)
        assertEquals(0f, existing.oscillatorSlot(0).noiseLevel, 0f)
        assertEquals(1f, v6.oscillatorSlot(0).noiseColor, 0f)
    }

    @Test fun touchNoiseKnobsPreserveConcurrentWaveControlsAndFadeWithoutJump() {
        val reference = synth()
        val designed = synth()
        for (s in listOf(reference, designed)) s.setOscillatorSlot(0, noiseSlot(0f, 0f))
        reference.noteOn(60)
        designed.noteOn(60)
        reference.renderStereo(FloatArray(2_048), 1_024)
        designed.renderStereo(FloatArray(2_048), 1_024)

        designed.setOscillatorNoise(0, 1f, .8f)
        // Old seven-parameter UI controls must not erase the independently
        // edited noise blend or color when they publish a newer oscillator slot.
        designed.setOscillatorSlot(0, true, .67f, .85f, -12, 7f, .8f)
        assertEquals(1f, designed.oscillatorSlot(0).noiseLevel, 0f)
        assertEquals(.8f, designed.oscillatorSlot(0).noiseColor, 0f)
        reference.setOscillatorSlot(0, true, .67f, .85f, -12, 7f, .8f)

        val dry = FloatArray(64 * 2)
        val mix = FloatArray(dry.size)
        reference.renderStereo(dry, 64)
        designed.renderStereo(mix, 64)
        // First frame has only a 1/64 slew step; the noise source is not
        // instantaneously slammed at full level by the touch event.
        assertTrue(abs(mix[0] - dry[0]) < .12f)
        assertTrue(abs(mix[1] - dry[1]) < .12f)
        val moreDry = FloatArray(2_048)
        val moreNoise = FloatArray(moreDry.size)
        reference.renderStereo(moreDry, 1_024)
        designed.renderStereo(moreNoise, 1_024)
        assertTrue(moreDry.indices.sumOf {
            abs((moreDry[it] - moreNoise[it]).toDouble())
        } > 5.0)
    }

    @Test fun coloredOscillatorsAreExactlyBufferSizeInvariant() {
        val whole = synth()
        val by128 = synth()
        for (s in listOf(whole, by128)) {
            for (slot in 0..3) s.setOscillatorSlot(slot, noiseSlot(
                level = if (slot == 0) 1f else .45f,
                color = slot / 3f,
            ))
            s.noteOn(60)
        }
        val expected = FloatArray(8_192 * 2)
        val actual = FloatArray(expected.size)
        whole.renderStereo(expected, 8_192)
        val chunk = FloatArray(128 * 2)
        for (offset in actual.indices step chunk.size) {
            by128.renderStereo(chunk, 128)
            System.arraycopy(chunk, 0, actual, offset, chunk.size)
        }
        assertArrayEquals(expected, actual, 0f)
    }

    @Test fun sixteenVoiceEightLaneNoiseChordIsFiniteAndNotesAreReleased() {
        val synth = synth(16)
        for (slot in 0..7) {
            synth.setOscillatorSlot(slot, noiseSlot(
                level = if (slot % 2 == 0) .6f else 1f,
                color = (slot % 3) / 2f,
            ))
        }
        for (note in 48..63) synth.noteOn(note)
        val audio = FloatArray(6_144 * 2)
        synth.renderStereo(audio, 6_144)
        assertEquals(16, synth.activeVoiceCount)
        assertTrue(audio.any { abs(it) > .005f })
        assertTrue(audio.all { it.isFinite() && abs(it) <= 1f })
        synth.allNotesOff()
        synth.renderStereo(FloatArray(8_192 * 2), 8_192)
        // activeVoiceCount is the block-start snapshot; refresh after release.
        synth.renderStereo(FloatArray(2), 1)
        assertEquals("No stuck active keys after releasing a dense noise chord",
            0, synth.activeVoiceCount)
    }

    @Test fun diskFormatV6AudioRecallMatchesDirectTouchProgramming() {
        val patch = FactoryPatches.DEFAULT.copy(
            id = "stored-colored-osc",
            oscillatorSlots = listOf(noiseSlot(.9f, .5f)),
        )
        val encoded = PatchCodec.encode(patch)
        val stored = synth()
        val edited = synth()
        stored.loadPatch(PatchCodec.decode(encoded))
        edited.loadPatch(FactoryPatches.DEFAULT)
        edited.setOscillatorSlot(0, noiseSlot(.9f, .5f))
        stored.noteOn(72)
        edited.noteOn(72)
        val a = FloatArray(4_096 * 2)
        val b = FloatArray(a.size)
        stored.renderStereo(a, 4_096)
        edited.renderStereo(b, 4_096)
        assertArrayEquals("V6 patch recall must program the real noise audio worker",
            a, b, 0f)
        assertTrue(a.any { abs(it) > .001f })
    }
}
