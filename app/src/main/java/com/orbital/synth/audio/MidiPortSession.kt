package com.orbital.synth.audio

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opt-in source identity for MIDI listeners. Legacy listeners still receive
 * their existing callbacks; source-aware listeners receive exactly one
 * source-tagged callback per note event instead. Both onSource methods default
 * to the legacy methods for compatibility during app migrations.
 *
 * sourceId identifies one physical/virtual MIDI OUTPUT PORT connection, not a
 * MIDI channel, and changes whenever that port reconnects. Pair it with
 * channel and pitch for stable voice ownership across ports and retriggers.
 */
interface SourceAwareMidiMessageListener : MidiMessageListener {
    fun onSourceNoteOn(sourceId: Long, channel: Int, note: Int, velocity: Int) {
        onNoteOn(channel, note, velocity)
    }

    fun onSourceNoteOff(sourceId: Long, channel: Int, note: Int, velocity: Int) {
        onNoteOff(channel, note, velocity)
    }
}

/** Pure Kotlin adapter used by both normal event delivery and port cleanup. */
internal class MidiSourceRouter(
    private val sourceId: Long,
    private val target: MidiMessageListener
) : MidiMessageListener {
    init { require(sourceId > 0L) }

    override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
        if (target is SourceAwareMidiMessageListener) {
            target.onSourceNoteOn(sourceId, channel, note, velocity)
        } else target.onNoteOn(channel, note, velocity)
    }

    override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
        if (target is SourceAwareMidiMessageListener) {
            target.onSourceNoteOff(sourceId, channel, note, velocity)
        } else target.onNoteOff(channel, note, velocity)
    }

    override fun onControlChange(channel: Int, controller: Int, value: Int) {
        target.onControlChange(channel, controller, value)
    }

    override fun onPitchBend(channel: Int, value: Int) {
        target.onPitchBend(channel, value)
    }
}

/**
 * A port begins closed to input until its parent device connection is accepted.
 * Closing is irreversible, so a late activation or callback cannot revive a
 * detached source or send phantom notes from a rejected connection.
 */
internal class MidiPortGate {
    private val state = AtomicInteger(0) // 0=pending, 1=active, 2=detached

    val acceptsInput: Boolean get() = state.get() == 1
    fun activate(): Boolean = state.compareAndSet(0, 1)
    fun close() { state.set(2) }
}

/**
 * One MIDI cable/port's note ownership. A removed USB controller must release
 * precisely its own held notes, not trigger a global synth panic that cuts
 * touch, sequencer, or other MIDI sources.
 *
 * MIDI sustain (CC64) and sostenuto (CC66) are handled here because the synth's
 * note API is pitch-based. Sostenuto captures only keys already held when the
 * pedal goes down, while sustain also holds notes struck later. Notes held by
 * both pedals are released only when BOTH pedals are lifted. Channel mode CC120,
 * disconnect and close release all notes immediately; CC123 follows MIDI
 * All Notes Off semantics and respects both pedals.
 *
 * All input and closure are serialized. A callback racing with close() either
 * completes first and gets its notes released, or is ignored after closure.
 */
internal class MidiPortSession(private val target: MidiMessageListener) : Closeable {
    private val keysDown = IntArray(CHANNEL_COUNT * NOTE_COUNT)
    private val pedalLatched = IntArray(CHANNEL_COUNT * NOTE_COUNT)
    private val sostenutoCaptured = IntArray(CHANNEL_COUNT * NOTE_COUNT)
    private val sostenutoLatched = IntArray(CHANNEL_COUNT * NOTE_COUNT)
    private val dualLatched = IntArray(CHANNEL_COUNT * NOTE_COUNT)
    private val sustainDown = BooleanArray(CHANNEL_COUNT)
    private val sostenutoDown = BooleanArray(CHANNEL_COUNT)
    private var closed = false

    private val decoder = MidiStreamDecoder(object : MidiMessageListener {
        override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
            val slot = slot(channel, note)
            // Bound all sounding voices for this pitch, including either pedal.
            // A faulty controller cannot trigger unbounded release work on detach.
            val sounding = keysDown[slot] + pedalLatched[slot] +
                sostenutoLatched[slot] + dualLatched[slot]
            if (sounding < MAX_HELD_PER_KEY) {
                keysDown[slot]++
                target.onNoteOn(channel, note, velocity)
            }
        }

        override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
            releaseOneKey(channel, note, velocity)
        }

        override fun onControlChange(channel: Int, controller: Int, value: Int) {
            when (controller) {
                64 -> {
                    val next = value >= 64
                    if (!next && sustainDown[channel]) releaseSustain(channel)
                    sustainDown[channel] = next
                }
                66 -> {
                    val next = value >= 64
                    if (next && !sostenutoDown[channel]) captureSostenuto(channel)
                    if (!next && sostenutoDown[channel]) releaseSostenuto(channel)
                    sostenutoDown[channel] = next
                }
                120 -> releaseChannel(channel) // All Sound Off: immediate under either pedal
                123 -> releaseHeldKeys(channel) // All Notes Off: obey both hold pedals
                121 -> {
                    // Reset All Controllers: release both pedal latches but retain
                    // all physically held keys, which still require matching offs.
                    sustainDown[channel] = false
                    sostenutoDown[channel] = false
                    releaseAllLatched(channel)
                }
            }
            target.onControlChange(channel, controller, value)
        }

        override fun onPitchBend(channel: Int, value: Int) {
            target.onPitchBend(channel, value)
        }
    })

    @Synchronized
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (!closed) decoder.feed(bytes, offset, length)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        decoder.reset()
        for (channel in 0 until CHANNEL_COUNT) {
            releaseChannel(channel)
            sustainDown[channel] = false
            sostenutoDown[channel] = false
        }
    }

    /**
     * Port note-offs have no per-keypress identity. On overlapping retriggers,
     * match the newest non-captured press first so an older held key can remain
     * protected by sostenuto. Single keypresses retain exact MIDI behavior.
     */
    private fun releaseOneKey(channel: Int, note: Int, velocity: Int) {
        val slot = slot(channel, note)
        if (keysDown[slot] <= 0) return
        val captured = sostenutoDown[channel] &&
            sostenutoCaptured[slot] >= keysDown[slot]
        keysDown[slot]--
        if (captured) sostenutoCaptured[slot]--
        val underSustain = sustainDown[channel]
        when {
            underSustain && captured -> dualLatched[slot]++
            underSustain -> pedalLatched[slot]++
            captured -> sostenutoLatched[slot]++
            else -> target.onNoteOff(channel, note, velocity)
        }
    }

    private fun captureSostenuto(channel: Int) {
        // Only physically down keys are captured on the transition to pedal-down.
        // Sustain-latched notes and notes struck afterwards are excluded.
        for (note in 0 until NOTE_COUNT) {
            val slot = slot(channel, note)
            sostenutoCaptured[slot] = keysDown[slot]
        }
    }

    private fun releaseSustain(channel: Int) {
        for (note in 0 until NOTE_COUNT) {
            val slot = slot(channel, note)
            val free = pedalLatched[slot]
            pedalLatched[slot] = 0
            repeat(free) { target.onNoteOff(channel, note, 0) }

            val shared = dualLatched[slot]
            dualLatched[slot] = 0
            // If sostenuto remains down, the note still belongs to that pedal.
            if (sostenutoDown[channel]) sostenutoLatched[slot] += shared
            else repeat(shared) { target.onNoteOff(channel, note, 0) }
        }
    }

    private fun releaseSostenuto(channel: Int) {
        for (note in 0 until NOTE_COUNT) {
            val slot = slot(channel, note)
            sostenutoCaptured[slot] = 0
            val free = sostenutoLatched[slot]
            sostenutoLatched[slot] = 0
            repeat(free) { target.onNoteOff(channel, note, 0) }

            val shared = dualLatched[slot]
            dualLatched[slot] = 0
            if (sustainDown[channel]) pedalLatched[slot] += shared
            else repeat(shared) { target.onNoteOff(channel, note, 0) }
        }
    }

    private fun releaseAllLatched(channel: Int) {
        for (note in 0 until NOTE_COUNT) {
            val slot = slot(channel, note)
            val held = pedalLatched[slot] + sostenutoLatched[slot] + dualLatched[slot]
            pedalLatched[slot] = 0
            sostenutoLatched[slot] = 0
            dualLatched[slot] = 0
            sostenutoCaptured[slot] = 0
            repeat(held) { target.onNoteOff(channel, note, 0) }
        }
    }

    /**
     * MIDI CC123 releases physically held keys, but held sound remains until
     * the sustain and/or sostenuto pedals actually release. Repeated hardware
     * Note Off after this channel-mode command is safely ignored.
     */
    private fun releaseHeldKeys(channel: Int) {
        for (note in 0 until NOTE_COUNT) {
            val slot = slot(channel, note)
            while (keysDown[slot] > 0) releaseOneKey(channel, note, 0)
        }
    }

    private fun releaseChannel(channel: Int) {
        for (note in 0 until NOTE_COUNT) {
            val slot = slot(channel, note)
            val held = keysDown[slot] + pedalLatched[slot] +
                sostenutoLatched[slot] + dualLatched[slot]
            keysDown[slot] = 0
            pedalLatched[slot] = 0
            sostenutoCaptured[slot] = 0
            sostenutoLatched[slot] = 0
            dualLatched[slot] = 0
            repeat(held) { target.onNoteOff(channel, note, 0) }
        }
    }

    private fun slot(channel: Int, note: Int) = channel * NOTE_COUNT + note

    private companion object {
        const val CHANNEL_COUNT = 16
        const val NOTE_COUNT = 128
        const val MAX_HELD_PER_KEY = 32
    }
}
