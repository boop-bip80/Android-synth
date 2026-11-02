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
    val enabledSteps: Set<Int>,
    /** Source notes survive mute/unmute; render-time generated rests remain separate. */
    val sourcePattern: StepPattern,
    /** Changes on selection/load, allowing stale UI grid edits to be discarded. */
    val revision: Long,
    /** The groove of the selected pattern, retained when saving a user copy. */
    val swing: Double,
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
    private var selectedId = factory.id
    private var selectedName = factory.name
    private var selectionRevision = 0L
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
        enabled.indices.filter { enabled[it] }.toSet(), basePattern, selectionRevision, factory.swing
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
        // Keep the original Unit API. Attach a selection revision so a queued
        // switch cannot apply this old-grid edit to a different pattern.
        val snapshot = patternSnapshot
        if (index in 0 until snapshot.stepCount) {
            commands.offer(Command.StepEnabled(index, value, snapshot.revision))
        }
    }

    /** Resize an editable grid from 1 to 64 steps, retaining in-range notes. */
    fun setStepCount(steps: Int): Boolean {
        if (steps !in 1..64) return false
        commands.offer(Command.Resize(steps, patternSnapshot.revision))
        return true
    }

    /**
     * Replace all notes at one zero-based step, supporting chords and per-note
     * gate/probability/ties. A nonempty edited step is automatically enabled.
     */
    fun setStepNotes(index: Int, notes: List<SequencerNote>): Boolean {
        val snapshot = patternSnapshot
        if (index !in 0 until snapshot.stepCount) return false
        val step = try {
            SequencerStep(notes.toList())
        } catch (_: IllegalArgumentException) {
            return false
        }
        commands.offer(Command.StepNotes(index, step, snapshot.revision))
        return true
    }

    /**
     * Load a validated independent pattern, including a 32/64-step polymeter.
     * Freeze caller-owned lists before enqueueing; the render thread alone
     * performs note release, transport replacement and snapshot publication.
     */
    fun loadPattern(
        id: String,
        name: String,
        pattern: StepPattern,
        bpm: Double = 120.0,
        swing: Double = 0.0,
        enabledSteps: Set<Int>? = null
    ): Boolean {
        if (!Regex("[a-z0-9][a-z0-9-]{0,63}").matches(id) ||
            FactoryPatterns.byId(id) != null || name.isBlank() || name.length > 80 ||
            !bpm.isFinite() || bpm !in 20.0..300.0 ||
            !swing.isFinite() || swing !in 0.0..1.0 ||
            enabledSteps?.any { it !in pattern.steps.indices } == true
        ) return false
        val frozen = try {
            pattern.copy(steps = pattern.steps.map { it.copy(notes = it.notes.toList()) })
        } catch (_: IllegalArgumentException) {
            return false
        }
        commands.offer(Command.LoadPattern(id, name, frozen, bpm, swing, enabledSteps?.toSet()))
        return true
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
                is Command.LoadPattern -> applyPattern(
                    command.id, command.name, command.pattern, command.bpm, command.swing,
                    command.enabledSteps
                )
                is Command.Resize -> {
                    if (command.revision != selectionRevision || command.steps == basePattern.steps.size) continue
                    val previousEnabled = enabled
                    basePattern = basePattern.copy(steps = List(command.steps) { position ->
                        basePattern.steps.getOrNull(position) ?: SequencerStep()
                    })
                    enabled = BooleanArray(command.steps) { position ->
                        position < previousEnabled.size && previousEnabled[position]
                    }
                    rebuildAndRelease()
                }
                is Command.StepNotes -> {
                    if (command.revision != selectionRevision || command.index !in basePattern.steps.indices) continue
                    basePattern = basePattern.copy(steps = basePattern.steps.mapIndexed { position, step ->
                        if (position == command.index) command.step else step
                    })
                    enabled[command.index] = command.step.notes.isNotEmpty()
                    rebuildAndRelease()
                }
                is Command.StepEnabled -> {
                    if (command.revision != selectionRevision || command.index !in enabled.indices) continue
                    if (enabled[command.index] != command.value) {
                        enabled[command.index] = command.value
                        rebuildAndRelease()
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
        factory = selected
        applyPattern(selected.id, selected.name, selected.pattern, selected.bpm, selected.swing)
    }

    private fun applyPattern(
        id: String, name: String, pattern: StepPattern, bpm: Double, swing: Double,
        enabledSteps: Set<Int>? = null
    ) {
        selectedId = id
        selectedName = name
        selectionRevision++
        basePattern = pattern
        enabled = BooleanArray(pattern.steps.size) {
            enabledSteps?.contains(it) ?: pattern.steps[it].notes.isNotEmpty()
        }
        // Install the audible projection, keeping muted source notes intact for
        // later re-enabling and for lossless saved-sequence round trips.
        releaseHeldNotes(sequencer.replacePattern(audiblePattern()))
        releaseHeldNotes(sequencer.configure(bpm = bpm, swing = swing))
        anchorSample = sequencer.cursorSample
        tempoSnapshot = bpm.toInt()
        publishPatternSnapshot()
    }

    /** Convert the editable source and mute grid into one transport pattern. */
    private fun rebuildAndRelease() {
        // A live mute, note edit or length change must not jump playback to bar 1.
        releaseHeldNotes(sequencer.editPatternPreservingPhase(audiblePattern()))
        publishPatternSnapshot()
    }

    private fun audiblePattern(): StepPattern = StepPattern(
        steps = basePattern.steps.mapIndexed { index, step ->
            if (!enabled[index]) SequencerStep()
            else if (step.notes.isNotEmpty()) step
            else generatedStep(index)
        },
        subdivisionsPerBeat = basePattern.subdivisionsPerBeat
    )

    private fun publishPatternSnapshot() {
        patternSnapshot = FactoryPatternSelection(
            selectedId, selectedName, basePattern.steps.size,
            enabled.indices.filter { enabled[it] }.toSet(), basePattern, selectionRevision,
            sequencer.swing,
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
        data class LoadPattern(
            val id: String, val name: String, val pattern: StepPattern,
            val bpm: Double, val swing: Double, val enabledSteps: Set<Int>?
        ) : Command
        data class Resize(val steps: Int, val revision: Long) : Command
        data class StepNotes(val index: Int, val step: SequencerStep, val revision: Long) : Command
        data class Play(val value: Boolean) : Command
        data class Tempo(val bpm: Int) : Command
        data class StepEnabled(val index: Int, val value: Boolean, val revision: Long) : Command
    }
}
