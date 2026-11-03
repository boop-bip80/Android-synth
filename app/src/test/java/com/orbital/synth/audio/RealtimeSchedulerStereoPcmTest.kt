package com.orbital.synth.audio

import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.music.NoteEventKind
import com.orbital.synth.core.music.SequencerNote
import com.orbital.synth.core.music.SequencerStep
import com.orbital.synth.core.music.StepPattern
import com.orbital.synth.core.music.StepSequencer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Golden stereo PCM: compare the original List-based event/render segmentation
 * against the production primitive-sink renderer, with independent real synths.
 */
class RealtimeSchedulerStereoPcmTest {
    private val sourceId = -2L

    private fun synth(): OrbitalSynth = OrbitalSynth(sampleRate = 8_000, maxVoices = 8).apply {
        setParameter(ParameterId.WAVE, 0.51f)
        setParameter(ParameterId.MORPH, 0.32f)
        setParameter(ParameterId.CUTOFF, 0.89f)
        setParameter(ParameterId.RESONANCE, 0.22f)
        setParameter(ParameterId.MOVEMENT, 0f)
        setParameter(ParameterId.LFO_DEPTH, 0f)
        setParameter(ParameterId.FX_AMOUNT, 0f)
        setParameter(ParameterId.ATTACK, 0f)
        setParameter(ParameterId.DECAY, 0.12f)
        setParameter(ParameterId.SUSTAIN, 1f)
        setParameter(ParameterId.RELEASE, 0f)
        for (slot in 0 until oscillatorSlotCount) {
            setOscillatorSlot(slot, OscillatorSlot(
                enabled = slot % 3 != 0, waveform = slot / 7f,
                morph = 0.3f + slot * 0.06f, level = 0.22f,
                coarseSemitones = (slot - 4) * 2, fineCents = slot * 3f,
                noiseLevel = if (slot % 4 == 0) 0.17f else 0f,
                noiseColor = slot / 7f
            ))
        }
    }

    private fun pattern() = StepPattern(listOf(
        SequencerStep(listOf(
            SequencerNote(60, velocity = 99, gate = 0.5),
            SequencerNote(64, velocity = 85, gate = 1.0)
        )),
        SequencerStep(listOf(
            SequencerNote(64, velocity = 86, gate = 1.0, tie = true),
            SequencerNote(67, velocity = 108, gate = 0.7, probability = 0.7)
        )),
        SequencerStep(listOf(
            SequencerNote(48, velocity = 93, gate = 1.0),
            SequencerNote(72, velocity = 104, gate = 0.31, probability = 0.38)
        )),
        SequencerStep()
    ), subdivisionsPerBeat = 8)

    private fun oldRenderSegment(
        synth: OrbitalSynth,
        output: FloatArray,
        scratch: FloatArray,
        startFrame: Int,
        frames: Int,
        totalFrames: Int
    ) {
        if (startFrame == 0 && frames == totalFrames) {
            synth.renderStereo(output, frames)
        } else {
            synth.renderStereo(scratch, frames)
            System.arraycopy(scratch, 0, output, startFrame * 2, frames * 2)
        }
    }

    /** The unmodified pre-PR renderer's List path, frozen as a test oracle. */
    private fun oldRenderBlock(
        sequencer: StepSequencer,
        synth: OrbitalSynth,
        output: FloatArray,
        scratch: FloatArray,
        frames: Int
    ) {
        if (!sequencer.isPlaying) {
            synth.renderStereo(output, frames)
            return
        }
        val blockStart = sequencer.cursorSample
        val events = sequencer.advance(frames)
        var rendered = 0
        for (event in events) {
            val atFrame = (event.sampleTime - blockStart).toInt()
            require(atFrame in rendered until frames)
            if (atFrame > rendered) {
                oldRenderSegment(synth, output, scratch, rendered, atFrame - rendered, frames)
                rendered = atFrame
            }
            when (event.kind) {
                NoteEventKind.NOTE_ON ->
                    synth.noteOn(sourceId, 0, event.midiNote, event.velocity.coerceIn(1, 127) / 127f)
                NoteEventKind.NOTE_OFF -> synth.noteOff(sourceId, 0, event.midiNote)
            }
        }
        if (rendered < frames) {
            oldRenderSegment(synth, output, scratch, rendered, frames - rendered, frames)
        }
    }

    @Test fun liveEightLayerStereoPcmMatchesFrozenListRendererThroughTiesAndSwing() {
        val oldSynth = synth()
        val newSynth = synth()
        val oldSeq = StepSequencer(pattern(), sampleRate = 8_000,
            bpm = 267.0, swing = 0.62, seed = 2468)
        val newSeq = StepSequencer(pattern(), sampleRate = 8_000,
            bpm = 267.0, swing = 0.62, seed = 2468)
        val renderer = SampleAccurateSequencerRenderer(
            renderFrames = { buffer, frames -> newSynth.renderStereo(buffer, frames) },
            noteOn = { note, velocity -> newSynth.noteOn(sourceId, 0, note, velocity) },
            noteOff = { note -> newSynth.noteOff(sourceId, 0, note) }
        )
        oldSeq.start(atSample = 17)
        newSeq.start(atSample = 17)
        var maxPeak = 0f
        var sampleOffset = 0L

        fun assertSamePcm(frames: Int, label: String) {
            val baseline = FloatArray(frames * 2)
            val optimized = FloatArray(frames * 2)
            val legacyScratch = FloatArray(frames * 2)
            val sinkScratch = FloatArray(frames * 2)
            oldRenderBlock(oldSeq, oldSynth, baseline, legacyScratch, frames)
            renderer.renderBlock(newSeq, optimized, sinkScratch, frames)
            for (index in baseline.indices) {
                assertEquals(
                    "$label @ absolute PCM ${sampleOffset * 2 + index}",
                    java.lang.Float.floatToRawIntBits(baseline[index]),
                    java.lang.Float.floatToRawIntBits(optimized[index])
                )
                assertTrue("Non-finite PCM at $index", optimized[index].isFinite())
                maxPeak = maxOf(maxPeak, abs(optimized[index]))
            }
            sampleOffset += frames
        }

        val sizes = intArrayOf(1, 17, 192, 512, 7, 192, 1, 320)
        repeat(12) { cycle ->
            for (frames in sizes) assertSamePcm(frames, "cycle$cycle/frames$frames")
            if (cycle == 6) {
                val oldOffs = oldSeq.configure(bpm = 295.0, swing = 0.2)
                val newOffs = newSeq.configure(bpm = 295.0, swing = 0.2)
                assertEquals(oldOffs, newOffs)
                oldOffs.forEach { oldSynth.noteOff(sourceId, 0, it.midiNote) }
                newOffs.forEach { newSynth.noteOff(sourceId, 0, it.midiNote) }
            }
        }
        assertTrue("Must exercise audible chord and real DSP", maxPeak > 0.002f)
        val oldOffs = oldSeq.stop()
        val newOffs = newSeq.stop()
        assertEquals(oldOffs, newOffs)
        oldOffs.forEach { oldSynth.noteOff(sourceId, 0, it.midiNote) }
        newOffs.forEach { newSynth.noteOff(sourceId, 0, it.midiNote) }
        repeat(4) { assertSamePcm(512, "afterStop$it") }
        assertEquals(0, oldSynth.activeVoiceCount)
        assertEquals(0, newSynth.activeVoiceCount)
    }
}
