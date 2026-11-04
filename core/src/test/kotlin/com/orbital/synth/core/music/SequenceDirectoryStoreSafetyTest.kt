package com.orbital.synth.core.music

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A substituted sequence bank or destination must never redirect private music data. */
class SequenceDirectoryStoreSafetyTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sequence(id: String = "user-pattern"): UserSequence = UserSequence(
        id = id,
        name = "Safe pattern",
        pattern = StepPattern(steps = listOf(
            SequencerStep(listOf(SequencerNote(60, 100))),
            SequencerStep(),
        )),
    )

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected a rejected symbolic-link boundary")
        } catch (_: IllegalArgumentException) {
            // Refusing the boundary is the required outcome.
        }
    }

    @Test fun symlinkedSequenceDestinationCannotOverwriteOrDeleteExternalData() {
        val bank = temp.newFolder("real-sequence-bank").toPath()
        val outside = temp.newFile("unrelated-user-data").toPath()
        val original = "DO NOT OVERWRITE".toByteArray(StandardCharsets.UTF_8)
        Files.write(outside, original)
        val linked = bank.resolve("user-pattern.orbital-sequence")
        Files.createSymbolicLink(linked, outside)

        val store = SequenceDirectoryStore(bank)
        rejects { store.save(sequence()) }
        assertNull(store.load("user-pattern")) // NOFOLLOW on individual sequence files.
        assertTrue(store.list().isEmpty())
        rejects { store.delete("user-pattern") }
        assertArrayEquals(original, Files.readAllBytes(outside))
        assertTrue(Files.isSymbolicLink(linked))
    }

    @Test fun symlinkedBankCannotReadWriteListOrDeleteFromAnotherDirectory() {
        val realDirectory = temp.newFolder("unrelated-directory").toPath()
        val outsideData = realDirectory.resolve("user-pattern.orbital-sequence")
        val original = SequenceCodec.encode(sequence()).toByteArray(StandardCharsets.UTF_8)
        Files.write(outsideData, original)
        val link = temp.root.toPath().resolve("sequence-bank-symlink")
        Files.createSymbolicLink(link, realDirectory)

        val store = SequenceDirectoryStore(link)
        rejects { store.save(sequence()) }
        rejects { store.load("user-pattern") }
        rejects { store.list() }
        rejects { store.delete("user-pattern") }
        assertArrayEquals(original, Files.readAllBytes(outsideData))
    }

    @Test fun realSequenceBankStillRoundTripsWithoutLeavingTemporaryFiles() {
        val bank = temp.root.toPath().resolve("new-private-sequence-bank")
        val store = SequenceDirectoryStore(bank)
        val saved = sequence()
        assertNull(store.load(saved.id))
        assertTrue(store.list().isEmpty())
        store.save(saved)
        assertEquals(saved, store.load(saved.id))
        assertEquals(listOf(saved), store.list())
        assertTrue(store.delete(saved.id))
        assertNull(store.load(saved.id))
        assertTrue(Files.list(bank).use { entries ->
            entries.noneMatch { it.fileName.toString().endsWith(".tmp") }
        })
    }
}
