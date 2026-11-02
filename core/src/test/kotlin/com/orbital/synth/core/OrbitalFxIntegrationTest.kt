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

    @Test fun diffuseReverbTailIsAudibleBeforeFirstDelayEcho() {
        val wet = fixedSynth().apply {
            setParameter(ParameterId.FX_AMOUNT, 1f)
            setParameter(ParameterId.RELEASE, 0f)
        }
        val dry = fixedSynth().apply { setParameter(ParameterId.RELEASE, 0f) }
        val lead = FloatArray(2_048 * 2)
        for (engine in listOf(wet, dry)) {
            engine.noteOn(72)
            engine.renderStereo(lead, 2_048)
            engine.allNotesOff()
        }
        val wetTail = FloatArray(8_192 * 2)
        val dryTail = FloatArray(wetTail.size)
        wet.renderStereo(wetTail, 8_192)
        dry.renderStereo(dryTail, 8_192)
        // Voice release is only ~20 ms; the stereo delay is 290 ms. A signal
        // 100–170 ms later can therefore come from the new reverb, not either.
        val first = 4_800 * 2
        val wetPeak = (first until wetTail.size).maxOf { abs(wetTail[it]) }
        val dryPeak = (first until dryTail.size).maxOf { abs(dryTail[it]) }
        assertTrue("Integrated reverb should retain an early diffuse tail", wetPeak > 0.00001f)
        assertTrue("The released dry voice should be silent", dryPeak < 0.00001f)
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

    @Test fun bypassClearsChorusDelayAndReverbHistoryBeforeReenablingFx() {
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

        // All processors must forget pre-bypass audio; no delayed tails should be
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

    @Test fun disablingFxCrossfadesExactOldWetBusToDryThenStaysBitExactDry() {
        val fading = fixedSynth().apply { setParameter(ParameterId.FX_AMOUNT, 1f) }
        val continuouslyWet = fixedSynth().apply { setParameter(ParameterId.FX_AMOUNT, 1f) }
        val continuouslyDry = fixedSynth()
        val initial = FloatArray(4_096 * 2)
        for (synth in listOf(fading, continuouslyWet, continuouslyDry)) {
            synth.noteOn(69)
            synth.renderStereo(initial, 4_096)
        }

        fading.setParameter(ParameterId.FX_AMOUNT, 0f)
        val fadeFrames = 480 // 10 ms at the fixedSynth() 48 kHz rate
        val fadeOut = FloatArray(fadeFrames * 2)
        val oldWet = FloatArray(fadeOut.size)
        val dry = FloatArray(fadeOut.size)
        fading.renderStereo(fadeOut, fadeFrames)
        continuouslyWet.renderStereo(oldWet, fadeFrames)
        continuouslyDry.renderStereo(dry, fadeFrames)

        for (frame in 0 until fadeFrames) {
            val wetFraction = 1f - (frame + 1f) / fadeFrames
            for (channel in 0..1) {
                val index = frame * 2 + channel
                val expected = dry[index] * (1f - wetFraction) + oldWet[index] * wetFraction
                assertEquals(
                    "Fade-out must preserve old wet FX configuration and drive",
                    expected, fadeOut[index], 0.0002f
                )
            }
        }
        // Exactly dry after 10 ms; the next buffer is bit-for-bit equal to
        // an engine that never enabled FX, not an asymptotic wet tail.
        val offOutput = FloatArray(1_024 * 2)
        val dryOutput = FloatArray(offOutput.size)
        fading.renderStereo(offOutput, 1_024)
        continuouslyDry.renderStereo(dryOutput, 1_024)
        assertArrayEquals(dryOutput, offOutput, 0f)
    }

    @Test fun rapidFxToggleReversesFadeWithoutReplayingOrRestartingWetHistory() {
        val toggled = fixedSynth().apply { setParameter(ParameterId.FX_AMOUNT, 0.8f) }
        val continuouslyWet = fixedSynth().apply { setParameter(ParameterId.FX_AMOUNT, 0.8f) }
        val continuouslyDry = fixedSynth()
        val initial = FloatArray(4_096 * 2)
        for (synth in listOf(toggled, continuouslyWet, continuouslyDry)) {
            synth.noteOn(72)
            synth.renderStereo(initial, 4_096)
        }

        // The first 128 samples fade toward dry, then reverse before completing.
        toggled.setParameter(ParameterId.FX_AMOUNT, 0f)
        val down = FloatArray(128 * 2)
        val wetDown = FloatArray(down.size)
        val dryDown = FloatArray(down.size)
        toggled.renderStereo(down, 128)
        continuouslyWet.renderStereo(wetDown, 128)
        continuouslyDry.renderStereo(dryDown, 128)
        for (frame in 0 until 128) {
            val fraction = 1f - (frame + 1f) / 480f
            val i = frame * 2
            assertEquals(
                dryDown[i] * (1f - fraction) + wetDown[i] * fraction,
                down[i], 0.0002f
            )
        }

        toggled.setParameter(ParameterId.FX_AMOUNT, 0.8f)
        val up = FloatArray(128 * 2)
        val wetUp = FloatArray(up.size)
        val dryUp = FloatArray(up.size)
        toggled.renderStereo(up, 128)
        continuouslyWet.renderStereo(wetUp, 128)
        continuouslyDry.renderStereo(dryUp, 128)
        val startFraction = 1f - 128f / 480f
        for (frame in 0 until 128) {
            val fraction = startFraction + (1f - startFraction) * (frame + 1f) / 480f
            val i = frame * 2
            assertEquals(
                "Reenable must reverse from the current wet fraction",
                dryUp[i] * (1f - fraction) + wetUp[i] * fraction,
                up[i], 0.0003f
            )
            assertTrue(up[i].isFinite() && abs(up[i]) <= 1.00001f)
            assertTrue(up[i + 1].isFinite() && abs(up[i + 1]) <= 1.00001f)
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
