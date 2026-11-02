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
        assertEquals(10, fake.transport.factoryPatterns.size)
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
