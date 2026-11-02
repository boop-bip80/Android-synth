package com.orbital.synth.core.music

import org.junit.Assert.*
import org.junit.Test

class StepSequencerTest {
    private fun pattern(vararg steps: SequencerStep) = StepPattern(steps.toList())
    private fun note(n: Int, gate: Double = 1.0, probability: Double = 1.0, tie: Boolean = false) =
        SequencerNote(n, velocity = 100, gate = gate, probability = probability, tie = tie)

    @Test fun sampleAccurateNoteOnAndOffAndBoundaryDelivery() {
        val sequencer = StepSequencer(pattern(SequencerStep(listOf(note(60, gate = 0.5))), SequencerStep()))
        sequencer.start()
        assertEquals(listOf(TimedNoteEvent(0, NoteEventKind.NOTE_ON, 60, 100)), sequencer.advance(3000))
        assertEquals(listOf(TimedNoteEvent(3000, NoteEventKind.NOTE_OFF, 60)), sequencer.advance(1))
        assertTrue(sequencer.advance(2999).isEmpty())
        assertEquals(6000L, sequencer.cursorSample)
        assertTrue(sequencer.advance(6000).isEmpty())
        assertEquals(NoteEventKind.NOTE_ON, sequencer.advance(1).single().kind)
    }

    @Test fun swingDelaysOddStepsWithoutMovingEvenBoundaries() {
        val sequencer = StepSequencer(
            pattern(SequencerStep(listOf(note(60, 0.5))), SequencerStep(listOf(note(62, 0.5)))),
            swing = 0.5,
        )
        sequencer.start()
        val events = sequencer.advance(12_001)
        assertEquals(listOf(0L, 3000L, 7350L, 9675L, 12000L), events.map { it.sampleTime })
        assertEquals(
            listOf(NoteEventKind.NOTE_ON, NoteEventKind.NOTE_OFF, NoteEventKind.NOTE_ON,
                NoteEventKind.NOTE_OFF, NoteEventKind.NOTE_ON),
            events.map { it.kind }
        )
    }

    @Test fun tiesExtendNoteButDoNotRetriggerIt() {
        val seq = StepSequencer(pattern(
            SequencerStep(listOf(note(60, gate = 0.4))),
            SequencerStep(listOf(note(60, gate = 1.0, tie = true))),
            SequencerStep(),
            SequencerStep(),
        ))
        seq.start()
        assertEquals(listOf(TimedNoteEvent(0, NoteEventKind.NOTE_ON, 60, 100)), seq.advance(12_000))
        assertEquals(listOf(TimedNoteEvent(12_000, NoteEventKind.NOTE_OFF, 60)), seq.advance(1))
    }

    @Test fun tieLoopIsBoundedToOnePatternCycle() {
        val seq = StepSequencer(pattern(
            SequencerStep(listOf(note(60))),
            SequencerStep(listOf(note(60, tie = true))),
            SequencerStep(listOf(note(60, tie = true))),
            SequencerStep(listOf(note(60, tie = true))),
        ))
        seq.start()
        val events = seq.advance(24_001)
        assertEquals(listOf(0L, 24_000L, 24_000L), events.map { it.sampleTime })
        assertEquals(
            listOf(NoteEventKind.NOTE_ON, NoteEventKind.NOTE_OFF, NoteEventKind.NOTE_ON),
            events.map { it.kind },
        )
    }

    @Test fun repeatedPitchRetriggersOffBeforeOnWithoutStuckVoices() {
        val seq = StepSequencer(pattern(
            SequencerStep(listOf(note(60, gate = 1.0))),
            SequencerStep(listOf(note(60, gate = 1.0))),
        ))
        seq.start()
        val events = seq.advance(6001)
        assertEquals(listOf(0L, 6000L, 6000L), events.map { it.sampleTime })
        assertEquals(
            listOf(NoteEventKind.NOTE_ON, NoteEventKind.NOTE_OFF, NoteEventKind.NOTE_ON),
            events.map { it.kind },
        )
        assertEquals(listOf(TimedNoteEvent(6001, NoteEventKind.NOTE_OFF, 60)), seq.stop())
    }

    @Test fun polyphonicStepAndStopEmitAllNotesOff() {
        val seq = StepSequencer(pattern(SequencerStep(listOf(note(60), note(64), note(67)))))
        seq.start()
        val ons = seq.advance(1000)
        assertEquals(3, ons.size)
        assertTrue(ons.all { it.kind == NoteEventKind.NOTE_ON })
        val offs = seq.stop()
        assertEquals(listOf(60, 64, 67), offs.map { it.midiNote })
        assertTrue(offs.all { it.kind == NoteEventKind.NOTE_OFF && it.sampleTime == 1000L })
        assertTrue(seq.advance(5000).isEmpty())
    }

    @Test fun replacingPatternReleasesHeldNotesAndRestartsAtCursor() {
        val seq = StepSequencer(pattern(SequencerStep(listOf(note(60)))))
        seq.start()
        seq.advance(1000)
        assertEquals(listOf(TimedNoteEvent(1000, NoteEventKind.NOTE_OFF, 60)),
            seq.replacePattern(pattern(SequencerStep(listOf(note(72))))))
        assertEquals(listOf(TimedNoteEvent(1000, NoteEventKind.NOTE_ON, 72, 100)),
            seq.advance(1))
    }

    @Test fun tempoReconfigurationReleasesHeldNotesAndReanchors() {
        val seq = StepSequencer(pattern(SequencerStep(listOf(note(60)))))
        seq.start()
        seq.advance(1000)
        assertEquals(1, seq.configure(bpm = 60.0, swing = 0.3).size)
        assertEquals(1000L, seq.advance(1).single().sampleTime)
        assertEquals(60.0, seq.bpm, 0.0)
    }

    @Test fun probabilityIsIndependentOfHostBlockSegmentation() {
        val steps = List(8) { SequencerStep(listOf(note(60 + it, probability = 0.42))) }
        val full = StepSequencer(StepPattern(steps), seed = 987654321)
        val split = StepSequencer(StepPattern(steps), seed = 987654321)
        full.start()
        split.start()
        val expected = full.advance(96_000)
        val actual = buildList {
            repeat(192) { addAll(split.advance(500)) }
        }
        assertEquals(expected, actual)
    }

    @Test fun zeroProbabilitySkipsAndOneAlwaysTriggers() {
        val seq = StepSequencer(pattern(
            SequencerStep(listOf(note(60, probability = 0.0), note(64, probability = 1.0))),
        ))
        seq.start()
        val all = seq.advance(6001)
        assertTrue(all.none { it.midiNote == 60 })
        assertTrue(all.any { it.midiNote == 64 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidProbabilityIsRejected() {
        SequencerNote(60, probability = Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateStepPitchesAreRejected() {
        SequencerStep(listOf(note(60), note(60)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidTempoIsRejected() {
        StepSequencer(bpm = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSwingIsRejected() {
        StepSequencer(swing = Double.POSITIVE_INFINITY)
    }

    @Test fun callerCannotMutateInstalledPatternNotes() {
        val source = mutableListOf(note(60))
        val seq = StepSequencer(pattern(SequencerStep(source)))
        source.clear()
        seq.start()
        assertEquals(60, seq.advance(1).single().midiNote)
    }
}
