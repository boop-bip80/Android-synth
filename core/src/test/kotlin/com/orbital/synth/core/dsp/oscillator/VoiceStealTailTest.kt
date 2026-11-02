package com.orbital.synth.core.dsp.oscillator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VoiceStealTailTest {
    @Test fun retriggerKeepsTheOldLowPassFilterStateInsteadOfInjectingDryAudio() {
        val reference = sustainedVoice(48_000, 69, 120.0)
        val stolen = sustainedVoice(48_000, 69, 120.0)
        repeat(5_000) {
            reference.nextSample(0.75f, 1f, 1.0)
            stolen.nextSample(0.75f, 1f, 1.0)
        }

        // An inaudible new note isolates the fading ghost from the new voice.
        stolen.begin(80, 0f, 2L, null, 0.0, 0.75f, 1f)
        stolen.preparePitch(1.0)
        assertTrue("A retrigger must preserve the audible old note", stolen.hasTail)
        for (frame in 0 until 64) {
            val expected = reference.nextSample(0.75f, 1f, 1.0) *
                ((64 - frame) / 64f)
            val actual = stolen.nextSample(0.75f, 1f, 1.0)
            assertEquals("Filtered ghost diverged at frame $frame", expected, actual, 0.0001f)
        }
        assertTrue("Ghost tail must terminate after 64 frames", !stolen.hasTail)
    }

    @Test fun stolenHighNotePreservesLastBendIncrementAndWaveformAtLowSampleRates() {
        val reference = sustainedVoice(8_000, 127, 3_000.0)
        val stolen = sustainedVoice(8_000, 127, 3_000.0)
        reference.configureUnison(0.8f, 0.7f)
        stolen.configureUnison(0.8f, 0.7f)
        // The pitch-multiplied center oscillator is limited to 0.45 cycles/frame
        // at 8 kHz; rebuilding the ghost rate from MIDI frequency aliases.
        reference.preparePitch(1.7)
        stolen.preparePitch(1.7)
        repeat(4_000) {
            reference.nextSample(1f, 1f, 1.7)
            stolen.nextSample(1f, 1f, 1.7)
        }

        // New-note controls are deliberately different: ghost should use the
        // previous rendered tone, not the controls arriving with its replacement.
        stolen.begin(60, 0f, 2L, null, 0.0, 0f, 0f)
        stolen.preparePitch(1.0)
        var maxDifference = 0f
        for (frame in 0 until 64) {
            val expected = reference.nextSample(1f, 1f, 1.7) * ((64 - frame) / 64f)
            val actual = stolen.nextSample(0f, 0f, 1.0)
            maxDifference = maxOf(maxDifference, abs(expected - actual))
            assertTrue("Ghost sample at $frame is not finite", actual.isFinite())
        }
        assertTrue("Stolen tone changed its pitch/timbre: $maxDifference",
            maxDifference < 0.005f)
    }

    @Test fun changingWaveAndMorphOnRetriggerDoesNotRewriteTheOldTail() {
        val reference = sustainedVoice(48_000, 69, 18_000.0)
        val stolen = sustainedVoice(48_000, 69, 18_000.0)
        repeat(3_000) {
            reference.nextSample(1f, 1f, 1.0)
            stolen.nextSample(1f, 1f, 1.0)
        }

        stolen.begin(60, 0f, 2L, null, 0.0, 0f, 0f)
        stolen.preparePitch(1.0)
        for (frame in 0 until 64) {
            val expected = reference.nextSample(1f, 1f, 1.0) * ((64 - frame) / 64f)
            val actual = stolen.nextSample(0f, 0f, 1.0)
            assertEquals("Old timbre was replaced at frame $frame", expected, actual, 0.0001f)
        }
    }

    private fun sustainedVoice(sampleRate: Int, note: Int, cutoff: Double): Voice =
        Voice(sampleRate).apply {
            configure(
                attackSeconds = 0.001,
                decaySeconds = 0.1,
                sustainLevel = 1f,
                releaseSeconds = 0.3,
                cutoffHz = cutoff,
                resonance = 0.2f
            )
            begin(note, 1f, 1L, null, 0.0, 0.75f, 1f)
            preparePitch(1.0)
        }
}
