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
        assertTrue(1 in fake.transport.enabledSteps)
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
}
