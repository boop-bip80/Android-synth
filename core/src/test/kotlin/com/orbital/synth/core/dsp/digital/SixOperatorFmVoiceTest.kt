package com.orbital.synth.core.dsp.digital

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SixOperatorFmVoiceTest {
    @Test fun everyAlgorithmProducesBoundedAudibleOutputAtMusicalPitches() {
        for (algorithm in FmAlgorithm.entries) {
            val voice = SixOperatorFmVoice(48_000)
            voice.configure(algorithm, character = 0.9f, feedback = 0.8f)
            voice.noteOn(57, 0.82f)
            var energy = 0.0
            repeat(4_096) {
                val sample = voice.nextSample()
                assertTrue("non-finite output for " + algorithm, sample.isFinite())
                assertTrue("out-of-range output for " + algorithm, abs(sample) <= 1f)
                energy += sample * sample
            }
            assertTrue("algorithm must generate sound: " + algorithm, energy > 1.0)
        }
    }

    @Test fun highRegisterAndExtremeControllerAutomationRemainFinite() {
        val voice = SixOperatorFmVoice(48_000)
        for (operator in 0..5) {
            voice.configureOperator(
                operator, ratio = 16.0 + operator, level = 1f,
                modulationScale = 2f, detuneCents = 100f
            )
        }
        voice.noteOn(127, 1f)
        repeat(10_000) { frame ->
            if (frame % 100 == 0) {
                voice.configure(
                    FmAlgorithm.entries[(frame / 100) % FmAlgorithm.entries.size],
                    character = if (frame % 200 == 0) 1f else 0f,
                    feedback = if (frame % 300 == 0) 1f else 0f
                )
            }
            val s = voice.nextSample(pitchMultiplier = 1.5)
            assertTrue(s.isFinite())
            assertTrue(abs(s) <= 1f)
        }
    }

    @Test fun nextSampleIsDeterministicRegardlessOfRenderBlockPartition() {
        val whole = SixOperatorFmVoice(48_000)
        val fragments = SixOperatorFmVoice(48_000)
        whole.configure(FmAlgorithm.NEBULA, 0.77f, 0.2f)
        fragments.configure(FmAlgorithm.NEBULA, 0.77f, 0.2f)
        whole.noteOn(64)
        fragments.noteOn(64)
        val expected = FloatArray(4_800) { whole.nextSample() }
        val actual = FloatArray(4_800)
        var cursor = 0
        repeat(10) {
            repeat(480) { actual[cursor++] = fragments.nextSample() }
        }
        assertArrayEquals(expected, actual, 0f)
    }

    @Test fun algorithmsAndCharacterHaveActualMusicalEffect() {
        val dry = SixOperatorFmVoice(48_000)
        val modulated = SixOperatorFmVoice(48_000)
        dry.configure(FmAlgorithm.ADDITIVE, 0f, 0f)
        modulated.configure(FmAlgorithm.ELECTRIC_PIANO, 1f, 0.8f)
        dry.noteOn(69)
        modulated.noteOn(69)
        var difference = 0.0
        repeat(2_048) {
            difference += abs(dry.nextSample() - modulated.nextSample())
        }
        assertTrue("FM routing and character must alter waveform", difference > 10.0)
    }

    @Test fun independentOperatorEnvelopesReleaseAndLegatoRetunePreservePhases() {
        val voice = SixOperatorFmVoice(48_000)
        voice.setOperatorEnvelope(0, 0.002, 0.01, 0.4f, 0.04)
        voice.setOperatorEnvelope(5, 0.010, 0.2, 0.1f, 0.08)
        voice.noteOn(60, 0.8f)
        repeat(4_800) { voice.nextSample() }
        voice.retune(72)
        assertEquals(72, voice.note)
        assertTrue(voice.nextSample().isFinite())
        voice.noteOff()
        repeat(12_000) {
            assertTrue(voice.nextSample().isFinite())
        }
        assertFalse("release must retire the audio voice", voice.isActive)
        assertEquals(-1, voice.note)
    }

    @Test fun glideFrequencyOverrideTracksWithoutReinitializingEnvelope() {
        val voice = SixOperatorFmVoice(48_000)
        voice.noteOn(69)
        var last = 0f
        repeat(4_800) { frame ->
            last = voice.nextSampleAtFrequency(220.0 + frame * (220.0 / 4_800))
            assertTrue(last.isFinite())
        }
        assertTrue(voice.isActive)
        assertEquals(69, voice.note)
        voice.hardStop()
        assertFalse(voice.isActive)
        assertEquals(0f, voice.nextSample(), 0f)
    }
}
