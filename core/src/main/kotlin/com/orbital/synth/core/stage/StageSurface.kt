package com.orbital.synth.core.stage

import kotlin.math.abs
import kotlin.math.hypot

/** Output of [StageSurface]; the host forwards these to the one shared audio engine. */
sealed interface SurfaceEvent {
    data class NoteOn(val note: Int, val velocity: Float) : SurfaceEvent
    data class NoteOff(val note: Int) : SurfaceEvent

    /**
     * Raw gesture axes, not synth parameters. The host maps them as offsets around
     * the patch's own settings, so touching never permanently rewrites a sound.
     * [brightness] and [bloom] are -1..1; [motion] and [pressure] are 0..1.
     */
    data class Expression(
        val brightness: Float,
        val bloom: Float,
        val motion: Float,
        val pressure: Float,
    ) : SurfaceEvent

    /** A finger started a lane (initial touch or a slide into a new lane). Visual + guide hook. */
    data class Strike(
        val pointerId: Long,
        val lane: Int,
        val velocity: Float,
        val x: Float,
        val chordSymbol: String,
        val roman: String,
        /** True when this strike came from sliding rather than a fresh touch. */
        val slid: Boolean,
        /** Every lane that now sounds (the touched lane first, then chord tones). */
        val lanes: IntArray = intArrayOf(lane),
    ) : SurfaceEvent

    /** A finger left the screen or was cancelled. */
    data class Release(val pointerId: Long, val lane: Int) : SurfaceEvent
}

/** A read-only view of one finger for the renderer. */
class SurfaceContact(
    val pointerId: Long,
    val lane: Int,
    val x: Float,
    val y: Float,
    val notes: IntArray,
)

/**
 * Deterministic multitouch state machine for the diatonic stage.
 *
 * Guarantees, covered by fuzz tests:
 *  * Every sounding MIDI note is reference counted, so overlapping fingers or
 *    chords never produce a stuck or prematurely released note.
 *  * Every [SurfaceEvent.NoteOn] is balanced by a [SurfaceEvent.NoteOff] once
 *    all contacts end (release, cancel or [cancelAll]).
 *  * A slide links into the next lane by starting the new note BEFORE releasing
 *    the old one, so envelopes overlap and the line stays legato.
 *
 * No Android or Compose dependency; one UI thread must own an instance.
 */
class StageSurface(
    initialGrid: DiatonicGrid,
    private val maxContacts: Int = 10,
) {
    init { require(maxContacts in 1..64) }

    private class Contact(
        var x: Float,
        var y: Float,
        var lane: Int,
        var voicing: ChordVoicing,
        var lastTimeMs: Long,
    )

    private val contacts = LinkedHashMap<Long, Contact>()
    private val refs = IntArray(128)

    var grid: DiatonicGrid = initialGrid
        private set
    var chord: ChordAssist = ChordAssist.OFF
        private set
    /** When true, dragging across lanes slides legato; when false a touch holds its lane. */
    var glide: Boolean = true
        private set
    var pressureSensitivity: Float = 0.5f
        private set

    val activePointerCount: Int get() = contacts.size

    /** Number of distinct MIDI notes currently sounding (for tests and the HUD). */
    val soundingNoteCount: Int get() = refs.count { it > 0 }

    fun contactSnapshot(): List<SurfaceContact> = contacts.map { (id, c) ->
        SurfaceContact(id, c.lane, c.x, c.y, c.voicing.notes)
    }

    fun touchDown(
        pointerId: Long,
        x: Float,
        y: Float,
        pressure: Float = 1f,
        eventTimeMs: Long = 0L,
    ): List<SurfaceEvent> {
        val events = ArrayList<SurfaceEvent>(8)
        val existing = contacts.remove(pointerId)
        if (existing == null && contacts.size >= maxContacts) return events
        if (existing != null) {
            // A repeated DOWN for the same id is a fresh strike: release first.
            releaseVoicing(existing.voicing, events)
            events += SurfaceEvent.Release(pointerId, existing.lane)
        }
        val px = unit(x, 0.5f)
        val py = unit(y, 0.5f)
        val lane = grid.laneForY(py)
        val voicing = ChordVoicer.voice(grid, lane, chord)
        val contact = Contact(px, py, lane, voicing, eventTimeMs)
        contacts[pointerId] = contact
        val velocity = velocity(px, pressure)
        acquireVoicing(voicing, velocity, events)
        events += SurfaceEvent.Strike(
            pointerId, lane, velocity, px, voicing.symbol, voicing.roman, slid = false,
            lanes = voicing.lanes,
        )
        events += expression(px, py, pressure, 0f)
        return events
    }

    fun touchMove(
        pointerId: Long,
        x: Float,
        y: Float,
        pressure: Float = 1f,
        eventTimeMs: Long = 0L,
    ): List<SurfaceEvent> {
        val contact = contacts[pointerId] ?: return emptyList()
        val px = unit(x, contact.x)
        val py = unit(y, contact.y)
        val elapsed = eventTimeMs - contact.lastTimeMs
        val speed = if (elapsed in 1L..1_000L) {
            (hypot(px - contact.x, py - contact.y) * 1_000f / elapsed / 1.7f).coerceIn(0f, 1f)
        } else 0f

        val events = ArrayList<SurfaceEvent>(8)
        if (glide) {
            val position = grid.continuousLane(py)
            // Hysteresis: the finger must travel well into the next lane, which
            // stops boundary jitter from machine-gunning retriggers.
            if (abs(position - contact.lane) > LANE_HYSTERESIS) {
                val next = grid.laneForY(py)
                if (next != contact.lane) {
                    slideTo(contact, next, velocity(px, pressure), pointerId, px, events)
                }
            }
        }
        contact.x = px
        contact.y = py
        contact.lastTimeMs = eventTimeMs
        events += expression(px, py, pressure, speed)
        return events
    }

    fun touchUp(pointerId: Long): List<SurfaceEvent> {
        val contact = contacts.remove(pointerId) ?: return emptyList()
        val events = ArrayList<SurfaceEvent>(6)
        releaseVoicing(contact.voicing, events)
        events += SurfaceEvent.Release(pointerId, contact.lane)
        return events
    }

    /** Gesture cancellation, stage exit or lifecycle loss: release everything exactly once. */
    fun cancelAll(): List<SurfaceEvent> {
        if (contacts.isEmpty()) return emptyList()
        val events = ArrayList<SurfaceEvent>(contacts.size * 4)
        for ((id, contact) in contacts) {
            releaseVoicing(contact.voicing, events)
            events += SurfaceEvent.Release(id, contact.lane)
        }
        contacts.clear()
        return events
    }

    /**
     * Retune the instrument. Fingers that are already down move to the lane under
     * them in the new grid (new notes first, then the old ones, so nothing sticks).
     */
    fun configure(
        grid: DiatonicGrid = this.grid,
        chord: ChordAssist = this.chord,
        glide: Boolean = this.glide,
        pressureSensitivity: Float = this.pressureSensitivity,
    ): List<SurfaceEvent> {
        this.glide = glide
        this.pressureSensitivity =
            if (pressureSensitivity.isFinite()) pressureSensitivity.coerceIn(0f, 1f) else 0.5f
        val gridChanged = grid !== this.grid && !sameLayout(grid, this.grid)
        val chordChanged = chord != this.chord
        this.grid = grid
        this.chord = chord
        if (!gridChanged && !chordChanged) return emptyList()
        val events = ArrayList<SurfaceEvent>(contacts.size * 4)
        for ((id, contact) in contacts) {
            val lane = this.grid.laneForY(contact.y)
            val voicing = ChordVoicer.voice(this.grid, lane, this.chord)
            if (!voicing.notes.contentEquals(contact.voicing.notes)) {
                val velocity = velocity(contact.x, 1f)
                acquireVoicing(voicing, velocity, events)
                releaseVoicing(contact.voicing, events)
                contact.voicing = voicing
                events += SurfaceEvent.Strike(
                    id, lane, velocity, contact.x, voicing.symbol, voicing.roman, slid = true,
                    lanes = voicing.lanes,
                )
            }
            contact.lane = lane
        }
        return events
    }

    private fun sameLayout(a: DiatonicGrid, b: DiatonicGrid): Boolean =
        a.key == b.key && a.lowestOctave == b.lowestOctave && a.octaves == b.octaves

    private fun slideTo(
        contact: Contact,
        lane: Int,
        velocity: Float,
        pointerId: Long,
        x: Float,
        events: MutableList<SurfaceEvent>,
    ) {
        val voicing = ChordVoicer.voice(grid, lane, chord)
        // Start the new note(s) first: the old release tail overlaps the new attack.
        acquireVoicing(voicing, velocity, events)
        releaseVoicing(contact.voicing, events)
        contact.voicing = voicing
        contact.lane = lane
        events += SurfaceEvent.Strike(
            pointerId, lane, velocity, x, voicing.symbol, voicing.roman, slid = true,
            lanes = voicing.lanes,
        )
    }

    private fun acquireVoicing(voicing: ChordVoicing, velocity: Float, out: MutableList<SurfaceEvent>) {
        for ((index, note) in voicing.notes.withIndex()) {
            if (note !in 0..127) continue
            // Retrigger a note another finger still holds so the new strike is audible.
            if (refs[note] > 0) out += SurfaceEvent.NoteOff(note)
            refs[note]++
            val v = if (index == 0) velocity else velocity * ChordVoicer.SUPPORT_VELOCITY
            out += SurfaceEvent.NoteOn(note, v.coerceIn(0.05f, 1f))
        }
    }

    private fun releaseVoicing(voicing: ChordVoicing, out: MutableList<SurfaceEvent>) {
        for (note in voicing.notes) {
            if (note !in 0..127 || refs[note] <= 0) continue
            refs[note]--
            if (refs[note] == 0) out += SurfaceEvent.NoteOff(note)
        }
    }

    /** Touch to the right is struck harder; pressure, when a device reports it, nudges this. */
    private fun velocity(x: Float, pressure: Float): Float {
        val pressureDelta = (finite(pressure, 1f) - 1f).coerceIn(-1f, 0f)
        return (0.52f + 0.40f * x + 0.22f * pressureDelta * pressureSensitivity)
            .coerceIn(0.2f, 1f)
    }

    private fun expression(x: Float, y: Float, pressure: Float, speed: Float): SurfaceEvent.Expression {
        val pressureValue = finite(pressure, 1f).coerceIn(0f, 1f)
        return SurfaceEvent.Expression(
            brightness = (x * 2f - 1f).coerceIn(-1f, 1f),
            bloom = ((1f - y) * 2f - 1f).coerceIn(-1f, 1f),
            motion = speed.coerceIn(0f, 1f),
            pressure = pressureValue,
        )
    }

    private fun finite(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    private fun unit(value: Float, fallback: Float): Float =
        finite(value, fallback).coerceIn(0f, 1f)

    companion object {
        /** Fraction of a lane a finger must pass the centre to commit to the next lane. */
        const val LANE_HYSTERESIS = 0.62f
    }
}

/** Maps raw gesture axes onto synth parameters as offsets around the patch's own values. */
object StageExpressionMap {
    data class Targets(val cutoff: Float, val morph: Float, val movement: Float, val fx: Float)

    fun targets(
        baseCutoff: Float,
        baseMorph: Float,
        baseMovement: Float,
        baseFx: Float,
        gesture: SurfaceEvent.Expression,
    ): Targets = Targets(
        cutoff = clamp(baseCutoff + gesture.brightness * 0.32f),
        morph = clamp(baseMorph + gesture.bloom * 0.28f),
        movement = clamp(baseMovement + gesture.motion * 0.45f),
        fx = clamp(baseFx + gesture.motion * 0.22f),
    )

    private fun clamp(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}
