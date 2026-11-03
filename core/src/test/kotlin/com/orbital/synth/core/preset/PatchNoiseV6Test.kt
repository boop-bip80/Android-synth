package com.orbital.synth.core.preset

import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exact V1-V5 compatibility plus V6 colored-oscillator migration. */
class PatchNoiseV6Test {
    @get:Rule val tmp = TemporaryFolder()

    private val colored = listOf(
        OscillatorSlot(enabled = true, waveform = .25f, morph = .7f,
            level = .8f, coarseSemitones = -12, fineCents = 8f,
            noiseLevel = .65f, noiseColor = 0f),
        OscillatorSlot(enabled = true, waveform = .7f, morph = .4f,
            level = .5f, noiseLevel = 1f, noiseColor = .5f),
        OscillatorSlot(enabled = false, waveform = .9f, level = 0f,
            noiseLevel = .3f, noiseColor = 1f),
    )

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Corrupt patch accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun v6RoundTripsThreeNoiseColorsAlongsideV5StepsAndSevenFxEnum() {
        val patch = FactoryPatches.DEFAULT.copy(
            id = "v6-colored-waves",
            oscillatorSlots = colored,
            modulationSteps = ModulationStepConfig(enabled = true),
            fxSlots = listOf(FxModuleConfig(FxModuleType.BITCRUSH,
                enabled = true, wet = .65f, depth = .75f)),
        )
        val encoded = PatchCodec.encode(patch)
        assertEquals(6, PatchCodec.CURRENT_VERSION)
        assertTrue(encoded.startsWith("ORBITAL-PATCH/6\n"))
        assertTrue(encoded.contains("step.seq="))
        assertTrue(encoded.contains("fx.0=1,BITCRUSH,"))
        assertEquals(8, encoded.lines().first { it.startsWith("osc.0=") }
            .substringAfter('=').split(',').size)
        assertEquals(patch, PatchCodec.decode(encoded))
        assertEquals(encoded, PatchCodec.encode(PatchCodec.decode(encoded)))

        val directory = tmp.newFolder("noisepatches").toPath()
        PatchDirectoryStore(directory).save(patch)
        val reopened = PatchDirectoryStore(directory).load(patch.id)
        assertEquals(patch, reopened)
        assertEquals(encoded, PatchCodec.encode(reopened!!))
    }

    @Test fun allFivePriorVersionsRetainExactHeadersAndNeutralNoiseFields() {
        val candidates = listOf(
            1 to FactoryPatches.DEFAULT,
            2 to FactoryPatches.DEFAULT.copy(
                oscillatorSlots = listOf(OscillatorSlot(enabled = true, level = .7f))),
            3 to FactoryPatches.DEFAULT.copy(
                modulationRoutes = listOf(ModulationRouteConfig())),
            4 to FactoryPatches.DEFAULT.copy(fxSlots = listOf(FxModuleConfig())),
            5 to FactoryPatches.DEFAULT.copy(
                modulationSteps = ModulationStepConfig(enabled = true)),
        )
        for ((version, patch) in candidates) {
            val encoded = PatchCodec.encode(patch)
            assertTrue("V$version must not be silently upgraded",
                encoded.startsWith("ORBITAL-PATCH/$version\n"))
            val restored = PatchCodec.decode(encoded)
            assertEquals(patch, restored)
            for (slot in restored.oscillatorSlots) {
                assertEquals(0f, slot.noiseLevel, 0f)
                assertEquals(0f, slot.noiseColor, 0f)
            }
            assertEquals(encoded, PatchCodec.encode(restored))
        }
    }

    @Test fun v6AllowsNoisePatchWithoutStepSequencerButRetainsV5OnRecall() {
        val onlyNoise = FactoryPatches.DEFAULT.copy(
            id = "noise-only",
            oscillatorSlots = colored.take(1),
        )
        val encoded = PatchCodec.encode(onlyNoise)
        assertTrue(encoded.startsWith("ORBITAL-PATCH/6\n"))
        assertTrue(encoded.contains("mod.tempo=120.0"))
        assertFalse(encoded.contains("step.seq="))
        assertEquals(onlyNoise, PatchCodec.decode(encoded))
        assertNull(PatchCodec.decode(encoded).modulationSteps)

        val synth = OrbitalSynth(8_000)
        synth.loadPatch(FactoryPatches.DEFAULT.copy(
            id = "old-v5",
            modulationSteps = ModulationStepConfig(enabled = true)))
        assertTrue(synth.modulationSteps.enabled)
        synth.loadPatch(onlyNoise)
        assertFalse(synth.modulationSteps.enabled)
        assertEquals(onlyNoise.oscillatorSlots[0], synth.oscillatorSlot(0))
    }

    @Test fun recallOfLegacyPatchesClearsAllPreviouslySavedNoiseConfigurations() {
        val synth = OrbitalSynth(8_000)
        val saved = FactoryPatches.DEFAULT.copy(
            id = "remember-noise",
            oscillatorSlots = colored,
        )
        synth.loadPatch(PatchCodec.decode(PatchCodec.encode(saved)))
        assertEquals(.65f, synth.oscillatorSlot(0).noiseLevel, 0f)
        assertEquals(1f, synth.oscillatorSlot(1).noiseLevel, 0f)

        val old = FactoryPatches.DEFAULT.copy(
            id = "v2-wave",
            oscillatorSlots = listOf(OscillatorSlot(enabled = true, level = .6f)),
        )
        synth.loadPatch(PatchCodec.decode(PatchCodec.encode(old)))
        assertEquals(0f, synth.oscillatorSlot(0).noiseLevel, 0f)
        assertEquals(0f, synth.oscillatorSlot(1).noiseLevel, 0f)
        assertEquals(0f, synth.oscillatorSlot(2).noiseColor, 0f)
        synth.loadPatch(FactoryPatches.DEFAULT)
        for (i in 0 until synth.oscillatorSlotCount) {
            assertEquals(OscillatorSlot(), synth.oscillatorSlot(i))
        }
    }

    @Test fun malformedV6NoiseFieldsAndVersionSmugglingAreRejected() {
        val encoded = PatchCodec.encode(FactoryPatches.DEFAULT.copy(
            id = "malformed-v6",
            oscillatorSlots = colored.take(1),
        ))
        val field = encoded.lines().first { it.startsWith("osc.0=") }
        val values = field.substringAfter('=').split(',')
        fun replaced(fields: List<String>): String =
            encoded.replace(field, "osc.0=" + fields.joinToString(","))

        rejects { PatchCodec.decode(replaced(values.dropLast(1))) }
        rejects { PatchCodec.decode(replaced(values + "0.7")) }
        rejects { PatchCodec.decode(replaced(values.dropLast(1) + "NaN")) }
        rejects { PatchCodec.decode(replaced(values.dropLast(1) + "Infinity")) }
        rejects { PatchCodec.decode(replaced(values.dropLast(1) + "1.2")) }
        rejects { PatchCodec.decode(replaced(values.dropLast(2) + "-0.1" + "0.5")) }
        rejects { PatchCodec.decode(encoded.replace("ORBITAL-PATCH/6", "ORBITAL-PATCH/5")) }
        rejects { PatchCodec.decode(encoded.replace("ORBITAL-PATCH/6", "ORBITAL-PATCH/7")) }
        rejects { PatchCodec.decode(encoded.replace("mod.tempo=120.0\n", "")) }
        rejects { PatchCodec.decode(encoded.replace(field + "\n", field + "\n" + field + "\n")) }
    }
}
