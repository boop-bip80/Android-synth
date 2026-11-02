package com.orbital.synth.core

import com.orbital.synth.core.dsp.oscillator.Voice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class OrbitalSynthTest {
    @Test fun silenceIsExactlyZeroAndWritesOnlyRequestedFrames() {
        val synth = OrbitalSynth()
        val output = FloatArray(128) { 7f }
        synth.renderStereo(output, 48)
        for (i in 0 until 96) assertEquals(0f, output[i], 0f)
        for (i in 96 until output.size) assertEquals(7f, output[i], 0f)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun a4HasCorrectPitchAt48000And44100() {
        assertFrequency(sampleRate = 48_000, expectedHz = 440.0)
        assertFrequency(sampleRate = 44_100, expectedHz = 440.0)
    }

    @Test fun bendMovesByExactOctaveAndClampsToTwelveSemitones() {
        val synth = pureSine()
        synth.setPitchBend(1000f)
        synth.noteOn(69)
        val output = FloatArray(48_000 * 2)
        synth.renderStereo(output, 48_000)
        assertTrue(abs(estimateHz(output, 48_000, 8_000, 45_000) - 880.0) < 8.0)
        synth.setPitchBend(Float.NaN) // ignored; no poisoning of audio path
        synth.renderStereo(output, 48_000)
        assertBounded(output)
        assertTrue(abs(estimateHz(output, 48_000, 8_000, 45_000) - 880.0) < 8.0)
    }

    @Test fun deterministicSamplesAcrossIndependentInstances() {
        val a = OrbitalSynth()
        val b = OrbitalSynth()
        for (synth in listOf(a, b)) {
            synth.noteOn(60, 0.8f)
            synth.noteOn(67, 0.55f)
            synth.setParameter(ParameterId.MORPH, 0.93f)
            synth.setParameter(ParameterId.MOVEMENT, 0.63f)
        }
        val left = FloatArray(4096)
        val right = FloatArray(4096)
        a.renderStereo(left, 2048)
        b.renderStereo(right, 2048)
        assertArrayEquals(left, right, 0f)
        assertTrue(rms(left) > 0.001)
    }

    @Test fun extendedIdleRenderingKeepsFutureNotesIdenticalAcrossBufferSizes() {
        val largeBuffers = OrbitalSynth()
        val smallBuffers = OrbitalSynth()
        for (synth in listOf(largeBuffers, smallBuffers)) {
            synth.setParameter(ParameterId.MOVEMENT, 0.85f)
            synth.setParameter(ParameterId.LFO_RATE, 0.71f)
            synth.setParameter(ParameterId.LFO_DEPTH, 0.72f)
        }

        // The optimized silence path must advance time without touching the guard region.
        val silentBlock = FloatArray(48_000 * 2 + 4) { 9f }
        largeBuffers.renderStereo(silentBlock, 48_000)
        for (index in 0 until 48_000 * 2) assertEquals(0f, silentBlock[index], 0f)
        for (index in 48_000 * 2 until silentBlock.size) {
            assertEquals(9f, silentBlock[index], 0f)
        }

        val shortBlock = FloatArray(480 * 2)
        repeat(100) { smallBuffers.renderStereo(shortBlock, 480) }
        largeBuffers.noteOn(69)
        smallBuffers.noteOn(69)
        val a = FloatArray(8_192)
        val b = FloatArray(a.size)
        largeBuffers.renderStereo(a, a.size / 2)
        smallBuffers.renderStereo(b, b.size / 2)
        assertArrayEquals("Idle rendering changed future oscillator/LFO timing", a, b, 0f)
        assertTrue(rms(a) > 0.001)
    }

    @Test fun sixteenNotesAreBoundedAndTheSeventeenthStealsAvoice() {
        val synth = OrbitalSynth(maxVoices = 16)
        for (note in 48..63) synth.noteOn(note, 0.9f)
        val audio = FloatArray(2048)
        synth.renderStereo(audio, 1024)
        assertEquals(16, synth.activeVoiceCount)
        assertBounded(audio)
        synth.noteOn(80, 1f)
        synth.renderStereo(audio, 1024)
        assertEquals(16, synth.activeVoiceCount)
        assertBounded(audio)
    }

    @Test fun releaseEventuallyStopsAllVoices() {
        val synth = OrbitalSynth()
        synth.setParameter(ParameterId.RELEASE, 0f)
        synth.noteOn(60)
        val output = FloatArray(8192 * 2)
        synth.renderStereo(output, 8192)
        synth.allNotesOff()
        synth.renderStereo(output, 8192)
        assertEquals(0, synth.activeVoiceCount)
        for (i in 7_000 until 8_192) {
            assertEquals(0f, output[2 * i], 0.00001f)
            assertEquals(0f, output[2 * i + 1], 0.00001f)
        }
    }

    @Test fun polyMonoLegatoVoiceCountsAndHeldNoteFallback() {
        val synth = pureSine()
        val output = FloatArray(48_000 * 2)
        synth.noteOn(60)
        synth.noteOn(64)
        synth.renderStereo(output, 1_024)
        assertEquals(2, synth.activeVoiceCount)

        synth.setParameter(ParameterId.RELEASE, 0f)
        synth.setVoiceMode(VoiceMode.MONO)
        synth.renderStereo(output, 48_000) // polyphonic release tails are now finished
        assertEquals(1, synth.activeVoiceCount)
        assertEquals(VoiceMode.MONO, synth.voiceMode)

        synth.noteOn(67)
        synth.renderStereo(output, 48_000)
        assertEquals(1, synth.activeVoiceCount)
        assertTrue(abs(estimateHz(output, 48_000, 4_000, 46_000) - 391.995) < 5.0)

        // Releasing the newest held key restores the previous held key.
        synth.noteOff(67)
        synth.renderStereo(output, 48_000)
        assertEquals(1, synth.activeVoiceCount)
        assertTrue(abs(estimateHz(output, 48_000, 4_000, 46_000) - 329.628) < 5.0)

        synth.setVoiceMode(VoiceMode.LEGATO)
        synth.noteOn(72)
        synth.renderStereo(output, 48_000)
        assertEquals(1, synth.activeVoiceCount)
        assertEquals(VoiceMode.LEGATO, synth.voiceMode)
        assertTrue(abs(estimateHz(output, 48_000, 4_000, 46_000) - 523.251) < 7.0)
    }

    @Test fun legatoRetunesWithoutRetriggeringEnvelope() {
        val voice = Voice(sampleRate = 48_000)
        voice.configure(0.01, 0.1, 0.8f, 0.1, 10_000.0, 0.2f)
        voice.begin(60, 1f, 1L, null, 0.0, 0f, 0f)
        voice.preparePitch(1.0)
        repeat(1_000) { voice.nextSample(0f, 0f, 1.0) }
        val level = voice.envelopeLevel
        assertTrue(level > 0.1f)
        voice.begin(64, 0.7f, 2L, null, 0.0, 0f, 0f, legato = true)
        assertEquals(level, voice.envelopeLevel, 0f)
        assertEquals(64, voice.note)
        voice.begin(67, 1f, 3L, null, 0.0, 0f, 0f, legato = false)
        assertEquals(0f, voice.envelopeLevel, 0f)
    }

    @Test fun returningFromMonoToPolyRestoresHeldChordWithoutDroppingLead() {
        val synth = OrbitalSynth(maxVoices = 3)
        synth.setParameter(ParameterId.RELEASE, 0f)
        val audio = FloatArray(8_192)
        synth.setVoiceMode(VoiceMode.MONO)
        synth.noteOn(60)
        synth.noteOn(64)
        synth.noteOn(67)
        synth.renderStereo(audio, 1_024)
        assertEquals(1, synth.activeVoiceCount)

        synth.setVoiceMode(VoiceMode.POLY)
        synth.renderStereo(audio, 1_024)
        assertEquals("All three held keys should sound again in poly", 3,
            synth.activeVoiceCount)

        // There is no phantom note: releasing each held key releases its voice.
        synth.noteOff(60)
        synth.renderStereo(audio, 4_096)
        assertEquals(2, synth.activeVoiceCount)
        synth.noteOff(64)
        synth.noteOff(67)
        synth.renderStereo(audio, 4_096)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun returningFromMonoToPolyRestoresStackedHoldsWithinVoiceLimit() {
        val synth = OrbitalSynth(maxVoices = 2)
        synth.setParameter(ParameterId.RELEASE, 0f)
        val audio = FloatArray(8_192)
        synth.setVoiceMode(VoiceMode.LEGATO)
        synth.noteOn(69)
        synth.noteOn(69)
        synth.renderStereo(audio, 512)
        assertEquals(1, synth.activeVoiceCount)

        synth.setVoiceMode(VoiceMode.POLY)
        synth.renderStereo(audio, 512)
        assertEquals(2, synth.activeVoiceCount)
        synth.noteOff(69)
        synth.renderStereo(audio, 4_096)
        assertEquals(1, synth.activeVoiceCount)
        synth.noteOff(69)
        synth.renderStereo(audio, 4_096)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun wavetableMorphAndFilterProduceAudiblyDifferentOutputs() {
        val sine = pureSine()
        val glass = OrbitalSynth()
        glass.setParameter(ParameterId.WAVE, 1f)
        glass.setParameter(ParameterId.MORPH, 1f)
        glass.setParameter(ParameterId.MOVEMENT, 0f)
        sine.noteOn(60)
        glass.noteOn(60)
        val a = FloatArray(8192)
        val b = FloatArray(8192)
        sine.renderStereo(a, 4096)
        glass.renderStereo(b, 4096)
        var difference = 0.0
        for (i in a.indices) difference += abs((a[i] - b[i]).toDouble())
        assertTrue(difference > 20.0)

        val low = OrbitalSynth()
        val high = OrbitalSynth()
        for (s in listOf(low, high)) {
            s.noteOn(96)
            s.setParameter(ParameterId.ATTACK, 0f)
            s.setParameter(ParameterId.MOVEMENT, 0f)
        }
        low.setParameter(ParameterId.CUTOFF, 0f)
        high.setParameter(ParameterId.CUTOFF, 1f)
        low.renderStereo(a, 4096)
        high.renderStereo(b, 4096)
        assertTrue(rms(b) > rms(a) * 2.0)
    }

    @Test fun invalidNotesAndParametersCannotProduceNanOrOverflow() {
        val synth = OrbitalSynth()
        synth.noteOn(-10)
        synth.noteOn(128)
        synth.noteOn(60, Float.NaN)
        synth.noteOn(60, Float.POSITIVE_INFINITY)
        synth.setParameter(ParameterId.CUTOFF, Float.NaN)
        assertEquals(OrbitalSynth.defaultValue(ParameterId.CUTOFF),
            synth.parameter(ParameterId.CUTOFF), 0f)
        synth.setParameter(ParameterId.CUTOFF, -100f)
        assertEquals(0f, synth.parameter(ParameterId.CUTOFF), 0f)
        synth.setParameter(ParameterId.CUTOFF, 200f)
        assertEquals(1f, synth.parameter(ParameterId.CUTOFF), 0f)
        val audio = FloatArray(1024)
        synth.renderStereo(audio, 512)
        for (sample in audio) assertEquals(0f, sample, 0f)
        synth.noteOn(69, 100f)
        synth.renderStereo(audio, 512)
        assertBounded(audio)

        try {
            OrbitalSynth(sampleRate = 100)
            fail("Too-low sample rate must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            OrbitalSynth(maxVoices = 0)
            fail("Zero voices must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            synth.renderStereo(FloatArray(20), 11)
            fail("Undersized output buffer must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun noteOnVelocityZeroActsAsNoteOff() {
        val synth = OrbitalSynth()
        synth.setParameter(ParameterId.RELEASE, 0f)
        val audio = FloatArray(8192 * 2)
        synth.noteOn(69)
        synth.renderStereo(audio, 1024)
        assertEquals(1, synth.activeVoiceCount)
        synth.noteOn(69, 0f)
        synth.renderStereo(audio, 8192)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun overlappingSamePitchNotesReleaseOneVoiceAtATime() {
        val synth = OrbitalSynth(maxVoices = 4)
        synth.setParameter(ParameterId.RELEASE, 0f)
        val audio = FloatArray(4096 * 2)
        synth.noteOn(60)
        synth.renderStereo(audio, 512)
        synth.noteOn(60)
        synth.renderStereo(audio, 512)
        assertEquals(2, synth.activeVoiceCount)

        // A sequencer note-off must not kill a touch voice sustaining the same pitch.
        synth.noteOff(60)
        synth.renderStereo(audio, 4096)
        assertEquals(1, synth.activeVoiceCount)
        assertTrue(rms(audio) > 0.001)

        synth.noteOff(60)
        synth.renderStereo(audio, 4096)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun repeatedSamePitchIsHeldUntilFinalReleaseWithOneVoiceCapacity() {
        val synth = OrbitalSynth(maxVoices = 1)
        synth.setVoiceMode(VoiceMode.LEGATO)
        synth.setParameter(ParameterId.RELEASE, 0f)
        val audio = FloatArray(4096 * 2)
        synth.noteOn(69)
        synth.noteOn(69)
        synth.renderStereo(audio, 512)
        assertEquals(1, synth.activeVoiceCount)

        synth.noteOff(69)
        synth.renderStereo(audio, 4096)
        assertEquals(1, synth.activeVoiceCount)
        assertTrue(rms(audio) > 0.001)

        synth.noteOff(69)
        synth.renderStereo(audio, 4096)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun allNotesOffResetsOverlappingHeldCounts() {
        val synth = OrbitalSynth(maxVoices = 2)
        synth.setParameter(ParameterId.RELEASE, 0f)
        val audio = FloatArray(4096 * 2)
        synth.noteOn(60)
        synth.noteOn(60)
        synth.renderStereo(audio, 512)
        synth.allNotesOff()
        synth.renderStereo(audio, 4096)
        assertEquals(0, synth.activeVoiceCount)

        synth.noteOn(60)
        synth.renderStereo(audio, 512)
        synth.noteOff(60)
        synth.renderStereo(audio, 4096)
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun voiceCountChangeDoesNotJumpTheLevelOfAnExistingVoice() {
        val solo = pureSine()
        val layered = pureSine()
        for (synth in listOf(solo, layered)) {
            synth.setParameter(ParameterId.SUSTAIN, 1f)
            synth.noteOn(69)
        }
        val first = FloatArray(4000 * 2)
        val second = FloatArray(4000 * 2)
        solo.renderStereo(first, 4000)
        layered.renderStereo(second, 4000)
        assertArrayEquals(first, second, 0f)

        // The newly triggered sine starts at zero, so its first sample should not
        // cause a large change to the old A4 voice. Without gain slewing, the
        // polyphony gain jumps from .62 to .62/sqrt(2) at this exact boundary.
        layered.noteOn(84)
        val soloAfter = FloatArray(256)
        val layeredAfter = FloatArray(256)
        solo.renderStereo(soloAfter, 128)
        layered.renderStereo(layeredAfter, 128)
        assertTrue(
            "Polyphony transition cut existing note level abruptly",
            abs(layeredAfter[0] - soloAfter[0]) < 0.02f
        )
        assertBounded(layeredAfter)
    }

    private fun pureSine(sampleRate: Int = 48_000): OrbitalSynth = OrbitalSynth(sampleRate).apply {
        setParameter(ParameterId.WAVE, 0f)
        setParameter(ParameterId.MORPH, 0f)
        setParameter(ParameterId.CUTOFF, 1f)
        setParameter(ParameterId.RESONANCE, 0f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.ATTACK, 0f)
        setParameter(ParameterId.GLIDE, 0f)
    }

    private fun assertFrequency(sampleRate: Int, expectedHz: Double) {
        val synth = pureSine(sampleRate)
        synth.noteOn(69)
        val audio = FloatArray(sampleRate * 2)
        synth.renderStereo(audio, sampleRate)
        val estimate = estimateHz(audio, sampleRate, sampleRate / 6, sampleRate * 5 / 6)
        assertTrue("Expected A4 near " + expectedHz + ", found " + estimate,
            abs(estimate - expectedHz) < 4.0)
        assertBounded(audio)
    }

    private fun estimateHz(interleaved: FloatArray, rate: Int, start: Int, end: Int): Double {
        var crossings = 0
        var previous = interleaved[(start - 1) * 2]
        for (frame in start until end) {
            val current = interleaved[frame * 2]
            if (previous <= 0f && current > 0f) crossings++
            previous = current
        }
        return crossings.toDouble() * rate / (end - start)
    }

    private fun rms(data: FloatArray): Double {
        var sum = 0.0
        for (sample in data) sum += sample * sample
        return sqrt(sum / data.size)
    }

    private fun assertBounded(data: FloatArray) {
        for (sample in data) {
            assertFalse("Non-finite audio sample", sample.isNaN() || sample.isInfinite())
            assertTrue("Audio sample outside [-1,1]: " + sample, abs(sample) <= 1.00001f)
        }
    }
}
