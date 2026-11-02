package com.orbital.synth.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Offline host tests for the actual master FX render path, not isolated processors. */
class OrbitalFxIntegrationTest {
    @Test fun zeroFxAmountKeepsExactLegacyDrySignal() {
        val defaultDry = fixedSynth()
        val explicitlyDry = fixedSynth().apply {
            setParameter(ParameterId.FX_AMOUNT, 0f)
        }
        defaultDry.noteOn(64)
        explicitlyDry.noteOn(64)
        val a = FloatArray(16_384)
        val b = FloatArray(a.size)
        defaultDry.renderStereo(a, a.size / 2)
        explicitlyDry.renderStereo(b, b.size / 2)
        assertArrayEquals(a, b, 0f)
        assertTrue(a.any { abs(it) > 0.001f })
    }

    @Test fun wetControlChangesAudibleSignalAndNeverProducesOutOfRangePcm() {
        val dry = fixedSynth()
        val wet = fixedSynth().apply { setParameter(ParameterId.FX_AMOUNT, 1f) }
        dry.noteOn(69, 0.85f)
        wet.noteOn(69, 0.85f)
        val a = FloatArray(48_000)
        val b = FloatArray(a.size)
        dry.renderStereo(a, a.size / 2)
        wet.renderStereo(b, b.size / 2)
        val totalDifference = a.indices.sumOf { abs(a[it] - b[it]).toDouble() }
        assertTrue("FX_AMOUNT should be audibly effective", totalDifference > 100.0)
        for (sample in b) assertTrue(sample.isFinite() && abs(sample) <= 1.00001f)
    }

    @Test fun delayEchoSurvivesAfterLastVoiceEnvelopeFinishes() {
        val wet = fixedSynth().apply {
            setParameter(ParameterId.FX_AMOUNT, 1f)
            setParameter(ParameterId.RELEASE, 0f)
        }
        val dry = fixedSynth().apply { setParameter(ParameterId.RELEASE, 0f) }
        val leadIn = FloatArray(2_048 * 2)
        val wetTail = FloatArray(48_000 * 2)
        val dryTail = FloatArray(wetTail.size)
        for (synth in listOf(wet, dry)) {
            synth.noteOn(72)
            synth.renderStereo(leadIn, 2_048)
            synth.allNotesOff()
        }
        wet.renderStereo(wetTail, 48_000)
        dry.renderStereo(dryTail, 48_000)
        assertEquals(0, wet.activeVoiceCount)
        assertEquals(0, dry.activeVoiceCount)
        // An approximately 290-ms delay fires long after the 20-ms voice release.
        val echoStart = 11_000 * 2
        val echoEnd = 20_000 * 2
        val wetPeak = (echoStart until echoEnd).maxOf { abs(wetTail[it]) }
        val dryPeak = (echoStart until echoEnd).maxOf { abs(dryTail[it]) }
        assertTrue("Delay did not produce a post-release tail", wetPeak > 0.0001f)
        assertTrue("Dry output must be silent after release", dryPeak < 0.00001f)
    }

    @Test fun extremeEffectsAndBypassNeverProduceNonFiniteOutput() {
        val synth = OrbitalSynth()
        synth.setParameter(ParameterId.FX_AMOUNT, 1f)
        synth.setParameter(ParameterId.RESONANCE, 1f)
        synth.setParameter(ParameterId.CUTOFF, 1f)
        for (note in 48..72 step 2) synth.noteOn(note, 1f)
        val block = FloatArray(2_048)
        repeat(96) {
            if (it == 48) {
                synth.setParameter(ParameterId.FX_AMOUNT, 0f)
                synth.allNotesOff()
            }
            synth.renderStereo(block, block.size / 2)
            for (sample in block) {
                assertFalse(sample.isNaN() || sample.isInfinite())
                assertTrue(abs(sample) <= 1.00001f)
            }
        }
        // Reopening after bypass should not replay frozen pre-bypass echoes.
        synth.setParameter(ParameterId.FX_AMOUNT, 1f)
        synth.renderStereo(block, block.size / 2)
        for (sample in block) assertTrue(sample.isFinite())
    }

    @Test fun bypassClearsChorusAndDelayHistoryBeforeReenablingFx() {
        val synth = fixedSynth().apply {
            setParameter(ParameterId.RELEASE, 0f)
            setParameter(ParameterId.FX_AMOUNT, 1f)
        }
        val block = FloatArray(4_096 * 2)
        synth.noteOn(72)
        synth.renderStereo(block, 4_096)
        synth.allNotesOff()

        synth.setParameter(ParameterId.FX_AMOUNT, 0f)
        synth.renderStereo(block, 4_096)
        assertEquals(0, synth.activeVoiceCount)

        // Both processors must forget pre-bypass audio; no delayed echoes should be
        // resurrected when the musician turns FX back on while the synth is silent.
        synth.setParameter(ParameterId.FX_AMOUNT, 1f)
        synth.renderStereo(block, 4_096)
        for (sample in block) assertEquals(0f, sample, 0f)
    }

    @Test fun reenablingSmallFxAfterFullWetBypassPreservesDryAttack() {
        val restored = fixedSynth().apply {
            setParameter(ParameterId.RELEASE, 0f)
            setParameter(ParameterId.FX_AMOUNT, 1f)
        }
        val dry = fixedSynth().apply {
            setParameter(ParameterId.RELEASE, 0f)
        }
        val scratch = FloatArray(4_096 * 2)
        restored.noteOn(69)
        restored.renderStereo(scratch, 4_096)
        restored.allNotesOff()
        restored.renderStereo(scratch, 4_096)
        restored.setParameter(ParameterId.FX_AMOUNT, 0f)
        restored.renderStereo(scratch, 4_096)
        assertEquals(0, restored.activeVoiceCount)

        // The previous fully wet controls must not suppress a new note
        // when FX are reopened at only 10% intensity.
        restored.setParameter(ParameterId.FX_AMOUNT, 0.1f)
        restored.noteOn(69)
        dry.noteOn(69)
        val restoredAttack = FloatArray(512)
        val dryAttack = FloatArray(512)
        restored.renderStereo(restoredAttack, 256)
        dry.renderStereo(dryAttack, 256)
        val restoredPower = restoredAttack.sumOf { (it * it).toDouble() }
        val dryPower = dryAttack.sumOf { (it * it).toDouble() }
        assertTrue("Dry comparison attack unexpectedly silent", dryPower > 0.001)
        assertTrue(
            "FX re-enable inherited stale wet gain and suppressed the attack",
            restoredPower > dryPower * 0.5
        )
        for (sample in restoredAttack) {
            assertTrue(sample.isFinite() && abs(sample) <= 1.00001f)
        }
    }

    private fun fixedSynth() = OrbitalSynth().apply {
        setParameter(ParameterId.WAVE, 0.17f)
        setParameter(ParameterId.MORPH, 0.55f)
        setParameter(ParameterId.CUTOFF, 0.9f)
        setParameter(ParameterId.RESONANCE, 0.1f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.LFO_DEPTH, 0f)
        setParameter(ParameterId.ATTACK, 0f)
    }
}
