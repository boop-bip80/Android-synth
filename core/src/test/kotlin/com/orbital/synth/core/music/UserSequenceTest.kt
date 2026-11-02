package com.orbital.synth.core.music

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserSequenceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun sample(id: String = "comet-triplet"): UserSequence = UserSequence(
        id = id,
        name = "Comet ♫ / + &",
        pattern = StepPattern(
            steps = listOf(
                SequencerStep(listOf(
                    SequencerNote(60, 113, .50, .75),
                    SequencerNote(67, 95, 1.0, .625),
                )),
                SequencerStep(listOf(SequencerNote(60, 87, .9, 1.0, tie = true))),
                SequencerStep(),
            ),
            subdivisionsPerBeat = 3,
        ),
        bpm = 137.5,
        swing = .42,
    )

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Invalid or future schema is intentionally rejected.
        }
    }

    @Test fun deterministicallyRoundTripsEveryNotePropertyAndUnicode() {
        val original = sample()
        val first = SequenceCodec.encode(original)
        assertTrue(first.startsWith("ORBITAL-SEQUENCE/1\n"))
        assertTrue(first.contains("step.2=\n"))
        assertEquals(original, SequenceCodec.decode(first))
        assertEquals(first, SequenceCodec.encode(SequenceCodec.decode(first)))
    }

    @Test fun maximalPatternRoundTripsWithoutDroppingChords() {
        val chord = SequencerStep((48..63).map {
            SequencerNote(it, 127, 1.0, 1.0, it % 2 == 0)
        })
        val full = sample("full-grid").copy(
            pattern = StepPattern(List(64) { index ->
                if (index % 4 == 0) chord else SequencerStep()
            }, subdivisionsPerBeat = 16),
        )
        val restored = SequenceCodec.decode(SequenceCodec.encode(full))
        assertEquals(64, restored.pattern.steps.size)
        assertEquals(chord, restored.pattern.steps[60])
        assertEquals(full, restored)
    }

    @Test fun restoredPatternProducesSameSampleAddressedEvents() {
        val original = sample().pattern
        val copy = SequenceCodec.decode(SequenceCodec.encode(sample())).pattern
        fun render(pattern: StepPattern): List<TimedNoteEvent> {
            val sequencer = StepSequencer(pattern, sampleRate = 48_000, bpm = 137.5, swing = .42, seed = 7L)
            sequencer.start()
            return buildList {
                repeat(32) { addAll(sequencer.advance(256)) }
                addAll(sequencer.stop())
            }
        }
        assertEquals(render(original), render(copy))
    }

    @Test fun rejectsUnknownVersionsDuplicateMissingAndOutOfRangeRows() {
        val canonical = SequenceCodec.encode(sample())
        rejects { SequenceCodec.decode(canonical.replace("ORBITAL-SEQUENCE/1", "ORBITAL-SEQUENCE/2")) }
        rejects { SequenceCodec.decode(canonical + "step.0=\n") }
        rejects { SequenceCodec.decode(canonical + "step.3=\n") }
        rejects { SequenceCodec.decode(canonical.replace(Regex("^step.2=.*\\n", RegexOption.MULTILINE), "")) }
        rejects { SequenceCodec.decode(canonical + "mystery=value\n") }
        rejects { SequenceCodec.decode(canonical.replace("step.1=", "step.01=")) }
        rejects { SequenceCodec.decode(canonical.replace("steps=3", "steps=65")) }
        rejects { SequenceCodec.decode(canonical.replace("steps=3", "steps=0")) }
        rejects { SequenceCodec.decode(canonical.replace("subdivisions=3", "subdivisions=17")) }
    }

    @Test fun rejectsBadNumericValuesMalformedTuplesAndUnsafeMetadata() {
        val canonical = SequenceCodec.encode(sample())
        rejects { SequenceCodec.decode(canonical.replace("bpm=137.5", "bpm=NaN")) }
        rejects { SequenceCodec.decode(canonical.replace("swing=0.42", "swing=1.5")) }
        rejects { SequenceCodec.decode(canonical.replace("step.2=", "step.2=128,100,0.5,1.0,0")) }
        rejects { SequenceCodec.decode(canonical.replace("step.2=", "step.2=61,0,0.5,1.0,0")) }
        rejects { SequenceCodec.decode(canonical.replace("step.2=", "step.2=61,100,NaN,1.0,0")) }
        rejects { SequenceCodec.decode(canonical.replace("step.2=", "step.2=61,100,0.5,2.0,0")) }
        rejects { SequenceCodec.decode(canonical.replace("step.2=", "step.2=61,100,0.5,1.0,yes")) }
        rejects { SequenceCodec.decode(canonical.replace("step.2=", "step.2=61,100,0.5,1.0")) }
        rejects { SequenceCodec.decode(canonical.replace("name=", "name=%XX")) }
        rejects { UserSequence("../escape", "Bad", StepPattern()) }
        rejects { UserSequence("has_underscore", "Bad", StepPattern()) }
        rejects { UserSequence("safe", "\n", StepPattern()) }
        rejects { UserSequence("safe", "OK", StepPattern(), bpm = Double.POSITIVE_INFINITY) }
        rejects { SequenceCodec.decode("x".repeat(SequenceCodec.MAX_BYTES + 1)) }
    }

    @Test fun rejectsDuplicatePitchesInOneStepAndDoesNotLoseCorruption() {
        val canonical = SequenceCodec.encode(sample())
        rejects {
            SequenceCodec.decode(canonical.replace(
                "step.2=",
                "step.2=60,100,0.5,1.0,0;60,90,0.5,1.0,0",
            ))
        }
    }

    @Test fun directoryStoreSavesListsReplacesAndDeletes() {
        val store = SequenceDirectoryStore(temporary.root.toPath().resolve("sequences"))
        val a = sample("alpha")
        val b = sample("beta")
        assertTrue(store.list().isEmpty())
        assertNull(store.load("alpha"))
        store.save(b)
        store.save(a)
        assertEquals(listOf("alpha", "beta"), store.list().map { it.id })
        assertEquals(a, store.load("alpha"))
        val updated = a.copy(name = "Revised")
        store.save(updated)
        assertEquals(updated, store.load("alpha"))
        assertTrue(store.delete("alpha"))
        assertNull(store.load("alpha"))
        assertFalse(store.delete("alpha"))
        assertTrue(Files.list(temporary.root.toPath().resolve("sequences")).use { paths ->
            paths.noneMatch { it.fileName.toString().endsWith(".tmp") }
        })
    }

    @Test fun directoryStoreRejectsPathTraversalAndEmbeddedIdMismatch() {
        val directory = temporary.newFolder("corrupt-bank").toPath()
        val store = SequenceDirectoryStore(directory)
        rejects { store.load("../outside") }
        rejects { store.delete("bad/id") }
        val content = SequenceCodec.encode(sample("different-id"))
        Files.write(directory.resolve("expected-id.orbital-sequence"), content.toByteArray(StandardCharsets.UTF_8))
        rejects { store.load("expected-id") }
        Files.write(directory.resolve("broken.orbital-sequence"), "ORBITAL-SEQUENCE/9\n".toByteArray(StandardCharsets.UTF_8))
        rejects { store.list() }
    }
}
