package com.orbital.synth.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic producer/worker scheduling tests. The injected seam runs only
 * after the producer has read its epoch, so the tests reproduce the exact race
 * that a normal stress test would almost never hit reliably.
 */
class PerformanceInputEpochTest {
    private class Harness(
        beforeInputEnqueue: () -> Unit,
    ) {
        val held = mutableSetOf<Triple<Long, Int, Int>>()
        val attacks = mutableListOf<Triple<Long, Int, Int>>()
        val transport = PerformanceArpTransport(
            sampleRate = 48_000,
            renderFrames = { samples, frames ->
                for (frame in 0 until frames) {
                    samples[frame * 2] = held.size.toFloat()
                    samples[frame * 2 + 1] = held.size.toFloat()
                }
            },
            noteOn = { source, channel, note, _ ->
                val key = Triple(source, channel, note)
                attacks += key
                held += key
            },
            noteOff = { source, channel, note ->
                held.remove(Triple(source, channel, note))
            },
            beforeInputEnqueue = beforeInputEnqueue
        )

        fun render(): FloatArray {
            val pcm = FloatArray(192 * 2)
            transport.renderBlock(pcm, FloatArray(pcm.size), 192)
            return pcm
        }
    }

    @Test
    fun noteOnPausedAfterEpochReadCannotReappearAfterFocusRestart() {
        val captured = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val accepted = AtomicBoolean(true)
        val harness = Harness {
            captured.countDown()
            if (!resume.await(5, TimeUnit.SECONDS)) {
                error("producer was not resumed")
            }
        }
        harness.transport.onWorkerStart()
        val producer = Thread {
            try {
                accepted.set(harness.transport.inputNoteOn(17, 0, 60))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        producer.start()
        try {
            assertTrue("producer never captured active epoch", captured.await(5, TimeUnit.SECONDS))
            harness.transport.onWorkerStop()
            harness.transport.onWorkerStart()
        } finally {
            resume.countDown()
            producer.join(5_000)
        }

        assertFalse("producer remained blocked", producer.isAlive)
        assertEquals(null, failure.get())
        assertFalse("stale old-worker input must be rejected", accepted.get())
        assertEquals(0f, harness.render()[0], 0f)
        assertTrue(harness.held.isEmpty())

        // A genuinely fresh key using the new worker epoch is still accepted.
        assertTrue(harness.transport.inputNoteOn(17, 0, 60))
        assertEquals(1f, harness.render()[0], 0f)
        harness.transport.onWorkerStop()
        assertTrue(harness.held.isEmpty())
    }

    @Test
    fun delayedOldNoteOffCannotKillNewWorkerKeyOfSameSourceAndPitch() {
        val blockNextInput = AtomicBoolean(false)
        val captured = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val accepted = AtomicBoolean(true)
        val harness = Harness {
            if (blockNextInput.getAndSet(false)) {
                captured.countDown()
                if (!resume.await(5, TimeUnit.SECONDS)) {
                    error("producer was not resumed")
                }
            }
        }
        harness.transport.onWorkerStart()
        assertTrue(harness.transport.inputNoteOn(23, 3, 69))
        harness.render()
        assertEquals(1, harness.held.size)

        blockNextInput.set(true)
        val producer = Thread {
            try {
                accepted.set(harness.transport.inputNoteOff(23, 3, 69))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        producer.start()
        try {
            assertTrue("old note-off never captured epoch", captured.await(5, TimeUnit.SECONDS))
            harness.transport.onWorkerStop()
            assertTrue(harness.held.isEmpty())
            // A fresh tap can be queued during the replacement worker's start
            // gap; the old producer must not release that new note.
            harness.transport.armInputForPendingWorker()
            assertTrue(harness.transport.inputNoteOn(23, 3, 69))
            harness.transport.onWorkerStart()
            assertEquals(1f, harness.render()[0], 0f)
        } finally {
            resume.countDown()
            producer.join(5_000)
        }

        assertFalse(producer.isAlive)
        assertEquals(null, failure.get())
        assertFalse("old worker's late note-off must be rejected", accepted.get())
        assertEquals(1f, harness.render()[0], 0f)
        assertEquals(setOf(Triple(23L, 3, 69)), harness.held)
        harness.transport.onWorkerStop()
        assertTrue(harness.held.isEmpty())
    }

    @Test
    fun prearmedFirstTouchIsAcceptedAndRenderedExactlyOnceOnWorkerStart() {
        val harness = Harness {}
        val key = Triple(-1L, 0, 64)
        harness.transport.armInputForPendingWorker()

        assertTrue("first finger press must queue before Thread.start()", 
            harness.transport.inputNoteOn(key.first, key.second, key.third))
        assertTrue("no DSP callback may run on the UI producer", harness.held.isEmpty())
        assertTrue(harness.attacks.isEmpty())

        harness.transport.onWorkerStart()
        assertEquals(1f, harness.render()[0], 0f)
        assertEquals("one real NoteOn for the first contact", listOf(key), harness.attacks)
        assertEquals(1f, harness.render()[0], 0f)
        assertEquals("continued audio blocks must not reattack", listOf(key), harness.attacks)

        assertTrue(harness.transport.inputNoteOff(key.first, key.second, key.third))
        assertEquals(0f, harness.render()[0], 0f)
        harness.transport.onWorkerStop()
        assertTrue(harness.held.isEmpty())
    }

    @Test
    fun prearmedInputIsInvalidatedWhenAudioTrackPlayFailsBeforeFirstRender() {
        val harness = Harness {}
        harness.transport.armInputForPendingWorker()
        assertTrue(harness.transport.inputNoteOn(-1L, 0, 60))
        // AudioTrack.play() raises before renderBlock: the worker's finally
        // still runs onWorkerStop() and must discard its startup input epoch.
        harness.transport.onWorkerStop()
        assertFalse(harness.transport.inputNoteOn(-1L, 0, 60))

        harness.transport.armInputForPendingWorker()
        harness.transport.onWorkerStart()
        assertEquals(0f, harness.render()[0], 0f)
        assertTrue(harness.attacks.isEmpty())
        harness.transport.onWorkerStop()
    }

    @Test
    fun rejectedThreadStartCancelsPrearmedNotesAndEveryBoundedQueueReservation() {
        val harness = Harness {}
        harness.transport.armInputForPendingWorker()
        assertTrue(harness.transport.inputNoteOn(-1L, 0, 60))
        assertTrue(harness.transport.inputNoteOn(-1L, 0, 61))
        assertTrue(harness.transport.inputNoteOff(-1L, 0, 61))
        // A nonphysical parameter edit must not own a capacity reservation.
        assertTrue(harness.transport.setTempo(121))
        assertEquals(3, harness.transport.pendingPhysicalInputCount)

        // Thread.start() failed outright, so NO worker exists to call
        // onWorkerStop(). Discard every command while decrementing #142's
        // bounded physical queue reservation for that exact command.
        harness.transport.cancelPendingWorkerInput()
        assertEquals("startup abort must never leak queue reservations",
            0, harness.transport.pendingPhysicalInputCount)
        assertFalse(harness.transport.inputNoteOn(-1L, 0, 60))
        assertTrue(harness.held.isEmpty())
        assertTrue(harness.attacks.isEmpty())

        // The next worker still accepts its very first real physical note,
        // without replaying the aborted note-on and note-off.
        harness.transport.armInputForPendingWorker()
        assertTrue(harness.transport.inputNoteOn(-1L, 0, 62))
        assertEquals(1, harness.transport.pendingPhysicalInputCount)
        harness.transport.onWorkerStart()
        assertEquals(1f, harness.render()[0], 0f)
        assertEquals(0, harness.transport.pendingPhysicalInputCount)
        assertEquals(listOf(Triple(-1L, 0, 62)), harness.attacks)
        harness.transport.onWorkerStop()
        assertTrue(harness.held.isEmpty())
        assertEquals(0, harness.transport.pendingPhysicalInputCount)
    }
}
