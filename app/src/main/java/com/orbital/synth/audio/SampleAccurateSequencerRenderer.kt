package com.orbital.synth.audio

import com.orbital.synth.core.music.NoteEventKind
import com.orbital.synth.core.music.StepSequencer
import com.orbital.synth.core.music.TimedNoteSink

/**
 * Executes absolute-sample sequencer events by splitting the synth's render calls
 * at exact event boundaries. A single preallocated scratch buffer is reused for
 * every partial segment; the scheduler emits primitives to one persistent sink
 * rather than allocating an event List (including empty Lists) every AudioTrack
 * block. A callback may still allocate downstream voice-command objects.
 *
 * The client must call this from exactly one audio render thread.
 */
internal class SampleAccurateSequencerRenderer(
    private val renderFrames: (FloatArray, Int) -> Unit,
    private val noteOn: (Int, Float) -> Unit,
    private val noteOff: (Int) -> Unit
) {
    // Audio-thread-only context for the immutable, once-created note sink.
    private var eventOutput = FloatArray(0)
    private var eventScratch = FloatArray(0)
    private var eventStart = 0L
    private var eventFrames = 0
    private var eventRendered = 0
    private val sequencerSink = TimedNoteSink { sampleTime, kind, midiNote, velocity ->
        val offset = (sampleTime - eventStart).toInt()
        require(offset in eventRendered until eventFrames) {
            "Sequencer events must be sorted and inside the current audio block"
        }
        if (offset > eventRendered) {
            renderSegment(eventOutput, eventScratch, eventRendered,
                offset - eventRendered, eventFrames)
            eventRendered = offset
        }
        when (kind) {
            NoteEventKind.NOTE_ON -> noteOn(midiNote, velocity.coerceIn(1, 127) / 127f)
            NoteEventKind.NOTE_OFF -> noteOff(midiNote)
        }
    }

    fun renderBlock(
        sequencer: StepSequencer,
        output: FloatArray,
        scratch: FloatArray,
        frames: Int
    ) {
        require(frames >= 0 && output.size >= frames * 2 && scratch.size >= frames * 2)
        if (frames == 0) return
        if (!sequencer.isPlaying) {
            renderFrames(output, frames)
            return
        }

        eventOutput = output
        eventScratch = scratch
        eventStart = sequencer.cursorSample
        eventFrames = frames
        eventRendered = 0
        sequencer.advanceInto(frames, sequencerSink)
        if (eventRendered < frames) {
            renderSegment(output, scratch, eventRendered, frames - eventRendered, frames)
        }
    }

    private fun renderSegment(
        output: FloatArray,
        scratch: FloatArray,
        startFrame: Int,
        segmentFrames: Int,
        totalFrames: Int
    ) {
        if (startFrame == 0 && segmentFrames == totalFrames) {
            renderFrames(output, segmentFrames)
        } else {
            renderFrames(scratch, segmentFrames)
            System.arraycopy(scratch, 0, output, startFrame * 2, segmentFrames * 2)
        }
    }
}
