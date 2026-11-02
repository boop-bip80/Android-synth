package com.orbital.synth.core.performance

import com.orbital.synth.core.music.NoteEventKind
import com.orbital.synth.core.music.TimedNoteEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceArpeggiatorTest {
    private fun onEvents(events: List<TimedNoteEvent>): List<TimedNoteEvent> =
        events.filter { it.kind == NoteEventKind.NOTE_ON }

    private fun offEvents(events: List<TimedNoteEvent>): List<TimedNoteEvent> =
        events.filter { it.kind == NoteEventKind.NOTE_OFF }

    @Test
    fun ascendingArpeggioUsesAbsoluteSampleSteps() {
        val arp = PerformanceArpeggiator(settings = ArpeggiatorSettings(gate = 0.5))
        assertTrue(arp.noteOn(1, 0, 67, 80))
        assertTrue(arp.noteOn(1, 0, 60, 100))
        assertTrue(arp.noteOn(1, 0, 64, 90))
        arp.start()
        val events = arp.advance(18_000)
        assertEquals(listOf(60, 64, 67), onEvents(events).map { it.midiNote })
        assertEquals(listOf(0L, 6_000L, 12_000L), onEvents(events).map { it.sampleTime })
        assertEquals(listOf(3_000L, 9_000L, 15_000L), offEvents(events).map { it.sampleTime })
    }

    @Test
    fun ratchetsHaveTheirOwnGatesAndRetriggerCleanly() {
        val arp = PerformanceArpeggiator(
            settings = ArpeggiatorSettings(ratchets = 3, gate = 0.5)
        )
        arp.noteOn(1, 0, 60)
        arp.start()
        val events = arp.advance(6_000)
        assertEquals(listOf(0L, 2_000L, 4_000L), onEvents(events).map { it.sampleTime })
        assertEquals(listOf(1_000L, 3_000L, 5_000L), offEvents(events).map { it.sampleTime })
        assertEquals(6, events.size)
    }

    @Test
    fun chordModeRendersAllUniquePitchesWithOneSharedGate() {
        val arp = PerformanceArpeggiator(
            settings = ArpeggiatorSettings(direction = ArpeggiatorDirection.CHORD)
        )
        arp.noteOn(1, 0, 67)
        arp.noteOn(2, 1, 60)
        arp.noteOn(3, 2, 64)
        arp.noteOn(4, 1, 60)
        arp.start()
        val events = arp.advance(1)
        assertEquals(listOf(60, 64, 67), onEvents(events).map { it.midiNote })
        assertTrue(events.all { it.sampleTime == 0L })
    }

    @Test
    fun physicalSourceOwnershipNeverReleasesAnotherMidiPort() {
        val arp = PerformanceArpeggiator()
        arp.noteOn(11, 0, 60)
        arp.noteOn(22, 0, 60)
        arp.start()
        assertEquals(1, onEvents(arp.advance(100)).size)
        assertTrue(arp.noteOff(11, 0, 60).isEmpty())
        assertTrue(arp.noteOff(11, 0, 60).isEmpty())
        val offs = arp.noteOff(22, 0, 60)
        assertEquals(listOf(TimedNoteEvent(100, NoteEventKind.NOTE_OFF, 60)), offs)
        assertTrue(arp.advance(20_000).isEmpty())
    }

    @Test
    fun duplicateInputPressesMustBalanceTheirNoteOffs() {
        val arp = PerformanceArpeggiator()
        arp.noteOn(11, 1, 60)
        arp.noteOn(11, 1, 60)
        arp.start()
        arp.advance(200)
        assertTrue(arp.noteOff(11, 1, 60).isEmpty())
        assertEquals(1, arp.noteOff(11, 1, 60).size)
        assertTrue(arp.noteOff(11, 1, 60).isEmpty())
    }

    @Test
    fun latchRetainsReleasedChordButNewGestureReplacesIt() {
        val arp = PerformanceArpeggiator(
            settings = ArpeggiatorSettings(latch = true, gate = 0.5)
        )
        arp.noteOn(1, 0, 60)
        arp.start()
        arp.advance(100)
        assertTrue(arp.noteOff(1, 0, 60).isEmpty())
        val continuation = arp.advance(6_000)
        assertTrue(onEvents(continuation).any { it.midiNote == 60 && it.sampleTime == 6_000L })

        arp.noteOn(1, 0, 64)
        val changed = arp.advance(12_000)
        assertTrue(onEvents(changed).any { it.midiNote == 64 })
        assertFalse(onEvents(changed).any { it.midiNote == 60 })
        assertTrue(arp.noteOff(1, 0, 64).isEmpty())
        assertTrue(arp.clearLatch().isEmpty())
    }

    @Test
    fun stoppingAndPanicReturnPendingReleasesAndCancelRatchets() {
        val arp = PerformanceArpeggiator(
            settings = ArpeggiatorSettings(ratchets = 8, gate = 1.0)
        )
        arp.noteOn(1, 0, 60)
        arp.start()
        arp.advance(1)
        assertEquals(
            listOf(TimedNoteEvent(1, NoteEventKind.NOTE_OFF, 60)),
            arp.stop()
        )
        assertTrue(arp.advance(48_000).isEmpty())
        arp.start()
        assertEquals(1, onEvents(arp.advance(1)).size)
        assertEquals(1, arp.panic().size)
        arp.start()
        assertTrue(arp.advance(12_000).isEmpty())
    }

    @Test
    fun changingTempoReleasesNotesAndRestartsAtCurrentSample() {
        val arp = PerformanceArpeggiator()
        arp.noteOn(1, 0, 60)
        arp.start()
        arp.advance(100)
        val offs = arp.configure(ArpeggiatorSettings(bpm = 60.0))
        assertEquals(
            listOf(TimedNoteEvent(100, NoteEventKind.NOTE_OFF, 60)), offs
        )
        val events = arp.advance(1)
        assertEquals(listOf(100L), onEvents(events).map { it.sampleTime })
    }

    @Test
    fun randomProbabilityAndSwingAreIndependentOfBufferBoundaries() {
        val settings = ArpeggiatorSettings(
            direction = ArpeggiatorDirection.RANDOM,
            octaves = 2,
            ratchets = 2,
            probability = 0.65,
            swing = 0.75,
            gate = 0.4
        )
        val whole = PerformanceArpeggiator(settings = settings, seed = 82L)
        val blocks = PerformanceArpeggiator(settings = settings, seed = 82L)
        for (arp in listOf(whole, blocks)) {
            arp.noteOn(1, 0, 60)
            arp.noteOn(1, 0, 64)
            arp.noteOn(1, 0, 67)
            arp.start(atSample = 73)
        }
        val expected = whole.advance(96_000)
        val actual = ArrayList<TimedNoteEvent>()
        repeat(48) { actual += blocks.advance(2_000) }
        assertEquals(expected, actual)
        assertEquals(96_073L, whole.cursorSample)
        assertEquals(96_073L, blocks.cursorSample)
    }

    @Test
    fun sourceDisconnectMaintainsRemainingChords() {
        val arp = PerformanceArpeggiator(
            settings = ArpeggiatorSettings(direction = ArpeggiatorDirection.CHORD)
        )
        arp.noteOn(11, 0, 60)
        arp.noteOn(11, 0, 64)
        arp.noteOn(22, 0, 67)
        arp.start()
        arp.advance(100)
        assertTrue(arp.allNotesOff(11).isEmpty())
        val newNotes = onEvents(arp.advance(6_000)).filter { it.sampleTime == 6_000L }
        assertEquals(listOf(67), newNotes.map { it.midiNote })
        assertEquals(1, arp.allNotesOff(22).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSwingIsRejected() {
        ArpeggiatorSettings(swing = Double.NaN)
    }
}
