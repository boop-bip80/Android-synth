package com.orbital.synth.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Regression for real-time panic and bounded note ingress under hostile MIDI floods. */
class SynthNoteQueueSafetyTest {
    private fun synth(): OrbitalSynth = OrbitalSynth(sampleRate = 8_000, maxVoices = 4).apply {
        setParameter(ParameterId.RELEASE, 0f)
    }

    private fun block(s: OrbitalSynth, frames: Int = 512) {
        s.renderStereo(FloatArray(frames * 2), frames)
    }

    /** White-box logical held leases, not release-envelope tails. */
    private fun heldNotes(s: OrbitalSynth): Int {
        val field = OrbitalSynth::class.java.getDeclaredField("holdActive")
        field.isAccessible = true
        return (field.get(s) as BooleanArray).count { it }
    }

    @Test fun hostileHundredThousandNoteFloodHasFiniteQueueAndNextBlockPanic() {
        val s = synth()
        s.noteOn(54)
        block(s)
        assertEquals(1, heldNotes(s))
        repeat(100_000) { s.noteOn(69) }
        assertTrue("Persistent input queue must remain bounded",
            s.pendingNoteEventCount <= 2_048)
        // The emergency release is worker-owned and applies before draining
        // the first 512 old notes, not 200 FIFO buffers later.
        block(s, 1)
        assertEquals("Overload must panic all old held leases in NEXT buffer", 0, heldNotes(s))
        repeat(6) { block(s) }
        assertEquals(0, s.pendingNoteEventCount)
        assertEquals("Never reattack stale queued note-ons after overload", 0, heldNotes(s))
    }

    @Test fun freshNoteAfterOverloadReleaseSurvivesOldStaleQueuedOffs() {
        val s = synth()
        val source = 44L
        s.noteOn(source, 2, 60)
        block(s)
        assertEquals(1, heldNotes(s))
        // Old OFF is deliberately buried behind the saturated old generation.
        repeat(2_047) { s.noteOn(source, 2, 66) }
        s.noteOff(source, 2, 60) // 2,048 pending note reservations
        s.noteOn(source, 2, 66) // 2,049th triggers emergency global release
        block(s, 1)
        assertEquals(0, heldNotes(s))
        // First worker block opened a new input generation and retired 512
        // old events. A fresh same-source, same-pitch note must remain playable.
        s.noteOn(source, 2, 60)
        repeat(5) { block(s) }
        assertEquals("Stale old-epoch OFF may not kill a fresh same-pitch note",
            1, heldNotes(s))
        assertEquals(0, s.pendingNoteEventCount)
        s.noteOff(source, 2, 60)
        block(s)
        assertEquals(0, heldNotes(s))
    }

    @Test fun manualPanicKeepsAnImmediatelyFollowingFreshSamePitchNote() {
        val s = synth()
        s.noteOn(81L, 0, 64)
        block(s)
        s.noteOff(81L, 0, 64) // This OFF becomes stale at the panic.
        s.allNotesOff()
        s.noteOn(81L, 0, 64) // Fresh attack must be accepted before worker wakes.
        block(s, 1)
        assertEquals(1, heldNotes(s))
        s.noteOff(81L, 0, 64)
        block(s, 1)
        assertEquals(0, heldNotes(s))
        assertEquals(0, s.pendingNoteEventCount)
    }

    @Test fun sourceReleaseAndFxMoveTicketArePreservedBelowAndDuringFlood() {
        val s = synth()
        s.noteOn(101L, 3, 60)
        s.noteOn(202L, 4, 67)
        block(s)
        assertEquals(2, heldNotes(s))
        s.allNotesOff(101L)
        block(s)
        assertEquals("Source 101 must not silence source 202 below overload", 1, heldNotes(s))
        val ticket = s.requestFxSlotMove(0, 1)
        assertTrue(ticket > 0)
        repeat(5_000) { s.noteOn(72) }
        repeat(6) { block(s) }
        assertEquals("Worker-owned FX reorders remain acknowledged despite note panic",
            ticket, s.lastAppliedFxMoveTicket)
        assertEquals(0, s.pendingNoteEventCount)
        assertEquals(0, heldNotes(s)) // deliberate overload may silence all sources
    }

    @Test fun eightMpscFloodProducersAndConcurrentPanicNeverLeakReservations() {
        val s = synth()
        val start = CountDownLatch(1)
        val firstFailure = AtomicReference<Throwable?>()
        val workers = List(8) { threadIndex ->
            thread(isDaemon = true) {
                try {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    repeat(12_500) { i ->
                        val source = threadIndex.toLong() + 10L
                        val note = 48 + (i % 24)
                        if ((i and 1) == 0) s.noteOn(source, threadIndex, note)
                        else s.noteOff(source, threadIndex, note)
                        if (i % 1_001 == 0) s.allNotesOff()
                    }
                } catch (failure: Throwable) {
                    firstFailure.compareAndSet(null, failure)
                }
            }
        }
        start.countDown()
        for (worker in workers) worker.join(20_000)
        firstFailure.get()?.let { throw AssertionError("Concurrent producer failed", it) }
        assertTrue("Timed-out flood producer", workers.none { it.isAlive })
        assertTrue("CAS reservations may never exceed cap", s.pendingNoteEventCount in 0..2_048)
        repeat(8) { block(s) }
        assertEquals("All enqueued or canceled reservations must be returned",
            0, s.pendingNoteEventCount)
        assertEquals(0, heldNotes(s))
        s.noteOn(99L, 0, 72)
        block(s)
        assertEquals("Fresh note must still work after panic storm", 1, heldNotes(s))
        s.noteOff(99L, 0, 72)
        block(s)
        assertEquals(0, heldNotes(s))
    }

    @Test fun activeRendererAndFourMpscProducersCannotUnderflowCapacityOrLosePanic() {
        val s = synth()
        val begin = CountDownLatch(1)
        val producersDone = CountDownLatch(4)
        val failure = AtomicReference<Throwable?>()
        val renderer = thread(isDaemon = true, name = "core-audio-safety-render") {
            try {
                assertTrue(begin.await(10, TimeUnit.SECONDS))
                val pcm = FloatArray(128 * 2)
                var blocks = 0
                while ((producersDone.count > 0L || s.pendingNoteEventCount > 0) &&
                    blocks++ < 4_000
                ) {
                    s.renderStereo(pcm, 128)
                    val count = s.pendingNoteEventCount
                    if (count !in 0..2_048) throw AssertionError(
                        "Note reservations outside [0,2048] while rendering: $count")
                }
            } catch (problem: Throwable) {
                failure.compareAndSet(null, problem)
            }
        }
        val producers = List(4) { index ->
            thread(isDaemon = true) {
                try {
                    assertTrue(begin.await(10, TimeUnit.SECONDS))
                    repeat(12_500) { tick ->
                        val note = 48 + ((tick + index) % 24)
                        val source = (100 + index).toLong()
                        if ((tick and 1) == 0) s.noteOn(source, index, note)
                        else s.noteOff(source, index, note)
                        if (tick % 331 == 0) s.allNotesOff()
                    }
                } catch (problem: Throwable) {
                    failure.compareAndSet(null, problem)
                } finally {
                    producersDone.countDown()
                }
            }
        }
        begin.countDown()
        for (producer in producers) producer.join(20_000)
        renderer.join(20_000)
        failure.get()?.let { throw AssertionError("Live core queue race failed", it) }
        assertTrue("Timed-out producer", producers.none { it.isAlive })
        assertFalse("Timed-out single audio render worker", renderer.isAlive)
        s.allNotesOff()
        repeat(8) { block(s) }
        assertEquals("Every CAS reservation must be discharged", 0, s.pendingNoteEventCount)
        assertEquals("Final global panic must release every source", 0, heldNotes(s))
    }

    @Test fun normalSourceSpecificReleaseDoesNotAdvanceGlobalPanicEpoch() {
        val s = synth()
        s.noteOn(7L, 0, 60)
        s.noteOn(8L, 0, 60)
        block(s)
        assertEquals(2, heldNotes(s))
        s.allNotesOff(7L)
        block(s)
        assertEquals(1, heldNotes(s))
        s.noteOff(8L, 0, 60)
        block(s)
        assertEquals(0, heldNotes(s))
    }
}
