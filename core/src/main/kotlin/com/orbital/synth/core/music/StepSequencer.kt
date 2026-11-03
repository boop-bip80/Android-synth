package com.orbital.synth.core.music

import kotlin.math.roundToLong

/** Values are intentionally MIDI-facing, not tied to any Android audio API. */
data class SequencerNote(
    val midiNote: Int,
    val velocity: Int = 100,
    val gate: Double = 0.85,
    val probability: Double = 1.0,
    /** A true tie continues this pitch from the preceding step when it is held. */
    val tie: Boolean = false,
) {
    init {
        require(midiNote in 0..127)
        require(velocity in 1..127)
        require(gate.isFinite() && gate in 0.01..1.0)
        require(probability.isFinite() && probability in 0.0..1.0)
    }
}

data class SequencerStep(val notes: List<SequencerNote> = emptyList()) {
    init {
        require(notes.size <= 16)
        require(notes.map { it.midiNote }.distinct().size == notes.size) {
            "A step cannot contain duplicate MIDI pitches"
        }
    }
}

data class StepPattern(
    val steps: List<SequencerStep> = List(16) { SequencerStep() },
    /** 4 = sixteenth notes; 2 = eighth notes in ordinary quarter-note BPM. */
    val subdivisionsPerBeat: Int = 4,
) {
    init {
        require(steps.size in 1..64)
        require(subdivisionsPerBeat in 1..16)
    }
}

enum class NoteEventKind { NOTE_ON, NOTE_OFF }

data class TimedNoteEvent(
    /** Absolute sample position, so a caller can render up to this offset precisely. */
    val sampleTime: Long,
    val kind: NoteEventKind,
    val midiNote: Int,
    val velocity: Int = 0,
)

/**
 * Deterministic, sample-addressed step scheduler. Call advance() on ONE thread.
 * Results include sample offsets but are not themselves an audio renderer.
 *
 * - step probability is stable for a seed and absolute step ordinal;
 * - swing moves odd step onsets later, without drifting bar boundaries;
 * - a tie extends a prior note for the following step, even across buffer boundaries;
 * - stop/reconfigure/replacePattern return explicit NOTE_OFFs: dispatch them.
 *
 * No wall clock, Android APIs, or background thread is involved.
 */
class StepSequencer(
    pattern: StepPattern = StepPattern(),
    val sampleRate: Int = 48_000,
    bpm: Double = 120.0,
    swing: Double = 0.0,
    val seed: Long = 0L,
) {
    init { require(sampleRate in 8_000..384_000) }

    var pattern: StepPattern = freeze(pattern)
        private set

    var bpm: Double = validBpm(bpm)
        private set

    /** 0 is straight; 1 delays odd subdivisions by 45% of a straight step. */
    var swing: Double = validSwing(swing)
        private set

    var isPlaying: Boolean = false
        private set

    var cursorSample: Long = 0L
        private set

    private var anchorSample: Long = 0L
    private var nextStepOrdinal: Long = 0L
    // One direct-addressed due time per MIDI pitch. Long.MAX_VALUE means idle.
    // MIDI note indices are 0..127, so no boxing/maps/filter/sort on the render worker.
    private val activeOffTimes = LongArray(128) { Long.MAX_VALUE }

    /** Start fresh at an absolute sample cursor. Stop first if already playing. */
    fun start(atSample: Long = cursorSample) {
        require(!isPlaying) { "Call stop() before restarting transport" }
        require(atSample >= 0)
        require(activeOffTimes.all { it == Long.MAX_VALUE })
        cursorSample = atSample
        anchorSample = atSample
        nextStepOrdinal = 0L
        isPlaying = true
    }

    /** Silence all active sequencer notes at the current sample position. */
    fun stop(): List<TimedNoteEvent> {
        val offs = ArrayList<TimedNoteEvent>()
        for (note in 0..127) {
            if (activeOffTimes[note] != Long.MAX_VALUE) {
                offs += TimedNoteEvent(cursorSample, NoteEventKind.NOTE_OFF, note)
                activeOffTimes[note] = Long.MAX_VALUE
            }
        }
        isPlaying = false
        return offs
    }

    /** Change patterns without leaving hanging notes; transport restarts at this cursor. */
    fun replacePattern(value: StepPattern): List<TimedNoteEvent> {
        val wasPlaying = isPlaying
        val offs = stop()
        pattern = freeze(value)
        if (wasPlaying) start(cursorSample)
        return offs
    }

    /**
     * Apply a live grid/mute edit without restarting the groove at step zero.
     * Held notes are explicitly released at the edit cursor; pending step timing
     * and absolute probability ordinals remain unchanged. Subdivisions must be
     * unchanged because changing the clock requires configure/replacePattern.
     */
    fun editPatternPreservingPhase(value: StepPattern): List<TimedNoteEvent> {
        val replacement = freeze(value)
        require(replacement.subdivisionsPerBeat == pattern.subdivisionsPerBeat) {
            "Live edits cannot change subdivisions per beat"
        }
        val offs = ArrayList<TimedNoteEvent>()
        for (note in 0..127) {
            if (activeOffTimes[note] != Long.MAX_VALUE) {
                offs += TimedNoteEvent(cursorSample, NoteEventKind.NOTE_OFF, note)
                activeOffTimes[note] = Long.MAX_VALUE
            }
        }
        pattern = replacement
        return offs
    }

    /** Tempo/swing edits reset phase at this cursor and explicitly release held notes. */
    fun configure(bpm: Double = this.bpm, swing: Double = this.swing): List<TimedNoteEvent> {
        val newBpm = validBpm(bpm)
        val newSwing = validSwing(swing)
        val wasPlaying = isPlaying
        val offs = stop()
        this.bpm = newBpm
        this.swing = newSwing
        if (wasPlaying) start(cursorSample)
        return offs
    }

    /**
     * Consume a block [cursorSample, cursorSample + frames). Events on the far
     * boundary are delivered in the next block, exactly once.
     */
    fun advance(frames: Int): List<TimedNoteEvent> {
        require(frames in 0..2_000_000)
        if (!isPlaying || frames == 0) return emptyList()
        // API compatibility for offline clients. The real audio renderers instead
        // call advanceInto() with a pre-created sink, avoiding this List entirely.
        val events = ArrayList<TimedNoteEvent>()
        advanceInto(frames, TimedNoteSink { sampleTime, kind, note, velocity ->
            events.add(TimedNoteEvent(sampleTime, kind, note, velocity))
        })
        return events
    }

    /**
     * Single-worker, allocation-free scheduling for [cursorSample, cursorSample + frames).
     * The sink must be preallocated by its caller and must not re-enter this sequencer.
     *
     * The playhead advances to the block end BEFORE callbacks, matching advance()'s
     * historical state visibility to downstream sample renderers. Far-boundary
     * events are deferred to the next block. At a shared timestamp release all
     * due pitches ascending, then schedule notes in their original chord order.
     */
    fun advanceInto(frames: Int, sink: TimedNoteSink) {
        require(frames in 0..2_000_000)
        if (!isPlaying || frames == 0) return
        require(cursorSample <= Long.MAX_VALUE - frames)
        val end = cursorSample + frames
        cursorSample = end

        while (true) {
            val stepTime = sampleAtStep(nextStepOrdinal)
            var nextOff = Long.MAX_VALUE
            for (note in 0..127) {
                if (activeOffTimes[note] < nextOff) nextOff = activeOffTimes[note]
            }
            if (minOf(stepTime, nextOff) >= end) break

            if (nextOff <= stepTime) {
                for (note in 0..127) {
                    if (activeOffTimes[note] == nextOff) {
                        activeOffTimes[note] = Long.MAX_VALUE
                        sink.emit(nextOff, NoteEventKind.NOTE_OFF, note, 0)
                    }
                }
            } else {
                scheduleStep(nextStepOrdinal, stepTime, sink)
                nextStepOrdinal++
            }
        }
    }

    private fun scheduleStep(ordinal: Long, whenSample: Long, sink: TimedNoteSink) {
        val step = pattern.steps[(ordinal % pattern.steps.size).toInt()]
        for (index in step.notes.indices) {
            val note = step.notes[index]
            if (!shouldTrigger(note.probability, ordinal, index)) continue
            val pitch = note.midiNote
            if (note.tie && activeOffTimes[pitch] != Long.MAX_VALUE) {
                // The prior onset already looked ahead through this tied step.
                continue
            }
            if (activeOffTimes[pitch] != Long.MAX_VALUE) {
                activeOffTimes[pitch] = Long.MAX_VALUE
                sink.emit(whenSample, NoteEventKind.NOTE_OFF, pitch, 0)
            }
            sink.emit(whenSample, NoteEventKind.NOTE_ON, pitch, note.velocity)

            // Inline tie lookahead keeps the original probability/index order,
            // without Pair or withIndex/IndexedValue allocations per note.
            var lastOrdinal = ordinal
            var lastGate = note.gate
            for (offset in 1 until pattern.steps.size) {
                val nextOrdinal = ordinal + offset
                val next = pattern.steps[(nextOrdinal % pattern.steps.size).toInt()]
                var tiedIndex = -1
                for (candidateIndex in next.notes.indices) {
                    val candidate = next.notes[candidateIndex]
                    if (candidate.midiNote == pitch && candidate.tie &&
                        shouldTrigger(candidate.probability, nextOrdinal, candidateIndex)) {
                        tiedIndex = candidateIndex
                        break
                    }
                }
                if (tiedIndex < 0) break
                lastOrdinal = nextOrdinal
                lastGate = next.notes[tiedIndex].gate
            }
            val lastStart = sampleAtStep(lastOrdinal)
            val length = sampleAtStep(lastOrdinal + 1) - lastStart
            // Swing delays the following odd onset but must not lengthen the
            // preceding even step's gate; odd steps use their shortened gap.
            val straightLength =
                (sampleRate * 60.0 / (bpm * pattern.subdivisionsPerBeat)).roundToLong()
            val gateLength =
                if ((lastOrdinal and 1L) == 0L) minOf(length, straightLength) else length
            activeOffTimes[pitch] =
                lastStart + maxOf(1L, (gateLength * lastGate).roundToLong())
        }
    }

    private fun sampleAtStep(ordinal: Long): Long {
        val samplesPerStep = sampleRate * 60.0 / (bpm * pattern.subdivisionsPerBeat)
        val offset = if ((ordinal and 1L) == 1L) samplesPerStep * swing * 0.45 else 0.0
        return anchorSample + (ordinal * samplesPerStep + offset).roundToLong()
    }

    private fun shouldTrigger(probability: Double, ordinal: Long, noteIndex: Int): Boolean {
        if (probability <= 0.0) return false
        if (probability >= 1.0) return true
        // SplitMix64 avalanche; no shared RNG state and repeatable across block sizes.
        var z = seed xor (ordinal * 0x9E3779B97F4A7C15UL.toLong())
        z = z xor ((noteIndex + 1L) * 0xBF58476D1CE4E5B9UL.toLong())
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
        val fraction = ((z xor (z ushr 31)) ushr 11).toDouble() / 9007199254740992.0
        return fraction < probability
    }

    private fun freeze(value: StepPattern): StepPattern =
        value.copy(steps = value.steps.map { it.copy(notes = it.notes.toList()) })

    private fun validBpm(value: Double): Double {
        require(value.isFinite() && value in 20.0..300.0)
        return value
    }

    private fun validSwing(value: Double): Double {
        require(value.isFinite() && value in 0.0..1.0)
        return value
    }
}
