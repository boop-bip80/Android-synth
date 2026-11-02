package com.orbital.synth.core.music

import org.junit.Assert.*
import org.junit.Test

class FactoryPatternsTest {
    @Test fun eachFactoryPatternHasUniqueIdValidLengthAndSound() {
        assertEquals(12, FactoryPatterns.ALL.size)
        assertEquals(FactoryPatterns.ALL.size, FactoryPatterns.ALL.map { it.id }.toSet().size)
        assertSame(FactoryPatterns.DEFAULT, FactoryPatterns.byId("aurora"))
        for (factory in FactoryPatterns.ALL) {
            assertTrue(factory.pattern.steps.size in 1..64)
            assertTrue(factory.pattern.steps.any { it.notes.isNotEmpty() })
            assertNotNull(FactoryPatterns.byId(factory.id))
        }
    }

    @Test fun polymetricFactoryPatternsExposeThirtyTwoAndSixtyFourSteps() {
        val eclipse = FactoryPatterns.byId("eclipse-32")!!
        val deepOrbit = FactoryPatterns.byId("deep-orbit-64")!!
        assertEquals(32, eclipse.pattern.steps.size)
        assertEquals(64, deepOrbit.pattern.steps.size)
        assertEquals(4, eclipse.pattern.subdivisionsPerBeat)
        assertEquals(4, deepOrbit.pattern.subdivisionsPerBeat)
        assertTrue(eclipse.pattern.steps.any { it.notes.size >= 2 })
        assertTrue(deepOrbit.pattern.steps.any { it.notes.size >= 2 })
    }

    @Test fun everyFactoryPatternReturnsBalancedNotesAfterTransportStop() {
        for (factory in FactoryPatterns.ALL) {
            val seq = StepSequencer(
                factory.pattern,
                bpm = factory.bpm,
                swing = factory.swing,
                seed = 42L,
            )
            seq.start()
            val events = seq.advance(350_000) + seq.stop()
            val notesHeld = mutableSetOf<Int>()
            for (event in events) {
                when (event.kind) {
                    NoteEventKind.NOTE_ON -> assertTrue(notesHeld.add(event.midiNote))
                    NoteEventKind.NOTE_OFF -> assertTrue(notesHeld.remove(event.midiNote))
                }
            }
            assertTrue(factory.name + " leaves no notes held", notesHeld.isEmpty())
            assertTrue(factory.name + " should make sound", events.any { it.kind == NoteEventKind.NOTE_ON })
        }
    }
}
