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
 * MIDI sustain is handled here because the synth's note API is pitch-based and
 * does not expose CC64. Released keys are latched while the pedal is down, then
 * their matching note-offs are delivered when the pedal rises. Channel mode
 * panic (CC120/123), disconnect and close always release both kinds of notes.
 *
 * All input and closure are serialized. A callback racing with close() either
 * completes first and gets its notes released, or is ignored after closure.
 */
internal class MidiPortSession(private val target: MidiMessageListener) : Closeable {
    private val keysDown = IntArray(CHANNEL_COUNT * NOTE_COUNT)
    private val pedalLatched = IntArray(CHANNEL_COUNT * NOTE_COUNT)
    private val sustainDown = BooleanArray(CHANNEL_COUNT)
    private var closed = false

    private val decoder = MidiStreamDecoder(object : MidiMessageListener {
        override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
            val slot = slot(channel, note)
            // A hostile/malfunctioning controller must not make disconnect
            // emit an unbounded number of release callbacks.
            if (keysDown[slot] + pedalLatched[slot] < MAX_HELD_PER_KEY) {
                keysDown[slot]++
                target.onNoteOn(channel, note, velocity)
            }
        }

        override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
            val slot = slot(channel, note)
            if (keysDown[slot] <= 0) return
            keysDown[slot]--
            if (sustainDown[channel]) {
                pedalLatched[slot]++
            } else {
                target.onNoteOff(channel, note, velocity)
            }
        }

        override fun onControlChange(channel: Int, controller: Int, value: Int) {
            when (controller) {
                64 -> {
                    val next = value >= 64
                    if (!next && sustainDown[channel]) releaseLatched(channel)
                    sustainDown[channel] = next
                }
                120, 123 -> releaseChannel(channel)
                121 -> {
                    // Reset All Controllers: physically held keys remain on.
                    sustainDown[channel] = false
                    releaseLatched(channel)
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
        }
    }

    private fun releaseLatched(channel: Int) {
        for (note in 0 until NOTE_COUNT) {
            val slot = slot(channel, note)
            val held = pedalLatched[slot]
            pedalLatched[slot] = 0
            repeat(held) { target.onNoteOff(channel, note, 0) }
        }
    }

    private fun releaseChannel(channel: Int) {
        for (note in 0 until NOTE_COUNT) {
            val slot = slot(channel, note)
            val held = keysDown[slot] + pedalLatched[slot]
            keysDown[slot] = 0
            pedalLatched[slot] = 0
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
