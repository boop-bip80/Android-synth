package com.orbital.synth.core.preset

import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.dsp.processing.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

class PatchStepsV5Test {
    @get:Rule val tmp = TemporaryFolder()

    private val steps = ModulationStepConfig(
        enabled = true,
        values = List(16) { index -> (index - 8) / 8f },
        length = 11,
        beatsPerStep = .5f,
        glide = .42f,
        direction = StepDirection.PING_PONG,
        retriggerOnNote = true,
    )

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Malformed V5 patch accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun v5RoundTripsAllSixteenPadsExistingFxEnumAndV3LfoRoutes() {
        val fx = FxModuleType.entries.take(6).mapIndexed { index, type ->
            FxModuleConfig(type, enabled = index % 2 == 0, wet = index / 6f,
                depth = .5f, rate = .25f, stereo = .75f)
        }
        val patch = FactoryPatches.DEFAULT.copy(
            id = "full-v5-patch",
            oscillatorSlots = listOf(OscillatorSlot(true, .5f, .5f, .3f, 7, -3f)),
            modulationRoutes = listOf(ModulationRouteConfig(
                enabled = true, source = ModSource.STEP,
                destination = ModDestination.AMP, depth = .5f,
            )),
            fxSlots = fx,
            modulationSteps = steps,
            modulationTempoBpm = 131f,
        )
        val text = PatchCodec.encode(patch)
        assertEquals(6, PatchCodec.CURRENT_VERSION)
        assertTrue(text.startsWith("ORBITAL-PATCH/5\n"))
        assertTrue(text.contains("step.seq=1,PING_PONG,11,0.5,0.42,1"))
        assertEquals(1, text.lines().count { it.startsWith("step.seq=") })
        assertEquals(patch, PatchCodec.decode(text))
        assertEquals(text, PatchCodec.encode(PatchCodec.decode(text)))
        assertEquals(fx, PatchCodec.decode(text).fxSlots)
        // V4 FX palette is append-only; prove V5 patch recall retains every FX enum
        // by its stable name, including modules beyond the six live FX slots.
        for (type in FxModuleType.entries) {
            val fxOnly = patch.copy(
                fxSlots = listOf(FxModuleConfig(type = type, enabled = true, wet = .4f)),
            )
            val encoded = PatchCodec.encode(fxOnly)
            assertTrue("Missing FX type ${type.name}", encoded.contains("fx.0=1,${type.name},"))
            assertEquals("V5 corrupted FX type ${type.name}", fxOnly, PatchCodec.decode(encoded))
        }
    }

    @Test fun v1V2V3AndV4KeepTheirWireHeadersWithoutStepFields() {
        val candidates = listOf(
            1 to FactoryPatches.DEFAULT,
            2 to FactoryPatches.DEFAULT.copy(oscillatorSlots = listOf(OscillatorSlot())),
            3 to FactoryPatches.DEFAULT.copy(modulationRoutes = listOf(ModulationRouteConfig())),
            4 to FactoryPatches.DEFAULT.copy(fxSlots = listOf(FxModuleConfig())),
        )
        for ((version, patch) in candidates) {
            val encoded = PatchCodec.encode(patch)
            assertTrue("Version $version", encoded.startsWith("ORBITAL-PATCH/$version\n"))
            assertFalse(encoded.contains("step.seq="))
            assertNull(PatchCodec.decode(encoded).modulationSteps)
            assertEquals(patch, PatchCodec.decode(encoded))
        }
    }

    @Test fun diskReopenRestoresRhythmAndLoadingV4ClearsPreviousStepState() {
        val directory = tmp.newFolder("v5-bank").toPath()
        val store = PatchDirectoryStore(directory)
        val patch = FactoryPatches.DEFAULT.copy(
            id = "user-v5-rhythm",
            modulationSteps = steps,
            modulationRoutes = listOf(ModulationRouteConfig(
                enabled = true, source = ModSource.STEP,
                destination = ModDestination.MORPH, depth = .4f,
            )),
        )
        store.save(patch)
        val restored = PatchDirectoryStore(directory).load(patch.id)!!
        assertEquals(patch, restored)
        val synth = OrbitalSynth(sampleRate = 8_000)
        synth.loadPatch(restored)
        assertEquals(steps, synth.modulationSteps)
        synth.noteOn(60)
        synth.renderStereo(FloatArray(2_500 * 2), 2_500)

        val legacy = FactoryPatches.DEFAULT.copy(
            fxSlots = listOf(FxModuleConfig(FxModuleType.DELAY, false, .5f)),
        )
        synth.loadPatch(PatchCodec.decode(PatchCodec.encode(legacy)))
        assertFalse(synth.modulationSteps.enabled)
        synth.renderStereo(FloatArray(16 * 2), 16)
        assertEquals(0, synth.modulationStepPlayhead)
    }

    @Test fun v5StepRecallProducesSameAudiblePcmAsDirectEditorSettings() {
        val route = ModulationRouteConfig(
            enabled = true, source = ModSource.STEP,
            destination = ModDestination.AMP, depth = .5f,
        )
        val program = FactoryPatches.DEFAULT.copy(
            modulationSteps = steps.copy(length = 2, beatsPerStep = .25f,
                values = listOf(1f, -1f) + List(14) { 0f }),
            modulationRoutes = listOf(route),
        )
        val restored = OrbitalSynth(sampleRate = 8_000, maxVoices = 2)
        restored.loadPatch(PatchCodec.decode(PatchCodec.encode(program)))
        val direct = OrbitalSynth(sampleRate = 8_000, maxVoices = 2)
        direct.loadPatch(FactoryPatches.DEFAULT)
        direct.setModulationSteps(program.modulationSteps!!)
        direct.setModulationRouteConfig(0, route)
        restored.noteOn(60)
        direct.noteOn(60)
        val a = FloatArray(5_000 * 2)
        val b = FloatArray(a.size)
        restored.renderStereo(a, 5_000)
        direct.renderStereo(b, 5_000)
        assertArrayEquals("Saved V5 rhythmic gate must match actual live UI programming",
            b, a, .0001f)
        assertTrue(a.any { abs(it) > .005f })
    }

    @Test fun v5RejectsGapsDuplicatedFieldsInvalidModesNaNsAndFutureVersions() {
        val patch = FactoryPatches.DEFAULT.copy(modulationSteps = steps)
        val text = PatchCodec.encode(patch)
        assertTrue(text.contains("step.seq="))
        rejects { PatchCodec.decode(text.replace("step.seq=", "step.other=")) }
        rejects { PatchCodec.decode(text + text.lines().first { it.startsWith("step.seq=") } + "\n") }
        rejects { PatchCodec.decode(text.replace("PING_PONG", "UNSUPPORTED")) }
        rejects { PatchCodec.decode(text.replace("step.seq=1", "step.seq=9")) }
        rejects { PatchCodec.decode(text.replace("PING_PONG,11", "PING_PONG,0")) }
        rejects { PatchCodec.decode(text.replace("0.5,0.42", "0.0,0.42")) }
        rejects { PatchCodec.decode(text.replace("0.5,0.42", "NaN,0.42")) }
        rejects { PatchCodec.decode(text.replace("0.5,0.42", "0.5,Infinity")) }
        rejects { PatchCodec.decode(text.replace("0.42,1", "2.0,1")) }
        rejects { PatchCodec.decode(text.replace("step.seq=1,", "step.seq=1,0,")) }
        rejects { PatchCodec.decode(text.replace("ORBITAL-PATCH/5", "ORBITAL-PATCH/7")) }
        val v4 = PatchCodec.encode(FactoryPatches.DEFAULT.copy(
            fxSlots = listOf(FxModuleConfig()),
        ))
        rejects { PatchCodec.decode(v4 + "step.seq=0,FORWARD,16,0.25,0.0,0\n") }
    }
}
