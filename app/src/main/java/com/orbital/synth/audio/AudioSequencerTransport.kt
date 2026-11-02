package com.orbital.synth.audio

import com.orbital.synth.core.music.FactoryPatterns
import com.orbital.synth.core.music.FactorySequence
import com.orbital.synth.core.music.NoteEventKind
import com.orbital.synth.core.music.SequencerNote
import com.orbital.synth.core.music.SequencerStep
import com.orbital.synth.core.music.StepPattern
import com.orbital.synth.core.music.StepSequencer
import com.orbital.synth.core.music.TimedNoteEvent
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToLong

/**
 * Immutable factory selection snapshot for Compose. Published atomically on
 * the render worker after the queued change has been applied.
 */
data class FactoryPatternSelection(
    val id: String,
    val name: String,
    val stepCount: Int,
    val enabledSteps: Set<Int>
)

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
    // Mutated exclusively by the audio worker. Callers can only enqueue commands.
    private var factory = FactoryPatterns.DEFAULT
    private var basePattern = factory.pattern
    private var enabled = BooleanArray(basePattern.steps.size) { index ->
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
    /** One atomic UI snapshot, so ID, name, step count and grid never mix across patterns. */
    @Volatile private var patternSnapshot = FactoryPatternSelection(
        factory.id, factory.name, basePattern.steps.size,
        enabled.indices.filter { enabled[it] }.toSet()
    )

    val factoryPatterns: List<FactorySequence> get() = FactoryPatterns.ALL
    val selection: FactoryPatternSelection get() = patternSnapshot
    val selectedPatternId: String get() = patternSnapshot.id
    val selectedPatternName: String get() = patternSnapshot.name
    val stepCount: Int get() = patternSnapshot.stepCount

    val isPlaying: Boolean get() = playingSnapshot
    val playhead: Int get() = playheadSnapshot
    val tempo: Int get() = tempoSnapshot
    val enabledSteps: Set<Int> get() = patternSnapshot.enabledSteps

    fun setPlaying(value: Boolean) {
        desiredPlaying = value
        commands.offer(Command.Play(value))
    }

    fun setTempo(bpm: Int) {
        if (bpm in 20..300) commands.offer(Command.Tempo(bpm))
    }

    fun setStepEnabled(index: Int, value: Boolean) {
        // Never read the worker-owned mutable grid from a UI thread. The active
        // snapshot is safe even when a factory swap is queued concurrently.
        if (index in 0 until patternSnapshot.stepCount && index < 64) {
            commands.offer(Command.StepEnabled(index, value))
        }
    }

    /**
     * Validates synchronously and queues the selection for the render worker.
     * ID/name, tempo, grid and playhead snapshots update on its next render.
     */
    fun selectFactoryPattern(id: String): Boolean {
        val selected = FactoryPatterns.byId(id) ?: return false
        commands.offer(Command.SelectFactoryPattern(selected))
        return true
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
                is Command.SelectFactoryPattern -> applyFactoryPattern(command.factory)
                is Command.StepEnabled -> {
                    if (command.index !in enabled.indices) continue
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
                        patternSnapshot = patternSnapshot.copy(
                            enabledSteps = enabled.indices.filter { enabled[it] }.toSet()
                        )
                    }
                }
            }
        }
    }

    /**
     * Worker-only factory switch: release the old sequence's owned notes,
     * preserve play/stop state, reset tempo/swing and restore the factory grid.
     * Neither change touches keys from MIDI/touch directly; accurate same-pitch
     * voice identity ultimately depends on source-tagged synth notes.
     */
    private fun applyFactoryPattern(selected: FactorySequence) {
        releaseHeldNotes(sequencer.replacePattern(selected.pattern))
        releaseHeldNotes(sequencer.configure(bpm = selected.bpm, swing = selected.swing))
        factory = selected
        basePattern = selected.pattern
        enabled = BooleanArray(basePattern.steps.size) { basePattern.steps[it].notes.isNotEmpty() }
        anchorSample = sequencer.cursorSample
        tempoSnapshot = selected.bpm.toInt()
        patternSnapshot = FactoryPatternSelection(
            selected.id, selected.name, basePattern.steps.size,
            enabled.indices.filter { enabled[it] }.toSet()
        )
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
        data class SelectFactoryPattern(val factory: FactorySequence) : Command
        data class Play(val value: Boolean) : Command
        data class Tempo(val bpm: Int) : Command
        data class StepEnabled(val index: Int, val value: Boolean) : Command
    }
}
