package com.orbital.synth.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SourceNoteOwnershipTest {
    @Test fun samePitchFromTouchTwoMidiPortsAndLegacyReleasesIndependently() {
        val synth = testSynth()
        synth.noteOn(69)
        synth.noteOn(-1L, 0, 69)
        synth.noteOn(100L, 0, 69)
        synth.noteOn(101L, 0, 69)
        tick(synth, 512)
        assertEquals(4, synth.activeVoiceCount)

        synth.noteOff(100L, 0, 69)
        tick(synth)
        assertEquals(3, synth.activeVoiceCount)

        synth.noteOff(69) // Legacy API cannot release a tagged hold.
        tick(synth)
        assertEquals(2, synth.activeVoiceCount)

        synth.noteOff(-1L, 0, 69)
        tick(synth)
        assertEquals(1, synth.activeVoiceCount)

        synth.noteOff(101L, 0, 69)
        tick(synth)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun repeatedTaggedOnsStackAndExtraOffsNeverReleaseAnotherSource() {
        val synth = testSynth()
        synth.noteOn(7L, 3, 60, 0.4f)
        synth.noteOn(7L, 3, 60, 0.9f)
        synth.noteOn(8L, 3, 60)
        tick(synth, 512)
        assertEquals(3, synth.activeVoiceCount)

        synth.noteOff(7L, 3, 60)
        tick(synth)
        assertEquals(2, synth.activeVoiceCount)
        synth.noteOff(7L, 3, 60)
        tick(synth)
        assertEquals(1, synth.activeVoiceCount)
        synth.noteOff(7L, 3, 60) // unmatched duplicate off
        synth.noteOff(7L, 2, 60) // wrong channel
        tick(synth)
        assertEquals(1, synth.activeVoiceCount)

        synth.noteOff(8L, 3, 60)
        tick(synth)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun stolenVoiceOwnerCannotReleaseTheNewOwnerAndSurvivorIsReinstated() {
        val synth = testSynth(maxVoices = 1)
        synth.noteOn(10L, 0, 60, 0.8f)
        tick(synth, 512)
        synth.noteOn(20L, 0, 72, 1f) // steals the single voice from source 10
        tick(synth, 512)
        assertEquals(1, synth.activeVoiceCount)

        synth.noteOff(10L, 0, 60) // old owner has no voice to release
        tick(synth, 512)
        assertEquals(1, synth.activeVoiceCount)

        synth.noteOn(10L, 0, 60) // steals it back
        tick(synth, 512)
        synth.noteOff(10L, 0, 60)
        tick(synth, 512)
        // Source 20 is still held. Recycle the free slot instead of silencing it.
        assertEquals(1, synth.activeVoiceCount)
        synth.noteOff(20L, 0, 72)
        tick(synth)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun samePitchOwnersOnAOneVoiceSynthRemainSoundingUntilFinalRelease() {
        val synth = testSynth(maxVoices = 1)
        synth.noteOn(-1L, 0, 69)
        synth.noteOn(100L, 0, 69)
        tick(synth, 512)
        synth.noteOff(100L, 0, 69)
        val audio = tick(synth, 1_024)
        assertEquals(1, synth.activeVoiceCount)
        assertTrue(audio.any { abs(it) > 0.0001f })

        synth.noteOff(-1L, 0, 69)
        tick(synth)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun scopedAllNotesOffDoesNotInterruptOtherSourcesOrLegacy() {
        val synth = testSynth()
        synth.noteOn(60)
        synth.noteOn(-1L, 0, 61)
        synth.noteOn(-1L, 1, 62)
        synth.noteOn(100L, 0, 63)
        tick(synth, 512)
        assertEquals(4, synth.activeVoiceCount)

        synth.allNotesOff(-1L) // Touch octave change, not global panic.
        tick(synth)
        assertEquals(2, synth.activeVoiceCount)
        synth.noteOff(-1L, 0, 61) // stale off cannot affect others
        tick(synth, 512)
        assertEquals(2, synth.activeVoiceCount)

        synth.allNotesOff()
        tick(synth)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun monoAndLegatoFallBackToTheLatestStillHeldTaggedSource() {
        for (mode in listOf(VoiceMode.MONO, VoiceMode.LEGATO)) {
            val synth = testSynth(maxVoices = 3)
            synth.setVoiceMode(mode)
            synth.noteOn(-1L, 0, 60)
            synth.noteOn(20L, 0, 64)
            synth.noteOn(30L, 0, 67)
            val first = tick(synth, 48_000)
            assertEquals(1, synth.activeVoiceCount)
            assertTrue(abs(estimateFrequency(first, 48_000) - 392.0) < 6.0)

            // Off from the nonlead source must not retune the current lead.
            synth.noteOff(20L, 0, 64)
            val unchanged = tick(synth, 48_000)
            assertTrue(abs(estimateFrequency(unchanged, 48_000) - 392.0) < 6.0)
            synth.allNotesOff(30L)
            val fallback = tick(synth, 48_000)
            assertEquals(1, synth.activeVoiceCount)
            assertTrue(abs(estimateFrequency(fallback, 48_000) - 261.63) < 6.0)

            synth.noteOff(-1L, 0, 60)
            tick(synth)
            assertEquals(0, synth.activeVoiceCount)
        }
    }

    @Test fun changingMonoToPolyRestoresDistinctOwnedHoldsEvenAtTheSamePitch() {
        val synth = testSynth(maxVoices = 3)
        synth.setVoiceMode(VoiceMode.MONO)
        synth.noteOn(-1L, 0, 69)
        synth.noteOn(7L, 0, 69)
        synth.noteOn(8L, 0, 72)
        tick(synth, 512)
        assertEquals(1, synth.activeVoiceCount)

        synth.setVoiceMode(VoiceMode.POLY)
        tick(synth, 512)
        assertEquals(3, synth.activeVoiceCount)
        synth.noteOff(7L, 0, 69)
        tick(synth)
        assertEquals(2, synth.activeVoiceCount)
        synth.noteOff(-1L, 0, 69)
        tick(synth)
        assertEquals(1, synth.activeVoiceCount)
        synth.noteOff(8L, 0, 72)
        tick(synth)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun invalidTaggedEventsHaveNoSideEffectsAndVelocityZeroIsAScopedOff() {
        val synth = testSynth()
        synth.noteOn(-1L, 0, 69)
        synth.noteOn(7L, 0, 69)
        synth.noteOn(7L, 16, 69) // invalid channel
        synth.noteOn(7L, 0, 128) // invalid pitch
        synth.noteOn(7L, 0, 60, Float.NaN)
        tick(synth, 512)
        assertEquals(2, synth.activeVoiceCount)

        synth.noteOn(7L, 0, 69, 0f)
        tick(synth)
        assertEquals(1, synth.activeVoiceCount)
        synth.noteOff(7L, 16, 69)
        tick(synth, 512)
        assertEquals(1, synth.activeVoiceCount)
        synth.noteOff(-1L, 0, 69)
        tick(synth)
        assertEquals(0, synth.activeVoiceCount)
    }

    private fun testSynth(maxVoices: Int = 16) = OrbitalSynth(maxVoices = maxVoices).apply {
        setParameter(ParameterId.WAVE, 0f)
        setParameter(ParameterId.MORPH, 0f)
        setParameter(ParameterId.CUTOFF, 1f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.ATTACK, 0f)
        setParameter(ParameterId.RELEASE, 0f)
        setParameter(ParameterId.GLIDE, 0f)
    }

    private fun tick(synth: OrbitalSynth, frames: Int = 4_096): FloatArray =
        FloatArray(frames * 2).also { synth.renderStereo(it, frames) }

    private fun estimateFrequency(audio: FloatArray, sampleRate: Int): Double {
        val start = sampleRate / 4
        val end = sampleRate * 3 / 4
        var crossings = 0
        var previous = audio[2 * (start - 1)]
        for (frame in start until end) {
            val sample = audio[2 * frame]
            if (previous <= 0f && sample > 0f) crossings++
            previous = sample
        }
        return crossings * sampleRate.toDouble() / (end - start)
    }
}
