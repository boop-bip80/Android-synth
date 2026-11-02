package com.orbital.synth.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class UnisonOscillatorTest {
    @Test fun unisonDefaultsAreNeutralAndDetuneAloneDoesNotChangeSound() {
        val dry = clearSine()
        val neutral = clearSine().apply {
            setParameter(ParameterId.UNISON, 0f)
            setParameter(ParameterId.DETUNE, 1f)
        }
        assertEquals(0f, dry.parameter(ParameterId.UNISON), 0f)
        assertEquals(0f, dry.parameter(ParameterId.DETUNE), 0f)
        dry.noteOn(69)
        neutral.noteOn(69)

        val a = FloatArray(12_000)
        val b = FloatArray(a.size)
        dry.renderStereo(a, a.size / 2)
        neutral.renderStereo(b, b.size / 2)
        // Even if the unvoiced side phases are moving, they must be perfectly inaudible.
        assertArrayEquals(a, b, 0f)
    }

    @Test fun detunedThreeOscillatorBlendProducesAudibleBeating() {
        val dry = clearSine()
        val wide = clearSine().apply {
            setParameter(ParameterId.UNISON, 1f)
            setParameter(ParameterId.DETUNE, 1f)
        }
        dry.noteOn(69)
        wide.noteOn(69)
        val a = FloatArray(48_000 * 2)
        val b = FloatArray(a.size)
        dry.renderStereo(a, 48_000)
        wide.renderStereo(b, 48_000)

        var difference = 0.0
        for (index in a.indices) {
            difference += abs((a[index] - b[index]).toDouble())
            assertTrue("Unison sample is non-finite", b[index].isFinite())
            assertTrue("Unison sample escaped output bounds", abs(b[index]) <= 1.00001f)
        }
        assertTrue("Detuned unison should change audible timbre", difference > 500.0)
    }

    @Test fun unisonWithZeroDetuneIsTransparentWithinFloatPrecision() {
        val dry = clearSine()
        val stacked = clearSine().apply {
            setParameter(ParameterId.UNISON, 1f)
            setParameter(ParameterId.DETUNE, 0f)
        }
        dry.noteOn(72, 0.8f)
        stacked.noteOn(72, 0.8f)
        val a = FloatArray(8_192)
        val b = FloatArray(a.size)
        dry.renderStereo(a, a.size / 2)
        stacked.renderStereo(b, b.size / 2)

        var largestError = 0f
        for (i in a.indices) largestError = maxOf(largestError, abs(a[i] - b[i]))
        assertTrue("Zero-detune unison must preserve original timbre", largestError < 0.00001f)
    }

    @Test fun unisonSlewIsSampleConsistentAcrossDifferentBlockSizes() {
        val oneBlock = clearSine().apply {
            setParameter(ParameterId.UNISON, 0.94f)
            setParameter(ParameterId.DETUNE, 0.72f)
        }
        val smallBlocks = clearSine().apply {
            setParameter(ParameterId.UNISON, 0.94f)
            setParameter(ParameterId.DETUNE, 0.72f)
        }
        oneBlock.noteOn(69)
        smallBlocks.noteOn(69)

        val large = FloatArray(4800 * 2)
        val combined = FloatArray(large.size)
        val scratch = FloatArray(480 * 2)
        oneBlock.renderStereo(large, 4800)
        for (block in 0 until 10) {
            smallBlocks.renderStereo(scratch, 480)
            System.arraycopy(scratch, 0, combined, block * scratch.size, scratch.size)
        }
        assertArrayEquals("Oscillator smoothing must not depend on render-block size",
            large, combined, 0f)
    }

    @Test fun rapidUnisonAutomationAndVoiceStealingStayFiniteAndRelease() {
        val synth = clearSine()
        synth.setParameter(ParameterId.RELEASE, 0f)
        val buffer = FloatArray(1024 * 2)
        for (note in 48..68) synth.noteOn(note)
        repeat(120) { block ->
            synth.setParameter(ParameterId.UNISON, if (block % 2 == 0) 1f else 0f)
            synth.setParameter(ParameterId.DETUNE, if (block % 3 == 0) 1f else 0f)
            synth.renderStereo(buffer, 1024)
            for (sample in buffer) {
                assertTrue(sample.isFinite())
                assertTrue(abs(sample) <= 1.00001f)
            }
        }
        synth.allNotesOff()
        synth.renderStereo(buffer, 1024)
        synth.renderStereo(buffer, 1024)
        assertEquals(0, synth.activeVoiceCount)
    }

    private fun clearSine(): OrbitalSynth = OrbitalSynth().apply {
        setParameter(ParameterId.WAVE, 0f)
        setParameter(ParameterId.MORPH, 0f)
        setParameter(ParameterId.CUTOFF, 1f)
        setParameter(ParameterId.RESONANCE, 0f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.FX_AMOUNT, 0f)
        setParameter(ParameterId.ATTACK, 0f)
        setParameter(ParameterId.SUSTAIN, 1f)
        setParameter(ParameterId.GLIDE, 0f)
    }
}
