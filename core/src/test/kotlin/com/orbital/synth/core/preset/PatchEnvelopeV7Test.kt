package com.orbital.synth.core.preset

import com.orbital.synth.core.LayerAmpEnvelope
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

/** Strict V7 opt-in codec plus V1–V6 byte-preserving import and disk storage. */
class PatchEnvelopeV7Test {
    @get:Rule val directory = TemporaryFolder()

    private val v7 = FactoryPatches.DEFAULT.copy(
        id = "layer-v7-envelope",
        oscillatorSlots = listOf(
            OscillatorSlot(
                enabled = true, waveform = .38f, morph = .72f,
                level = .75f, coarseSemitones = -12, fineCents = 17f,
                noiseLevel = .47f, noiseColor = .92f,
                ampEnvelope = LayerAmpEnvelope(
                    enabled = true, attack = .37f, decay = .64f,
                    sustain = .29f, release = .91f,
                ),
            ),
            OscillatorSlot(
                enabled = true, waveform = .7f, level = .4f,
                ampEnvelope = LayerAmpEnvelope(
                    enabled = false, attack = .5f, decay = .6f,
                    sustain = .8f, release = .3f,
                ),
            ),
        ),
        modulationRoutes = listOf(ModulationRouteConfig()),
        modulationTempoBpm = 127f,
        modulationSteps = ModulationStepConfig(enabled = true),
        fxSlots = listOf(FxModuleConfig(
            type = FxModuleType.REVERB, enabled = true,
            wet = .7f, depth = .45f, rate = .2f, stereo = .8f,
        )),
    )

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Corrupt V7 patch decoded successfully")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun optedInV7PreservesEveryEnvelopeAndV6NoiseStepFxAndRoutesOnDisk() {
        val encoded = PatchCodec.encode(v7)
        assertEquals(7, PatchCodec.CURRENT_VERSION)
        assertTrue(encoded.startsWith("ORBITAL-PATCH/7\n"))
        assertTrue(encoded.contains("osc.0=1,"))
        assertTrue(encoded.contains("step.seq="))
        assertTrue(encoded.contains("fx.0=1,REVERB,"))
        assertEquals(13, encoded.lines().first { it.startsWith("osc.0=") }
            .substringAfter('=').split(',').size)
        assertEquals(13, encoded.lines().first { it.startsWith("osc.1=") }
            .substringAfter('=').split(',').size)
        assertEquals(v7, PatchCodec.decode(encoded))
        assertEquals(encoded, PatchCodec.encode(PatchCodec.decode(encoded)))

        val path = directory.newFolder("v7-disk").toPath()
        PatchDirectoryStore(path).save(v7)
        val reopened = PatchDirectoryStore(path).load(v7.id)
        assertEquals(v7, reopened)
        assertEquals(encoded, PatchCodec.encode(reopened!!))

        val synth = OrbitalSynth(8_000)
        synth.loadPatch(reopened!!)
        assertEquals(v7.oscillatorSlots[0], synth.oscillatorSlot(0))
        assertEquals(v7.oscillatorSlots[1], synth.oscillatorSlot(1))
        assertEquals(OscillatorSlot(), synth.oscillatorSlot(2))
        assertEquals(.47f, synth.oscillatorSlot(0).noiseLevel, 0f)
        assertTrue(synth.oscillatorSlot(0).ampEnvelope.enabled)
    }

    @Test fun oldV1ToV6PatchesKeepExactVersionBytesAndBypassedEnvelope() {
        val previous = listOf(
            1 to FactoryPatches.DEFAULT,
            2 to FactoryPatches.DEFAULT.copy(
                oscillatorSlots = listOf(OscillatorSlot(enabled = true, level = .7f))),
            3 to FactoryPatches.DEFAULT.copy(
                modulationRoutes = listOf(ModulationRouteConfig())),
            4 to FactoryPatches.DEFAULT.copy(
                fxSlots = listOf(FxModuleConfig())),
            5 to FactoryPatches.DEFAULT.copy(
                modulationSteps = ModulationStepConfig(enabled = true)),
            6 to FactoryPatches.DEFAULT.copy(
                oscillatorSlots = listOf(OscillatorSlot(
                    enabled = true, level = .68f, noiseLevel = .35f,
                    noiseColor = .5f,
                )),
                modulationSteps = ModulationStepConfig(enabled = true),
            ),
        )
        for ((version, patch) in previous) {
            val encoded = PatchCodec.encode(patch)
            assertTrue("V$version incorrectly rewritten as V7",
                encoded.startsWith("ORBITAL-PATCH/$version\n"))
            val roundtrip = PatchCodec.decode(encoded)
            assertEquals(patch, roundtrip)
            assertEquals(encoded, PatchCodec.encode(roundtrip))
            for (layer in roundtrip.oscillatorSlots) {
                assertEquals(LayerAmpEnvelope(), layer.ampEnvelope)
            }
        }
    }

    @Test fun recallingOldPatchesClearsEveryPriorVcaIncludingDisabledEditMetadata() {
        val synth = OrbitalSynth(8_000)
        synth.loadPatch(v7)
        assertEquals(.91f, synth.oscillatorSlot(0).ampEnvelope.release, 0f)
        assertEquals(.5f, synth.oscillatorSlot(1).ampEnvelope.attack, 0f)
        val oldNoise = FactoryPatches.DEFAULT.copy(
            id = "old-noise-v6",
            oscillatorSlots = listOf(OscillatorSlot(
                enabled = true, level = .43f, noiseLevel = .6f, noiseColor = .5f,
            )),
        )
        synth.loadPatch(PatchCodec.decode(PatchCodec.encode(oldNoise)))
        assertEquals(LayerAmpEnvelope(), synth.oscillatorSlot(0).ampEnvelope)
        assertEquals(LayerAmpEnvelope(), synth.oscillatorSlot(1).ampEnvelope)
        assertEquals(.6f, synth.oscillatorSlot(0).noiseLevel, 0f)
        synth.loadPatch(FactoryPatches.DEFAULT)
        for (slot in 0 until 8) assertEquals(
            OscillatorSlot(), synth.oscillatorSlot(slot),
        )
    }

    @Test fun disabledButProgrammedEnvelopeMustBeSavedNotDiscarded() {
        val edited = FactoryPatches.DEFAULT.copy(
            id = "v7-disabled-edit",
            oscillatorSlots = listOf(OscillatorSlot(
                enabled = false,
                ampEnvelope = LayerAmpEnvelope(
                    enabled = false, attack = .81f, sustain = .38f,
                ),
            )),
        )
        val wire = PatchCodec.encode(edited)
        assertTrue(wire.startsWith("ORBITAL-PATCH/7\n"))
        assertEquals(edited, PatchCodec.decode(wire))
        assertEquals(.81f, PatchCodec.decode(wire)
            .oscillatorSlots[0].ampEnvelope.attack, 0f)
    }

    @Test fun maliciousEnvelopeFlagsLengthsNanInfinityAndOverflowsAreRejected() {
        val encoded = PatchCodec.encode(v7)
        val oldRow = encoded.lines().first { it.startsWith("osc.0=") }
        val fields = oldRow.substringAfter('=').split(',')
        fun replace(values: List<String>) =
            encoded.replace(oldRow, "osc.0=" + values.joinToString(","))
        fun update(index: Int, value: String): String =
            replace(fields.toMutableList().apply { this[index] = value })

        rejects { PatchCodec.decode(replace(fields.dropLast(1))) }
        rejects { PatchCodec.decode(replace(fields + "0.3")) }
        rejects { PatchCodec.decode(update(8, "true")) }
        rejects { PatchCodec.decode(update(8, "2")) }
        rejects { PatchCodec.decode(update(9, "NaN")) }
        rejects { PatchCodec.decode(update(10, "Infinity")) }
        rejects { PatchCodec.decode(update(11, "-0.001")) }
        rejects { PatchCodec.decode(update(12, "1.0001")) }
        rejects { PatchCodec.decode(update(6, "NaN")) }
        rejects { PatchCodec.decode(encoded.replace(
            "ORBITAL-PATCH/7", "ORBITAL-PATCH/6",
        )) }
        rejects { PatchCodec.decode(encoded.replace(
            "mod.tempo=127.0\n", "",
        )) }
        rejects { PatchCodec.decode(encoded.replace(
            oldRow + "\n", oldRow + "\n" + oldRow + "\n",
        )) }
        rejects { PatchCodec.decode(encoded.replace(
            "ORBITAL-PATCH/7", "ORBITAL-PATCH/1",
        )) }
    }

    @Test fun spoofingV7WithoutAnyRealEditedEnvelopeMustBeRejected() {
        val before = PatchCodec.encode(FactoryPatches.DEFAULT.copy(
            id = "v6-spoof", oscillatorSlots = listOf(OscillatorSlot(
                enabled = true, level = .4f, noiseLevel = .2f,
            )),
        ))
        val row = before.lines().first { it.startsWith("osc.0=") }
        val spoiled = before.replace("ORBITAL-PATCH/6", "ORBITAL-PATCH/7")
            .replace(row, row + ",0,0.0,0.0,1.0,0.0")
        rejects { PatchCodec.decode(spoiled) }
    }
}
