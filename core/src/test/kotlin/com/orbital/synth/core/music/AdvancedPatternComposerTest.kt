package com.orbital.synth.core.music

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AdvancedPatternComposerTest {
    private fun note(pitch: Int, velocity: Int = 90) = SequencerNote(
        midiNote = pitch, velocity = velocity, gate = 0.55,
    )

    @Test fun polymeterRepeatsAllLaneLengthsAndKeepsStrongestCollision() {
        val triple = StepPattern(listOf(
            SequencerStep(listOf(note(60, 70))),
            SequencerStep(),
            SequencerStep(listOf(note(64))),
        ))
        val quadruple = StepPattern(listOf(
            SequencerStep(listOf(note(60, 118))),
            SequencerStep(listOf(note(67))),
            SequencerStep(),
            SequencerStep(listOf(note(69))),
        ))
        val result = AdvancedPatternComposer.polymeter(triple, quadruple)
        assertEquals(12, result.steps.size)
        assertEquals(4, result.subdivisionsPerBeat)
        assertEquals(listOf(60), result.steps[0].notes.map { it.midiNote })
        assertEquals(118, result.steps[0].notes.single().velocity)
        assertEquals(listOf(60, 69), result.steps[3].notes.map { it.midiNote })
        assertEquals(listOf(64, 60), result.steps[8].notes.map { it.midiNote })
        assertEquals(result.steps[0], result.steps[12 % result.steps.size])
        // The source patterns were never mutated by composition.
        assertEquals(70, triple.steps[0].notes.single().velocity)
    }

    @Test fun polymeterRejectsIncompatibleOrUnrepresentableCycles() {
        val emptyNine = StepPattern(List(9) { SequencerStep() })
        val emptyEight = StepPattern(List(8) { SequencerStep() })
        assertThrowsArgument { AdvancedPatternComposer.polymeter() }
        assertThrowsArgument {
            AdvancedPatternComposer.polymeter(emptyNine, emptyEight)
        }
        assertThrowsArgument {
            AdvancedPatternComposer.polymeter(
                StepPattern(List(3) { SequencerStep() }, subdivisionsPerBeat = 4),
                StepPattern(List(3) { SequencerStep() }, subdivisionsPerBeat = 3),
            )
        }
        val sixteen = StepPattern(listOf(SequencerStep((0..15).map { note(it) })))
        val seventeenth = StepPattern(listOf(SequencerStep(listOf(note(16)))))
        assertThrowsArgument {
            AdvancedPatternComposer.polymeter(sixteen, seventeenth)
        }
    }

    @Test fun seededMelodicWalkIsReproducibleScaleConstrainedAndBounded() {
        val args = AdvancedPatternComposer.melodicWalk(
            steps = 64,
            scale = MusicalScales.MINOR_PENTATONIC,
            tonicPitchClass = 2,
            lowestMidiNote = 48,
            highestMidiNote = 72,
            startMidiNote = 61,
            maxDegreeLeap = 2,
            restProbability = 0.0,
            seed = 103902L,
        )
        val repeated = AdvancedPatternComposer.melodicWalk(
            steps = 64,
            scale = MusicalScales.MINOR_PENTATONIC,
            tonicPitchClass = 2,
            lowestMidiNote = 48,
            highestMidiNote = 72,
            startMidiNote = 61,
            maxDegreeLeap = 2,
            restProbability = 0.0,
            seed = 103902L,
        )
        val differentSeed = AdvancedPatternComposer.melodicWalk(
            steps = 64,
            scale = MusicalScales.MINOR_PENTATONIC,
            tonicPitchClass = 2,
            lowestMidiNote = 48,
            highestMidiNote = 72,
            startMidiNote = 61,
            maxDegreeLeap = 2,
            restProbability = 0.0,
            seed = 103903L,
        )
        assertEquals(args, repeated)
        assertNotEquals(args, differentSeed)
        val allowed = (48..72).filter {
            Math.floorMod(it - 2, 12) in MusicalScales.MINOR_PENTATONIC.degrees
        }
        val pitches = args.steps.map { it.notes.single().midiNote }
        assertTrue(pitches.all { it in allowed })
        val degreeIndices = pitches.map { allowed.indexOf(it) }
        assertTrue(degreeIndices.zipWithNext().all { (left, right) -> abs(left - right) <= 2 })
        assertEquals(106, args.steps[0].notes.single().velocity)
    }

    @Test fun controlledRestsDoNotProduceStuckNotesAndIgnoreBlockSize() {
        val generated = AdvancedPatternComposer.melodicWalk(
            steps = 32, seed = 5504L, restProbability = .45,
        )
        val straight = StepSequencer(generated, seed = 1L)
        val sliced = StepSequencer(generated, seed = 1L)
        straight.start()
        sliced.start()
        val whole = straight.advance(192_000) + straight.stop()
        val chunks = buildList {
            repeat(192) { addAll(sliced.advance(1000)) }
            addAll(sliced.stop())
        }
        assertEquals(whole, chunks)
        val held = mutableSetOf<Int>()
        for (event in whole) {
            if (event.kind == NoteEventKind.NOTE_ON) {
                assertTrue(held.add(event.midiNote))
            } else {
                assertTrue(held.remove(event.midiNote))
            }
        }
        assertTrue(held.isEmpty())

        val onlyRests = AdvancedPatternComposer.melodicWalk(restProbability = 1.0)
        assertTrue(onlyRests.steps.all { it.notes.isEmpty() })
    }

    @Test fun invalidGeneratorInputsFailBeforeProducingPatterns() {
        assertThrowsArgument { AdvancedPatternComposer.melodicWalk(steps = 0) }
        assertThrowsArgument { AdvancedPatternComposer.melodicWalk(steps = 65) }
        assertThrowsArgument { AdvancedPatternComposer.melodicWalk(lowestMidiNote = 90, highestMidiNote = 40) }
        assertThrowsArgument { AdvancedPatternComposer.melodicWalk(startMidiNote = 128) }
        assertThrowsArgument { AdvancedPatternComposer.melodicWalk(maxDegreeLeap = 13) }
        assertThrowsArgument { AdvancedPatternComposer.melodicWalk(restProbability = Double.NaN) }
        assertThrowsArgument { AdvancedPatternComposer.melodicWalk(restProbability = -0.1) }
        assertThrowsArgument { AdvancedPatternComposer.melodicWalk(velocity = 0) }
        assertThrowsArgument { AdvancedPatternComposer.melodicWalk(gate = 1.2) }
        assertThrowsArgument { AdvancedPatternComposer.melodicWalk(subdivisionsPerBeat = 0) }
        assertThrowsArgument {
            AdvancedPatternComposer.melodicWalk(
                scale = MusicalScales.WHOLE_TONE, tonicPitchClass = 0,
                lowestMidiNote = 61, highestMidiNote = 61,
            )
        }
    }

    private fun assertThrowsArgument(action: () -> Unit) {
        try {
            action()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
