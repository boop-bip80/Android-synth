package com.orbital.synth.core

import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The reference synth executes the exact original per-frame sin()/pow() math.
 * The optimized synth must produce the SAME RAW PCM BITS across its render bus.
 * The reference switch is worker-only and never used by the Android host.
 */
class ConstantPitchFastPathGoldenPcmTest {
    private data class Twins(val reference: OrbitalSynth, val optimized: OrbitalSynth)

    private fun twins(maxVoices: Int = 8): Twins {
        val reference = OrbitalSynth(sampleRate = 48_000, maxVoices = maxVoices)
        val optimized = OrbitalSynth(sampleRate = 48_000, maxVoices = maxVoices)
        reference.forceLegacyFramePitchMathForTests = true
        return Twins(reference, optimized)
    }

    private fun Twins.both(change: (OrbitalSynth) -> Unit) {
        change(reference)
        change(optimized)
    }

    private fun Twins.renderExact(label: String, frames: Int): FloatArray {
        val oldPcm = FloatArray(frames * 2)
        val newPcm = FloatArray(frames * 2)
        reference.renderStereo(oldPcm, frames)
        optimized.renderStereo(newPcm, frames)
        for (sample in oldPcm.indices) {
            assertEquals(
                "$label: PCM sample $sample of ${frames * 2} differs",
                java.lang.Float.floatToRawIntBits(oldPcm[sample]),
                java.lang.Float.floatToRawIntBits(newPcm[sample])
            )
            assertTrue("$label: nonfinite PCM at $sample", newPcm[sample].isFinite())
        }
        return newPcm
    }

    private fun configureClean(synth: OrbitalSynth) {
        synth.setParameter(ParameterId.WAVE, 0.42f)
        synth.setParameter(ParameterId.MORPH, 0.37f)
        synth.setParameter(ParameterId.CUTOFF, 0.83f)
        synth.setParameter(ParameterId.RESONANCE, 0.18f)
        synth.setParameter(ParameterId.MOVEMENT, 0f)
        synth.setParameter(ParameterId.LFO_DEPTH, 0f)
        synth.setParameter(ParameterId.FX_AMOUNT, 0f)
        synth.setParameter(ParameterId.ATTACK, 0f)
        synth.setParameter(ParameterId.DECAY, 0.15f)
        synth.setParameter(ParameterId.SUSTAIN, 1f)
        synth.setParameter(ParameterId.RELEASE, 0f)
    }

    private fun configureEightLayers(synth: OrbitalSynth) {
        for (slot in 0 until synth.oscillatorSlotCount) {
            synth.setOscillatorSlot(slot, OscillatorSlot(
                enabled = true,
                waveform = slot / 7f,
                morph = 0.25f + slot * 0.07f,
                level = 0.21f,
                coarseSemitones = (slot - 4) * 3,
                fineCents = (slot - 4) * 5f,
                noiseLevel = if (slot % 3 == 0) 0.4f else 0f,
                noiseColor = slot / 7f
            ))
        }
    }

    @Test fun idleSilenceAndFirstAttackAfterLfoPhaseAdvanceRemainByteExact() {
        for (frames in listOf(192, 512)) {
            val pair = twins()
            pair.both(::configureClean)
            repeat(21) { block ->
                val silent = pair.renderExact("idle$frames/$block", frames)
                assertTrue("Unused dry voices must be exactly silent",
                    silent.all { java.lang.Float.floatToRawIntBits(it) == 0 })
            }
            pair.both { it.noteOn(67, 0.92f) }
            var peak = 0f
            repeat(12) { block ->
                val pcm = pair.renderExact("attack$frames/$block", frames)
                for (value in pcm) peak = maxOf(peak, abs(value))
            }
            assertTrue("PCM comparison must include actual audible samples", peak > 0.002f)
        }
    }

    @Test fun eightLayersAndFiveEngineModesStayByteExactUnderDetuneAndBend() {
        for (frames in listOf(192, 512)) {
            val pair = twins(maxVoices = 8)
            pair.both {
                configureClean(it)
                configureEightLayers(it)
                it.setParameter(ParameterId.DETUNE, 0.45f)
                it.setParameter(ParameterId.UNISON, 0.55f)
                for (note in listOf(48, 55, 60, 64, 67, 72)) it.noteOn(note, 0.68f)
            }
            for (engine in 0..4) {
                pair.both {
                    it.setParameter(ParameterId.ENGINE, engine / 4f)
                    it.setPitchBend((engine - 2) * 2.25f)
                }
                repeat(3) { block ->
                    val pcm = pair.renderExact("layers$frames/engine$engine/block$block", frames)
                    assertTrue("Non-silent multi-oscillator chord expected",
                        pcm.any { abs(it) > 0.0001f })
                }
            }
            pair.both { it.allNotesOff() }
            repeat(3) { pair.renderExact("layers$frames/release$it", frames) }
        }
    }

    @Test fun pitchRouteConnectionAndDisconnectionNeverUseStaleConstantRatio() {
        for (frames in listOf(192, 512)) {
            val pair = twins()
            pair.both {
                configureClean(it)
                it.noteOn(69, 0.84f)
            }
            pair.renderExact("beforeCable$frames", frames)
            pair.both {
                it.setModulationRoute(0, ModSource.TOUCH_Y, ModDestination.PITCH, 0.35f)
                it.setModulationSource(ModSource.TOUCH_Y, 1f)
            }
            repeat(6) { pair.renderExact("cabled$frames/$it", frames) }
            pair.both { it.clearModulationRoute(0) }
            repeat(4) { pair.renderExact("uncabled$frames/$it", frames) }
        }
    }

    @Test fun liveVibratoFxEnableAndTailBypassRemainByteExact() {
        for (frames in listOf(192, 512)) {
            val pair = twins()
            pair.both {
                configureClean(it)
                it.setParameter(ParameterId.FX_AMOUNT, 0.78f)
                it.noteOn(74, 0.75f)
            }
            repeat(8) { pair.renderExact("wetDryLfo$frames/$it", frames) }
            pair.both {
                it.setParameter(ParameterId.MOVEMENT, 0.7f)
                it.setParameter(ParameterId.LFO_DEPTH, 0.55f)
            }
            repeat(8) { pair.renderExact("wetVibrato$frames/$it", frames) }
            pair.both {
                it.noteOff(74)
                it.setParameter(ParameterId.MOVEMENT, 0f)
                it.setParameter(ParameterId.LFO_DEPTH, 0f)
            }
            repeat(12) { pair.renderExact("wetTail$frames/$it", frames) }
            pair.both { it.setParameter(ParameterId.FX_AMOUNT, 0f) }
            repeat(6) { pair.renderExact("bypass$frames/$it", frames) }
        }
    }

    private fun completePattern(frameSize: Int): FloatArray {
        val pair = twins(maxVoices = 8)
        pair.both {
            configureClean(it)
            configureEightLayers(it)
            it.setPitchBend(1.75f)
            it.noteOn(48, 0.7f)
            it.noteOn(60, 0.8f)
        }
        val pcm = FloatArray(3_072 * 2)
        repeat(3_072 / frameSize) { block ->
            val fragment = pair.renderExact("split$frameSize/$block", frameSize)
            System.arraycopy(fragment, 0, pcm, block * fragment.size, fragment.size)
        }
        return pcm
    }

    @Test fun a192Versus512FrameRenderHasIdenticalGoldenPcm() {
        val a = completePattern(192)
        val b = completePattern(512)
        for (index in a.indices) {
            assertEquals(
                "Changing render block size must preserve the PCM sample at $index",
                java.lang.Float.floatToRawIntBits(a[index]),
                java.lang.Float.floatToRawIntBits(b[index])
            )
        }
        assertTrue("Cross-block fixture must contain audible sound", a.any { abs(it) > 0.002f })
    }
}
