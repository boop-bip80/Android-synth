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
    private val activeOffTimes = mutableMapOf<Int, Long>()

    /** Start fresh at an absolute sample cursor. Stop first if already playing. */
    fun start(atSample: Long = cursorSample) {
        require(!isPlaying) { "Call stop() before restarting transport" }
        require(atSample >= 0)
        require(activeOffTimes.isEmpty())
        cursorSample = atSample
        anchorSample = atSample
        nextStepOrdinal = 0L
        isPlaying = true
    }

    /** Silence all active sequencer notes at the current sample position. */
    fun stop(): List<TimedNoteEvent> {
        val offs = activeOffTimes.keys.sorted().map {
            TimedNoteEvent(cursorSample, NoteEventKind.NOTE_OFF, it)
        }
        activeOffTimes.clear()
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
        val offs = activeOffTimes.keys.sorted().map {
            TimedNoteEvent(cursorSample, NoteEventKind.NOTE_OFF, it)
        }
        activeOffTimes.clear()
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
        require(cursorSample <= Long.MAX_VALUE - frames)
        val end = cursorSample + frames
        val events = ArrayList<TimedNoteEvent>()

        while (true) {
            val stepTime = sampleAtStep(nextStepOrdinal)
            val nextOff = activeOffTimes.values.minOrNull() ?: Long.MAX_VALUE
            val nextEvent = minOf(stepTime, nextOff)
            if (nextEvent >= end) break

            // Releasing first at a shared timestamp guarantees a clear retrigger.
            if (nextOff <= stepTime) {
                val due = activeOffTimes.filterValues { it == nextOff }.keys.sorted()
                for (note in due) {
                    activeOffTimes.remove(note)
                    events += TimedNoteEvent(nextOff, NoteEventKind.NOTE_OFF, note)
                }
            } else {
                scheduleStep(nextStepOrdinal, stepTime, events)
                nextStepOrdinal++
            }
        }

        cursorSample = end
        return events
    }

    private fun scheduleStep(ordinal: Long, whenSample: Long, out: MutableList<TimedNoteEvent>) {
        val step = pattern.steps[(ordinal % pattern.steps.size).toInt()]
        for ((index, note) in step.notes.withIndex()) {
            if (!shouldTrigger(note.probability, ordinal, index)) continue

            if (note.tie && note.midiNote in activeOffTimes) {
                // The prior onset's lookahead already included this tied step.
                continue
            }

            if (activeOffTimes.remove(note.midiNote) != null) {
                out += TimedNoteEvent(whenSample, NoteEventKind.NOTE_OFF, note.midiNote)
            }

            out += TimedNoteEvent(whenSample, NoteEventKind.NOTE_ON, note.midiNote, note.velocity)
            val (lastOrdinal, lastGate) = tiedEnd(ordinal, note)
            val lastStart = sampleAtStep(lastOrdinal)
            val length = sampleAtStep(lastOrdinal + 1) - lastStart
            // Swing delays the following odd-step onset but must not elongate
            // the gate of the preceding even step. Odd-step gates instead use
            // their shortened time until the next fixed even boundary.
            val straightLength = (sampleRate * 60.0 / (bpm * pattern.subdivisionsPerBeat)).roundToLong()
            val gateLength = if ((lastOrdinal and 1L) == 0L) minOf(length, straightLength) else length
            activeOffTimes[note.midiNote] =
                lastStart + maxOf(1L, (gateLength * lastGate).roundToLong())
        }
    }

    /** Scan no more than one cycle: a loop of ties cannot hold notes indefinitely. */
    private fun tiedEnd(origin: Long, firstNote: SequencerNote): Pair<Long, Double> {
        var lastOrdinal = origin
        var lastGate = firstNote.gate
        for (offset in 1 until pattern.steps.size) {
            val nextOrdinal = origin + offset
            val next = pattern.steps[(nextOrdinal % pattern.steps.size).toInt()]
            val found = next.notes.withIndex().firstOrNull {
                it.value.midiNote == firstNote.midiNote && it.value.tie &&
                    shouldTrigger(it.value.probability, nextOrdinal, it.index)
            } ?: break
            lastOrdinal = nextOrdinal
            lastGate = found.value.gate
        }
        return lastOrdinal to lastGate
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
