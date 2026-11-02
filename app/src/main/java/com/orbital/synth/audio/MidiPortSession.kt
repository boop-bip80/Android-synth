package com.orbital.synth.audio

import java.io.Closeable

/**
 * One MIDI cable/port's note ownership. A removed USB controller must release
 * precisely its own held notes, not trigger a global synth panic that cuts
 * touch, sequencer, or other MIDI sources.
 *
 * All input and closure are serialized. A callback racing with close() either
 * completes first and gets its notes released, or is ignored after closure.
 */
internal class MidiPortSession(private val target: MidiMessageListener) : Closeable {
    private val notes = IntArray(16 * 128)
    private var closed = false

    private val decoder = MidiStreamDecoder(object : MidiMessageListener {
        override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
            val slot = channel * 128 + note
            // Bound the work a broken controller can force on disconnection.
            // MIDI allows retriggering a pitch; keep a distinct release for each.
            if (notes[slot] < MAX_HELD_PER_KEY) {
                notes[slot]++
                target.onNoteOn(channel, note, velocity)
            }
        }

        override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
            val slot = channel * 128 + note
            if (notes[slot] > 0) {
                notes[slot]--
                target.onNoteOff(channel, note, velocity)
            }
        }

        override fun onControlChange(channel: Int, controller: Int, value: Int) {
            // MIDI channel-mode All Sound Off / All Notes Off. We cannot pass a
            // channel-scoped panic to the synth, but can release owned voices.
            if (controller == 120 || controller == 123) releaseChannel(channel)
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
        for (channel in 0 until 16) releaseChannel(channel)
    }

    private fun releaseChannel(channel: Int) {
        for (note in 0 until 128) {
            val slot = channel * 128 + note
            val held = notes[slot]
            notes[slot] = 0
            repeat(held) { target.onNoteOff(channel, note, 0) }
        }
    }

    private companion object {
        const val MAX_HELD_PER_KEY = 32
    }
}
