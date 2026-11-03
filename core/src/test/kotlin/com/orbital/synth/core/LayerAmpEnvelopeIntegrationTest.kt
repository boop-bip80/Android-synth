package com.orbital.synth.core

import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Offline PCM in the actual five-family stereo voice renderer, not an isolated
 * envelope calculator. This additionally checks untouched V6 noise and OSC
 * modulation rendering bit-for-bit whenever the new layer VCA is bypassed.
 */
class LayerAmpEnvelopeIntegrationTest {
    @Test fun defaultDisabledEnvelopeIsRawBitExactWithExistingV6SignalPath() {
        for (engine in 0..4) {
            val legacy = clean()
            val explicit = clean()
            for (synth in arrayOf(legacy, explicit)) {
                synth.setParameter(ParameterId.ENGINE, engine / 4f)
                synth.setOscillatorSlot(
                    0, OscillatorSlot(
                        enabled = true, waveform = .32f, morph = .71f,
                        level = .64f, noiseLevel = .37f, noiseColor = .85f,
                    ),
                )
                synth.setOscillatorSlot(7, OscillatorSlot(
                    enabled = true, waveform = .85f, level = .18f, coarseSemitones = 7,
                ))
                synth.setModulationRoute(
                    0, ModSource.TOUCH_X, ModDestination.OSC1_GAIN, .25f,
                )
                synth.setModulationRoute(
                    1, ModSource.TOUCH_Y, ModDestination.OSC8_PITCH, .45f,
                )
                synth.setModulationSource(ModSource.TOUCH_X, .8f)
                synth.setModulationSource(ModSource.TOUCH_Y, .6f)
                synth.noteOn(62, .9f)
            }
            // Existing patch controls additionally copy an explicit disabled
            // VCA object; this must not alter oscillator/noise RNG arithmetic.
            explicit.setOscillatorSlot(
                0, explicit.oscillatorSlot(0).copy(ampEnvelope = LayerAmpEnvelope()),
            )
            val a = FloatArray(4_608 * 2)
            val b = FloatArray(a.size)
            legacy.renderStereo(a, 4_608)
            explicit.renderStereo(b, 4_608)
            assertBits(a, b)
            assertFiniteBounded(a)
        }
    }

    @Test fun perLayerAttackIsAudibleInEveryEngineWhileOffLayersStaySilent() {
        for (engine in 0..4) {
            val clean = clean()
            val shaped = clean()
            val baseline = OscillatorSlot(
                enabled = true, waveform = .68f, morph = 1f,
                level = .87f, noiseLevel = .1f, noiseColor = .5f,
                coarseSemitones = 7,
            )
            for (synth in arrayOf(clean, shaped)) {
                synth.setParameter(ParameterId.ENGINE, engine / 4f)
                synth.setOscillatorSlot(3, baseline)
            }
            shaped.setOscillatorSlot(3, baseline.copy(
                ampEnvelope = LayerAmpEnvelope(
                    enabled = true, attack = 1f, decay = .2f,
                    sustain = .3f, release = .45f,
                ),
            ))
            clean.noteOn(67, .85f)
            shaped.noteOn(67, .85f)
            val dry = FloatArray(2_048 * 2)
            val wet = FloatArray(dry.size)
            clean.renderStereo(dry, 2_048)
            shaped.renderStereo(wet, 2_048)
            assertTrue("Engine $engine must audibly shape independent OSC4 layer",
                meanDifference(dry, wet) > .0008)
            assertFiniteBounded(wet)
        }
        val empty = clean()
        val withDisabled = clean()
        withDisabled.setOscillatorSlot(
            6, OscillatorSlot(ampEnvelope = LayerAmpEnvelope(
                enabled = true, attack = 1f, decay = .1f, sustain = .3f, release = .3f,
            )),
        )
        empty.noteOn(57)
        withDisabled.noteOn(57)
        val a = FloatArray(4_096 * 2)
        val b = FloatArray(a.size)
        empty.renderStereo(a, 4_096)
        withDisabled.renderStereo(b, 4_096)
        assertBits(a, b)
    }

    @Test fun layerVcaStereoPcmIsBitExactIn192And512FrameBlocksAcrossRelease() {
        fun setup() = clean().apply {
            setOscillatorSlot(0, OscillatorSlot(
                enabled = true, waveform = .62f, level = .9f,
                ampEnvelope = LayerAmpEnvelope(
                    enabled = true, attack = .05f, decay = .1f,
                    sustain = .55f, release = .02f,
                ),
            ))
            setOscillatorSlot(2, OscillatorSlot(
                enabled = true, waveform = .3f, level = .5f,
                ampEnvelope = LayerAmpEnvelope(
                    enabled = true, attack = .7f, decay = .3f,
                    sustain = .8f, release = .23f,
                ),
            ))
            noteOn(64)
        }
        val full = setup()
        val by192 = setup()
        val by512 = setup()
        fun renderTimeline(synth: OrbitalSynth, block: Int): FloatArray {
            val all = FloatArray(6_144 * 2)
            val scratch = FloatArray(block * 2)
            for (chunk in 0 until (6_144 / block)) {
                if (chunk * block == 3_072) synth.noteOff(64)
                synth.renderStereo(scratch, block)
                System.arraycopy(scratch, 0, all, chunk * scratch.size, scratch.size)
            }
            return all
        }
        val a = renderTimeline(full, 3_072)
        val b = renderTimeline(by192, 192)
        val c = renderTimeline(by512, 512)
        assertBits(a, b)
        assertBits(a, c)
        assertFiniteBounded(c)
    }

    @Test fun liveEnvelopeUpdatesAndLegacySetterPreserveIndependentNoiseFields() {
        val synth = clean()
        synth.setOscillatorSlot(1, OscillatorSlot(
            enabled = true, level = .75f, noiseLevel = .42f,
            noiseColor = .89f, ampEnvelope = LayerAmpEnvelope(
                enabled = true, attack = .3f, decay = .4f,
                sustain = .6f, release = .7f,
            ),
        ))
        synth.setOscillatorSlot(
            index = 1, enabled = true, waveform = .25f, level = .9f,
        )
        val before = synth.oscillatorSlot(1)
        assertEquals(.42f, before.noiseLevel, 0f)
        assertEquals(.89f, before.noiseColor, 0f)
        assertTrue(before.ampEnvelope.enabled)
        assertEquals(.7f, before.ampEnvelope.release, 0f)
        synth.setOscillatorSlot(1, before.copy(ampEnvelope =
            before.ampEnvelope.copy(release = Float.NaN)))
        assertEquals(before, synth.oscillatorSlot(1))
        synth.noteOn(60)
        val a = FloatArray(192 * 2)
        repeat(30) {
            synth.renderStereo(a, 192)
            assertFiniteBounded(a)
        }
        synth.noteOff(60)
        synth.renderStereo(a, 192)
        assertFiniteBounded(a)
    }

    @Test fun sixteenVoiceEightLayerPolyphonyStealingAndHardStopHaveFinitePcm() {
        val synth = clean(16).apply {
            setParameter(ParameterId.RELEASE, 0f)
            for (slot in 0 until 8) {
                setOscillatorSlot(slot, OscillatorSlot(
                    enabled = true, level = .47f,
                    waveform = slot / 7f, noiseLevel = if (slot % 2 == 0) .12f else 0f,
                    ampEnvelope = LayerAmpEnvelope(
                        enabled = true,
                        attack = slot / 8f, decay = .1f, sustain = .5f,
                        release = .2f,
                    ),
                ))
            }
        }
        for (note in 41..72) synth.noteOn(note, .8f)
        val block = FloatArray(192 * 2)
        repeat(80) {
            synth.renderStereo(block, 192)
            assertFiniteBounded(block)
        }
        synth.allNotesOff()
        synth.renderStereo(FloatArray(12_288 * 2), 12_288)
        synth.renderStereo(block, 192)
        assertEquals(0, synth.activeVoiceCount)
        assertFiniteBounded(block)
    }

    @Test fun rapidCrossThreadLayerAdsrUpdatesRemainFiniteWithoutStuckNotes() {
        val synth = clean(8)
        for (slot in 0 until 8) synth.setOscillatorSlot(slot, OscillatorSlot(
            enabled = true, waveform = slot / 7f, level = .53f,
        ))
        synth.noteOn(60, .9f)
        val producer = Thread {
            repeat(2_500) { edit ->
                val lane = edit % 8
                val prior = synth.oscillatorSlot(lane)
                synth.setOscillatorSlot(lane, prior.copy(
                    ampEnvelope = LayerAmpEnvelope(
                        enabled = edit % 7 != 0,
                        attack = (edit % 11) / 11f,
                        decay = (edit % 13) / 13f,
                        sustain = (edit % 17) / 17f,
                        release = (edit % 19) / 19f,
                    ),
                ))
            }
        }
        producer.start()
        val output = FloatArray(192 * 2)
        repeat(120) {
            synth.renderStereo(output, 192)
            assertFiniteBounded(output)
        }
        producer.join(10_000)
        assertFalse("Atomic UI/worker VCA editing deadlocked", producer.isAlive)
        synth.allNotesOff()
        synth.renderStereo(FloatArray(12_288 * 2), 12_288)
        assertEquals(0, synth.activeVoiceCount)
    }

    private fun clean(maxVoices: Int = 4) = OrbitalSynth(
        sampleRate = 48_000, maxVoices = maxVoices,
    ).apply {
        setParameter(ParameterId.WAVE, 0f)
        setParameter(ParameterId.MORPH, 0f)
        setParameter(ParameterId.CUTOFF, .99f)
        setParameter(ParameterId.RESONANCE, 0f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.LFO_DEPTH, 0f)
        setParameter(ParameterId.FX_AMOUNT, 0f)
        setParameter(ParameterId.ATTACK, 0f)
        setParameter(ParameterId.DECAY, 0f)
        setParameter(ParameterId.SUSTAIN, 1f)
        setParameter(ParameterId.RELEASE, 0f)
    }

    private fun meanDifference(a: FloatArray, b: FloatArray): Double {
        var diff = 0.0
        for (i in a.indices) diff += abs((a[i] - b[i]).toDouble())
        return diff / a.size
    }

    private fun assertBits(a: FloatArray, b: FloatArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals("PCM float raw-bit mismatch at $i",
                java.lang.Float.floatToRawIntBits(a[i]),
                java.lang.Float.floatToRawIntBits(b[i]))
        }
    }

    private fun assertFiniteBounded(pcm: FloatArray) {
        for (i in pcm.indices) {
            assertTrue("Non-finite PCM at $i", pcm[i].isFinite())
            assertTrue("PCM outside [-1,1] at $i", abs(pcm[i]) <= 1.00001f)
        }
    }
}
