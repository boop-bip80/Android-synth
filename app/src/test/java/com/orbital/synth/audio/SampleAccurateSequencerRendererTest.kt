package com.orbital.synth.audio

import com.orbital.synth.core.music.SequencerNote
import com.orbital.synth.core.music.SequencerStep
import com.orbital.synth.core.music.StepPattern
import com.orbital.synth.core.music.StepSequencer
import org.junit.Assert.assertEquals
import org.junit.Test

class SampleAccurateSequencerRendererTest {
    private class FakeRenderer {
        var activeNote = 0f
        val events = mutableListOf<String>()
        val renderer = SampleAccurateSequencerRenderer(
            renderFrames = { buffer, frames ->
                for (frame in 0 until frames) {
                    buffer[frame * 2] = activeNote
                    buffer[frame * 2 + 1] = activeNote
                }
            },
            noteOn = { note, _ ->
                activeNote = note.toFloat()
                events += "on:$note"
            },
            noteOff = { note ->
                activeNote = 0f
                events += "off:$note"
            }
        )
    }

    private fun sequence(vararg steps: SequencerStep): StepSequencer =
        StepSequencer(StepPattern(steps.toList()), sampleRate = 8000, bpm = 300.0)

    @Test
    fun splitsWithinOneBlockAtExactNoteOffSample() {
        // 8 kHz * 60 / (300 BPM * 4) = 400 samples per sixteenth.
        // Gate 0.5 means NOTE_OFF at absolute sample 200.
        val seq = sequence(
            SequencerStep(listOf(SequencerNote(60, gate = 0.5))),
            SequencerStep()
        )
        seq.start()
        val fake = FakeRenderer()
        val output = FloatArray(512 * 2)
        val scratch = FloatArray(output.size)
        fake.renderer.renderBlock(seq, output, scratch, 512)
        assertEquals(60f, output[0], 0f)
        assertEquals(60f, output[199 * 2], 0f)
        assertEquals(0f, output[200 * 2], 0f)
        assertEquals(0f, output[511 * 2], 0f)
        assertEquals(listOf("on:60", "off:60"), fake.events)
        assertEquals(512L, seq.cursorSample)
    }

    @Test
    fun maintainsAbsoluteTimeAcrossBufferBoundaries() {
        val seq = sequence(
            SequencerStep(listOf(SequencerNote(60, gate = 0.5))),
            SequencerStep()
        )
        seq.start()
        val fake = FakeRenderer()
        val outputA = FloatArray(128 * 2)
        val outputB = FloatArray(128 * 2)
        val scratch = FloatArray(128 * 2)
        fake.renderer.renderBlock(seq, outputA, scratch, 128)
        fake.renderer.renderBlock(seq, outputB, scratch, 128)
        assertEquals(60f, outputA.last(), 0f)
        assertEquals(60f, outputB[71 * 2], 0f)
        assertEquals(0f, outputB[72 * 2], 0f)
        assertEquals(listOf("on:60", "off:60"), fake.events)
    }

    @Test
    fun releaseBeforeRetriggerAtSameSampleKeepsNewPitchAudible() {
        val seq = sequence(
            SequencerStep(listOf(SequencerNote(60, gate = 1.0))),
            SequencerStep(listOf(SequencerNote(67, gate = 1.0)))
        )
        seq.start()
        val fake = FakeRenderer()
        val output = FloatArray(450 * 2)
        val scratch = FloatArray(output.size)
        fake.renderer.renderBlock(seq, output, scratch, 450)
        assertEquals(60f, output[399 * 2], 0f)
        assertEquals(67f, output[400 * 2], 0f)
        assertEquals(listOf("on:60", "off:60", "on:67"), fake.events)
    }
}
