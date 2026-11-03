package com.orbital.synth.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OscillatorRackTest {
    @Test fun defaultRackPreservesLegacyAudioSampleForSample() {
        val a = cleanSynth()
        val b = cleanSynth()
        assertEquals(8, a.oscillatorSlotCount)
        for (slot in 0 until a.oscillatorSlotCount) {
            assertFalse(a.oscillatorSlot(slot).enabled)
            assertEquals(0f, a.oscillatorSlot(slot).level, 0f)
        }
        a.noteOn(60, 0.7f)
        b.noteOn(60, 0.7f)
        val original = FloatArray(4096 * 2)
        val untouched = FloatArray(original.size)
        a.renderStereo(original, 4096)
        b.renderStereo(untouched, 4096)
        assertArrayEquals(original, untouched, 0f)
    }

    @Test fun allEightProgrammableLanesContributeToActualRenderedAudio() {
        val baseline = cleanSynth()
        val layered = cleanSynth()
        for (slot in 0 until layered.oscillatorSlotCount) {
            layered.setOscillatorSlot(
                index = slot, enabled = true, waveform = slot / 7f,
                level = 0.38f, coarseSemitones = (slot - 4) * 2,
                fineCents = (slot - 4) * 6f, morph = 1f,
            )
        }
        baseline.noteOn(57)
        layered.noteOn(57)
        val dry = FloatArray(8_192 * 2)
        val wet = FloatArray(dry.size)
        baseline.renderStereo(dry, 8_192)
        layered.renderStereo(wet, 8_192)
        var change = 0.0
        for (i in dry.indices) {
            change += abs((dry[i] - wet[i]).toDouble())
            assertTrue("Non-finite layered sample at index $i", wet[i].isFinite())
            assertTrue("Layered output escaped hard limiter", abs(wet[i]) <= 1.00001f)
        }
        assertTrue("Eight slot rack must change the sound", change > 100.0)
        assertEquals(1f, layered.oscillatorSlot(7).waveform, 0f)
        assertEquals(6, layered.oscillatorSlot(7).coarseSemitones)
    }

    @Test fun rackSettingsAreValidatedAndReturnedAsImmutableSnapshots() {
        val synth = cleanSynth()
        synth.setOscillatorSlot(
            0, true, waveform = 4f, level = 2f,
            coarseSemitones = 99, fineCents = -200f, morph = -4f
        )
        val before = synth.oscillatorSlot(0)
        assertEquals(OscillatorSlot(true, 1f, 0f, 1f, 48, -100f), before)
        synth.setOscillatorSlot(0, false, Float.NaN, 0.5f)
        assertEquals("Non-finite edits must preserve previous patch", before, synth.oscillatorSlot(0))
        synth.clearOscillatorRack()
        assertFalse(synth.oscillatorSlot(0).enabled)
        for (i in 0 until 8) assertEquals(0f, synth.oscillatorSlot(i).level, 0f)
        try {
            synth.oscillatorSlot(8)
            throw AssertionError("Out-of-range slot should be rejected")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun enablingMidNoteIsDeclickedAndDisablingConvergesToBaseSound() {
        val stable = cleanSynth()
        val edited = cleanSynth()
        stable.noteOn(69)
        edited.noteOn(69)
        val warmUp = FloatArray(4_096 * 2)
        stable.renderStereo(warmUp, 4096)
        edited.renderStereo(warmUp, 4096)
        edited.setOscillatorSlot(0, true, waveform = 0.5f, level = 0.95f)
        val unchanged = FloatArray(2)
        val immediate = FloatArray(2)
        stable.renderStereo(unchanged, 1)
        edited.renderStereo(immediate, 1)
        assertTrue("Slot enable must begin with a small first-sample change",
            abs(immediate[0] - unchanged[0]) < 0.015f)
        val long = FloatArray(8192 * 2)
        val reference = FloatArray(long.size)
        edited.renderStereo(long, 8192)
        stable.renderStereo(reference, 8192)
        assertTrue("Full gain should sound different after its de-click ramp",
            averageDifference(long, reference) > 0.01)
        edited.setOscillatorSlot(0, false, waveform = 0.5f, level = 0.95f)
        val recover = FloatArray(12_288 * 2)
        edited.renderStereo(recover, 12_288)
        stable.renderStereo(FloatArray(recover.size), 12_288)
        val afterFade = FloatArray(4_096 * 2)
        val original = FloatArray(afterFade.size)
        edited.renderStereo(afterFade, 4_096)
        stable.renderStereo(original, 4_096)
        assertArrayEquals("Disabled rack must regain legacy phase-continuous signal",
            original, afterFade, 0.000001f)
    }

    @Test fun oscillatorAutomationIsBlockSizeIndependent() {
        val singleBlock = cleanSynth()
        val chunks = cleanSynth()
        for (synth in listOf(singleBlock, chunks)) {
            synth.setOscillatorSlot(0, true, 0.81f, level = 0.74f, coarseSemitones = 7)
            synth.setOscillatorSlot(3, true, 0.12f, level = 0.32f,
                coarseSemitones = -12, fineCents = -17f)
            synth.noteOn(62, 0.75f)
        }
        val large = FloatArray(8192 * 2)
        singleBlock.renderStereo(large, 8192)
        val small = FloatArray(512 * 2)
        val combined = FloatArray(large.size)
        for (chunk in 0 until 16) {
            chunks.renderStereo(small, 512)
            System.arraycopy(small, 0, combined, chunk * small.size, small.size)
        }
        assertArrayEquals("Slot envelopes and phase must be buffer invariant",
            large, combined, 0f)
    }

    @Test fun allFiveSoundEnginesCanHostAdditiveLanes() {
        for (engine in 0..4) {
            val base = cleanSynth()
            val layered = cleanSynth()
            base.setParameter(ParameterId.ENGINE, engine / 4f)
            layered.setParameter(ParameterId.ENGINE, engine / 4f)
            layered.setOscillatorSlot(2, true, 0.63f, 0.8f,
                coarseSemitones = 12, fineCents = 11f)
            base.noteOn(64)
            layered.noteOn(64)
            val a = FloatArray(4_096 * 2)
            val b = FloatArray(a.size)
            base.renderStereo(a, 4096)
            layered.renderStereo(b, 4096)
            assertTrue("Rack must be audible with architecture $engine",
                averageDifference(a, b) > 0.002)
            for (sample in b) {
                assertTrue(sample.isFinite())
                assertTrue(abs(sample) <= 1.00001f)
            }
        }
    }

    @Test fun rapidRackAutomationWithVoiceStealingAndReleaseRemainsBounded() {
        val synth = cleanSynth().apply {
            setParameter(ParameterId.RELEASE, 0f)
        }
        for (note in 35..63) synth.noteOn(note, 0.9f)
        val output = FloatArray(512 * 2)
        repeat(100) { block ->
            for (slot in 0 until 8) {
                synth.setOscillatorSlot(
                    slot, enabled = (slot + block) % 3 != 0,
                    waveform = ((slot + block) % 9) / 8f,
                    level = (slot % 3 + 1) / 4f,
                    coarseSemitones = (slot - 4) * 5,
                    fineCents = ((block + slot) % 50).toFloat(),
                )
            }
            synth.renderStereo(output, 512)
            for (sample in output) {
                assertTrue(sample.isFinite())
                assertTrue(abs(sample) <= 1.00001f)
            }
        }
        synth.allNotesOff()
        synth.renderStereo(FloatArray(8192 * 2), 8192)
        assertEquals(0, synth.activeVoiceCount)
        synth.clearOscillatorRack()
        synth.renderStereo(output, 512)
        for (sample in output) assertEquals(0f, sample, 0f)
    }

    private fun cleanSynth() = OrbitalSynth().apply {
        setParameter(ParameterId.WAVE, 0f)
        setParameter(ParameterId.MORPH, 0f)
        setParameter(ParameterId.CUTOFF, 1f)
        setParameter(ParameterId.RESONANCE, 0f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.FX_AMOUNT, 0f)
        setParameter(ParameterId.ATTACK, 0f)
        setParameter(ParameterId.RELEASE, 0f)
    }

    private fun averageDifference(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        var sum = 0.0
        for (i in a.indices) sum += abs((a[i] - b[i]).toDouble())
        return sum / a.size
    }
}
