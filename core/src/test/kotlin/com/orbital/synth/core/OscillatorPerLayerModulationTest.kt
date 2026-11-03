package com.orbital.synth.core

import com.orbital.synth.core.dsp.oscillator.LayeredOscillatorRack
import com.orbital.synth.core.dsp.oscillator.WavetableBank
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class OscillatorPerLayerModulationTest {
    @Test fun neutralAndClearedRoutesPreserveRawStereoPcmBits() {
        val dry = clean()
        val cleared = clean()
        for (slot in 0 until 8) {
            val config = OscillatorSlot(
                enabled = true, waveform = slot / 7f, morph = 0.8f,
                level = 0.35f, coarseSemitones = slot - 4,
                fineCents = slot * 11f, noiseLevel = if (slot == 3) 0.1f else 0f,
            )
            dry.setOscillatorSlot(slot, config)
            cleared.setOscillatorSlot(slot, config)
            cleared.setModulationRoute(
                slot, ModSource.TOUCH_X,
                ModDestination.entries[ModDestination.OSC1_GAIN.ordinal + slot],
                depth = 0.5f,
            )
            cleared.clearModulationRoute(slot)
        }
        cleared.setModulationSource(ModSource.TOUCH_X, 1f)
        dry.noteOn(60, 0.85f)
        cleared.noteOn(60, 0.85f)
        val a = FloatArray(8_192 * 2)
        val b = FloatArray(a.size)
        dry.renderStereo(a, 8_192)
        cleared.renderStereo(b, 8_192)
        assertRawBitsEqual(a, b)
    }

    @Test fun everyOscillatorGainCableChangesOnlyItsOwnAudibleLayer() {
        for (slot in 0 until 8) {
            val dry = clean()
            val routed = clean()
            val untouched = clean()
            val config = OscillatorSlot(
                enabled = true, waveform = 0.28f + slot * 0.08f,
                level = 0.9f, coarseSemitones = 3 + slot,
                morph = 1f,
            )
            for (machine in arrayOf(dry, routed, untouched)) {
                machine.setOscillatorSlot(slot, config)
                machine.noteOn(60)
            }
            routed.setModulationRoute(
                0, ModSource.TOUCH_X, gainDestination(slot), depth = -0.5f,
            )
            routed.setModulationSource(ModSource.TOUCH_X, 1f)
            // Routing OSC(n+1) while only OSC(n) is enabled must leave the
            // real sounding lane and the legacy engine sample-identical.
            untouched.setModulationRoute(
                0, ModSource.TOUCH_X, gainDestination((slot + 1) % 8), depth = -0.5f,
            )
            untouched.setModulationSource(ModSource.TOUCH_X, 1f)

            val a = FloatArray(4_096 * 2)
            val b = FloatArray(a.size)
            val c = FloatArray(a.size)
            dry.renderStereo(a, 4_096)
            routed.renderStereo(b, 4_096)
            untouched.renderStereo(c, 4_096)
            assertTrue("OSC${slot + 1}_GAIN must change its actual PCM",
                meanDifference(a, b) > 0.003)
            assertRawBitsEqual(a, c)
            assertBounded(b)
        }
    }

    @Test fun everyOscillatorPitchCableChangesOnlyItsOwnAudibleLayer() {
        for (slot in 0 until 8) {
            val dry = clean()
            val routed = clean()
            val untouched = clean()
            val config = OscillatorSlot(
                enabled = true, waveform = 0.53f,
                level = 0.86f, coarseSemitones = 0,
                fineCents = slot * 7f, morph = 1f,
            )
            for (machine in arrayOf(dry, routed, untouched)) {
                machine.setOscillatorSlot(slot, config)
                machine.noteOn(69)
            }
            routed.setModulationRoute(
                0, ModSource.TOUCH_Y, pitchDestination(slot), depth = 0.5f,
            )
            routed.setModulationSource(ModSource.TOUCH_Y, 1f)
            untouched.setModulationRoute(
                0, ModSource.TOUCH_Y, pitchDestination((slot + 1) % 8), depth = 0.5f,
            )
            untouched.setModulationSource(ModSource.TOUCH_Y, 1f)

            val a = FloatArray(4_096 * 2)
            val b = FloatArray(a.size)
            val c = FloatArray(a.size)
            dry.renderStereo(a, 4_096)
            routed.renderStereo(b, 4_096)
            untouched.renderStereo(c, 4_096)
            assertTrue("OSC${slot + 1}_PITCH must audibly detune its own wavetable",
                meanDifference(a, b) > 0.003)
            assertRawBitsEqual(a, c)
            assertBounded(b)
        }
    }

    @Test fun allFiveSynthesisEnginesHearBothPerLayerBuses() {
        for (engine in 0..4) {
            val untouched = clean()
            val modded = clean()
            val layer = OscillatorSlot(
                enabled = true, waveform = 0.63f, morph = 1f,
                level = 0.85f, coarseSemitones = 12,
            )
            for (machine in arrayOf(untouched, modded)) {
                machine.setParameter(ParameterId.ENGINE, engine / 4f)
                machine.setOscillatorSlot(2, layer)
                machine.noteOn(64)
            }
            modded.setModulationRoute(
                0, ModSource.TOUCH_X, ModDestination.OSC3_GAIN, -0.3f,
            )
            modded.setModulationRoute(
                1, ModSource.TOUCH_X, ModDestination.OSC3_PITCH, 0.4f,
            )
            modded.setModulationSource(ModSource.TOUCH_X, 1f)
            val baseline = FloatArray(4_096 * 2)
            val routed = FloatArray(baseline.size)
            untouched.renderStereo(baseline, 4_096)
            modded.renderStereo(routed, 4_096)
            assertTrue("Engine $engine must audibly render OSC3 gain/pitch cables",
                meanDifference(baseline, routed) > 0.002)
            assertBounded(routed)
        }
    }

    @Test fun oscillatorBusesStartAndReturnThroughFiniteEightMillisecondSlew() {
        val bus = OscillatorLayerModulation(48_000)
        val frame = FloatArray(ModDestination.entries.size) { 0.5f }
        frame[ModDestination.OSC4_GAIN.ordinal] = 1f
        frame[ModDestination.OSC4_PITCH.ordinal] = 1f
        assertFalse(bus.active)
        bus.configureMasks(1 shl 3, 1 shl 3)
        assertTrue(bus.active)
        assertEquals(1 shl 3, bus.mipSafetyMask)
        bus.next(frame)
        assertTrue("Gain must not jump", bus.gainMultipliers[3] in 1f..1.01f)
        assertTrue("Pitch must not jump", bus.pitchMultipliers[3] in 1.0..1.001)
        repeat(6_000) { bus.next(frame) }
        assertEquals(2f, bus.gainMultipliers[3], 0.001f)
        assertEquals(2.0.pow(0.5), bus.pitchMultipliers[3], 0.000002)
        for (slot in 0 until 8) {
            if (slot != 3) {
                assertEquals(1f, bus.gainMultipliers[slot], 0f)
                assertEquals(1.0, bus.pitchMultipliers[slot], 0.0)
            }
        }
        bus.configureMasks(0, 0)
        bus.next(null)
        assertTrue("Gain release must be smooth", bus.gainMultipliers[3] > 1.98f)
        assertTrue("Pitch release must be smooth", bus.pitchMultipliers[3] > 1.40)
        repeat(9_000) { bus.next(null) }
        assertFalse(bus.active)
        assertEquals(1f, bus.gainMultipliers[3], 0f)
        assertEquals(1.0, bus.pitchMultipliers[3], 0.0)
        assertEquals(0, bus.mipSafetyMask)
    }

    @Test fun onlyPitchRoutedLayerReservesExtraBandlimitHarmonicHeadroom() {
        val rack = LayeredOscillatorRack(48_000)
        for (i in 0..1) {
            rack.configure(i, OscillatorSlot(enabled = true, level = 0.8f, waveform = 0.5f))
            rack.primeSilentSlot(i)
        }
        rack.preparePitch(5_000.0, pitchModulationMask = 0b10)
        // Test-only reflection avoids a public DSP accessor in the shipped app.
        val levelsField = LayeredOscillatorRack::class.java.getDeclaredField("mipLevel")
        levelsField.isAccessible = true
        val levels = levelsField.get(rack) as IntArray
        assertEquals(WavetableBank.levelForFrequency(5_000.0, 48_000), levels[0])
        assertEquals(
            WavetableBank.levelForFrequency(
                5_000.0 * OscillatorLayerModulation.MAX_UPWARD_PITCH_RATIO, 48_000,
            ), levels[1],
        )
        assertTrue("Only routed layer should use a safer mip", levels[1] < levels[0])
        val fadesField = LayeredOscillatorRack::class.java.getDeclaredField("mipFade")
        fadesField.isAccessible = true
        val fades = fadesField.get(rack) as FloatArray
        assertEquals(WavetableBank.fadeToLowerLevel(5_000.0, 48_000, levels[0]),
            fades[0], 0f)
        assertEquals(WavetableBank.fadeToLowerLevel(
            5_000.0 * OscillatorLayerModulation.MAX_UPWARD_PITCH_RATIO, 48_000,
            levels[1]), fades[1], 0f)
    }

    @Test fun routedLfoIsBufferSizeIndependentAt192And512Frames() {
        val longBlock = clean().apply { setupLfoOscillation(this) }
        val chunks = clean().apply { setupLfoOscillation(this) }
        longBlock.noteOn(62)
        chunks.noteOn(62)
        val count = 3_072 // 16 x 192 = 6 x 512; identical MIDI timeline.
        val a = FloatArray(count * 2)
        val b = FloatArray(a.size)
        longBlock.renderStereo(a, count)
        val scratch = FloatArray(192 * 2)
        for (chunk in 0 until 16) {
            chunks.renderStereo(scratch, 192)
            System.arraycopy(scratch, 0, b, chunk * scratch.size, scratch.size)
        }
        assertRawBitsEqual(a, b)
        val chunks512 = clean().apply { setupLfoOscillation(this) }
        chunks512.noteOn(62)
        val c = FloatArray(a.size)
        val wide = FloatArray(512 * 2)
        for (chunk in 0 until 6) {
            chunks512.renderStereo(wide, 512)
            System.arraycopy(wide, 0, c, chunk * wide.size, wide.size)
        }
        assertRawBitsEqual(a, c)
    }

    @Test fun sixteenVoicesEightLayersAndRapidCrossThreadRouteChangesStayBoundedAndRelease() {
        val machine = clean(16).apply {
            setParameter(ParameterId.RELEASE, 0f)
            for (slot in 0 until 8) {
                setOscillatorSlot(
                    slot, OscillatorSlot(
                        enabled = true, waveform = slot / 7f, level = 0.6f,
                        coarseSemitones = slot * 3 - 10, noiseLevel = if (slot % 2 == 0) 0.15f else 0f,
                    )
                )
            }
            for (note in 48..79) noteOn(note, 0.9f) // Forces repeated 16-voice steals.
        }
        val producer = Thread {
            repeat(3_000) { update ->
                val slot = update % 8
                val dest = if (update % 2 == 0) gainDestination(slot)
                    else pitchDestination(slot)
                machine.setModulationRoute(
                    slot, ModSource.TOUCH_PRESSURE, dest,
                    if (update % 3 == 0) -0.5f else 0.5f,
                )
                machine.setModulationSource(
                    ModSource.TOUCH_PRESSURE, if (update % 2 == 0) 1f else -1f,
                )
            }
        }
        producer.start()
        val block = FloatArray(192 * 2)
        repeat(180) {
            machine.renderStereo(block, 192)
            assertBounded(block)
        }
        producer.join(10_000)
        assertFalse("Editor producer must not deadlock audio worker", producer.isAlive)
        machine.allNotesOff()
        machine.renderStereo(FloatArray(12_288 * 2), 12_288)
        machine.renderStereo(block, 192)
        assertEquals(0, machine.activeVoiceCount)
        assertBounded(block)
    }

    private fun setupLfoOscillation(synth: OrbitalSynth) {
        synth.setOscillatorSlot(0, OscillatorSlot(enabled = true, waveform = 0.52f, level = 0.7f))
        synth.setOscillatorSlot(7, OscillatorSlot(enabled = true, waveform = 0.75f, level = 0.5f))
        synth.setModulationRoute(0, ModSource.LFO1, ModDestination.OSC1_PITCH, 0.32f)
        synth.setModulationRoute(1, ModSource.LFO1, ModDestination.OSC8_GAIN, 0.45f)
        synth.setModulationLfo(0, true, LfoShape.SINE, rateHz = 4f)
    }

    private fun gainDestination(slot: Int): ModDestination =
        ModDestination.entries[ModDestination.OSC1_GAIN.ordinal + slot]

    private fun pitchDestination(slot: Int): ModDestination =
        ModDestination.entries[ModDestination.OSC1_PITCH.ordinal + slot]

    private fun clean(voices: Int = 4): OrbitalSynth = OrbitalSynth(
        sampleRate = 48_000, maxVoices = voices,
    ).apply {
        setParameter(ParameterId.WAVE, 0.0f)
        setParameter(ParameterId.MORPH, 0f)
        setParameter(ParameterId.CUTOFF, 0.99f)
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
        var difference = 0.0
        for (i in a.indices) difference += abs((a[i] - b[i]).toDouble())
        return difference / a.size
    }

    private fun assertRawBitsEqual(a: FloatArray, b: FloatArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals("Raw float bits mismatch at PCM sample $i",
                java.lang.Float.floatToRawIntBits(a[i]),
                java.lang.Float.floatToRawIntBits(b[i]))
        }
    }

    private fun assertBounded(pcm: FloatArray) {
        for (i in pcm.indices) {
            assertTrue("Non-finite PCM at sample $i", pcm[i].isFinite())
            assertTrue("Output escaped [-1,1] at sample $i", abs(pcm[i]) <= 1.00001f)
        }
    }
}
