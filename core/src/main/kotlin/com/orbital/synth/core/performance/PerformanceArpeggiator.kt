package com.orbital.synth.core.performance

import com.orbital.synth.core.music.NoteEventKind
import com.orbital.synth.core.music.TimedNoteEvent
import kotlin.math.roundToLong

/** Distinct musical traversal strategies; all use a stable note pool per step. */
enum class ArpeggiatorDirection { UP, DOWN, UP_DOWN, AS_PLAYED, RANDOM, CHORD }

/**
 * Immutable performance parameters. A ratchet subdivides one step into evenly spaced
 * retriggers; gate is measured relative to each ratchet, not the whole beat.
 */
data class ArpeggiatorSettings(
    val bpm: Double = 120.0,
    val subdivisionsPerBeat: Int = 4,
    val swing: Double = 0.0,
    val direction: ArpeggiatorDirection = ArpeggiatorDirection.UP,
    val octaves: Int = 1,
    val ratchets: Int = 1,
    val gate: Double = 0.8,
    val probability: Double = 1.0,
    val latch: Boolean = false,
) {
    init {
        require(bpm.isFinite() && bpm in 20.0..300.0)
        require(subdivisionsPerBeat in 1..16)
        require(swing.isFinite() && swing in 0.0..1.0)
        require(octaves in 1..4)
        require(ratchets in 1..8)
        require(gate.isFinite() && gate in 0.05..1.0)
        require(probability.isFinite() && probability in 0.0..1.0)
    }
}

/**
 * Sample-addressed, source-aware performance arpeggiator for the single audio worker.
 *
 * Feed physical noteOn/noteOff calls on that worker and dispatch every event returned
 * by advance(), noteOff(), stop(), configure(), allNotesOff() or panic(). The output
 * uses the existing TimedNoteEvent contract and can be split at exact sample offsets,
 * like the step sequencer. It never reads wall time, locks, or allocates per sample.
 *
 * Source/channel ownership prevents one MIDI port's note-off from removing another
 * port's held note. MIDI pitches emitted by this class should be played under one
 * dedicated arpeggiator source ID by the caller (not under physical input IDs).
 *
 * The physical-key table is bounded at 256 simultaneous source/channel/pitch tuples;
 * a full table rejects new key-ons instead of stealing another owner's key.
 * Chord mode intentionally caps its emitted chord to 16 notes to bound polyphony.
 */
class PerformanceArpeggiator(
    val sampleRate: Int = 48_000,
    settings: ArpeggiatorSettings = ArpeggiatorSettings(),
    val seed: Long = 0L,
) {
    init { require(sampleRate in 8_000..192_000) }

    var settings: ArpeggiatorSettings = settings
        private set

    var cursorSample: Long = 0
        private set

    var isPlaying: Boolean = false
        private set

    private val heldSource = LongArray(256)
    private val heldChannel = IntArray(256)
    private val heldNote = IntArray(256)
    private val heldVelocity = IntArray(256)
    private val heldPresses = IntArray(256)
    private var heldCount = 0

    private val latchedNote = IntArray(128)
    private val latchedVelocity = IntArray(128)
    private var latchedCount = 0

    // Reused scratch for every step, never allocated in an audio sample loop.
    private val poolNote = IntArray(128)
    private val poolVelocity = IntArray(128)
    private var poolCount = 0
    private val selectedNote = IntArray(16)
    private val selectedVelocity = IntArray(16)
    private var selectedCount = 0
    private val activeOffTime = LongArray(128) { Long.MAX_VALUE }

    private var anchorSample = 0L
    private var nextStep = 0L
    private var ratchetStart = 0L
    private var ratchetEnd = 0L
    private var ratchetIndex = 0
    private var ratchetCount = 0

    /** Input changes must be made by the same thread calling advance(). */
    fun noteOn(sourceId: Long, channel: Int, midiNote: Int, velocity: Int = 100): Boolean {
        if (channel !in 0..15 || midiNote !in 0..127 || velocity !in 1..127) return false
        val existing = findHeld(sourceId, channel, midiNote)
        if (existing >= 0) {
            heldPresses[existing] = (heldPresses[existing] + 1).coerceAtMost(65_535)
            heldVelocity[existing] = velocity
            if (settings.latch) addLatched(midiNote, velocity)
            return true
        }
        if (heldCount == heldNote.size) return false

        // First key of a new physical gesture replaces the previous latched chord.
        if (settings.latch && heldCount == 0) latchedCount = 0
        heldSource[heldCount] = sourceId
        heldChannel[heldCount] = channel
        heldNote[heldCount] = midiNote
        heldVelocity[heldCount] = velocity
        heldPresses[heldCount] = 1
        heldCount++
        if (settings.latch) addLatched(midiNote, velocity)
        return true
    }

    /**
     * Return immediate note-offs if the final physical key was released with
     * latch disabled. Those offs must be dispatched before rendering more audio.
     */
    fun noteOff(sourceId: Long, channel: Int, midiNote: Int): List<TimedNoteEvent> {
        val index = findHeld(sourceId, channel, midiNote)
        if (index < 0) return emptyList()
        if (heldPresses[index] > 1) {
            heldPresses[index]--
            return emptyList()
        }
        removeHeld(index)
        return releaseIfNoInput()
    }

    /** Remove a disconnected MIDI source without stealing other sources' notes. */
    fun allNotesOff(sourceId: Long): List<TimedNoteEvent> {
        var index = 0
        while (index < heldCount) {
            if (heldSource[index] == sourceId) removeHeld(index) else index++
        }
        return releaseIfNoInput()
    }

    /** Start a new beat-zero phrase. Stop first if already running. */
    fun start(atSample: Long = cursorSample) {
        require(!isPlaying) { "Call stop before restarting" }
        require(atSample >= 0L)
        require(activeOffTime.all { it == Long.MAX_VALUE })
        cursorSample = atSample
        anchorSample = atSample
        nextStep = 0
        ratchetIndex = 0
        ratchetCount = 0
        isPlaying = true
    }

    /** Explicitly release every note that this arpeggiator currently owns. */
    fun stop(): List<TimedNoteEvent> {
        val offs = releaseAll()
        isPlaying = false
        return offs
    }

    /** Reconfiguration restarts beat phase and returns any required note-offs. */
    fun configure(value: ArpeggiatorSettings): List<TimedNoteEvent> {
        val wasPlaying = isPlaying
        val offs = stop()
        settings = value
        if (!value.latch) latchedCount = 0
        if (wasPlaying) start(cursorSample)
        return offs
    }

    /** Full panic also discards physically pressed and latched notes. */
    fun panic(): List<TimedNoteEvent> {
        val offs = stop()
        heldCount = 0
        latchedCount = 0
        return offs
    }

    /** Clear the captured chord; current physical keys can still be played. */
    fun clearLatch(): List<TimedNoteEvent> {
        latchedCount = 0
        return if (heldCount == 0) releaseAll() else emptyList()
    }

    /**
     * Consume the half-open sample range [cursorSample, cursorSample + frames).
     * Boundary events appear once in the following block, not twice.
     * NOTE_OFF always precedes NOTE_ON when they share a sample timestamp.
     */
    fun advance(frames: Int): List<TimedNoteEvent> {
        require(frames in 0..2_000_000)
        if (!isPlaying || frames == 0) return emptyList()
        require(cursorSample <= Long.MAX_VALUE - frames)
        val end = cursorSample + frames
        val events = ArrayList<TimedNoteEvent>()

        while (true) {
            val stepTime = sampleAtStep(nextStep)
            val offTime = nextOffTime()
            val retriggerTime = if (ratchetIndex < ratchetCount) {
                ratchetSample(ratchetIndex)
            } else {
                Long.MAX_VALUE
            }
            val due = minOf(stepTime, offTime, retriggerTime)
            if (due >= end) break

            when {
                // The previous voice must finish before a same-sample retrigger.
                offTime <= stepTime && offTime <= retriggerTime -> {
                    for (note in 0..127) {
                        if (activeOffTime[note] == offTime) {
                            activeOffTime[note] = Long.MAX_VALUE
                            events += TimedNoteEvent(offTime, NoteEventKind.NOTE_OFF, note)
                        }
                    }
                }
                stepTime <= retriggerTime -> {
                    prepareStep(nextStep, stepTime)
                    nextStep++
                }
                else -> emitRatchet(retriggerTime, events)
            }
        }

        cursorSample = end
        return events
    }

    private fun prepareStep(ordinal: Long, whenSample: Long) {
        ratchetIndex = 0
        ratchetCount = 0
        selectedCount = 0
        if (!shouldPlay(ordinal)) return
        buildPool()
        if (poolCount == 0) return

        if (settings.direction == ArpeggiatorDirection.CHORD) {
            selectedCount = minOf(16, poolCount)
            for (index in 0 until selectedCount) {
                selectedNote[index] = poolNote[index]
                selectedVelocity[index] = poolVelocity[index]
            }
        } else {
            val index = when (settings.direction) {
                ArpeggiatorDirection.DOWN ->
                    poolCount - 1 - (ordinal % poolCount).toInt()
                ArpeggiatorDirection.UP_DOWN -> {
                    val cycle = if (poolCount == 1) 1 else poolCount * 2 - 2
                    val phase = (ordinal % cycle).toInt()
                    if (phase < poolCount) phase else cycle - phase
                }
                ArpeggiatorDirection.RANDOM ->
                    ((mix(seed xor ordinal) ushr 1) % poolCount).toInt()
                else -> (ordinal % poolCount).toInt()
            }
            selectedCount = 1
            selectedNote[0] = poolNote[index]
            selectedVelocity[0] = poolVelocity[index]
        }

        ratchetStart = whenSample
        ratchetEnd = sampleAtStep(ordinal + 1)
        ratchetCount = settings.ratchets
    }

    private fun emitRatchet(atSample: Long, out: MutableList<TimedNoteEvent>) {
        val index = ratchetIndex
        val next = if (index + 1 == ratchetCount) {
            ratchetEnd
        } else {
            ratchetSample(index + 1)
        }
        val gateSamples = maxOf(1L, ((next - atSample) * settings.gate).roundToLong())
        for (slot in 0 until selectedCount) {
            val note = selectedNote[slot]
            if (activeOffTime[note] != Long.MAX_VALUE) {
                out += TimedNoteEvent(atSample, NoteEventKind.NOTE_OFF, note)
            }
            activeOffTime[note] = atSample + gateSamples
            out += TimedNoteEvent(
                atSample, NoteEventKind.NOTE_ON, note, selectedVelocity[slot]
            )
        }
        ratchetIndex++
    }

    private fun ratchetSample(index: Int): Long =
        ratchetStart + (ratchetEnd - ratchetStart) * index / ratchetCount

    private fun buildPool() {
        poolCount = 0
        val useLatch = settings.latch && latchedCount > 0
        val count = if (useLatch) latchedCount else heldCount
        for (octave in 0 until settings.octaves) {
            for (index in 0 until count) {
                val note = (if (useLatch) latchedNote[index] else heldNote[index]) + 12 * octave
                if (note <= 127) {
                    addPool(note, if (useLatch) latchedVelocity[index] else heldVelocity[index])
                }
            }
        }
        if (settings.direction == ArpeggiatorDirection.AS_PLAYED) return

        // Insertion sort is bounded by 128 MIDI pitches; at most once per step.
        for (index in 1 until poolCount) {
            val note = poolNote[index]
            val velocity = poolVelocity[index]
            var cursor = index
            while (cursor > 0 && poolNote[cursor - 1] > note) {
                poolNote[cursor] = poolNote[cursor - 1]
                poolVelocity[cursor] = poolVelocity[cursor - 1]
                cursor--
            }
            poolNote[cursor] = note
            poolVelocity[cursor] = velocity
        }
    }

    private fun addPool(note: Int, velocity: Int) {
        for (index in 0 until poolCount) {
            if (poolNote[index] == note) {
                poolVelocity[index] = maxOf(poolVelocity[index], velocity)
                return
            }
        }
        if (poolCount == poolNote.size) return
        poolNote[poolCount] = note
        poolVelocity[poolCount] = velocity
        poolCount++
    }

    private fun addLatched(note: Int, velocity: Int) {
        for (index in 0 until latchedCount) {
            if (latchedNote[index] == note) {
                latchedVelocity[index] = velocity
                return
            }
        }
        if (latchedCount == latchedNote.size) return
        latchedNote[latchedCount] = note
        latchedVelocity[latchedCount] = velocity
        latchedCount++
    }

    private fun findHeld(source: Long, channel: Int, note: Int): Int {
        for (index in 0 until heldCount) {
            if (heldSource[index] == source &&
                heldChannel[index] == channel && heldNote[index] == note
            ) return index
        }
        return -1
    }

    private fun removeHeld(index: Int) {
        for (slot in index until heldCount - 1) {
            heldSource[slot] = heldSource[slot + 1]
            heldChannel[slot] = heldChannel[slot + 1]
            heldNote[slot] = heldNote[slot + 1]
            heldVelocity[slot] = heldVelocity[slot + 1]
            heldPresses[slot] = heldPresses[slot + 1]
        }
        heldCount--
    }

    private fun releaseIfNoInput(): List<TimedNoteEvent> =
        if (heldCount == 0 && !settings.latch) releaseAll() else emptyList()

    private fun releaseAll(): List<TimedNoteEvent> {
        val offs = ArrayList<TimedNoteEvent>()
        for (note in 0..127) {
            if (activeOffTime[note] != Long.MAX_VALUE) {
                offs += TimedNoteEvent(cursorSample, NoteEventKind.NOTE_OFF, note)
                activeOffTime[note] = Long.MAX_VALUE
            }
        }
        // Cancel queued ratchets so they cannot retrigger after a release.
        ratchetCount = 0
        ratchetIndex = 0
        return offs
    }

    private fun nextOffTime(): Long {
        var next = Long.MAX_VALUE
        for (note in 0..127) {
            if (activeOffTime[note] < next) next = activeOffTime[note]
        }
        return next
    }

    private fun sampleAtStep(ordinal: Long): Long {
        val samplesPerStep =
            sampleRate * 60.0 / (settings.bpm * settings.subdivisionsPerBeat)
        val swingOffset = if ((ordinal and 1L) != 0L) {
            samplesPerStep * settings.swing * 0.45
        } else 0.0
        return anchorSample + (ordinal * samplesPerStep + swingOffset).roundToLong()
    }

    private fun shouldPlay(ordinal: Long): Boolean {
        if (settings.probability <= 0.0) return false
        if (settings.probability >= 1.0) return true
        val fraction = (mix(seed xor (ordinal * 0x9E3779B97F4A7C15UL.toLong()))
            ushr 11).toDouble() / 9007199254740992.0
        return fraction < settings.probability
    }

    private fun mix(input: Long): Long {
        var z = input + 0x9E3779B97F4A7C15UL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
        return z xor (z ushr 31)
    }
}
