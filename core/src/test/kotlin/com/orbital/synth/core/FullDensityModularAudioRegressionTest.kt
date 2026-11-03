package com.orbital.synth.core

import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import com.orbital.synth.core.dsp.processing.StepDirection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Audio correctness at the advertised *simultaneous* sound-design capacities.
 *
 * This is an offline deterministic engine regression, not a benchmark or a claim
 * of measured Android-device latency/CPU/thermal performance. A separate
 * hardware profiler is required before making realtime performance promises.
 */
class FullDensityModularAudioRegressionTest {
    private fun fullyProgrammedSynth(sampleRate: Int = 8_000): OrbitalSynth = OrbitalSynth(
        sampleRate = sampleRate, maxVoices = 16,
    ).apply {
        setParameter(ParameterId.WAVE, .37f)
        setParameter(ParameterId.MORPH, .24f)
        setParameter(ParameterId.CUTOFF, .84f)
        setParameter(ParameterId.RESONANCE, .22f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.FX_AMOUNT, 0f)
        setParameter(ParameterId.ATTACK, 0f)
        setParameter(ParameterId.DECAY, 0f)
        setParameter(ParameterId.SUSTAIN, 1f)
        setParameter(ParameterId.RELEASE, 0f)

        assertEquals(8, oscillatorSlotCount)
        assertEquals(8, modulationLfoCount)
        assertEquals(64, modulationRouteCount)
        assertEquals(6, fxSlotCount)

        for (slot in 0 until oscillatorSlotCount) {
            setOscillatorSlot(
                index = slot, enabled = true,
                waveform = slot / 7f, level = .075f,
                coarseSemitones = slot - 4, fineCents = slot * 5f,
                morph = .33f + slot * .075f,
            )
        }
        for (slot in 0 until modulationLfoCount) {
            setModulationLfo(
                slot, enabled = true,
                shape = LfoShape.entries[slot % LfoShape.entries.size],
                rateHz = .8f + slot * .35f,
                retriggerOnNote = true,
            )
        }
        setModulationTempoBpm(129f)
        setModulationSteps(
            ModulationStepConfig(
                enabled = true,
                direction = StepDirection.PING_PONG,
                beatsPerStep = .25f,
                glide = .2f,
                retriggerOnNote = true,
            )
        )
        setModulationSource(ModSource.TOUCH_X, .57f)
        setModulationSource(ModSource.TOUCH_Y, -.24f)
        setModulationSource(ModSource.TOUCH_PRESSURE, .83f)

        val sources = arrayOf(
            ModSource.LFO1, ModSource.LFO2, ModSource.LFO3, ModSource.LFO4,
            ModSource.LFO5, ModSource.LFO6, ModSource.LFO7, ModSource.LFO8,
            ModSource.STEP, ModSource.TOUCH_X, ModSource.TOUCH_Y,
            ModSource.TOUCH_PRESSURE,
        )
        // Only live-wired destinations: a GUI label must never count as DSP.
        val destinations = arrayOf(
            ModDestination.MORPH, ModDestination.PITCH,
            ModDestination.CUTOFF, ModDestination.RESONANCE,
            ModDestination.AMP, ModDestination.PAN,
        )
        for (route in 0 until modulationRouteCount) {
            setModulationRoute(
                route,
                sources[route % sources.size],
                destinations[(route / 3) % destinations.size],
                if (route % 2 == 0) .035f else -.025f,
                ModCurve.entries[route % ModCurve.entries.size],
            )
        }
        // All six simultaneously process real stereo PCM; includes the costly
        // reverb and phaser, and bitcrush, rather than only mock FX labels.
        for (slot in 0 until fxSlotCount) {
            setFxSlot(
                slot, FxModuleType.entries[slot + 1], enabled = true,
                wet = .22f, depth = .46f, rate = .35f, stereo = .62f,
            )
        }
    }

    @Test fun sixteenVoiceFullGraphProducesFiniteAudioIndependentOfCallbackSize() {
        val whole = fullyProgrammedSynth()
        val partitioned = fullyProgrammedSynth()
        for (note in 48..63) {
            whole.noteOn(note, .68f)
            partitioned.noteOn(note, .68f)
        }

        val totalFrames = 3_072
        val once = FloatArray(totalFrames * 2)
        val blocks = FloatArray(once.size)
        val scratch = FloatArray(256 * 2)
        whole.renderStereo(once, totalFrames)
        repeat(totalFrames / 256) { block ->
            partitioned.renderStereo(scratch, 256)
            System.arraycopy(scratch, 0, blocks, block * scratch.size, scratch.size)
        }

        assertEquals(16, whole.activeVoiceCount)
        assertEquals(16, partitioned.activeVoiceCount)
        assertTrue("Full sound-design graph must output audible PCM",
            once.any { abs(it) > .005f })
        assertArrayEquals(
            "16 voices + 8 oscillators + 8 LFOs + 64 routes + STEP + 6 FX" +
                " must not depend on callback block size",
            once, blocks, 0.00001f,
        )
        for (sample in once) {
            assertTrue("All realtime samples must be finite", sample.isFinite())
            assertTrue("Stereo bus must remain in PCM range", abs(sample) <= 1.00001f)
        }
        assertTrue(whole.modulationRoute(63).enabled)
        assertEquals(StepDirection.PING_PONG, whole.modulationSteps.direction)
    }

    /**
     * The default 8 kHz full-capacity regression is cheap enough to run often.
     * This bounded companion checks the same 16-voice/8-osc/8-LFO/64-route/6-FX
     * configuration at standard audio device rates. It is intentionally an
     * offline PCM correctness test, not a realtime CPU/thermal benchmark.
     */
    @Test fun fullDensityGraphIsStableAtFortyFourAndFortyEightKilohertz() {
        for (rate in intArrayOf(44_100, 48_000)) {
            val wholeSynth = fullyProgrammedSynth(rate)
            val blockSynth = fullyProgrammedSynth(rate)
            for (note in 48..63) {
                wholeSynth.noteOn(note, .68f)
                blockSynth.noteOn(note, .68f)
            }

            val frames = 1_536
            val whole = FloatArray(frames * 2)
            val piecewise = FloatArray(whole.size)
            val scratch = FloatArray(256 * 2)
            wholeSynth.renderStereo(whole, frames)
            repeat(frames / 256) { chunk ->
                blockSynth.renderStereo(scratch, 256)
                System.arraycopy(scratch, 0, piecewise, chunk * scratch.size, scratch.size)
            }

            assertEquals("All voices must survive at sample rate " + rate, 16, wholeSynth.activeVoiceCount)
            assertEquals("All chunked voices must survive at sample rate " + rate, 16, blockSynth.activeVoiceCount)
            assertTrue("Full-density PCM must be audible at sample rate " + rate,
                whole.any { abs(it) > .005f })
            assertArrayEquals(
                "Full-density offline PCM must be callback-size independent at sample rate " + rate,
                whole, piecewise, 0.00001f,
            )
            assertTrue("Full-density PCM must be finite and bounded at sample rate " + rate,
                whole.all { it.isFinite() && abs(it) <= 1.00001f })
        }
    }

    @Test fun denseGraphCanBeEditedWhilePlayingWithoutStuckVoicesOrNaN() {
        val synth = fullyProgrammedSynth()
        for (note in 48..63) synth.noteOn(note, .72f)
        val pcm = FloatArray(256 * 2)
        repeat(24) { block ->
            synth.setModulationSource(
                ModSource.TOUCH_PRESSURE,
                if (block % 2 == 0) 1f else -1f,
            )
            synth.setModulationRoute(
                63, ModSource.STEP, ModDestination.PAN,
                if (block % 3 == 0) .15f else -.15f,
            )
            synth.setFxSlot(
                5, FxModuleType.BITCRUSH, enabled = true,
                wet = .18f + block / 200f, depth = .22f + block / 100f,
                rate = .42f, stereo = .6f,
            )
            synth.renderStereo(pcm, 256)
            assertTrue("Automation block must never output NaN/Inf", pcm.all { it.isFinite() })
            assertTrue("Automation must remain in PCM range",
                pcm.all { abs(it) <= 1.00001f })
        }

        synth.allNotesOff()
        synth.renderStereo(FloatArray(4_096 * 2), 4_096)
        assertEquals("Full graph must release all sixteen synth voices", 0, synth.activeVoiceCount)
    }

    @Test fun seventhEffectHotSwitchDoesNotCorruptDensePatchOrStuckNotes() {
        val original = fullyProgrammedSynth()
        val switched = fullyProgrammedSynth()
        for (note in 48..63) {
            original.noteOn(note, .27f)
            switched.noteOn(note, .27f)
        }
        val warmup = FloatArray(1_024 * 2)
        original.renderStereo(warmup, 1_024)
        switched.renderStereo(warmup, 1_024)

        // Initially all six processors run TREMOLO..BITCRUSH. This enables
        // the seventh type, SATURATION, during an already-playing 16-note chord.
        assertEquals(7, FxModuleType.entries.size)
        switched.setFxSlot(5, FxModuleType.SATURATION,
            enabled = true, wet = .86f, depth = .91f)
        val unchanged = FloatArray(3_072 * 2)
        val edited = FloatArray(unchanged.size)
        original.renderStereo(unchanged, 3_072)
        switched.renderStereo(edited, 3_072)
        var difference = 0.0
        for (i in unchanged.indices) {
            difference += abs((unchanged[i] - edited[i]).toDouble())
            assertTrue(edited[i].isFinite())
            assertTrue(abs(edited[i]) <= 1.00001f)
        }
        assertTrue("The seventh FX type must audibly replace BITCRUSH even under full load",
            difference > 1.0)
        assertTrue(switched.modulationRoute(63).enabled)
        assertEquals(16, switched.activeVoiceCount)
        switched.allNotesOff()
        switched.renderStereo(FloatArray(4_096 * 2), 4_096)
        assertEquals(0, switched.activeVoiceCount)
    }
}
