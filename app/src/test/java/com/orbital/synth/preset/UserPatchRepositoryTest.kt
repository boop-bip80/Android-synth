package com.orbital.synth.preset

import com.orbital.synth.core.OscillatorSlot

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
}
