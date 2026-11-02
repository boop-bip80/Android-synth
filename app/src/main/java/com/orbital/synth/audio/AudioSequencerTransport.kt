package com.orbital.synth.audio

import com.orbital.synth.core.music.FactoryPatterns
import com.orbital.synth.core.music.NoteEventKind
import com.orbital.synth.core.music.SequencerNote
import com.orbital.synth.core.music.SequencerStep
import com.orbital.synth.core.music.StepPattern
import com.orbital.synth.core.music.StepSequencer
import com.orbital.synth.core.music.TimedNoteEvent
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToLong

/**
 * Thread boundary between touch controls and the sample-addressed sequencer.
 *
 * Public setter methods enqueue immutable commands and never mutate StepSequencer.
 * Exactly one audio render worker calls onWorkerStart/renderBlock/onWorkerStop.
 * Volatile immutable snapshots keep UI diagnostics safe to read without taking
 * any locks on the audio thread.
 */
internal class AudioSequencerTransport(
    sampleRate: Int,
    renderFrames: (FloatArray, Int) -> Unit,
    noteOn: (Int, Float) -> Unit,
    private val noteOff: (Int) -> Unit
) {
    private val factory = FactoryPatterns.DEFAULT
    private val basePattern = factory.pattern
    private val enabled = BooleanArray(basePattern.steps.size) { index ->
        basePattern.steps[index].notes.isNotEmpty()
    }
    private val sequencer = StepSequencer(
        pattern = basePattern,
        sampleRate = sampleRate,
        bpm = factory.bpm,
        swing = factory.swing
    )
    private val renderer = SampleAccurateSequencerRenderer(renderFrames, noteOn, noteOff)
    private val commands = ConcurrentLinkedQueue<Command>()
    private var anchorSample = 0L

    @Volatile private var desiredPlaying = false
    @Volatile private var playingSnapshot = false
    @Volatile private var playheadSnapshot = -1
    @Volatile private var tempoSnapshot = factory.bpm.toInt()
    @Volatile private var enabledSnapshot = enabled.indices.filter { enabled[it] }.toSet()

    val isPlaying: Boolean get() = playingSnapshot
    val playhead: Int get() = playheadSnapshot
    val tempo: Int get() = tempoSnapshot
    val enabledSteps: Set<Int> get() = enabledSnapshot

    fun setPlaying(value: Boolean) {
        desiredPlaying = value
        commands.offer(Command.Play(value))
    }

    fun setTempo(bpm: Int) {
        if (bpm in 20..300) commands.offer(Command.Tempo(bpm))
    }

    fun setStepEnabled(index: Int, value: Boolean) {
        if (index in enabled.indices) commands.offer(Command.StepEnabled(index, value))
    }

    fun onWorkerStart() {
        // Resume the user-requested transport after app focus/lifecycle restart.
        // Pending commands are still drained on the next render callback.
        if (desiredPlaying && !sequencer.isPlaying) {
            sequencer.start()
            anchorSample = sequencer.cursorSample
            playingSnapshot = true
        }
    }

    fun renderBlock(output: FloatArray, scratch: FloatArray, frames: Int) {
        drainCommands()
        renderer.renderBlock(sequencer, output, scratch, frames)
        publishState()
    }

    fun onWorkerStop() {
        releaseHeldNotes(sequencer.stop())
        playingSnapshot = false
        playheadSnapshot = -1
    }

    private fun drainCommands() {
        // Bound command processing during rapid slider/step edits.
        for (index in 0 until 128) {
            when (val command = commands.poll() ?: break) {
                is Command.Play -> {
                    if (command.value && !sequencer.isPlaying) {
                        sequencer.start()
                        anchorSample = sequencer.cursorSample
                    } else if (!command.value && sequencer.isPlaying) {
                        releaseHeldNotes(sequencer.stop())
                    }
                }
                is Command.Tempo -> {
                    releaseHeldNotes(sequencer.configure(bpm = command.bpm.toDouble()))
                    tempoSnapshot = command.bpm
                    anchorSample = sequencer.cursorSample
                }
                is Command.StepEnabled -> {
                    if (enabled[command.index] != command.value) {
                        enabled[command.index] = command.value
                        val updated = StepPattern(
                            steps = basePattern.steps.mapIndexed { stepIndex, step ->
                                if (!enabled[stepIndex]) SequencerStep()
                                else if (step.notes.isNotEmpty()) step
                                else generatedStep(stepIndex)
                            },
                            subdivisionsPerBeat = basePattern.subdivisionsPerBeat
                        )
                        releaseHeldNotes(sequencer.replacePattern(updated))
                        anchorSample = sequencer.cursorSample
                        enabledSnapshot = enabled.indices.filter { enabled[it] }.toSet()
                    }
                }
            }
        }
    }

    /**
     * A factory rest may be turned into a note by tapping its inactive cell.
     * Reuse the nearest preceding factory pitch to stay musically coherent;
     * fall back to middle C if the factory pattern contains no notes.
     */
    private fun generatedStep(index: Int): SequencerStep {
        val count = basePattern.steps.size
        var pitch = 60
        for (distance in 1..count) {
            val previous = (index - distance + count) % count
            val note = basePattern.steps[previous].notes.firstOrNull()
            if (note != null) {
                pitch = note.midiNote
                break
            }
        }
        return SequencerStep(listOf(SequencerNote(pitch, velocity = 92, gate = 0.72)))
    }

    private fun releaseHeldNotes(events: List<TimedNoteEvent>) {
        for (event in events) {
            if (event.kind == NoteEventKind.NOTE_OFF) noteOff(event.midiNote)
        }
    }

    private fun publishState() {
        playingSnapshot = sequencer.isPlaying
        if (!sequencer.isPlaying) {
            playheadSnapshot = -1
            return
        }
        val stepCount = sequencer.pattern.steps.size
        val samplesPerStep =
            sequencer.sampleRate * 60.0 / (sequencer.bpm * sequencer.pattern.subdivisionsPerBeat)
        val elapsed = (sequencer.cursorSample - anchorSample).coerceAtLeast(0L)
        var step = (elapsed / samplesPerStep).toLong().coerceAtLeast(0L)
        // Correct straight-time approximation for the delayed odd steps under swing.
        while (step > 0L && stepTime(step, samplesPerStep, sequencer.swing) > elapsed) step--
        while (stepTime(step + 1L, samplesPerStep, sequencer.swing) <= elapsed) step++
        playheadSnapshot = (step % stepCount).toInt()
    }

    private fun stepTime(step: Long, stepSamples: Double, swing: Double): Long {
        val swingOffset = if ((step and 1L) == 1L) stepSamples * swing * 0.45 else 0.0
        return (step * stepSamples + swingOffset).roundToLong()
    }

    private sealed interface Command {
        data class Play(val value: Boolean) : Command
        data class Tempo(val bpm: Int) : Command
        data class StepEnabled(val index: Int, val value: Boolean) : Command
    }
}
