package com.orbital.synth.audio

/**
 * MIDI 1.0 channel-voice messages exposed with original channel numbers (0..15).
 * Callbacks execute on the MIDI receiver thread and must return promptly.
 */
interface MidiMessageListener {
    fun onNoteOn(channel: Int, note: Int, velocity: Int) {}
    fun onNoteOff(channel: Int, note: Int, velocity: Int) {}
    fun onControlChange(channel: Int, controller: Int, value: Int) {}
    fun onPitchBend(channel: Int, value: Int) {} // 0..16383, center 8192
}

/**
 * Incremental MIDI 1.0 stream decoder. Handles running status, fragmented packets,
 * zero-velocity note-off, SysEx and interleaved system real-time messages.
 *
 * Instances are stateful; create one per physical/virtual input port.
 */
class MidiStreamDecoder(private val listener: MidiMessageListener) {
    private var runningStatus = -1
    private var activeStatus = -1
    private var firstData = -1
    private var systemDataRemaining = 0
    private var sysex = false

    @Synchronized
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= data.size && length <= data.size - offset)
        for (index in offset until offset + length) {
            val value = data[index].toInt() and 0xFF
            if (value >= 0xF8) continue // real-time messages never disturb running status

            if (sysex) {
                if (value == 0xF7) {
                    sysex = false
                    continue
                }
                if (value < 0x80) continue
                sysex = false // recover from a truncated SysEx on an unexpected status
            }

            if (value >= 0x80) {
                onStatus(value)
                continue
            }

            if (systemDataRemaining > 0) {
                systemDataRemaining--
                continue
            }
            if (activeStatus < 0) activeStatus = runningStatus
            if (activeStatus < 0) continue

            val type = activeStatus and 0xF0
            if (type == 0xC0 || type == 0xD0) {
                // Program change / channel pressure are single-byte events; not mapped here.
                firstData = -1
            } else if (firstData < 0) {
                firstData = value
            } else {
                dispatch(activeStatus, firstData, value)
                firstData = -1
            }
        }
    }

    @Synchronized
    fun reset() {
        runningStatus = -1
        activeStatus = -1
        firstData = -1
        systemDataRemaining = 0
        sysex = false
    }

    private fun onStatus(status: Int) {
        firstData = -1
        if (status in 0x80..0xEF) {
            runningStatus = status
            activeStatus = status
            systemDataRemaining = 0
            return
        }

        runningStatus = -1
        activeStatus = -1
        systemDataRemaining = when (status) {
            0xF1, 0xF3 -> 1
            0xF2 -> 2
            else -> 0
        }
        if (status == 0xF0) sysex = true
    }

    private fun dispatch(status: Int, data1: Int, data2: Int) {
        val channel = status and 0x0F
        when (status and 0xF0) {
            0x80 -> listener.onNoteOff(channel, data1, data2)
            0x90 -> {
                if (data2 == 0) listener.onNoteOff(channel, data1, 0)
                else listener.onNoteOn(channel, data1, data2)
            }
            0xB0 -> listener.onControlChange(channel, data1, data2)
            0xE0 -> listener.onPitchBend(channel, (data2 shl 7) or data1)
        }
    }
}
