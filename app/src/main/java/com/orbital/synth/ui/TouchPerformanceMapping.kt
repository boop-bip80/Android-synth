package com.orbital.synth.ui

import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Musical pitch grids for an unmarked, keyboard-free performance surface.
 * The performer can freely choose a scale; no screen-space key graphics are required.
 */
enum class TouchScale(val semitones: IntArray) {
    CHROMATIC(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)),
    MAJOR(intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    NATURAL_MINOR(intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    DORIAN(intArrayOf(0, 2, 3, 5, 7, 9, 10)),
    MINOR_PENTATONIC(intArrayOf(0, 3, 5, 7, 10)),
    BLUES(intArrayOf(0, 3, 5, 6, 7, 10)),
    WHOLE_TONE(intArrayOf(0, 2, 4, 6, 8, 10)),
}

/** The physical axis that selects the pitched voice on the empty performance stage. */
enum class TouchPitchAxis { HORIZONTAL, VERTICAL }

/**
 * A root MIDI note plus a finite set of horizontally selectable scale degrees.
 * Defaults to C4..C6 in the chromatic scale. Out-of-range configuration values
 * are clamped at the mapping boundary; no invalid MIDI note can be produced.
 */
data class TouchPerformanceConfig(
    val rootMidiNote: Int = 60,
    val noteCount: Int = 25,
    val scale: TouchScale = TouchScale.CHROMATIC,
    val glissando: Boolean = true,
    /** 0 disables pressure response; 1 maximizes its *optional* contribution. */
    val pressureSensitivity: Float = 0.5f,
    val pitchAxis: TouchPitchAxis = TouchPitchAxis.HORIZONTAL,
)

/** A sound action for the existing host callbacks; no audio synthesis happens in UI code. */
sealed interface TouchVoiceEvent {
    data class NoteOn(val note: Int, val velocity: Float) : TouchVoiceEvent
    data class NoteOff(val note: Int) : TouchVoiceEvent

    /** Normalized synthesizer parameters; listeners must dispatch these to the live engine. */
    data class Expression(
        val cutoff: Float,
        val morph: Float,
        val movement: Float,
        val fx: Float,
    ) : TouchVoiceEvent
}

/**
 * Deterministic multitouch state machine for a keyboardless XY stage.
 *
 * A contact owns one MIDI note until it is released, cancelled, or intentionally
 * glides to a new note. Every emitted NoteOn has exactly one corresponding
 * NoteOff when all contacts are eventually released. Distinct fingers can hold
 * the same pitch independently. Hardware pressure is OPTIONAL: default 1f means
 * neutral, while Y-position and drag speed always produce expression.
 *
 * This class has no Android/Compose dependency and must be owned by one UI
 * thread. It does not perform any audio rendering or I/O.
 */
class TouchPerformanceMapper(
    initialConfig: TouchPerformanceConfig = TouchPerformanceConfig(),
    private val maxContacts: Int = 10,
) {
    init { require(maxContacts in 1..64) }

    private data class Contact(
        var x: Float,
        var y: Float,
        var noteIndex: Int,
        var note: Int,
        var lastTimeMs: Long,
    )

    private val contacts = LinkedHashMap<Long, Contact>()
    private var config = initialConfig

    val activePointerCount: Int get() = contacts.size
    val currentConfig: TouchPerformanceConfig get() = config

    fun touchDown(
        pointerId: Long,
        x: Float,
        y: Float,
        pressure: Float = 1f,
        eventTimeMs: Long = 0L,
    ): List<TouchVoiceEvent> {
        val before = contacts.remove(pointerId)
        if (before == null && contacts.size >= maxContacts) return emptyList()
        val px = unit(x, 0.5f)
        val py = unit(y, 0.5f)
        val index = indexFor(pitchPosition(px, py))
        val note = noteFor(index)
        contacts[pointerId] = Contact(px, py, index, note, eventTimeMs)
        val actions = ArrayList<TouchVoiceEvent>(if (before == null) 2 else 3)
        if (before != null) actions += TouchVoiceEvent.NoteOff(before.note)
        actions += TouchVoiceEvent.NoteOn(note, velocity(py, pressure))
        actions += expression(px, py, pressure, 0f)
        return actions
    }

    fun touchMove(
        pointerId: Long,
        x: Float,
        y: Float,
        pressure: Float = 1f,
        eventTimeMs: Long = 0L,
    ): List<TouchVoiceEvent> {
        val contact = contacts[pointerId] ?: return emptyList()
        // Unavailable or invalid coordinates retain the previous stable position.
        val px = unit(x, contact.x)
        val py = unit(y, contact.y)
        val elapsed = eventTimeMs - contact.lastTimeMs
        val speed = if (elapsed in 1L..1_000L) {
            (hypot(px - contact.x, py - contact.y) * 1_000f / elapsed / 1.7f)
                .coerceIn(0f, 1f)
        } else {
            0f
        }

        val events = ArrayList<TouchVoiceEvent>(3)
        if (config.glissando) {
            val position = continuousIndex(pitchPosition(px, py))
            // Stay on a note for a small boundary margin to avoid finger-jitter
            // retriggers, but allow purposeful long sweeps to cross many notes.
            if (kotlin.math.abs(position - contact.noteIndex) > 0.62f) {
                val index = indexFor(pitchPosition(px, py))
                val next = noteFor(index)
                if (next != contact.note) {
                    events += TouchVoiceEvent.NoteOff(contact.note)
                    events += TouchVoiceEvent.NoteOn(next, velocity(py, pressure))
                }
                contact.noteIndex = index
                contact.note = next
            }
        }
        contact.x = px
        contact.y = py
        contact.lastTimeMs = eventTimeMs
        events += expression(px, py, pressure, speed)
        return events
    }

    /** Unknown/up-already-released pointer IDs never release another finger's voice. */
    fun touchUp(pointerId: Long): List<TouchVoiceEvent> {
        val contact = contacts.remove(pointerId) ?: return emptyList()
        return listOf(TouchVoiceEvent.NoteOff(contact.note))
    }

    /** Must be called on gesture cancellation, stage exit and activity disposal. */
    fun cancelAll(): List<TouchVoiceEvent> {
        if (contacts.isEmpty()) return emptyList()
        val actions = contacts.values.map { TouchVoiceEvent.NoteOff(it.note) }
        contacts.clear()
        return actions
    }

    /**
     * A scale/root change while notes are held retunes them without lost releases.
     * The returned NoteOff/NoteOn pairs must be dispatched in order.
     */
    fun updateConfig(next: TouchPerformanceConfig): List<TouchVoiceEvent> {
        if (next == config) return emptyList()
        config = next
        val actions = ArrayList<TouchVoiceEvent>(contacts.size * 2)
        for (contact in contacts.values) {
            val index = indexFor(pitchPosition(contact.x, contact.y))
            val newNote = noteFor(index)
            if (newNote != contact.note) {
                actions += TouchVoiceEvent.NoteOff(contact.note)
                actions += TouchVoiceEvent.NoteOn(newNote, velocity(contact.y, 1f))
            }
            contact.noteIndex = index
            contact.note = newNote
        }
        return actions
    }

    private fun pitchPosition(x: Float, y: Float): Float =
        if (config.pitchAxis == TouchPitchAxis.HORIZONTAL) x else 1f - y

    private fun continuousIndex(x: Float): Float =
        x * (config.noteCount.coerceIn(1, 64) - 1)

    private fun indexFor(x: Float): Int =
        continuousIndex(x).roundToInt().coerceIn(0, config.noteCount.coerceIn(1, 64) - 1)

    private fun noteFor(index: Int): Int {
        val notes = config.scale.semitones
        val octaveOffset = index / notes.size
        val interval = notes[index % notes.size]
        return (config.rootMidiNote.coerceIn(0, 127) + octaveOffset * 12 + interval).coerceIn(0, 127)
    }

    private fun velocity(y: Float, pressure: Float): Float {
        // Pressure readings differ wildly between phones. Vertical position is
        // always available; pressure only makes a small optional adjustment.
        val pressureDelta = (finite(pressure, 1f) - 1f).coerceIn(-1f, 1f)
        val sensitivity = finite(config.pressureSensitivity, 0.5f).coerceIn(0f, 1f)
        return (0.58f + 0.28f * (1f - y) + 0.24f * pressureDelta * sensitivity)
            .coerceIn(0.12f, 1f)
    }

    private fun expression(
        x: Float,
        y: Float,
        pressure: Float,
        movementSpeed: Float,
    ): TouchVoiceEvent.Expression {
        val pressureDelta = (finite(pressure, 1f) - 1f).coerceIn(-1f, 1f)
        val pressureSensitivity = finite(config.pressureSensitivity, 0.5f).coerceIn(0f, 1f)
        val filterAxis = if (config.pitchAxis == TouchPitchAxis.HORIZONTAL) y else x
        val pitchAxis = pitchPosition(x, y)
        val brightness = (1f - filterAxis).pow(0.65f)
        return TouchVoiceEvent.Expression(
            cutoff = (0.06f + brightness * 0.92f).coerceIn(0f, 1f),
            morph = (0.08f + pitchAxis * 0.86f + pressureDelta * 0.12f * pressureSensitivity).coerceIn(0f, 1f),
            movement = (0.10f + movementSpeed * 0.58f + (1f - y) * 0.22f)
                .coerceIn(0f, 1f),
            fx = (0.09f + movementSpeed * 0.50f + pressureDelta * 0.24f * pressureSensitivity)
                .coerceIn(0f, 1f),
        )
    }

    private fun finite(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    private fun unit(value: Float, fallback: Float): Float =
        finite(value, fallback).coerceIn(0f, 1f)
}
