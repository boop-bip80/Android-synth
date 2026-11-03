package com.orbital.synth.core

import com.orbital.synth.core.preset.FactoryPatches
import com.orbital.synth.core.preset.loadPatch
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies all architecture choices actually reach the real renderStereo path. */
class MultiEngineRuntimeTest {
    private fun preparedEngine(index: Int): OrbitalSynth = OrbitalSynth(maxVoices = 8).apply {
        setParameter(ParameterId.ENGINE, index / 4f)
        setParameter(ParameterId.CHARACTER, .72f)
        setParameter(ParameterId.WAVE, .62f)
        setParameter(ParameterId.MORPH, .64f)
        setParameter(ParameterId.CUTOFF, .70f)
        setParameter(ParameterId.RESONANCE, .44f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.FX_AMOUNT, 0f)
        setParameter(ParameterId.ATTACK, 0f)
        setParameter(ParameterId.SUSTAIN, 1f)
        setParameter(ParameterId.RELEASE, 0f)
    }

    @Test fun allFiveModesProduceFiniteAudibleBoundedAudio() {
        val outputs = (0..4).map { mode ->
            val synth = preparedEngine(mode)
            synth.noteOn(60, .91f)
            val audio = FloatArray(4096 * 2)
            synth.renderStereo(audio, 4096)
            assertEquals("mode $mode must retain its note", 1, synth.activeVoiceCount)
            assertTrue("mode $mode must be finite", audio.all { it.isFinite() && abs(it) <= 1f })
            val rms = sqrt(audio.sumOf { (it * it).toDouble() } / audio.size)
            assertTrue("mode $mode cannot be silent: rms=$rms", rms > .0002)
            audio
        }
        for (i in outputs.indices) {
            for (j in i + 1 until outputs.size) {
                val difference = outputs[i].indices.sumOf {
                    abs((outputs[i][it] - outputs[j][it]).toDouble())
                }
                assertTrue("engine $i and $j must not be aliases", difference > 0.5)
            }
        }
    }

    @Test fun engineSwitchWhileSustainingAndPolyStealingRemainFinite() {
        val synth = preparedEngine(0)
        for (note in 45..52) synth.noteOn(note, .86f)
        val buffer = FloatArray(512 * 2)
        for (mode in intArrayOf(1, 2, 3, 4, 0, 3, 1)) {
            synth.setParameter(ParameterId.ENGINE, mode / 4f)
            for (iteration in 0..11) {
                synth.renderStereo(buffer, 512)
                assertTrue("mode $mode returned NaN/Inf", buffer.all { it.isFinite() && abs(it) <= 1f })
            }
        }
        for (note in 53..75) {
            synth.noteOn(note, .8f) // exercises voice recycling in each architecture
            synth.renderStereo(buffer, 512)
            assertTrue(buffer.all { it.isFinite() && abs(it) <= 1f })
        }
        synth.allNotesOff()
        repeat(60) { synth.renderStereo(buffer, 512) }
        assertEquals(0, synth.activeVoiceCount)
    }

    @Test fun oldPatchAlwaysResetsEngineAndCharacterToOriginalWavetable() {
        val synth = preparedEngine(4)
        val previousFormat = FactoryPatches.DEFAULT.copy(
            parameters = FactoryPatches.DEFAULT.parameters -
                setOf("UNISON", "DETUNE", "ENGINE", "CHARACTER"),
        )
        synth.loadPatch(previousFormat)
        assertEquals(0f, synth.parameter(ParameterId.ENGINE), 0f)
        assertEquals(0f, synth.parameter(ParameterId.CHARACTER), 0f)
        assertEquals(0f, synth.parameter(ParameterId.UNISON), 0f)
        assertEquals(0f, synth.parameter(ParameterId.DETUNE), 0f)
    }
}
