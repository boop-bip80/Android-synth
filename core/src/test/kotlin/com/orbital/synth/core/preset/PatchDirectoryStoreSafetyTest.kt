package com.orbital.synth.core.preset

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** App-private patch persistence must not follow substituted bank/file symlinks. */
class PatchDirectoryStoreSafetyTest {
    @get:Rule val temp = TemporaryFolder()

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected a rejected symbolic-link boundary")
        } catch (_: IllegalArgumentException) {
            // Never redirect bank reads/writes/deletes outside the configured directory.
        }
    }

    @Test fun symlinkedDestinationCannotReplaceOrDeleteExternalPatchData() {
        val bank = temp.newFolder("real-patch-bank").toPath()
        val outside = temp.newFile("unrelated-user-data").toPath()
        val original = "DO NOT OVERWRITE".toByteArray(StandardCharsets.UTF_8)
        Files.write(outside, original)
        val patch = FactoryPatches.DEFAULT.copy(id = "user-boundary")
        val linkedTarget = bank.resolve("user-boundary.orbital")
        Files.createSymbolicLink(linkedTarget, outside)

        val store = PatchDirectoryStore(bank)
        rejects { store.save(patch) }
        assertNull(store.load("user-boundary")) // NOFOLLOW on the file itself
        rejects { store.delete("user-boundary") }
        assertArrayEquals(original, Files.readAllBytes(outside))
        assertTrue(Files.isSymbolicLink(linkedTarget))
    }

    @Test fun symlinkedBankCannotReadWriteListOrDeleteOtherDirectory() {
        val realDirectory = temp.newFolder("unrelated-directory").toPath()
        val outsideData = realDirectory.resolve("user-boundary.orbital")
        val original = PatchCodec.encode(FactoryPatches.DEFAULT.copy(id = "user-boundary"))
            .toByteArray(StandardCharsets.UTF_8)
        Files.write(outsideData, original)
        val link = temp.root.toPath().resolve("patch-bank-symlink")
        Files.createSymbolicLink(link, realDirectory)

        val store = PatchDirectoryStore(link)
        rejects { store.save(FactoryPatches.DEFAULT.copy(id = "user-boundary")) }
        rejects { store.load("user-boundary") }
        rejects { store.list() }
        rejects { store.delete("user-boundary") }
        assertArrayEquals(original, Files.readAllBytes(outsideData))
    }

    @Test fun ordinaryPatchBankStillRoundTripsAndCleansTemps() {
        val bank = temp.root.toPath().resolve("new-private-patch-bank")
        val store = PatchDirectoryStore(bank)
        val patch = FactoryPatches.DEFAULT.copy(id = "user-safe")
        assertNull(store.load(patch.id))
        assertTrue(store.list().isEmpty())
        store.save(patch)
        assertEquals(patch, store.load(patch.id))
        assertEquals(listOf(patch), store.list())
        assertTrue(store.delete(patch.id))
        assertNull(store.load(patch.id))
        assertTrue(Files.list(bank).use { entries ->
            entries.noneMatch { it.fileName.toString().endsWith(".tmp") }
        })
    }
}
