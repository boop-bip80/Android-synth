package com.orbital.synth.core.music

import org.junit.Assert.*
import org.junit.Test

class PatternComposerTest {
    @Test fun euclideanPulseCountsAndDistributionStayExact() {
        for (steps in 1..64) {
            for (pulses in 0..steps) {
                val rhythm = PatternComposer.euclidean(steps, pulses, 60)
                val hitPositions = rhythm.steps.indices.filter { rhythm.steps[it].notes.isNotEmpty() }
                assertEquals("steps=$steps pulses=$pulses", pulses, hitPositions.size)
                if (pulses > 0) assertEquals(0, hitPositions.first())
                if (pulses in 2 until steps) {
                    val gaps = hitPositions.indices.map {
                        val current = hitPositions[it]
                        val next = if (it == hitPositions.lastIndex) hitPositions.first() + steps
                                   else hitPositions[it + 1]
                        next - current
                    }
                    assertTrue("steps=$steps pulses=$pulses gaps=$gaps",
                        (gaps.maxOrNull()!! - gaps.minOrNull()!!) <= 1)
                }
            }
        }
    }

    @Test fun rotationMovesAttacksForwardAndAccentsCountPulses() {
        val plain = PatternComposer.euclidean(8, 3, 60, velocity = 77, accentEvery = 2,
            accentVelocity = 120)
        assertEquals(listOf(0, 3, 6), plain.steps.indices.filter {
            plain.steps[it].notes.isNotEmpty()
        })
        assertEquals(listOf(120, 77, 120), listOf(0, 3, 6).map {
            plain.steps[it].notes.single().velocity
        })
        val moved = PatternComposer.euclidean(8, 3, 60, rotation = 2,
            velocity = 77, accentEvery = 2, accentVelocity = 120)
        assertEquals(listOf(0, 2, 5), moved.steps.indices.filter {
            moved.steps[it].notes.isNotEmpty()
        })
        assertEquals(moved, PatternComposer.rotate(plain, 2))
        assertEquals(plain, PatternComposer.rotate(plain, Int.MIN_VALUE))
    }

    @Test fun transpositionRejectsOverflowRatherThanCollapsingChordTones() {
        val pattern = StepPattern(listOf(SequencerStep(listOf(
            SequencerNote(0), SequencerNote(125, gate = .25, tie = true),
        ))))
        val moved = PatternComposer.transpose(pattern, 2)
        assertEquals(listOf(2, 127), moved.steps.single().notes.map { it.midiNote })
        assertEquals(.25, moved.steps.single().notes.last().gate, 0.0)
        assertTrue(moved.steps.single().notes.last().tie)
        try {
            PatternComposer.transpose(pattern, 3)
            fail("Overflow should be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            PatternComposer.transpose(pattern, Int.MIN_VALUE)
            fail("Negative overflow should be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun quantizationDeduplicatesChordsByStrongestVelocity() {
        val input = StepPattern(listOf(SequencerStep(listOf(
            SequencerNote(63, velocity = 44),
            SequencerNote(62, velocity = 100),
            SequencerNote(66, velocity = 80, tie = true),
        ))))
        val output = PatternComposer.quantize(input, ScaleQuantizer(MusicalScales.MAJOR))
        assertEquals(listOf(62, 65), output.steps.single().notes.map { it.midiNote })
        assertEquals(100, output.steps.single().notes[0].velocity)
        assertTrue(output.steps.single().notes.last().tie)
        // Inputs remain unchanged, so editors can implement undo with retained values.
        assertEquals(3, input.steps.single().notes.size)
    }

    @Test fun layeringRetainsStrongestCollisionAndPlaybackBalancesAllNotes() {
        val first = PatternComposer.euclidean(16, 5, 60, velocity = 62)
        val stronger = PatternComposer.euclidean(16, 3, 60, rotation = 0, velocity = 117)
        val bass = PatternComposer.euclidean(16, 7, 48, rotation = 3)
        val combined = PatternComposer.layer(first, stronger, bass)
        assertEquals(117, combined.steps[0].notes.first { it.midiNote == 60 }.velocity)
        assertEquals(16, combined.steps.size)
        assertTrue(combined.steps.all {
            it.notes.map { note -> note.midiNote }.distinct().size == it.notes.size
        })
        val sequencer = StepSequencer(combined)
        sequencer.start()
        val events = sequencer.advance(48_000) + sequencer.stop()
        val held = mutableSetOf<Int>()
        for (event in events) {
            when (event.kind) {
                NoteEventKind.NOTE_ON -> assertTrue(held.add(event.midiNote))
                NoteEventKind.NOTE_OFF -> assertTrue(held.remove(event.midiNote))
            }
        }
        assertTrue(held.isEmpty())
    }

    @Test fun invalidSettingsAndIncompatibleLayerLengthsAreRejected() {
        listOf(
            { PatternComposer.euclidean(0, 0, 60) },
            { PatternComposer.euclidean(8, 9, 60) },
            { PatternComposer.euclidean(8, 1, 128) },
            { PatternComposer.euclidean(8, 1, 60, accentEvery = -1) },
            { PatternComposer.layer() },
            { PatternComposer.layer(StepPattern(), StepPattern(List(8) { SequencerStep() })) },
        ).forEach { operation ->
            try {
                operation()
                fail("Invalid pattern operation should fail")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
