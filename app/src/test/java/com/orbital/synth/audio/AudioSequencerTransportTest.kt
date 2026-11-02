package com.orbital.synth.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSequencerTransportTest {
    private class Fake {
        val held = mutableSetOf<Int>()
        var noteOnCalls = 0
        var noteOffCalls = 0
        val transport = AudioSequencerTransport(
            sampleRate = 48_000,
            renderFrames = { buffer, frames ->
                val value = held.size.toFloat()
                for (frame in 0 until frames) {
                    buffer[frame * 2] = value
                    buffer[frame * 2 + 1] = value
                }
            },
            noteOn = { note, _ ->
                held.add(note)
                noteOnCalls++
            },
            noteOff = { note ->
                held.remove(note)
                noteOffCalls++
            }
        )
        val output = FloatArray(192 * 2)
        val scratch = FloatArray(output.size)
        fun render() = transport.renderBlock(output, scratch, 192)
        fun renderFrames(frames: Int) = transport.renderBlock(
            FloatArray(frames * 2), FloatArray(frames * 2), frames
        )
    }

    @Test
    fun startsFromQueuedPlayAndStopsWithAllHeldNotesReleased() {
        val fake = Fake()
        fake.transport.setPlaying(true)
        fake.transport.onWorkerStart()
        fake.render()
        assertTrue(fake.transport.isPlaying)
        assertTrue(fake.noteOnCalls >= 1)
        assertTrue(fake.held.isNotEmpty())
        assertTrue(fake.transport.playhead in 0..15)

        fake.transport.setPlaying(false)
        fake.render()
        assertFalse(fake.transport.isPlaying)
        assertTrue(fake.held.isEmpty())
        assertTrue(fake.noteOffCalls >= fake.noteOnCalls)
        assertEquals(-1, fake.transport.playhead)
    }

    @Test
    fun mutedStepNeverTriggersAndStepStatePublishesSafely() {
        val fake = Fake()
        fake.transport.setStepEnabled(0, false)
        fake.transport.setPlaying(true)
        fake.transport.onWorkerStart()
        fake.render()
        assertEquals(0, fake.noteOnCalls)
        assertFalse(0 in fake.transport.enabledSteps)
        // Factory step 1 is intentionally a rest; step 2 remains enabled.
        assertTrue(2 in fake.transport.enabledSteps)
        assertEquals(0f, fake.output[0], 0f)
    }

    @Test
    fun tempoEditsAreAppliedByRenderWorkerAndInvalidValuesAreIgnored() {
        val fake = Fake()
        fake.transport.setTempo(240)
        fake.render()
        assertEquals(240, fake.transport.tempo)
        fake.transport.setTempo(0)
        fake.transport.setTempo(301)
        fake.render()
        assertEquals(240, fake.transport.tempo)
    }

    @Test
    fun enablingFactoryRestCreatesAnAudibleStep() {
        val fake = Fake()
        // Aurora factory slot 1 is an intentional rest.
        assertFalse(1 in fake.transport.enabledSteps)
        fake.transport.setTempo(300)
        fake.transport.setStepEnabled(1, true)
        fake.transport.setPlaying(true)
        fake.transport.onWorkerStart()
        // At 300 BPM step 1 begins near sample 2508; 20 blocks cover 3840 frames.
        repeat(20) { fake.render() }
        assertTrue(1 in fake.transport.enabledSteps)
        // Step 0 has two notes; the enabled rest must add a third note onset.
        assertTrue(fake.noteOnCalls >= 3)
    }

    @Test
    fun lifecycleShutdownReleasesNotesAndRestartHonorsDesiredPlayback() {
        val fake = Fake()
        fake.transport.setPlaying(true)
        fake.transport.onWorkerStart()
        fake.render()
        assertTrue(fake.held.isNotEmpty())
        fake.transport.onWorkerStop()
        assertTrue(fake.held.isEmpty())
        assertFalse(fake.transport.isPlaying)

        fake.transport.onWorkerStart()
        fake.render()
        assertTrue(fake.transport.isPlaying)
        assertTrue(fake.held.isNotEmpty())
    }

    @Test
    fun factorySelectionValidatesImmediatelyAndPublishesAtomicWorkerSnapshot() {
        val fake = Fake()
        assertEquals(12, fake.transport.factoryPatterns.size)
        assertEquals("aurora", fake.transport.selectedPatternId)
        assertEquals("Aurora Orbit", fake.transport.selectedPatternName)
        assertEquals(16, fake.transport.stepCount)
        assertFalse(fake.transport.selectFactoryPattern("missing"))
        assertTrue(fake.transport.selectFactoryPattern("comet"))
        // Enqueue never mutates worker-owned state on the UI thread.
        assertEquals("aurora", fake.transport.selectedPatternId)
        fake.render()
        val snapshot = fake.transport.selection
        assertEquals("comet", snapshot.id)
        assertEquals("Comet Cascade", snapshot.name)
        assertEquals(16, snapshot.stepCount)
        assertEquals((0 until 16).toSet(), snapshot.enabledSteps)
        assertEquals(138, fake.transport.tempo)
    }

    @Test
    fun switchingFactoryDuringPlaybackReleasesOldNotesAndStartsNewPattern() {
        val fake = Fake()
        fake.transport.setPlaying(true)
        fake.transport.onWorkerStart()
        fake.render()
        assertTrue(fake.held.contains(60))
        assertTrue(fake.held.contains(67))
        val before = fake.noteOffCalls

        assertTrue(fake.transport.selectFactoryPattern("comet"))
        fake.render()
        assertTrue(fake.transport.isPlaying)
        assertTrue(fake.noteOffCalls >= before + 2)
        assertFalse(fake.held.contains(60))
        assertFalse(fake.held.contains(67))
        assertTrue(fake.held.contains(48))
        assertTrue(fake.transport.playhead in 0..15)
    }

    @Test
    fun selectableLongFactorySequencesExposeFullGridAndResetEdits() {
        val fake = Fake()
        assertTrue(fake.transport.selectFactoryPattern("eclipse-32"))
        fake.render()
        assertEquals(32, fake.transport.stepCount)
        assertEquals("eclipse-32", fake.transport.selectedPatternId)
        assertEquals(32, fake.transport.selection.sourcePattern.steps.size)
        assertTrue(fake.transport.enabledSteps.all { it in 0..31 })

        fake.transport.setStepEnabled(0, false)
        fake.render()
        assertFalse(0 in fake.transport.enabledSteps)
        assertTrue(fake.transport.selectFactoryPattern("deep-orbit-64"))
        fake.render()
        assertEquals("deep-orbit-64", fake.transport.selectedPatternId)
        assertEquals(64, fake.transport.stepCount)
        assertEquals(64, fake.transport.selection.sourcePattern.steps.size)
        assertTrue(fake.transport.enabledSteps.all { it in 0..63 })
        assertTrue(fake.transport.selection.revision >= 2L)

        assertTrue(fake.transport.selectFactoryPattern("eclipse-32"))
        fake.render()
        assertTrue(0 in fake.transport.enabledSteps)
        assertEquals(32, fake.transport.stepCount)
    }

    @Test
    fun editingAndResizingLongGridsPreservesSourceNotesAndStaysBounded() {
        val fake = Fake()
        assertTrue(fake.transport.loadPattern(
            "user-long", "User Long", com.orbital.synth.core.music.StepPattern(
                List(32) { com.orbital.synth.core.music.SequencerStep() }
            )
        ))
        fake.render()
        assertEquals(32, fake.transport.stepCount)
        assertTrue(fake.transport.enabledSteps.isEmpty())
        assertTrue(fake.transport.setStepNotes(31, listOf(
            com.orbital.synth.core.music.SequencerNote(71, velocity = 97, gate = .6)
        )))
        fake.render()
        assertTrue(31 in fake.transport.enabledSteps)
        assertEquals(71, fake.transport.selection.sourcePattern.steps[31].notes.single().midiNote)

        assertTrue(fake.transport.setStepCount(64))
        // Queued resize cannot partially publish the edited source or length.
        assertEquals(32, fake.transport.stepCount)
        fake.render()
        assertEquals(64, fake.transport.stepCount)
        assertTrue(31 in fake.transport.enabledSteps)
        assertFalse(63 in fake.transport.enabledSteps)
        assertTrue(fake.transport.setStepNotes(63, listOf(
            com.orbital.synth.core.music.SequencerNote(84)
        )))
        fake.render()
        assertTrue(63 in fake.transport.enabledSteps)
        assertEquals(84, fake.transport.selection.sourcePattern.steps[63].notes.single().midiNote)

        fake.transport.setStepEnabled(31, false)
        fake.render()
        assertFalse(31 in fake.transport.enabledSteps)
        fake.transport.setStepEnabled(31, true)
        fake.render()
        assertEquals(71, fake.transport.selection.sourcePattern.steps[31].notes.single().midiNote)
        assertTrue(31 in fake.transport.enabledSteps)

        assertTrue(fake.transport.setStepCount(32))
        fake.render()
        assertEquals(32, fake.transport.stepCount)
        assertEquals(32, fake.transport.selection.sourcePattern.steps.size)
        assertTrue(fake.transport.enabledSteps.all { it in 0..31 })
    }

    @Test
    fun sixtyFourStepCustomPatternRespectsSampleBoundaryAndCycleWrap() {
        val fake = Fake()
        val silent = com.orbital.synth.core.music.SequencerStep()
        val hit = com.orbital.synth.core.music.SequencerStep(listOf(
            com.orbital.synth.core.music.SequencerNote(84, gate = .5)
        ))
        assertTrue(fake.transport.loadPattern(
            "user-boundary", "Boundary 64",
            com.orbital.synth.core.music.StepPattern(List(64) { if (it == 63) hit else silent }),
            bpm = 120.0, swing = 0.0
        ))
        fake.transport.setPlaying(true)
        fake.transport.onWorkerStart()
        fake.render()
        assertEquals(64, fake.transport.stepCount)
        assertEquals(0, fake.noteOnCalls)
        // 63 sixteenth notes at 120 BPM, 48 kHz = 378000 samples.
        fake.renderFrames(378_000 - 192)
        assertEquals(0, fake.noteOnCalls)
        assertEquals(63, fake.transport.playhead)
        fake.renderFrames(1)
        assertEquals(1, fake.noteOnCalls)
        assertTrue(84 in fake.held)
        assertEquals(63, fake.transport.playhead)
        fake.renderFrames(6_000)
        assertEquals(0, fake.transport.playhead)
        assertFalse(84 in fake.held)
        fake.transport.setPlaying(false)
        fake.render()
        assertTrue(fake.held.isEmpty())
        assertEquals(-1, fake.transport.playhead)
    }

    @Test
    fun loadingNewPatternReleasesHeldFactoryNotesAndRestartsAtZero() {
        val fake = Fake()
        fake.transport.setPlaying(true)
        fake.transport.onWorkerStart()
        fake.render()
        assertTrue(60 in fake.held)
        assertTrue(67 in fake.held)
        val oldOffs = fake.noteOffCalls
        assertTrue(fake.transport.loadPattern(
            "user-replacement", "Replacement",
            com.orbital.synth.core.music.StepPattern(List(32) { position ->
                if (position == 0) com.orbital.synth.core.music.SequencerStep(listOf(
                    com.orbital.synth.core.music.SequencerNote(72, gate = 1.0)
                )) else com.orbital.synth.core.music.SequencerStep()
            }), bpm = 100.0, swing = 0.05
        ))
        fake.render()
        assertTrue(fake.transport.isPlaying)
        assertEquals(32, fake.transport.stepCount)
        assertEquals("user-replacement", fake.transport.selectedPatternId)
        assertEquals(0, fake.transport.playhead)
        assertTrue(fake.noteOffCalls >= oldOffs + 2)
        assertFalse(60 in fake.held)
        assertFalse(67 in fake.held)
        assertTrue(72 in fake.held)
    }

    @Test
    fun outdatedGridCommandsCannotMutateNewSelection() {
        val fake = Fake()
        assertTrue(fake.transport.selectFactoryPattern("comet"))
        // This toggle was issued against Aurora's published revision.
        fake.transport.setStepEnabled(0, false)
        fake.render()
        assertEquals("comet", fake.transport.selectedPatternId)
        assertTrue(0 in fake.transport.enabledSteps)
        assertEquals(16, fake.transport.stepCount)
        assertFalse(fake.transport.setStepCount(0))
        assertFalse(fake.transport.setStepCount(65))
        assertFalse(fake.transport.setStepNotes(16, emptyList()))
        assertFalse(fake.transport.setStepNotes(0, listOf(
            com.orbital.synth.core.music.SequencerNote(60),
            com.orbital.synth.core.music.SequencerNote(60)
        )))
        assertFalse(fake.transport.loadPattern(
            "comet", "Conflict", com.orbital.synth.core.music.StepPattern()
        ))
        assertFalse(fake.transport.loadPattern(
            "user-invalid", "Invalid", com.orbital.synth.core.music.StepPattern(), bpm = 0.0
        ))
    }

    @Test
    fun queuedPatternLoadFreezesMutableCallerData() {
        val fake = Fake()
        val sourceSteps = MutableList(32) { com.orbital.synth.core.music.SequencerStep() }
        sourceSteps[31] = com.orbital.synth.core.music.SequencerStep(listOf(
            com.orbital.synth.core.music.SequencerNote(76)
        ))
        val input = com.orbital.synth.core.music.StepPattern(sourceSteps)
        assertTrue(fake.transport.loadPattern("user-frozen", "Frozen", input))
        sourceSteps.clear()
        fake.render()
        assertEquals(32, fake.transport.stepCount)
        assertEquals(76, fake.transport.selection.sourcePattern.steps[31].notes.single().midiNote)
    }

    @Test
    fun factorySwitchResetsStepEditsTempoAndSwingWhileKeepingGridBounded() {
        val fake = Fake()
        fake.transport.setStepEnabled(0, false)
        fake.render()
        assertFalse(0 in fake.transport.enabledSteps)
        fake.transport.setTempo(240)
        fake.render()
        assertEquals(240, fake.transport.tempo)

        fake.transport.setStepEnabled(63, true) // beyond current 16-step bank: ignored
        assertTrue(fake.transport.selectFactoryPattern("ion-bass"))
        fake.render()
        assertEquals("ion-bass", fake.transport.selectedPatternId)
        assertEquals(126, fake.transport.tempo)
        assertEquals(16, fake.transport.stepCount)
        assertTrue(0 in fake.transport.enabledSteps)
        assertFalse(1 in fake.transport.enabledSteps)
        assertTrue(fake.transport.enabledSteps.all { it in 0 until fake.transport.stepCount })

        // Re-selecting a factory restores its original grid after editing.
        fake.transport.setStepEnabled(0, false)
        fake.render()
        assertFalse(0 in fake.transport.enabledSteps)
        assertTrue(fake.transport.selectFactoryPattern("ion-bass"))
        fake.render()
        assertTrue(0 in fake.transport.enabledSteps)
    }
}
