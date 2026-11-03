package com.orbital.synth.core.preset

import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

class PatchFxV4Test {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val fxLayers = listOf(
        FxModuleConfig(FxModuleType.SATURATION, true, .88f, .79f, .38f, .12f),
        // A disabled module must retain all of its editable settings and position.
        FxModuleConfig(FxModuleType.DELAY, false, .67f, .55f, .25f, .9f),
        FxModuleConfig(FxModuleType.CHORUS, true, .41f, .61f, .75f, 1f),
        FxModuleConfig(FxModuleType.TREMOLO, true, .54f, .48f, .8f, .5f),
        FxModuleConfig(FxModuleType.DELAY, true, .32f, .43f, .1f, 1f),
        FxModuleConfig(FxModuleType.SATURATION, false, .19f, .77f, .7f, 0f),
    )
    private val v3Route = ModulationRouteConfig(
        true, ModSource.TOUCH_PRESSURE, ModDestination.PITCH, .36f, ModCurve.SOFT,
    )
    private val v3Lfo = ModulationLfoConfig(
        true, LfoShape.TRIANGLE, 3f, 4f, true,
    )

    private fun rejects(action: () -> Unit) {
        try {
            action()
            fail("Expected a corrupt FX patch to be rejected")
        } catch (_: IllegalArgumentException) {
            // Decoder and SynthPatch constructor explicitly reject malformed state.
        }
    }

    @Test fun v4RoundTripsSixOrderedEffectsAndAllOlderSoundDesignFields() {
        val patch = FactoryPatches.DEFAULT.copy(
            id = "fx-preset-v4",
            oscillatorSlots = listOf(OscillatorSlot(true, .7f, .3f, .25f, 7, -10f)),
            modulationRoutes = listOf(v3Route),
            modulationLfos = listOf(v3Lfo),
            modulationTempoBpm = 128f,
            fxSlots = fxLayers,
        )
        val encoded = PatchCodec.encode(patch)
        assertEquals(6, PatchCodec.CURRENT_VERSION)
        assertTrue(encoded.startsWith("ORBITAL-PATCH/4\n"))
        assertTrue(encoded.contains("fx.0=1,SATURATION,"))
        assertTrue(encoded.contains("fx.1=0,DELAY,"))
        assertTrue(encoded.contains("fx.5=0,SATURATION,"))
        assertTrue(encoded.contains("mod.tempo=128.0\n"))
        assertEquals(6, encoded.lines().count { it.startsWith("fx.") })
        assertEquals(patch, PatchCodec.decode(encoded))
        assertEquals(encoded, PatchCodec.encode(PatchCodec.decode(encoded)))
    }

    @Test fun importingV1V2AndV3PreservesPreviousWireVersionsWithoutInventingFx() {
        val v1 = FactoryPatches.DEFAULT
        val v2 = v1.copy(
            oscillatorSlots = listOf(OscillatorSlot(true, .5f, .3f, .2f, 0, 0f)),
        )
        val v3 = v2.copy(modulationRoutes = listOf(v3Route),
            modulationLfos = listOf(v3Lfo), modulationTempoBpm = 133f)
        for ((version, patch) in listOf(1 to v1, 2 to v2, 3 to v3)) {
            val encoded = PatchCodec.encode(patch)
            assertTrue(encoded.startsWith("ORBITAL-PATCH/${version}\n"))
            val decoded = PatchCodec.decode(encoded)
            assertEquals(patch, decoded)
            assertTrue(decoded.fxSlots.isEmpty())
            assertEquals(encoded, PatchCodec.encode(decoded))
        }
    }

    @Test fun loadingNewAndOlderPatchesAlwaysReplacesAllSixFxConfigurations() {
        val synth = OrbitalSynth()
        synth.loadPatch(FactoryPatches.DEFAULT.copy(fxSlots = fxLayers))
        for (index in fxLayers.indices) assertEquals(fxLayers[index], synth.fxSlot(index))

        val oneFx = FxModuleConfig(FxModuleType.CHORUS, true, .58f, .7f, .3f, 1f)
        synth.loadPatch(FactoryPatches.DEFAULT.copy(fxSlots = listOf(oneFx)))
        assertEquals(oneFx, synth.fxSlot(0))
        for (index in 1 until synth.fxSlotCount) {
            assertEquals("Loading a short V4 patch must not inherit old FX in slot $index",
                FxModuleConfig(), synth.fxSlot(index))
        }

        val olderV3 = FactoryPatches.DEFAULT.copy(modulationRoutes = listOf(v3Route))
        synth.loadPatch(PatchCodec.decode(PatchCodec.encode(olderV3)))
        for (index in 0 until synth.fxSlotCount) {
            assertEquals("V3 must clear any previously configured V4 FX rack",
                FxModuleConfig(), synth.fxSlot(index))
        }
    }

    @Test fun diskSaveReopenAndLegacyRecallPreserveV4ThenClearToDryDefaults() {
        val folder = temporaryFolder.newFolder("v4-fx-bank").toPath()
        val writer = PatchDirectoryStore(folder)
        val programmed = FactoryPatches.DEFAULT.copy(
            id = "persisted-fx-stack", fxSlots = fxLayers,
            modulationRoutes = listOf(v3Route), modulationTempoBpm = 109f,
        )
        writer.save(programmed)
        val reopened = PatchDirectoryStore(folder)
        val readBack = reopened.load(programmed.id)!!
        assertEquals(programmed, readBack)
        assertTrue(PatchCodec.encode(readBack).startsWith("ORBITAL-PATCH/4\n"))
        val synth = OrbitalSynth()
        synth.loadPatch(readBack)
        for (i in fxLayers.indices) assertEquals(fxLayers[i], synth.fxSlot(i))
        assertEquals(v3Route, synth.modulationRoute(0))
        assertEquals(109f, synth.modulationTempoBpm, 0f)

        writer.save(FactoryPatches.DEFAULT.copy(id = "persisted-v1"))
        synth.loadPatch(PatchDirectoryStore(folder).load("persisted-v1")!!)
        for (i in 0 until synth.fxSlotCount) assertEquals(FxModuleConfig(), synth.fxSlot(i))
        assertFalse(synth.modulationRoute(0).enabled)
    }

    @Test fun v4FxRecallReachesRealStereoDspAndMatchesDirectEditorConfiguration() {
        val encoded = PatchCodec.encode(FactoryPatches.DEFAULT.copy(
            fxSlots = listOf(FxModuleConfig(
                FxModuleType.SATURATION, enabled = true, wet = 1f, depth = .85f,
            )),
        ))
        val decoded = PatchCodec.decode(encoded)
        val recalled = OrbitalSynth()
        val manual = OrbitalSynth()
        recalled.loadPatch(decoded)
        manual.loadPatch(FactoryPatches.DEFAULT)
        manual.setFxSlot(0, decoded.fxSlots[0])
        recalled.noteOn(69)
        manual.noteOn(69)
        val fromPatch = FloatArray(4_096 * 2)
        val fromEditor = FloatArray(fromPatch.size)
        recalled.renderStereo(fromPatch, 4_096)
        manual.renderStereo(fromEditor, 4_096)
        assertArrayEquals("V4 FX must restore the same live DSP signal as direct editor controls",
            fromEditor, fromPatch, 0f)
        var nonzero = 0.0
        for (value in fromPatch) {
            assertTrue(value.isFinite())
            nonzero += abs(value.toDouble())
        }
        assertTrue(nonzero > 0.1)
    }

    @Test fun malformedFxKeysGapsDuplicateSlotsAndVersionsAreRejected() {
        val v4 = PatchCodec.encode(FactoryPatches.DEFAULT.copy(
            fxSlots = fxLayers.take(2),
        ))
        val originalFirst = "fx.0=1,SATURATION,0.88,0.79,0.38,0.12"
        assertTrue(v4.contains(originalFirst))
        rejects { PatchCodec.decode(v4.replace("fx.0=", "fx.6=")) }
        rejects { PatchCodec.decode(v4.replace("fx.0=", "fx.-1=")) }
        rejects { PatchCodec.decode(v4.replace("fx.0=", "fx.00=")) }
        rejects { PatchCodec.decode(v4.replace("fx.0=", "fx.2=")) }
        rejects { PatchCodec.decode(v4.replace("fx.1=", "fx.3=")) }
        rejects { PatchCodec.decode(v4 + originalFirst + "\n") }
        rejects { PatchCodec.decode(v4.replace("ORBITAL-PATCH/4", "ORBITAL-PATCH/3")) }
        rejects { PatchCodec.decode(v4.replace("ORBITAL-PATCH/4", "ORBITAL-PATCH/6")) }
        rejects { PatchCodec.decode(v4.replace("mod.tempo=120.0\n", "")) }
        rejects { PatchCodec.decode(v4.replace(Regex("fx\\.[0-5]=[^\\n]*\\n"), "")) }
    }

    @Test fun malformedFxFlagsTypesFieldsAndUnsafeNumbersAreRejected() {
        val v4 = PatchCodec.encode(FactoryPatches.DEFAULT.copy(
            fxSlots = listOf(FxModuleConfig(FxModuleType.DELAY, true, .6f, .5f, .25f, .9f)),
        ))
        val line = "fx.0=1,DELAY,0.6,0.5,0.25,0.9"
        assertTrue(v4.contains(line))
        for (replacement in listOf(
            "fx.0=2,DELAY,0.6,0.5,0.25,0.9",
            "fx.0=1,NOT_AN_EFFECT,0.6,0.5,0.25,0.9",
            "fx.0=1,DELAY,NaN,0.5,0.25,0.9",
            "fx.0=1,DELAY,Infinity,0.5,0.25,0.9",
            "fx.0=1,DELAY,-0.1,0.5,0.25,0.9",
            "fx.0=1,DELAY,1.1,0.5,0.25,0.9",
            "fx.0=1,DELAY,0.6,NaN,0.25,0.9",
            "fx.0=1,DELAY,0.6,0.5,-.01,0.9",
            "fx.0=1,DELAY,0.6,0.5,0.25,Infinity",
            "fx.0=1,DELAY,0.6,0.5,0.25",
            "fx.0=1,DELAY,0.6,0.5,0.25,0.9,EXTRA",
        )) {
            rejects { PatchCodec.decode(v4.replace(line, replacement)) }
        }
    }

    @Test fun immutableFxSnapshotValidationRejectsInvalidOrOverCapacityValues() {
        val original = FactoryPatches.DEFAULT
        rejects { original.copy(fxSlots = List(7) { FxModuleConfig() }) }
        rejects { original.copy(fxSlots = listOf(FxModuleConfig(wet = Float.NaN))) }
        rejects { original.copy(fxSlots = listOf(FxModuleConfig(wet = -0.01f))) }
        rejects { original.copy(fxSlots = listOf(FxModuleConfig(wet = 1.01f))) }
        rejects { original.copy(fxSlots = listOf(FxModuleConfig(depth = Float.POSITIVE_INFINITY))) }
        rejects { original.copy(fxSlots = listOf(FxModuleConfig(rate = Float.NEGATIVE_INFINITY))) }
        rejects { original.copy(fxSlots = listOf(FxModuleConfig(stereo = 2f))) }
    }
    @Test fun patchRecallDiscardsOldDelayHistoryEvenIfSameEffectReenabledWithoutRendering() {
        val synth = OrbitalSynth(sampleRate = 8_000)
        val delay = FxModuleConfig(
            FxModuleType.DELAY, enabled = true,
            wet = 1f, depth = 1f, rate = 0f, stereo = 0f,
        )
        synth.setParameter(com.orbital.synth.core.ParameterId.ATTACK, 0f)
        synth.setParameter(com.orbital.synth.core.ParameterId.RELEASE, 0f)
        synth.setParameter(com.orbital.synth.core.ParameterId.FX_AMOUNT, 0f)
        synth.setFxSlot(0, delay)
        synth.noteOn(69)
        synth.renderStereo(FloatArray(3_200 * 2), 3_200)
        synth.allNotesOff()
        val oldEchoes = FloatArray(1_600 * 2)
        synth.renderStereo(oldEchoes, 1_600)
        assertEquals(0, synth.activeVoiceCount)
        assertTrue("Regression setup must contain real delay feedback tails",
            oldEchoes.sumOf { abs(it.toDouble()) } > 0.1)

        // The next patch is legacy and therefore has no FX settings.
        // Re-enable the SAME delay preset before any render callback runs.
        // Without a queued DSP history reset, the old delay line still holds
        // notes even though UI slot snapshots were temporarily cleared.
        synth.loadPatch(FactoryPatches.DEFAULT)
        synth.setFxSlot(0, delay)
        val nextSound = FloatArray(2_800 * 2)
        synth.renderStereo(nextSound, 2_800)
        assertArrayEquals(
            "A patch switch must not resurrect the preceding patch's echoes",
            FloatArray(nextSound.size), nextSound, 0f,
        )
    }

    @Test fun queuedOldFxMovesNeverReorderNewlyRecalledPatchOrDisableFutureMoves() {
        val synth = OrbitalSynth()
        val former0 = FxModuleConfig(FxModuleType.TREMOLO, true, .5f)
        val former1 = FxModuleConfig(FxModuleType.DELAY, true, .8f)
        val recalled0 = FxModuleConfig(FxModuleType.CHORUS, true, .63f, .5f)
        val recalled1 = FxModuleConfig(FxModuleType.SATURATION, false, .44f, .9f)
        synth.setFxSlot(0, former0)
        synth.setFxSlot(1, former1)
        val canceledTicket = synth.requestFxSlotMove(0, 1) // queued, not yet drained
        assertTrue(canceledTicket > 0L)
        synth.loadPatch(FactoryPatches.DEFAULT.copy(
            fxSlots = listOf(recalled0, recalled1),
        ))
        assertTrue("Patch recall must explicitly cancel old move tickets",
            synth.lastCanceledFxMoveTicket >= canceledTicket)
        synth.renderStereo(FloatArray(0), 0)
        assertTrue(synth.lastAppliedFxMoveTicket < canceledTicket)
        assertEquals("The new patch must win over an obsolete queued move",
            recalled0, synth.fxSlot(0))
        assertEquals(recalled1, synth.fxSlot(1))
        for (index in 2 until synth.fxSlotCount) {
            assertEquals(FxModuleConfig(), synth.fxSlot(index))
        }

        val nextTicket = synth.requestFxSlotMove(0, 1)
        assertTrue(nextTicket > canceledTicket)
        synth.renderStereo(FloatArray(0), 0)
        assertEquals(nextTicket, synth.lastAppliedFxMoveTicket)
        assertEquals(recalled1, synth.fxSlot(0))
        assertEquals(recalled0, synth.fxSlot(1))
    }

    @Test fun moveTicketsAckEvenDuplicateProcessorsAndOrderRapidPostMoveEdits() {
        val synth = OrbitalSynth()
        val matching = FxModuleConfig(
            FxModuleType.SATURATION, enabled = true, wet = .55f, depth = .77f,
        )
        synth.setFxSlot(0, matching)
        synth.setFxSlot(1, matching)
        assertEquals(0L, synth.requestFxSlotMove(0, 0))
        val first = synth.requestFxSlotMove(0, 1)
        val second = synth.requestFxSlotMove(1, 2)
        assertTrue("Worker move tickets must be positive and ordered",
            first > 0L && second > first)
        assertEquals("Queued moves must not pretend to be completed", 0L,
            synth.lastAppliedFxMoveTicket)
        synth.renderStereo(FloatArray(0), 0)
        assertEquals("Ticket acknowledges completion despite duplicate-identical slots",
            second, synth.lastAppliedFxMoveTicket)
        assertEquals(0L, synth.lastCanceledFxMoveTicket)
        assertEquals(matching, synth.fxSlot(0))
        assertEquals(FxModuleConfig(), synth.fxSlot(1))
        assertEquals(matching, synth.fxSlot(2))

        // A touch UI waits for the acknowledgment before retargeting knobs.
        val edited = matching.copy(wet = .91f)
        synth.setFxSlot(2, edited)
        synth.renderStereo(FloatArray(0), 0)
        assertEquals(edited, synth.fxSlot(2))
        assertEquals(matching, synth.fxSlot(0))
    }

}
