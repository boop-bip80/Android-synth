package com.orbital.synth.audio

import com.orbital.synth.core.music.NoteEventKind
import com.orbital.synth.core.music.StepSequencer

/**
 * Executes absolute-sample sequencer events by splitting the synth's render calls
 * at event boundaries. A single preallocated scratch buffer is reused for every
 * partial segment. Only the sequencer's per-block event list allocates; never
 * allocate one object or array per output sample.
 *
 * The client must call this from exactly one audio render thread.
 */
internal class SampleAccurateSequencerRenderer(
    private val renderFrames: (FloatArray, Int) -> Unit,
    private val noteOn: (Int, Float) -> Unit,
    private val noteOff: (Int) -> Unit
) {
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

        val blockStart = sequencer.cursorSample
        val events = sequencer.advance(frames)
        var rendered = 0
        for (event in events) {
            val eventOffset = (event.sampleTime - blockStart).toInt()
            require(eventOffset in rendered until frames) {
                "Sequencer events must be sorted and inside the current audio block"
            }
            if (eventOffset > rendered) {
                renderSegment(output, scratch, rendered, eventOffset - rendered, frames)
                rendered = eventOffset
            }
            when (event.kind) {
                NoteEventKind.NOTE_ON ->
                    noteOn(event.midiNote, event.velocity.coerceIn(1, 127) / 127f)
                NoteEventKind.NOTE_OFF -> noteOff(event.midiNote)
            }
        }
        if (rendered < frames) renderSegment(output, scratch, rendered, frames - rendered, frames)
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
