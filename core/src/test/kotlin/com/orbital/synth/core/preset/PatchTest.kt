package com.orbital.synth.core.preset

import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.OscillatorSlot

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PatchTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected invalid payload or unsafe path.
        }
    }

    @Test fun allFactoryPatchesValidateAndHaveUniqueIdsAndParameters() {
        assertTrue(FactoryPatches.ALL.size >= 16)
        assertEquals(FactoryPatches.ALL.size, FactoryPatches.ALL.map { it.id }.toSet().size)
        assertSame(FactoryPatches.DEFAULT, FactoryPatches.byId("aurora"))
        val distinctFingerprints = FactoryPatches.ALL.map { it.parameters }.toSet()
        assertEquals(FactoryPatches.ALL.size, distinctFingerprints.size)
        for (patch in FactoryPatches.ALL) {
            assertEquals(PatchParameters.NAMES, patch.parameters.keys)
            assertTrue(patch.parameters.values.all { it in 0f..1f })
            assertTrue(patch.name.isNotBlank())
        }
    }

    @Test fun everyFactoryPatchRoundTripsExactlyAndDeterministically() {
        for (patch in FactoryPatches.ALL) {
            val first = PatchCodec.encode(patch)
            assertTrue(first.startsWith("ORBITAL-PATCH/1\n"))
            val parsed = PatchCodec.decode(first)
            assertEquals(patch, parsed)
            assertEquals(first, PatchCodec.encode(parsed))
        }
    }

    @Test fun additiveOscillatorRackV2RoundTripsAndLoadsIntoAudioEngine() {
        val layers = listOf(
            OscillatorSlot(true, .5f, .67f, .44f, -12, 7.5f),
            OscillatorSlot(true, 1f, 1f, .23f, 7, -13f),
            OscillatorSlot(),
        )
        val original = FactoryPatches.DEFAULT.copy(oscillatorSlots = layers)
        val encoded = PatchCodec.encode(original)
        assertTrue(encoded.startsWith("ORBITAL-PATCH/2\n"))
        assertEquals(original, PatchCodec.decode(encoded))
        assertEquals(encoded, PatchCodec.encode(PatchCodec.decode(encoded)))

        val synth = OrbitalSynth()
        synth.loadPatch(original)
        assertEquals(layers[0], synth.oscillatorSlot(0))
        assertEquals(layers[1], synth.oscillatorSlot(1))
        assertFalse(synth.oscillatorSlot(2).enabled)
        // Activating any original V1 factory patch must silence the entire rack.
        synth.loadPatch(FactoryPatches.DEFAULT)
        for (i in 0 until synth.oscillatorSlotCount) {
            assertFalse(synth.oscillatorSlot(i).enabled)
        }
        assertTrue(PatchCodec.encode(FactoryPatches.DEFAULT).startsWith("ORBITAL-PATCH/1\n"))
    }

    @Test fun corruptedOscillatorPatchIsRejectedBeforeEnteringEngine() {
        val prefix = PatchCodec.encode(FactoryPatches.DEFAULT)
            .replaceFirst("ORBITAL-PATCH/1", "ORBITAL-PATCH/2")
        rejects { PatchCodec.decode(prefix + "osc.0=1,NaN,1,0.3,0,0\n") }
        rejects { PatchCodec.decode(prefix + "osc.0=1,0,1,2.3,0,0\n") }
        rejects { PatchCodec.decode(prefix + "osc.0=1,0,1,0.5,999,0\n") }
        rejects { PatchCodec.decode(prefix + "osc.0=1,0,1,0.5,0,Infinity\n") }
        rejects { PatchCodec.decode(prefix + "osc.1=1,0,1,0.5,0,0\n") }
        rejects { PatchCodec.decode(prefix + "osc.0=1,0,1,0.5,0,0\nosc.0=0,0,1,0,0,0\n") }
        rejects { PatchCodec.decode(prefix + "osc.8=1,0,1,0.5,0,0\n") }
        rejects { PatchCodec.decode(prefix + "osc.0=maybe,0,1,0.5,0,0\n") }
    }

    @Test fun unicodeAndReservedCharactersRoundTrip() {
        val patch = SynthPatch(
            id = "my-patch", name = "Örbital ♫ / + ? = %", author = "A & B",
            description = "Bright\nlong tail = warm", tags = setOf("🎹", "warm/air"),
            parameters = mapOf("CUTOFF" to 0.512345f, "MORPH" to 0.75f),
        )
        assertEquals(patch, PatchCodec.decode(PatchCodec.encode(patch)))
    }

    @Test fun rejectsUnknownFutureVersion() {
        val encoded = PatchCodec.encode(FactoryPatches.DEFAULT)
        rejects { PatchCodec.decode(encoded.replace("ORBITAL-PATCH/1", "ORBITAL-PATCH/3")) }
    }

    @Test fun rejectsDuplicateFieldsAndUnknownParameterKeys() {
        val encoded = PatchCodec.encode(FactoryPatches.DEFAULT)
        rejects { PatchCodec.decode(encoded + "param.CUTOFF=0.5\n") }
        rejects { PatchCodec.decode(encoded + "param.UNKNOWN=0.5\n") }
        rejects { PatchCodec.decode(encoded + "id=duplicate\n") }
    }

    @Test fun rejectsMissingFieldsMalformedAndNonFiniteNumbers() {
        val encoded = PatchCodec.encode(FactoryPatches.DEFAULT)
        rejects { PatchCodec.decode(encoded.replace(Regex("^name=.*\\n", RegexOption.MULTILINE), "")) }
        rejects { PatchCodec.decode(encoded.replace("param.CUTOFF=0.63", "param.CUTOFF=NaN")) }
        rejects { PatchCodec.decode(encoded.replace("param.CUTOFF=0.63", "param.CUTOFF=1.8")) }
        rejects { PatchCodec.decode(encoded.replace("name=AURORA", "name=%Q1")) }
        rejects { PatchCodec.decode("not a patch") }
    }

    @Test fun rejectsOversizeAndUnsafePatchIds() {
        rejects { PatchCodec.decode("x".repeat(65_537)) }
        rejects { SynthPatch("../outside", "bad", parameters = mapOf("CUTOFF" to .5f)) }
        rejects { SynthPatch("safe", "bad", parameters = mapOf("CUTOFF" to Float.POSITIVE_INFINITY)) }
        rejects { SynthPatch("safe", "bad", parameters = mapOf("NOT_REAL" to .5f)) }
    }

    @Test fun collectionImportsExportsAndReplacesById() {
        val library = PatchCollection()
        assertEquals(FactoryPatches.ALL.size, library.list().size)
        val edited = FactoryPatches.DEFAULT.copy(
            name = "Custom Aurora", parameters = FactoryPatches.DEFAULT.parameters + ("CUTOFF" to .25f),
        )
        library.importPatch(PatchCodec.encode(edited))
        assertEquals(edited, library.get("aurora"))
        assertEquals(edited, PatchCodec.decode(library.exportPatch("aurora")!!))
        assertTrue(library.remove("aurora"))
        assertNull(library.get("aurora"))
        assertFalse(library.remove("aurora"))
    }

    @Test fun directoryStorePersistsAtomicallyAndListsSorted() {
        val dir = temporary.newFolder("patch-bank").toPath()
        val store = PatchDirectoryStore(dir)
        val a = FactoryPatches.ALL[0]
        val b = FactoryPatches.ALL[1]
        store.save(b)
        store.save(a)
        assertEquals(listOf(a.id, b.id).sorted(), store.list().map { it.id })
        assertEquals(a, store.load(a.id))
        assertEquals(b, store.load(b.id))
        val updated = a.copy(name = "Edited Aurora")
        store.save(updated)
        assertEquals(updated, store.load(a.id))
        assertTrue(store.delete(a.id))
        assertNull(store.load(a.id))
        assertFalse(store.delete(a.id))
        assertTrue(Files.list(dir).use { it.noneMatch { file -> file.fileName.toString().endsWith(".tmp") } })
    }

    @Test fun directoryRejectsTraversalAndCorruptPayloads() {
        val dir = temporary.newFolder("other-bank").toPath()
        val store = PatchDirectoryStore(dir)
        rejects { store.load("../sneaky") }
        Files.write(dir.resolve("bad.orbital"), "ORBITAL-PATCH/9\n".toByteArray(StandardCharsets.UTF_8))
        rejects { store.load("bad") }
    }

    @Test fun emptyDirectoryReturnsEmptyList() {
        val dir = temporary.newFolder("empty").toPath().resolve("missing")
        val store = PatchDirectoryStore(dir)
        assertTrue(store.list().isEmpty())
        assertNull(store.load("not-found"))
    }
    @Test fun unisonDetunePatchKeysRoundTripAndOldPatchesStillLoad() {
        val enhanced = FactoryPatches.DEFAULT.copy(
            parameters = FactoryPatches.DEFAULT.parameters +
                mapOf("UNISON" to .75f, "DETUNE" to .4f),
        )
        assertEquals(enhanced, PatchCodec.decode(PatchCodec.encode(enhanced)))
        assertEquals(0f, FactoryPatches.DEFAULT.parameter("UNISON"), 0f)
        assertEquals(0f, FactoryPatches.DEFAULT.parameter("DETUNE"), 0f)
        // Prior ORBITAL-PATCH/1 files contain neither field. They remain readable.
        val legacy = enhanced.copy(
            parameters = enhanced.parameters - setOf("UNISON", "DETUNE"),
        )
        assertEquals(legacy, PatchCodec.decode(PatchCodec.encode(legacy)))
        val previousGeneration = enhanced.copy(
            parameters = enhanced.parameters - setOf("UNISON", "DETUNE", "ENGINE", "CHARACTER"),
        )
        assertEquals(previousGeneration, PatchCodec.decode(PatchCodec.encode(previousGeneration)))
    }

}
