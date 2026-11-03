package com.orbital.synth.preset

import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.preset.FactoryPatches
import com.orbital.synth.core.preset.PatchCodec
import com.orbital.synth.core.preset.PatchDirectoryStore
import com.orbital.synth.core.preset.SynthPatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserFxPatchPersistenceTest {
    @get:Rule val temp = TemporaryFolder()

    private fun <T> success(outcome: PatchOutcome<T>): T = when (outcome) {
        is PatchOutcome.Success -> outcome.value
        is PatchOutcome.Failure -> throw AssertionError("Unexpected failure: " + outcome.kind)
    }

    private val original = listOf(
        FxModuleConfig(FxModuleType.DELAY, true, .75f, .58f, .24f, 1f),
        FxModuleConfig(FxModuleType.SATURATION, false, .4f, .85f, .2f, .1f),
        FxModuleConfig(FxModuleType.CHORUS, true, .35f, .6f, .52f, .93f),
    )

    @Test fun saveOverwriteExportImportAndReopenRetainAllFxEditorSettings() = runBlocking {
        val folder = temp.newFolder("v4-user").toPath()
        var liveFx = original
        val repository = UserPatchRepository(
            PatchDirectoryStore(folder),
            { mapOf("CUTOFF" to .6f, "FX_AMOUNT" to 0f) },
            {},
            Dispatchers.Unconfined,
            snapshotFxSlots = { liveFx },
        )
        val first = success(repository.saveCurrentAs("Orbit Echo", "FX editor"))
        assertEquals(original, first.patch.fxSlots)
        val exportedFirst = success(repository.exportPatchText(first.patch.id))
        assertTrue(exportedFirst.startsWith("ORBITAL-PATCH/4\n"))
        assertEquals(original, PatchCodec.decode(exportedFirst).fxSlots)

        liveFx = original.mapIndexed { i, config ->
            if (i == 0) config.copy(type = FxModuleType.TREMOLO,
                wet = .3f, enabled = false)
            else config.copy(stereo = .2f)
        }
        val overwritten = success(repository.overwriteCurrent(first.patch.id))
        assertEquals(liveFx, overwritten.fxSlots)
        assertEquals(liveFx, success(repository.load(first.patch.id)).fxSlots)
        assertEquals(liveFx, PatchDirectoryStore(folder).load(first.patch.id)!!.fxSlots)

        val imported = success(repository.importPatchText(exportedFirst))
        assertEquals(original, imported.patch.fxSlots)
        assertEquals(PatchCodec.encode(imported.patch),
            success(repository.exportPatchText(imported.patch.id)))
        assertEquals(liveFx, success(repository.load(first.patch.id)).fxSlots)
    }

    @Test fun savedSnapshotsDoNotAliasLaterFxMutationsOrLoseDisabledTypes() = runBlocking {
        val folder = temp.newFolder("v4-snapshots").toPath()
        val editable = original.toMutableList()
        val repository = UserPatchRepository(
            PatchDirectoryStore(folder),
            { mapOf("WAVE" to .5f) },
            {},
            Dispatchers.Unconfined,
            snapshotFxSlots = { editable },
        )
        val saved = success(repository.saveCurrentAs("Frozen Stack"))
        val captured = saved.patch.fxSlots
        editable[1] = editable[1].copy(type = FxModuleType.TREMOLO, enabled = true)
        editable.removeAt(0)
        assertEquals(original, captured)
        assertEquals(original, success(repository.load(saved.patch.id)).fxSlots)
        assertEquals(FxModuleType.SATURATION, captured[1].type)
        assertFalse("Disabled FX metadata must survive save", captured[1].enabled)
    }

    @Test fun invalidUserFxSnapshotIsReportedAndNeverWrittenToDisk() = runBlocking {
        val folder = temp.newFolder("v4-invalid").toPath()
        val repository = UserPatchRepository(
            PatchDirectoryStore(folder),
            { mapOf("CUTOFF" to .5f) },
            {},
            Dispatchers.Unconfined,
            snapshotFxSlots = { listOf(FxModuleConfig(wet = Float.NaN)) },
        )
        val error = repository.saveCurrentAs("Broken FX")
        val failed = error as? PatchOutcome.Failure
            ?: throw AssertionError("Invalid FX snapshot must fail")
        assertEquals(PatchFailureKind.INVALID_DATA, failed.kind)
        assertTrue(success(repository.listUser()).isEmpty())
    }

    @Test fun activatingImportedV4PatchDeliversTheEntireFxChainToTheApp() = runBlocking {
        val folder = temp.newFolder("v4-activation").toPath()
        val recalled = mutableListOf<SynthPatch>()
        val repository = UserPatchRepository(
            PatchDirectoryStore(folder),
            { mapOf("CUTOFF" to .7f) },
            { recalled += it },
            Dispatchers.Unconfined,
        )
        val encoded = PatchCodec.encode(FactoryPatches.DEFAULT.copy(
            fxSlots = original,
        ))
        val imported = success(repository.importPatchText(encoded))
        success(repository.activate(imported.patch.id))
        assertEquals(1, recalled.size)
        assertEquals(original, recalled.single().fxSlots)
        assertEquals(imported.patch, recalled.single())
        assertEquals(FactoryPatches.DEFAULT, success(repository.activate("aurora")))
        assertTrue(recalled.last().fxSlots.isEmpty())
    }
}
