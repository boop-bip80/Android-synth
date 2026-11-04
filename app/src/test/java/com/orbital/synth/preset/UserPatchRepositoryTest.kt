package com.orbital.synth.preset

import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import com.orbital.synth.core.dsp.processing.StepDirection

import com.orbital.synth.core.preset.FactoryPresets
import com.orbital.synth.core.preset.PatchCodec
import com.orbital.synth.core.preset.PatchDirectoryStore
import com.orbital.synth.core.preset.SynthPatch
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserPatchRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun repository(
        snapshot: () -> Map<String, Float> = { mapOf("WAVE" to .4f, "CUTOFF" to .75f) },
        applied: (SynthPatch) -> Unit = {},
    ): UserPatchRepository {
        val folder = temp.newFolder().toPath()
        return UserPatchRepository(
            PatchDirectoryStore(folder), snapshot, applied, Dispatchers.Unconfined
        )
    }

    private fun <T> success(result: PatchOutcome<T>): T = when (result) {
        is PatchOutcome.Success -> result.value
        is PatchOutcome.Failure -> throw AssertionError("Unexpected failure: " + result.kind)
    }

    private fun failure(result: PatchOutcome<*>, kind: PatchFailureKind) {
        val error = result as? PatchOutcome.Failure
            ?: throw AssertionError("Expected failure but saw success")
        assertEquals(kind, error.kind)
        assertTrue(error.message.isNotBlank())
    }

    @Test fun slugNormalizesAccentsPunctuationAndUnicodeFallback() {
        assertEquals("user-col-echo", UserPatchIds.baseForName("  Çöl Écho /  "))
        assertEquals("user-a-b", UserPatchIds.baseForName("A & B"))
        assertEquals("user-patch", UserPatchIds.baseForName("🌙 月光"))
        assertTrue(UserPatchIds.isUserId("user-col-echo"))
        assertFalse(UserPatchIds.isUserId("user-../private"))
        assertFalse(UserPatchIds.isUserId("aurora"))
    }

    @Test fun collidingSlugsReceiveDeterministicSafeSuffixes() {
        val huge = "VeryLongPatchTitle".repeat(10)
        val base = UserPatchIds.baseForName(huge)
        assertTrue(base.length <= 64)
        val second = UserPatchIds.uniqueForName(huge, setOf(base))
        val third = UserPatchIds.uniqueForName(huge, setOf(base, second))
        assertTrue(second.endsWith("-2"))
        assertTrue(third.endsWith("-3"))
        assertTrue(second.length <= 64 && third.length <= 64)
        assertTrue(UserPatchIds.isUserId(second))
        assertEquals(second, UserPatchIds.uniqueForName(huge, setOf(base)))
    }

    @Test fun capturesCurrentEngineValuesAndPersistsRoundtrip() = runBlocking {
        var cutoff = .65f
        val repository = repository(snapshot = {
            mapOf("WAVE" to .31f, "CUTOFF" to cutoff, "ATTACK" to .2f)
        })
        val saved = success(repository.saveCurrentAs("My Aurora", "Custom patch", setOf("user", "pad")))
        assertEquals("user-my-aurora", saved.patch.id)
        assertEquals(.65f, saved.patch.parameters["CUTOFF"]!!, 0.000001f)
        assertFalse(saved.renamedOnCollision)

        cutoff = .9f
        val loaded = success(repository.load(saved.patch.id))
        assertEquals(.65f, loaded.parameters["CUTOFF"]!!, 0.000001f)
        assertEquals(listOf(saved.patch.id), success(repository.listUser()).map { it.id })
        assertEquals(FactoryPresets.ALL.size + 1, success(repository.listAll()).size)
    }

    @Test fun savesAndRestoresEditableOscillatorLayersWithoutFlatteningThem() = runBlocking {
        val folder = temp.newFolder("oscillator-patches").toPath()
        var layer = OscillatorSlot(true, .75f, .48f, .28f, -7, 12.5f)
        val repository = UserPatchRepository(
            PatchDirectoryStore(folder),
            { mapOf("CUTOFF" to .65f) },
            {},
            Dispatchers.Unconfined,
            snapshotOscillators = { listOf(layer) },
        )
        val first = success(repository.saveCurrentAs("Orbit Layers"))
        assertEquals(listOf(layer), first.patch.oscillatorSlots)
        assertEquals(listOf(layer), PatchCodec.decode(success(
            repository.exportPatchText(first.patch.id)
        )).oscillatorSlots)

        layer = layer.copy(level = .92f, fineCents = -19f)
        val updated = success(repository.overwriteCurrent(first.patch.id))
        assertEquals(listOf(layer), updated.oscillatorSlots)
        assertEquals(listOf(layer), success(repository.load(first.patch.id)).oscillatorSlots)
    }

    @Test fun duplicateNamesDoNotOverwriteAndExplicitReplaceDoes() = runBlocking {
        var cutoff = .2f
        val repository = repository(snapshot = { mapOf("CUTOFF" to cutoff) })
        val one = success(repository.saveCurrentAs("Echo"))
        cutoff = .8f
        val two = success(repository.saveCurrentAs("Echo"))
        cutoff = .45f
        val three = success(repository.saveCurrentAs("Echo"))
        assertEquals(listOf("user-echo", "user-echo-2", "user-echo-3"),
            listOf(one.patch.id, two.patch.id, three.patch.id))
        assertFalse(one.renamedOnCollision)
        assertTrue(two.renamedOnCollision && three.renamedOnCollision)
        assertEquals(.2f, success(repository.load(one.patch.id)).parameters["CUTOFF"]!!, 0.000001f)

        cutoff = .7f
        val edited = success(repository.overwriteCurrent(one.patch.id, name = "New Echo"))
        assertEquals(one.patch.id, edited.id)
        assertEquals("New Echo", edited.name)
        assertEquals(.7f, success(repository.load(one.patch.id)).parameters["CUTOFF"]!!, 0.000001f)
        assertEquals(.8f, success(repository.load(two.patch.id)).parameters["CUTOFF"]!!, 0.000001f)
    }

    @Test fun activatesBothFactoryAndUserPatches() = runBlocking {
        val received = mutableListOf<SynthPatch>()
        val repository = repository(applied = { received += it })
        val user = success(repository.saveCurrentAs("Home"))
        assertEquals(FactoryPresets.AURORA, success(repository.activate("aurora")))
        assertEquals(user.patch, success(repository.activate(user.patch.id)))
        assertEquals(listOf("aurora", user.patch.id), received.map { it.id })
    }

    @Test fun importedPatchNeverOverwritesFactoryOrExistingUser() = runBlocking {
        val repository = repository()
        val factoryText = PatchCodec.encode(FactoryPresets.AURORA)
        val first = success(repository.importPatchText(factoryText))
        val second = success(repository.importPatchText(factoryText))
        assertEquals("user-aurora", first.patch.id)
        assertEquals("user-aurora-2", second.patch.id)
        assertTrue(second.renamedOnCollision)
        assertEquals(FactoryPresets.AURORA, success(repository.load("aurora")))
        assertEquals(first.patch, PatchCodec.decode(success(repository.exportPatchText(first.patch.id))))
        assertEquals(FactoryPresets.AURORA, PatchCodec.decode(success(repository.exportPatchText("aurora"))))
    }

    @Test fun invalidNamesAndMalformedImportReturnClearTypedErrors() = runBlocking {
        val repository = repository()
        failure(repository.saveCurrentAs("    "), PatchFailureKind.INVALID_NAME)
        failure(repository.importPatchText("NOT A PATCH"), PatchFailureKind.INVALID_FORMAT)
        failure(repository.importPatchText("ORBITAL-PATCH/99\n"), PatchFailureKind.INVALID_FORMAT)
        failure(repository.saveCurrentAs("Bad", tags = setOf("x".repeat(50))),
            PatchFailureKind.INVALID_DATA)
    }

    @Test fun concurrentSaveAsRequestsGetUniqueIdsAndPreserveEveryPatch() = runBlocking {
        val repository = repository(snapshot = { mapOf("CUTOFF" to .57f) })
        val saved = coroutineScope {
            (1..32).map {
                async(Dispatchers.Default) {
                    success(repository.saveCurrentAs("Poly Pad"))
                }
            }.awaitAll()
        }
        val ids = saved.map { it.patch.id }
        assertEquals("No two simultaneous saves may overwrite the same file", 32, ids.toSet().size)
        assertEquals(32, success(repository.listUser()).size)
        assertTrue(ids.contains("user-poly-pad"))
        assertTrue(ids.contains("user-poly-pad-32"))
        for (id in ids) {
            val patch = success(repository.load(id))
            assertEquals("Poly Pad", patch.name)
            assertEquals(.57f, patch.parameters["CUTOFF"]!!, .000001f)
        }
    }

    @Test fun saveNewRejectsExistingFileWithoutOverwritingAnyBytes() {
        val folder = temp.newFolder("exclusive-new-patch").toPath()
        val store = PatchDirectoryStore(folder)
        val original = FactoryPresets.AURORA.copy(id = "user-locked")
        assertTrue(store.saveNew(original))
        val path = folder.resolve("user-locked.orbital")
        val initialBytes = Files.readAllBytes(path)

        val conflicting = original.copy(name = "Would Overwrite")
        assertFalse(store.saveNew(conflicting))
        assertArrayEquals(initialBytes, Files.readAllBytes(path))
        assertEquals(original, store.load(original.id))
    }

    @Test fun twoRepositoryInstancesSaveAndImportWithoutLosingRacingPatches() = runBlocking {
        val folder = temp.newFolder("parallel-repositories").toPath()
        val store = PatchDirectoryStore(folder)
        val saver = UserPatchRepository(
            PatchDirectoryStore(folder), { mapOf("CUTOFF" to .23f) }, {},
            Dispatchers.Unconfined,
        )
        val importer = UserPatchRepository(
            PatchDirectoryStore(folder), { mapOf("CUTOFF" to .87f) }, {},
            Dispatchers.Unconfined,
        )
        val importText = PatchCodec.encode(FactoryPresets.AURORA.copy(name = "Race Pad"))
        // Each instance owns a different mutex; only exclusive disk publication
        // can prevent the two from clobbering identical ID candidates.
        val saved = coroutineScope {
            (1..64).map { index ->
                async(Dispatchers.Default) {
                    success(
                        if (index % 2 == 0) saver.saveCurrentAs("Race Pad")
                        else importer.importPatchText(importText)
                    )
                }
            }.awaitAll()
        }
        assertEquals(64, saved.map { it.patch.id }.toSet().size)
        assertEquals(64, store.storedIds().size)
        assertEquals(64, store.list().size)
        val savedById = saved.associateBy { it.patch.id }
        for ((id, result) in savedById) {
            assertEquals(result.patch, store.load(id))
        }
    }

    @Test fun oversizedImportReturnsTypedFormatErrorWithoutCreatingFiles() = runBlocking {
        val repository = repository()
        failure(repository.importPatchText("X".repeat(65_537)), PatchFailureKind.INVALID_FORMAT)
        assertTrue(success(repository.listUser()).isEmpty())
    }

    @Test fun rejectsTraversalAndFactoryDeletion() = runBlocking {
        val repository = repository()
        failure(repository.deleteUser("aurora"), PatchFailureKind.INVALID_ID)
        failure(repository.deleteUser("../user-echo"), PatchFailureKind.INVALID_ID)
        failure(repository.load("user-../etc"), PatchFailureKind.INVALID_ID)
        failure(repository.exportPatchText("../secret"), PatchFailureKind.INVALID_ID)
        failure(repository.overwriteCurrent("user-does-not-exist"), PatchFailureKind.NOT_FOUND)

        val saved = success(repository.saveCurrentAs("Delete Me"))
        assertTrue(success(repository.deleteUser(saved.patch.id)))
        failure(repository.deleteUser(saved.patch.id), PatchFailureKind.NOT_FOUND)
        failure(repository.load(saved.patch.id), PatchFailureKind.NOT_FOUND)
    }

    @Test fun corruptPatchReservesItsFilenameWithoutBlockingNewSavesOrRestart() = runBlocking {
        val folder = temp.newFolder("corrupt-id-reservation").toPath()
        val store = PatchDirectoryStore(folder)
        val original = "ORBITAL-PATCH/99\nkeep this exact user data".toByteArray(StandardCharsets.UTF_8)
        val damagedPath = folder.resolve("user-echo.orbital")
        Files.write(damagedPath, original)
        val repository = UserPatchRepository(
            store, { mapOf("CUTOFF" to .4f) }, {}, Dispatchers.Unconfined,
        )
        assertEquals(setOf("user-echo"), store.storedIds())
        failure(repository.listUser(), PatchFailureKind.INVALID_FORMAT)

        val first = success(repository.saveCurrentAs("Echo"))
        assertEquals("user-echo-2", first.patch.id)
        assertTrue(first.renamedOnCollision)
        assertArrayEquals(original, Files.readAllBytes(damagedPath))
        assertEquals(first.patch, success(repository.load(first.patch.id)))

        // New process/repository must still reserve both IDs, including corrupt data.
        val restarted = UserPatchRepository(
            PatchDirectoryStore(folder), { mapOf("CUTOFF" to .8f) }, {},
            Dispatchers.Unconfined,
        )
        val second = success(restarted.saveCurrentAs("Echo"))
        assertEquals("user-echo-3", second.patch.id)
        assertEquals(first.patch, success(restarted.load(first.patch.id)))
        assertArrayEquals(original, Files.readAllBytes(damagedPath))
        failure(restarted.load("user-echo"), PatchFailureKind.INVALID_FORMAT)
        failure(restarted.listUser(), PatchFailureKind.INVALID_FORMAT)
    }

    @Test fun importReservesFutureVersionAndExistingIdsWithoutChangingTheirBytes() = runBlocking {
        val folder = temp.newFolder("future-version-reservation").toPath()
        val store = PatchDirectoryStore(folder)
        val futurePath = folder.resolve("user-aurora.orbital")
        val futureBytes = "ORBITAL-PATCH/999\nreserved future patch".toByteArray(StandardCharsets.UTF_8)
        Files.write(futurePath, futureBytes)
        val existing = FactoryPresets.AURORA.copy(id = "user-aurora-2")
        store.save(existing)
        val existingBytes = Files.readAllBytes(folder.resolve("user-aurora-2.orbital"))
        val repository = UserPatchRepository(
            store, { mapOf("CUTOFF" to .4f) }, {}, Dispatchers.Unconfined,
        )
        assertEquals(setOf("user-aurora", "user-aurora-2"), store.storedIds())
        failure(repository.listAll(), PatchFailureKind.INVALID_FORMAT)

        val imported = success(repository.importPatchText(PatchCodec.encode(FactoryPresets.AURORA)))
        assertEquals("user-aurora-3", imported.patch.id)
        assertTrue(imported.renamedOnCollision)
        assertEquals(FactoryPresets.AURORA, success(repository.load("aurora")))
        assertEquals(existing, success(repository.load(existing.id)))
        assertEquals(imported.patch, success(repository.load(imported.patch.id)))
        assertArrayEquals(existingBytes, Files.readAllBytes(folder.resolve("user-aurora-2.orbital")))
        assertArrayEquals(futureBytes, Files.readAllBytes(futurePath))
        failure(repository.listUser(), PatchFailureKind.INVALID_FORMAT)
    }

    @Test fun corruptUserPatchIsReportedRatherThanSilentlyHidden() = runBlocking {
        val folder = temp.newFolder("corrupt-bank").toPath()
        val repository = UserPatchRepository(
            PatchDirectoryStore(folder), { mapOf("MORPH" to .3f) }, {},
            Dispatchers.Unconfined,
        )
        Files.write(folder.resolve("user-broken.orbital"),
            "ORBITAL-PATCH/99\n".toByteArray(StandardCharsets.UTF_8))
        failure(repository.listUser(), PatchFailureKind.INVALID_FORMAT)
        failure(repository.load("user-broken"), PatchFailureKind.INVALID_FORMAT)
    }
    @Test fun v3UserPatchSaveOverwriteAndImportPreserveModulationState() = runBlocking {
        val folder = temp.newFolder("modulation-patches").toPath()
        var route = ModulationRouteConfig(
            true, ModSource.TOUCH_PRESSURE, ModDestination.MORPH,
            0.7f, ModCurve.SOFT,
        )
        var lfo = ModulationLfoConfig(
            true, LfoShape.SAMPLE_HOLD, 3.5f, 4f, true,
        )
        var tempo = 136f
        val repository = UserPatchRepository(
            PatchDirectoryStore(folder),
            { mapOf("CUTOFF" to .65f) },
            {},
            Dispatchers.Unconfined,
            snapshotModulationRoutes = { listOf(route) },
            snapshotModulationLfos = { listOf(lfo) },
            snapshotModulationTempo = { tempo },
        )
        val first = success(repository.saveCurrentAs("Satellite Sweep"))
        assertEquals(listOf(route), first.patch.modulationRoutes)
        assertEquals(listOf(lfo), first.patch.modulationLfos)
        assertEquals(136f, first.patch.modulationTempoBpm, 0f)
        val exported = success(repository.exportPatchText(first.patch.id))
        assertTrue(exported.startsWith("ORBITAL-PATCH/3\n"))
        assertEquals(first.patch, PatchCodec.decode(exported))

        // A subsequent edit must not retroactively change earlier saved snapshots.
        route = route.copy(destination = ModDestination.PITCH, depth = -.35f)
        lfo = lfo.copy(shape = LfoShape.TRIANGLE, rateHz = 6f)
        tempo = 98f
        val replaced = success(repository.overwriteCurrent(first.patch.id))
        assertEquals(listOf(route), replaced.modulationRoutes)
        assertEquals(listOf(lfo), replaced.modulationLfos)
        assertEquals(98f, replaced.modulationTempoBpm, 0f)
        assertEquals(replaced, success(repository.load(first.patch.id)))
        val second = success(repository.importPatchText(exported))
        assertEquals(first.patch.modulationRoutes, second.patch.modulationRoutes)
        assertEquals(first.patch.modulationLfos, second.patch.modulationLfos)
        assertEquals(first.patch.modulationTempoBpm, second.patch.modulationTempoBpm, 0f)
    }

    @Test fun v5UserPatchSaveOverwriteImportSnapshotActualSixteenSteps() = runBlocking {
        val folder = temp.newFolder("step-patches").toPath()
        val mutablePads = MutableList(16) { if (it % 2 == 0) 1f else -1f }
        var steps = ModulationStepConfig(
            enabled = true, values = mutablePads,
            length = 12, beatsPerStep = .25f, glide = .3f,
            direction = StepDirection.PING_PONG,
        )
        val repository = UserPatchRepository(
            PatchDirectoryStore(folder), { mapOf("CUTOFF" to .65f) }, {},
            Dispatchers.Unconfined,
            snapshotModulationSteps = { steps },
        )
        val first = success(repository.saveCurrentAs("Pulse Steps"))
        val export = success(repository.exportPatchText(first.patch.id))
        assertTrue(export.startsWith("ORBITAL-PATCH/5\n"))
        assertEquals(steps, PatchCodec.decode(export).modulationSteps)

        // The user's editable list must not mutate a previously saved patch.
        mutablePads[0] = -1f
        assertEquals(1f, first.patch.modulationSteps!!.values[0], 0f)
        steps = ModulationStepConfig(
            enabled = true, values = List(16) { 0.5f }, length = 16,
            beatsPerStep = .5f, glide = 0f,
        )
        val replaced = success(repository.overwriteCurrent(first.patch.id))
        assertEquals(steps, replaced.modulationSteps)
        assertEquals(replaced, success(repository.load(first.patch.id)))
        val imported = success(repository.importPatchText(export))
        assertEquals(first.patch.modulationSteps, imported.patch.modulationSteps)
    }

}
