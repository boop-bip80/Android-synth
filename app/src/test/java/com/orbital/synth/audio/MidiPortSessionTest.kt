package com.orbital.synth.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiPortSessionTest {
    private class Events : MidiMessageListener {
        val notes = mutableListOf<String>()
        val controls = mutableListOf<String>()
        override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
            notes.add("on:$channel:$note:$velocity")
        }
        override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
            notes.add("off:$channel:$note:$velocity")
        }
        override fun onControlChange(channel: Int, controller: Int, value: Int) {
            controls.add("$channel:$controller:$value")
        }
    }

    @Test
    fun disconnectReleasesHeldNotesPerChannelWithoutTouchingOtherPort() {
        val events = Events()
        val usb = MidiPortSession(events)
        val bluetooth = MidiPortSession(events)
        usb.feed(byteArrayOf(0x90.toByte(), 60, 110, 60, 90, 0x91.toByte(), 60, 70))
        // Same pitch exists on two independent MIDI inputs at once.
        bluetooth.feed(byteArrayOf(0x90.toByte(), 60, 80))

        usb.close()
        assertEquals(
            listOf(
                "on:0:60:110", "on:0:60:90", "on:1:60:70", "on:0:60:80",
                "off:0:60:0", "off:0:60:0", "off:1:60:0"
            ),
            events.notes
        )
        // A second, still-live port can finish its note normally.
        bluetooth.feed(byteArrayOf(0x80.toByte(), 60, 40))
        assertEquals("off:0:60:40", events.notes.last())
        bluetooth.close()
        assertEquals(8, events.notes.size)
    }

    @Test
    fun closeIsIdempotentAndLatePacketsCannotReviveStuckNotes() {
        val events = Events()
        val port = MidiPortSession(events)
        port.feed(byteArrayOf(0x92.toByte(), 72, 127))
        port.close()
        port.close()
        port.feed(byteArrayOf(0x92.toByte(), 73, 120))
        assertEquals(listOf("on:2:72:127", "off:2:72:0"), events.notes)
    }

    @Test
    fun channelModeAllNotesOffReleasesOnlyMatchingChannelAndAvoidsDuplicate() {
        val events = Events()
        val port = MidiPortSession(events)
        port.feed(byteArrayOf(
            0x90.toByte(), 60, 120, 0x91.toByte(), 62, 110,
            0xB0.toByte(), 123, 0,
            0x80.toByte(), 60, 64 // redundant off after channel-mode release
        ))
        assertEquals(
            listOf("on:0:60:120", "on:1:62:110", "off:0:60:0"),
            events.notes
        )
        assertEquals(listOf("0:123:0"), events.controls)
        port.close()
        assertEquals("off:1:62:0", events.notes.last())
        assertEquals(4, events.notes.size)
    }

    @Test
    fun zeroVelocityOffAndFragmentationUseOwnershipCounts() {
        val events = Events()
        val port = MidiPortSession(events)
        port.feed(byteArrayOf(0x93.toByte(), 67))
        port.feed(byteArrayOf(100, 68, 80, 67, 0))
        // The remaining G# gets released on closing the port.
        port.close()
        assertEquals(
            listOf("on:3:67:100", "on:3:68:80", "off:3:67:0", "off:3:68:0"),
            events.notes
        )
    }
}
