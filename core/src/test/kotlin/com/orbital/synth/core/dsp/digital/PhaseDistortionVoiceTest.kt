package com.orbital.synth.core.dsp.digital

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhaseDistortionVoiceTest {
    @Test fun allSixShapesAreAudibleFiniteAndBounded() {
        for (shape in PhaseDistortionShape.entries) {
            val voice = PhaseDistortionVoice(48_000)
            voice.configure(shape, character = 0.95f, drive = 1f)
            voice.noteOn(60, 0.9f)
            var energy = 0.0
            repeat(5_000) {
                val sample = voice.nextSample()
                assertTrue("Non-finite phase-distortion sample: " + shape, sample.isFinite())
                assertTrue("Unbounded phase-distortion sample: " + shape, abs(sample) <= 1f)
                energy += sample * sample
            }
            assertTrue("Silent phase-distortion shape: " + shape, energy > 1.0)
        }
    }

    @Test fun colorAndShapeProduceDifferentTimbres() {
        val a = PhaseDistortionVoice(48_000).apply {
            configure(PhaseDistortionShape.SAW, 0.1f)
            noteOn(69)
        }
        val b = PhaseDistortionVoice(48_000).apply {
            configure(PhaseDistortionShape.RESONANT, 0.95f)
            noteOn(69)
        }
        var diff = 0.0
        repeat(3_200) {
            diff += abs(a.nextSample() - b.nextSample())
        }
        assertTrue("Different phase curves must change sound", diff > 30.0)
    }

    @Test fun phaseAndEnvelopeAreIndependentOfBlockPartitioning() {
        val whole = PhaseDistortionVoice(44_100).apply {
            configure(PhaseDistortionShape.FORMANT, 0.7f)
            noteOn(66, 0.75f)
        }
        val pieces = PhaseDistortionVoice(44_100).apply {
            configure(PhaseDistortionShape.FORMANT, 0.7f)
            noteOn(66, 0.75f)
        }
        val expected = FloatArray(4_410) { whole.nextSample() }
        val actual = FloatArray(4_410)
        var cursor = 0
        repeat(7) {
            repeat(630) { actual[cursor++] = pieces.nextSample() }
        }
        assertArrayEquals(expected, actual, 0f)
    }

    @Test fun releaseAndHardStopRetireVoices() {
        val voice = PhaseDistortionVoice(48_000)
        voice.setEnvelope(0.0, 0.1, 0.65f, 0.03)
        voice.noteOn(72)
        repeat(2_000) { voice.nextSample() }
        voice.retune(74)
        assertEquals(74, voice.note)
        voice.noteOff()
        repeat(2_000) { assertTrue(voice.nextSample().isFinite()) }
        assertFalse(voice.isActive)
        assertEquals(-1, voice.note)
        voice.noteOn(50)
        voice.hardStop()
        assertEquals(0f, voice.nextSample(), 0f)
    }

    @Test fun highPitchAndLiveShapeChangesRemainNumericallySafe() {
        val voice = PhaseDistortionVoice(96_000)
        voice.noteOn(127)
        repeat(8_000) { frame ->
            if (frame % 107 == 0) {
                voice.configure(
                    PhaseDistortionShape.entries[(frame / 107) % PhaseDistortionShape.entries.size],
                    character = if (frame % 2 == 0) 1f else 0f,
                    drive = 1f
                )
            }
            val sample = voice.nextSampleAtFrequency(9_000.0, 2.0)
            assertTrue(sample.isFinite())
            assertTrue(abs(sample) <= 1f)
        }
    }

    @Test fun invalidNotesDoNotStartVoice() {
        val voice = PhaseDistortionVoice(48_000)
        voice.noteOn(-1)
        assertFalse(voice.isActive)
        voice.noteOn(128)
        assertFalse(voice.isActive)
        voice.noteOn(60, Float.NaN)
        assertFalse(voice.isActive)
    }
}
