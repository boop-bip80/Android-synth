package com.orbital.synth.core.preset

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PatchModulationV3Test {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun v3DiskSaveReopenAndLegacySwitchPreserveOrClearModulation() {
        val path = temporaryFolder.newFolder("v3-bank").toPath()
        val writer = PatchDirectoryStore(path)
        val programmed = FactoryPatches.DEFAULT.copy(
            id = "saved-v3-modulation",
            modulationRoutes = listOf(sourceRoute),
            modulationLfos = listOf(slowLfo),
            modulationTempoBpm = 111f,
        )
        writer.save(programmed)

        // Model process restart: reopen a new persistence adapter, then load to DSP.
        val reopened = PatchDirectoryStore(path)
        assertEquals(programmed, reopened.load(programmed.id))
        val synth = OrbitalSynth()
        synth.loadPatch(reopened.load(programmed.id)!!)
        assertEquals(sourceRoute, synth.modulationRoute(0))
        assertEquals(slowLfo, synth.modulationLfo(0))
        assertEquals(111f, synth.modulationTempoBpm, 0f)

        // A physically stored legacy patch must also clear V3 runtime state.
        val legacy = FactoryPatches.DEFAULT.copy(id = "saved-legacy")
        writer.save(legacy)
        synth.loadPatch(PatchDirectoryStore(path).load(legacy.id)!!)
        assertFalse(synth.modulationRoute(0).enabled)
        assertEquals(ModulationLfoConfig(enabled = true), synth.modulationLfo(0))
        assertEquals(120f, synth.modulationTempoBpm, 0f)
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid V3 patch to be rejected")
        } catch (_: IllegalArgumentException) {
            // Strict parser or validated immutable patch correctly refused it.
        }
    }

    private val sourceRoute = ModulationRouteConfig(
        enabled = true, source = ModSource.TOUCH_X, destination = ModDestination.MORPH,
        depth = 0.5f, curve = ModCurve.LINEAR,
    )
    private val slowLfo = ModulationLfoConfig(
        enabled = true, shape = LfoShape.SINE, rateHz = 1f,
        beatsPerCycle = 4f, retriggerOnNote = false,
    )

    @Test fun v3RoundTripsMaximumRouteAndLfoCountWithDisabledMetadata() {
        val routes = List(64) { index ->
            when (index) {
                1 -> ModulationRouteConfig(
                    false, ModSource.TOUCH_PRESSURE, ModDestination.FX_AMOUNT,
                    -0.72f, ModCurve.EIGHT_STEPS,
                )
                63 -> ModulationRouteConfig(
                    true, ModSource.LFO8, ModDestination.OSC8_PITCH,
                    3.5f, ModCurve.EXPONENTIAL,
                )
                else -> ModulationRouteConfig()
            }
        }
        val lfos = List(8) { index ->
            ModulationLfoConfig(
                enabled = index % 2 == 0,
                shape = LfoShape.entries[index % LfoShape.entries.size],
                rateHz = index + 0.5f,
                beatsPerCycle = if (index % 2 == 0) 4f else 0f,
                retriggerOnNote = index == 7,
            )
        }
        val patch = FactoryPatches.DEFAULT.copy(
            oscillatorSlots = listOf(OscillatorSlot(true, .5f, .65f, .25f, 7, -13f)),
            modulationRoutes = routes,
            modulationLfos = lfos,
            modulationTempoBpm = 137.5f,
        )
        val encoded = PatchCodec.encode(patch)
        assertTrue(encoded.startsWith("ORBITAL-PATCH/3\n"))
        assertEquals(7, PatchCodec.CURRENT_VERSION)
        assertEquals(patch, PatchCodec.decode(encoded))
        assertEquals(encoded, PatchCodec.encode(PatchCodec.decode(encoded)))
        assertTrue(encoded.length <= 65_536)
    }

    @Test fun loadingV3RestoresEnabledAndDisabledRoutesLfosAndTempo() {
        val disabledWithEdits = ModulationRouteConfig(
            false, ModSource.TOUCH_Y, ModDestination.PAN, -0.65f, ModCurve.SOFT,
        )
        val patch = FactoryPatches.DEFAULT.copy(
            modulationRoutes = listOf(sourceRoute, disabledWithEdits),
            modulationLfos = listOf(slowLfo, ModulationLfoConfig(
                true, LfoShape.SAMPLE_HOLD, 12f, 0f, true,
            )),
            modulationTempoBpm = 93.25f,
        )
        val synth = OrbitalSynth()
        synth.loadPatch(PatchCodec.decode(PatchCodec.encode(patch)))
        assertEquals(sourceRoute, synth.modulationRoute(0))
        assertEquals(disabledWithEdits, synth.modulationRoute(1))
        assertEquals(slowLfo, synth.modulationLfo(0))
        assertEquals(93.25f, synth.modulationTempoBpm, 0f)
        assertTrue(synth.modulationLfo(1).retriggerOnNote)
        assertFalse(synth.modulationRoute(63).enabled)
        assertFalse(synth.modulationLfo(7).enabled)
    }

    @Test fun legacyV1AndV2PatchesClearAllFormerModulationWithoutLeakage() {
        val synth = OrbitalSynth()
        val advanced = FactoryPatches.DEFAULT.copy(
            modulationRoutes = listOf(sourceRoute),
            modulationLfos = listOf(ModulationLfoConfig(
                true, LfoShape.SQUARE, 14f, 2f, true,
            )),
            modulationTempoBpm = 172f,
        )
        synth.loadPatch(advanced)
        assertTrue(synth.modulationRoute(0).enabled)
        assertEquals(LfoShape.SQUARE, synth.modulationLfo(0).shape)
        synth.loadPatch(FactoryPatches.DEFAULT)
        for (slot in 0 until synth.modulationRouteCount) {
            assertFalse("V1 must clear route $slot", synth.modulationRoute(slot).enabled)
        }
        assertEquals(ModulationLfoConfig(enabled = true), synth.modulationLfo(0))
        for (slot in 1 until synth.modulationLfoCount) {
            assertEquals(ModulationLfoConfig(), synth.modulationLfo(slot))
        }
        assertEquals(120f, synth.modulationTempoBpm, 0f)

        synth.loadPatch(advanced)
        val v2 = FactoryPatches.DEFAULT.copy(
            oscillatorSlots = listOf(OscillatorSlot(true, .5f, .5f, .2f, 0, 0f))
        )
        assertTrue(PatchCodec.encode(v2).startsWith("ORBITAL-PATCH/2\n"))
        synth.loadPatch(PatchCodec.decode(PatchCodec.encode(v2)))
        assertEquals(ModulationLfoConfig(enabled = true), synth.modulationLfo(0))
        for (slot in 0 until synth.modulationRouteCount) {
            assertFalse("V2 must clear route $slot", synth.modulationRoute(slot).enabled)
        }
    }

    @Test fun newV3PatchDoesNotInheritOlderUnmentionedRouteSlots() {
        val synth = OrbitalSynth()
        val large = FactoryPatches.DEFAULT.copy(
            modulationRoutes = List(64) { index ->
                if (index == 63) sourceRoute else ModulationRouteConfig()
            },
        )
        val small = FactoryPatches.DEFAULT.copy(
            modulationRoutes = listOf(ModulationRouteConfig(
                true, ModSource.LFO1, ModDestination.PITCH, 0.12f,
            )),
        )
        synth.loadPatch(large)
        assertTrue(synth.modulationRoute(63).enabled)
        synth.loadPatch(small)
        assertTrue(synth.modulationRoute(0).enabled)
        assertFalse(synth.modulationRoute(63).enabled)
        assertEquals(0f, synth.modulationRoute(63).depth, 0f)
    }

    @Test fun malformedV3FieldsCannotReachTheAudioEngine() {
        val v3 = PatchCodec.encode(FactoryPatches.DEFAULT.copy(
            modulationRoutes = listOf(sourceRoute),
            modulationLfos = listOf(slowLfo),
            modulationTempoBpm = 133f,
        ))
        assertTrue(v3.contains("route.0=1,TOUCH_X,MORPH,0.5,LINEAR"))
        assertTrue(v3.contains("lfo.0=1,SINE,1.0,4.0,0"))
        rejects { PatchCodec.decode(v3.replace("route.0=", "route.64=")) }
        rejects { PatchCodec.decode(v3.replace("lfo.0=", "lfo.1=")) }
        rejects { PatchCodec.decode(v3.replace("route.0=1,", "route.0=2,")) }
        rejects { PatchCodec.decode(v3.replace("TOUCH_X", "UNKNOWN_SOURCE")) }
        rejects { PatchCodec.decode(v3.replace("MORPH,0.5", "UNKNOWN_TARGET,0.5")) }
        rejects { PatchCodec.decode(v3.replace("0.5,LINEAR", "NaN,LINEAR")) }
        rejects { PatchCodec.decode(v3.replace("0.5,LINEAR", "4.1,LINEAR")) }
        rejects { PatchCodec.decode(v3.replace("1.0,4.0", "Infinity,4.0")) }
        rejects { PatchCodec.decode(v3.replace("1.0,4.0", "1.0,0.01")) }
        rejects { PatchCodec.decode(v3.replace("mod.tempo=133.0\n", "")) }
        rejects { PatchCodec.decode(v3.replace("mod.tempo=133.0", "mod.tempo=NaN")) }
        rejects { PatchCodec.decode(v3 + "route.0=0,LFO1,MORPH,0,LINEAR\n") }
        rejects { PatchCodec.decode(v3.replace("route.0=", "route.1=")) }
        rejects { PatchCodec.decode(v3.replace("ORBITAL-PATCH/3", "ORBITAL-PATCH/6")) }
    }

    @Test fun olderPatchVersionsRejectNewVersionFields() {
        val legacy = PatchCodec.encode(FactoryPatches.DEFAULT)
        val v2 = PatchCodec.encode(FactoryPatches.DEFAULT.copy(
            oscillatorSlots = listOf(OscillatorSlot()),
        ))
        rejects { PatchCodec.decode(legacy + "route.0=1,TOUCH_X,MORPH,0.5,LINEAR\n") }
        rejects { PatchCodec.decode(v2 + "lfo.0=1,SINE,1.0,4.0,0\n") }
        assertEquals(FactoryPatches.DEFAULT, PatchCodec.decode(legacy))
    }

    @Test fun immutablePatchValidationRejectsNaNAndImpossibleModulation() {
        val base = FactoryPatches.DEFAULT
        rejects { base.copy(modulationTempoBpm = Float.NaN) }
        rejects { base.copy(modulationTempoBpm = 600f) }
        rejects { base.copy(modulationRoutes = listOf(sourceRoute.copy(depth = Float.NaN))) }
        rejects { base.copy(modulationRoutes = listOf(sourceRoute.copy(depth = 4.1f))) }
        rejects { base.copy(modulationLfos = listOf(slowLfo.copy(rateHz = Float.POSITIVE_INFINITY))) }
        rejects { base.copy(modulationLfos = listOf(slowLfo.copy(rateHz = 0f))) }
        rejects { base.copy(modulationLfos = listOf(slowLfo.copy(beatsPerCycle = .01f))) }
        rejects { base.copy(modulationLfos = List(9) { slowLfo }) }
        rejects { base.copy(modulationRoutes = List(65) { sourceRoute }) }
    }
}
