package com.orbital.synth.core.music

import com.orbital.synth.core.performance.ArpeggiatorDirection
import com.orbital.synth.core.performance.ArpeggiatorSettings
import com.orbital.synth.core.performance.PerformanceArpeggiator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/** Exact event ordering and no-heap scheduler transport tests. */
class RealtimeTimedNoteSinkTest {
    private class Recorder : TimedNoteSink {
        val events = ArrayList<TimedNoteEvent>()
        override fun emit(sampleTime: Long, kind: NoteEventKind, midiNote: Int, velocity: Int) {
            events.add(TimedNoteEvent(sampleTime, kind, midiNote, velocity))
        }
    }

    private class CountingSink : TimedNoteSink {
        var count = 0
        override fun emit(sampleTime: Long, kind: NoteEventKind, midiNote: Int, velocity: Int) {
            count++
        }
    }

    @Test fun stepHalfOpenBoundaryAndSimultaneousOffBeforeOnAreExact() {
        val seq = StepSequencer(
            StepPattern(listOf(
                SequencerStep(listOf(SequencerNote(60, gate = 0.5),
                    SequencerNote(64, gate = 1.0))),
                SequencerStep(listOf(SequencerNote(60, gate = 0.5))),
                SequencerStep()
            )),
            sampleRate = 8_000, bpm = 300.0
        )
        val sink = Recorder()
        seq.start()
        seq.advanceInto(400, sink)
        assertEquals(listOf(
            TimedNoteEvent(0, NoteEventKind.NOTE_ON, 60, 100),
            TimedNoteEvent(0, NoteEventKind.NOTE_ON, 64, 100),
            TimedNoteEvent(200, NoteEventKind.NOTE_OFF, 60),
        ), sink.events)
        seq.advanceInto(401, sink)
        assertEquals(listOf(
            TimedNoteEvent(400, NoteEventKind.NOTE_OFF, 64),
            TimedNoteEvent(400, NoteEventKind.NOTE_ON, 60, 100),
            TimedNoteEvent(600, NoteEventKind.NOTE_OFF, 60),
        ), sink.events.drop(3))
        assertEquals(801L, seq.cursorSample)
        assertTrue(seq.stop().isEmpty())
    }

    @Test fun stepStreamingMatchesLegacyListApiThroughProbabilityTiesSwingAndSplits() {
        val notes = List(8) { step ->
            SequencerStep(listOf(
                SequencerNote(48 + step, velocity = 70 + step, gate = 0.64,
                    probability = 0.31 + step * 0.07),
                SequencerNote(60, gate = 0.8, probability = 0.8, tie = step % 3 == 1),
            ))
        }
        val pattern = StepPattern(notes, subdivisionsPerBeat = 8)
        val list = StepSequencer(pattern, sampleRate = 8_000, bpm = 267.0,
            swing = 0.76, seed = 777)
        val streaming = StepSequencer(pattern, sampleRate = 8_000, bpm = 267.0,
            swing = 0.76, seed = 777)
        list.start(atSample = 137)
        streaming.start(atSample = 137)
        val sink = Recorder()
        val expected = ArrayList<TimedNoteEvent>()
        val chunks = intArrayOf(1, 192, 17, 512, 4096, 7, 300, 256)
        repeat(50) { iteration ->
            val frames = chunks[iteration % chunks.size]
            expected.addAll(list.advance(frames))
            streaming.advanceInto(frames, sink)
        }
        assertEquals(expected, sink.events)
        assertEquals(list.cursorSample, streaming.cursorSample)
        assertEquals(list.stop(), streaming.stop())
    }

    @Test fun sixteenNoteChordReleasesInAscendingPitchOrderWithoutOverflowOrStuckNotes() {
        val chord = SequencerStep((48..63).map { SequencerNote(it, gate = 1.0) })
        val seq = StepSequencer(StepPattern(listOf(chord)), sampleRate = 8_000, bpm = 300.0)
        val sink = Recorder()
        seq.start()
        seq.advanceInto(401, sink)
        assertEquals(48, sink.events.size)
        assertEquals((48..63).toList(), sink.events.take(16).map { it.midiNote })
        assertEquals((48..63).toList(), sink.events.drop(16).take(16).map { it.midiNote })
        assertTrue(sink.events.drop(16).take(16).all {
            it.sampleTime == 400L && it.kind == NoteEventKind.NOTE_OFF
        })
        assertEquals((48..63).toList(), seq.stop().map { it.midiNote })
        assertTrue(seq.advance(10_000).isEmpty())
    }

    @Test fun arpeggiatorRatchetReleaseAndRetriggerOrderIsSampleExact() {
        val arp = PerformanceArpeggiator(
            sampleRate = 48_000,
            settings = ArpeggiatorSettings(ratchets = 3, gate = 1.0)
        )
        val sink = Recorder()
        assertTrue(arp.noteOn(9, 0, 60, 103))
        arp.start()
        arp.advanceInto(6_001, sink)
        assertEquals(listOf(
            TimedNoteEvent(0, NoteEventKind.NOTE_ON, 60, 103),
            TimedNoteEvent(2_000, NoteEventKind.NOTE_OFF, 60),
            TimedNoteEvent(2_000, NoteEventKind.NOTE_ON, 60, 103),
            TimedNoteEvent(4_000, NoteEventKind.NOTE_OFF, 60),
            TimedNoteEvent(4_000, NoteEventKind.NOTE_ON, 60, 103),
            TimedNoteEvent(6_000, NoteEventKind.NOTE_OFF, 60),
            TimedNoteEvent(6_000, NoteEventKind.NOTE_ON, 60, 103),
        ), sink.events)
        assertEquals(listOf(TimedNoteEvent(6_001, NoteEventKind.NOTE_OFF, 60)),
            arp.stop())
    }

    @Test fun arpeggiatorStreamingMatchesListThroughChordRatchetsProbabilityAndLatch() {
        val settings = ArpeggiatorSettings(
            bpm = 276.0, subdivisionsPerBeat = 16, swing = 0.43,
            direction = ArpeggiatorDirection.CHORD, octaves = 2,
            ratchets = 8, gate = 0.48, probability = 0.71, latch = true
        )
        val list = PerformanceArpeggiator(sampleRate = 8_000, settings = settings, seed = 114)
        val streaming = PerformanceArpeggiator(sampleRate = 8_000, settings = settings, seed = 114)
        for (arp in listOf(list, streaming)) {
            assertTrue(arp.noteOn(11, 0, 60, 90))
            assertTrue(arp.noteOn(22, 1, 64, 94))
            assertTrue(arp.noteOn(22, 1, 67, 101))
            arp.start(atSample = 137)
        }
        val sink = Recorder()
        val expected = ArrayList<TimedNoteEvent>()
        val chunks = intArrayOf(1, 17, 192, 255, 512, 4_096)
        repeat(70) { iteration ->
            if (iteration == 30) {
                assertEquals(list.noteOff(11, 0, 60), streaming.noteOff(11, 0, 60))
            }
            val frames = chunks[iteration % chunks.size]
            expected.addAll(list.advance(frames))
            streaming.advanceInto(frames, sink)
        }
        assertEquals(expected, sink.events)
        assertEquals(list.cursorSample, streaming.cursorSample)
        assertEquals(list.panic(), streaming.panic())
    }

    @Test fun largeStreamingStepBlockAndNoInputArpDoNotBuildUnboundedLists() {
        val seq = StepSequencer(
            StepPattern(listOf(SequencerStep(listOf(SequencerNote(69, gate = 0.5))))),
            sampleRate = 8_000, bpm = 300.0
        )
        val sink = CountingSink()
        seq.start()
        seq.advanceInto(2_000_000, sink)
        assertTrue("Expected an event stream longer than any fixed 4K buffer", sink.count > 9_000)
        assertEquals(2_000_000L, seq.cursorSample)
        seq.stop()
        val arp = PerformanceArpeggiator()
        arp.start()
        arp.advanceInto(192, sink)
        assertEquals(192L, arp.cursorSample)
    }

    /**
     * HotSpot's per-thread byte counter checks the actual steady-state JVM path.
     * Only scheduler ticks + a preconstructed primitive sink happen inside the
     * measured interval; fixture setup and assertions are outside it.
     */
    @Test fun steadyStateSchedulersAllocateZeroJavaHeapBytesOnWorkerThread() {
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue("HotSpot allocation counting is required", mx.isThreadAllocatedMemorySupported)
        if (!mx.isThreadAllocatedMemoryEnabled) mx.isThreadAllocatedMemoryEnabled = true
        val step = StepSequencer(
            StepPattern(listOf(SequencerStep(listOf(SequencerNote(60))))),
            sampleRate = 48_000, bpm = 150.0
        )
        val arp = PerformanceArpeggiator(
            sampleRate = 48_000,
            settings = ArpeggiatorSettings(ratchets = 8, gate = 0.5)
        )
        val sink = CountingSink()
        step.start()
        arp.noteOn(1, 0, 64)
        arp.start()
        repeat(10_000) {
            step.advanceInto(192, sink)
            arp.advanceInto(192, sink)
        }
        val threadId = Thread.currentThread().id
        val before = mx.getThreadAllocatedBytes(threadId)
        repeat(15_000) {
            step.advanceInto(192, sink)
            arp.advanceInto(192, sink)
        }
        val allocated = mx.getThreadAllocatedBytes(threadId) - before
        assertEquals("Schedulers must not allocate even with active STEP and ratchets",
            0L, allocated)
        assertTrue("Warmup/measurement must exercise emitted notes", sink.count > 1_000)
    }
}
