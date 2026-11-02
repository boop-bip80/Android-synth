package com.orbital.synth.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MidiStreamDecoderTest {
    private class Spy : MidiMessageListener {
        val events = mutableListOf<String>()
        override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
            events.add("on:$channel:$note:$velocity")
        }
        override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
            events.add("off:$channel:$note:$velocity")
        }
        override fun onControlChange(channel: Int, controller: Int, value: Int) {
            events.add("cc:$channel:$controller:$value")
        }
        override fun onPitchBend(channel: Int, value: Int) {
            events.add("bend:$channel:$value")
        }
    }

    @Test
    fun runningStatusAndZeroVelocityNoteOffSurviveFragmentation() {
        val spy = Spy()
        val decoder = MidiStreamDecoder(spy)
        val message = byteArrayOf(
            0x90.toByte(), 60, 100, 61, 0,
            0xF8.toByte(), 62, 90
        )
        decoder.feed(message, 0, 2)
        decoder.feed(message, 2, 3)
        decoder.feed(message, 5, 3)
        assertEquals(
            listOf("on:0:60:100", "off:0:61:0", "on:0:62:90"),
            spy.events
        )
    }

    @Test
    fun sysexAndSystemCommonNeverBecomeNotes() {
        val spy = Spy()
        val decoder = MidiStreamDecoder(spy)
        decoder.feed(byteArrayOf(
            0x90.toByte(), 60, 40,
            0xF0.toByte(), 0x7D, 0x01, 0x10, 0xF7.toByte(),
            0xF2.toByte(), 0x01, 0x02,
            62, 50,
            0x91.toByte(), 65, 70
        ))
        assertEquals(listOf("on:0:60:40", "on:1:65:70"), spy.events)
    }

    @Test
    fun controlChangeAndFullResolutionPitchBendKeepChannel() {
        val spy = Spy()
        val decoder = MidiStreamDecoder(spy)
        decoder.feed(byteArrayOf(
            0xB2.toByte(), 74, 127,
            71, 2, // running-status second CC
            0xE2.toByte(), 0, 64,
            127, 127
        ))
        assertEquals(
            listOf("cc:2:74:127", "cc:2:71:2", "bend:2:8192", "bend:2:16383"),
            spy.events
        )
    }

    @Test
    fun programAndPressureOneByteMessagesCannotPoisonFollowingNotes() {
        val spy = Spy()
        val decoder = MidiStreamDecoder(spy)
        decoder.feed(byteArrayOf(
            0xC1.toByte(), 42, 43,
            0xD1.toByte(), 78, 79,
            0x91.toByte(), 69, 100,
            0x81.toByte(), 69, 64
        ))
        assertEquals(listOf("on:1:69:100", "off:1:69:64"), spy.events)
    }

    @Test
    fun resetAndInvalidRangesAreHandled() {
        val spy = Spy()
        val decoder = MidiStreamDecoder(spy)
        decoder.feed(byteArrayOf(0x90.toByte(), 60))
        decoder.reset()
        decoder.feed(byteArrayOf(100))
        assertEquals(emptyList<String>(), spy.events)

        try {
            decoder.feed(byteArrayOf(1, 2), 1, 2)
            fail("Expected invalid-range rejection")
        } catch (_: IllegalArgumentException) {
            // The caller cannot make a decoder overrun the provided byte array.
        }
    }

    @Test
    fun interleavedRealTimeByteWithinNoteDoesNotChangeRunningStatus() {
        val spy = Spy()
        val decoder = MidiStreamDecoder(spy)
        decoder.feed(byteArrayOf(
            0x93.toByte(), 55, 0xF8.toByte(), 100,
            56, 0xFE.toByte(), 80
        ))
        assertEquals(listOf("on:3:55:100", "on:3:56:80"), spy.events)
    }
}
