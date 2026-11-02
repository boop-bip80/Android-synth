package com.orbital.synth.core.music

import org.junit.Assert.*
import org.junit.Test

class FactoryPatternsTest {
    @Test fun eachFactoryPatternIsDistinctSixteenStepAndNonSilent() {
        assertEquals(10, FactoryPatterns.ALL.size)
        assertEquals(FactoryPatterns.ALL.size, FactoryPatterns.ALL.map { it.id }.toSet().size)
        assertSame(FactoryPatterns.DEFAULT, FactoryPatterns.byId("aurora"))
        for (factory in FactoryPatterns.ALL) {
            assertEquals(16, factory.pattern.steps.size)
            assertTrue(factory.pattern.steps.any { it.notes.isNotEmpty() })
            assertNotNull(FactoryPatterns.byId(factory.id))
        }
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
