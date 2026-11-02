package com.orbital.synth.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun sustainDefersOffEventsPerChannelAndReleasesOnPedalUp() {
        val events = Events()
        val port = MidiPortSession(events)
        port.feed(byteArrayOf(
            0x90.toByte(), 60, 100, 60, 95,
            0x91.toByte(), 62, 90,
            0xB0.toByte(), 64, 127,
            0xB1.toByte(), 64, 127,
            0x80.toByte(), 60, 40, 60, 41,
            0x81.toByte(), 62, 42
        ))
        assertEquals(
            listOf("on:0:60:100", "on:0:60:95", "on:1:62:90"),
            events.notes
        )

        port.feed(byteArrayOf(0xB0.toByte(), 64, 0))
        assertEquals(
            listOf(
                "on:0:60:100", "on:0:60:95", "on:1:62:90",
                "off:0:60:0", "off:0:60:0"
            ),
            events.notes
        )
        port.close()
        assertEquals("off:1:62:0", events.notes.last())
        assertEquals(6, events.notes.size)
    }

    @Test
    fun resetControllersReleasesLatchedNotesButNotKeysStillHeld() {
        val events = Events()
        val port = MidiPortSession(events)
        port.feed(byteArrayOf(
            0x90.toByte(), 65, 105, 66, 100,
            0xB0.toByte(), 64, 127,
            0x80.toByte(), 65, 40,
            0xB0.toByte(), 121, 0
        ))
        assertEquals(
            listOf("on:0:65:105", "on:0:66:100", "off:0:65:0"),
            events.notes
        )
        // Pedal reset has removed the latch; a new release is immediate.
        port.feed(byteArrayOf(0x80.toByte(), 66, 64))
        assertEquals("off:0:66:64", events.notes.last())
        port.close()
        assertEquals(4, events.notes.size)
    }

    @Test
    fun channelPanicAndDisconnectReleaseLatchedAndPhysicallyHeldNotesOnlyOnce() {
        val events = Events()
        val port = MidiPortSession(events)
        port.feed(byteArrayOf(
            0xB2.toByte(), 64, 127,
            0x92.toByte(), 70, 110, 71, 100,
            0x82.toByte(), 70, 30,
            0xB2.toByte(), 120, 0,
            0x82.toByte(), 70, 31, 71, 40
        ))
        assertEquals(
            listOf(
                "on:2:70:110", "on:2:71:100",
                "off:2:70:0", "off:2:71:0"
            ),
            events.notes
        )
        port.close()
        assertEquals(4, events.notes.size)
    }

    @Test
    fun repeatedNoteOffsDoNotDoubleReleaseSustainedNote() {
        val events = Events()
        val port = MidiPortSession(events)
        port.feed(byteArrayOf(
            0x90.toByte(), 60, 127,
            0xB0.toByte(), 64, 127,
            0x80.toByte(), 60, 0, 60, 0,
            0xB0.toByte(), 64, 0, 64, 0
        ))
        port.close()
        assertEquals(listOf("on:0:60:127", "off:0:60:0"), events.notes)
    }

    @Test
    fun heldAndLatchedNotesTogetherRespectPerKeyBound() {
        val events = Events()
        val port = MidiPortSession(events)
        port.feed(byteArrayOf(0xB0.toByte(), 64, 127))
        repeat(80) { port.feed(byteArrayOf(0x90.toByte(), 60, 100, 60, 0)) }
        port.feed(byteArrayOf(0xB0.toByte(), 64, 0))
        port.close()
        assertEquals(32, events.notes.count { it == "on:0:60:100" })
        assertEquals(32, events.notes.count { it == "off:0:60:0" })
    }

    @Test
    fun taggedPortsKeepIdenticalPitchesIndependentAcrossPedalAndDisconnect() {
        val events = mutableListOf<String>()
        val listener = object : SourceAwareMidiMessageListener {
            override fun onSourceNoteOn(sourceId: Long, channel: Int, note: Int, velocity: Int) {
                events += "on:$sourceId:$channel:$note"
            }
            override fun onSourceNoteOff(sourceId: Long, channel: Int, note: Int, velocity: Int) {
                events += "off:$sourceId:$channel:$note"
            }
        }
        val usb = MidiPortSession(MidiSourceRouter(101L, listener))
        val wireless = MidiPortSession(MidiSourceRouter(102L, listener))
        usb.feed(byteArrayOf(0x90.toByte(), 60, 120))
        wireless.feed(byteArrayOf(0x90.toByte(), 60, 100))
        usb.feed(byteArrayOf(0xB0.toByte(), 64, 127, 0x80.toByte(), 60, 0))
        assertEquals(listOf("on:101:0:60", "on:102:0:60"), events)

        // Closing a sustained USB note cannot send a release for the other port.
        usb.close()
        assertEquals(
            listOf("on:101:0:60", "on:102:0:60", "off:101:0:60"),
            events
        )
        wireless.feed(byteArrayOf(0x80.toByte(), 60, 0))
        wireless.close()
        assertEquals("off:102:0:60", events.last())
        assertEquals(4, events.size)
    }

    @Test
    fun sourceAwareDefaultsAndLegacyListenerBothReceiveExactlyOneEvent() {
        val legacy = Events()
        val oldPort = MidiPortSession(MidiSourceRouter(200L, legacy))
        oldPort.feed(byteArrayOf(0x91.toByte(), 61, 120))
        oldPort.close()
        assertEquals(listOf("on:1:61:120", "off:1:61:0"), legacy.notes)

        val fallback = Events()
        val aware = object : SourceAwareMidiMessageListener {
            override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
                fallback.onNoteOn(channel, note, velocity)
            }
            override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
                fallback.onNoteOff(channel, note, velocity)
            }
        }
        val newPort = MidiPortSession(MidiSourceRouter(201L, aware))
        newPort.feed(byteArrayOf(0x92.toByte(), 62, 99))
        newPort.close()
        assertEquals(listOf("on:2:62:99", "off:2:62:0"), fallback.notes)
    }

    @Test
    fun closedGateRejectsLatePacketsAndCannotBeReactivatedByOldAttach() {
        val events = mutableListOf<String>()
        val listener = object : SourceAwareMidiMessageListener {
            override fun onSourceNoteOn(sourceId: Long, channel: Int, note: Int, velocity: Int) {
                events += "on:$sourceId:$note"
            }
            override fun onSourceNoteOff(sourceId: Long, channel: Int, note: Int, velocity: Int) {
                events += "off:$sourceId:$note"
            }
        }
        val oldGate = MidiPortGate()
        val oldPort = MidiPortSession(MidiSourceRouter(301L, listener))
        assertFalse(oldGate.acceptsInput) // no input before connection acceptance
        assertTrue(oldGate.activate())
        if (oldGate.acceptsInput) oldPort.feed(byteArrayOf(0x90.toByte(), 64, 100))
        oldGate.close()
        oldPort.close()
        assertFalse(oldGate.acceptsInput)
        assertFalse(oldGate.activate()) // stale late openDevice callback
        if (oldGate.acceptsInput) oldPort.feed(byteArrayOf(0x90.toByte(), 65, 100))

        val reconnected = MidiPortSession(MidiSourceRouter(302L, listener))
        val newGate = MidiPortGate()
        assertTrue(newGate.activate())
        if (newGate.acceptsInput) reconnected.feed(byteArrayOf(0x90.toByte(), 64, 110))
        reconnected.close()
        assertEquals(
            listOf("on:301:64", "off:301:64", "on:302:64", "off:302:64"),
            events
        )
    }

    @Test
    fun cc123AllNotesOffRespectsSustainAndDoesNotReleaseOtherChannel() {
        val events = Events()
        val port = MidiPortSession(events)
        port.feed(byteArrayOf(
            0x90.toByte(), 60, 101,
            0xB0.toByte(), 64, 127,
            0x80.toByte(), 60, 20, // already latched under sustain
            0x90.toByte(), 61, 110, // still physically held
            0x91.toByte(), 62, 100, // separate MIDI channel
            0xB0.toByte(), 123, 0,  // all notes off, but pedal is down
            0x80.toByte(), 61, 0    // duplicate hardware release must not count
        ))
        assertEquals(
            listOf("on:0:60:101", "on:0:61:110", "on:1:62:100"),
            events.notes
        )
        port.feed(byteArrayOf(0xB0.toByte(), 64, 0))
        assertEquals(
            listOf(
                "on:0:60:101", "on:0:61:110", "on:1:62:100",
                "off:0:60:0", "off:0:61:0"
            ),
            events.notes
        )
        port.close()
        assertEquals("off:1:62:0", events.notes.last())
        assertEquals(6, events.notes.size)
    }

    @Test
    fun cc123AndSamePitchRetriggerUnderPedalDoNotDoubleRelease() {
        val events = Events()
        val port = MidiPortSession(events)
        port.feed(byteArrayOf(
            0xB0.toByte(), 64, 127,
            0x90.toByte(), 60, 100,
            0xB0.toByte(), 123, 0,
            0xB0.toByte(), 123, 0, // idempotent while nothing is held
            0x90.toByte(), 60, 105
        ))
        // The original sustained note remains sounding while the new key is down.
        assertEquals(listOf("on:0:60:100", "on:0:60:105"), events.notes)
        port.feed(byteArrayOf(0xB0.toByte(), 64, 0))
        assertEquals(
            listOf("on:0:60:100", "on:0:60:105", "off:0:60:0"),
            events.notes
        )
        port.feed(byteArrayOf(0x80.toByte(), 60, 35))
        port.close()
        assertEquals(
            listOf(
                "on:0:60:100", "on:0:60:105", "off:0:60:0",
                "off:0:60:35"
            ),
            events.notes
        )
    }

}
