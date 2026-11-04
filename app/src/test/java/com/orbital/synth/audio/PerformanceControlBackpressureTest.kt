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
 * A noisy touch/XY controller may publish far more edits than the 512-command
 * worker drain can consume. Notes and panic must never wait behind those edits.
 *
 * No test uses a timer as proof of a race: hooks/latches target exact handoffs.
 */
class PerformanceControlBackpressureTest {
    private class Harness(
        capacity: Int = 32,
        beforeControlEnqueue: (() -> Unit)? = null,
        beforeOverflowSnapshot: (() -> Unit)? = null,
    ) {
        val held = LinkedHashMap<Triple<Long, Int, Int>, Int>()
        val attacks = ArrayList<Triple<Long, Int, Int>>()
        val releases = ArrayList<Triple<Long, Int, Int>>()
        val transport = PerformanceArpTransport(
            sampleRate = 48_000,
            renderFrames = { buffer, frames ->
                val amplitude = held.values.sum().toFloat()
                for (i in 0 until frames * 2) buffer[i] = amplitude
            },
            noteOn = { source, channel, note, _ ->
                val key = Triple(source, channel, note)
                attacks += key
                held[key] = (held[key] ?: 0) + 1
            },
            noteOff = { source, channel, note ->
                val key = Triple(source, channel, note)
                releases += key
                val count = held[key] ?: 0
                if (count <= 1) held.remove(key) else held[key] = count - 1
            },
            maxPendingControlCommands = capacity,
            beforeControlEnqueue = beforeControlEnqueue,
            beforeOverflowSnapshot = beforeOverflowSnapshot,
        )

        fun render(frames: Int = 192): FloatArray {
            val pcm = FloatArray(frames * 2)
            transport.renderBlock(pcm, FloatArray(pcm.size), frames)
            return pcm
        }
    }

    @Test
    fun oneHundredThousandSettingsEditsCannotDelayPhysicalNoteOff() {
        val h = Harness()
        h.transport.onWorkerStart()
        assertTrue(h.transport.inputNoteOn(4L, 3, 60))
        h.render(1)
        assertEquals(1, h.held.values.sum())

        repeat(100_000) { i ->
            assertTrue(h.transport.setTempo(20 + (i % 281)))
            if (i % 3 == 0) assertTrue(h.transport.setSwing((i % 101) / 100.0))
            if (i % 7 == 0) assertTrue(h.transport.setGate(0.05 + (i % 96) / 100.0))
        }
        assertTrue("only the bounded control lane may occupy the queue",
            h.transport.pendingControlCommandCount in 0..32)
        assertTrue(h.transport.inputNoteOff(4L, 3, 60))
        val nextBlock = h.render(192)
        assertEquals("NoteOff must be dispatched in the immediately following block",
            0f, nextBlock[0], 0f)
        assertTrue("a control flood must not leave a held voice", h.held.isEmpty())
        assertEquals(0, h.transport.pendingPhysicalInputCount)
        assertEquals(0, h.transport.pendingControlCommandCount)
        assertEquals(h.transport.requestedSettingsControlRevision,
            h.transport.appliedSettingsControlRevision)
        assertTrue("the overflow request must have been applied at the worker boundary",
            h.transport.appliedSettingsControlRevision > 100_000L)
    }

    @Test
    fun oneHundredThousandEnableTogglesCannotStarveNoteOffOrRearmOldVoices() {
        val h = Harness(capacity = 8)
        h.transport.onWorkerStart()
        assertTrue(h.transport.inputNoteOn(90L, 0, 64))
        h.render(1)
        assertEquals(1, h.held.size)

        repeat(100_000) { i -> h.transport.setEnabled(i % 2 == 0) }
        // The final request is false, so normal direct-play ownership survives.
        assertFalse(h.transport.enabled)
        assertTrue(h.transport.pendingControlCommandCount <= 8)
        assertTrue(h.transport.inputNoteOff(90L, 0, 64))
        assertEquals(0f, h.render(1)[0], 0f)
        assertTrue(h.held.isEmpty())
        assertEquals(0, h.transport.pendingControlCommandCount)
        assertEquals(h.transport.requestedEnabledControlRevision,
            h.transport.appliedEnabledControlRevision)
        assertTrue(h.transport.inputNoteOn(90L, 0, 67))
        assertEquals(1f, h.render(1)[0], 0f)
        assertTrue(h.transport.inputNoteOff(90L, 0, 67))
        assertEquals(0f, h.render(1)[0], 0f)
    }

    @Test
    fun panicClosesInputEpochImmediatelyEvenWithControlQueueSaturated() {
        val h = Harness(capacity = 4)
        h.transport.onWorkerStart()
        assertTrue(h.transport.inputNoteOn(8L, 1, 70))
        assertEquals(1f, h.render(1)[0], 0f)

        repeat(100_000) { i -> h.transport.setEnabled(i % 2 == 0) }
        assertTrue(h.transport.pendingControlCommandCount <= 4)
        h.transport.panic()
        assertFalse("no new NoteOn may enter an epoch closed by panic",
            h.transport.inputNoteOn(8L, 1, 72))
        assertEquals(0f, h.render(1)[0], 0f)
        assertTrue(h.held.isEmpty())
        assertEquals(0, h.transport.pendingControlCommandCount)

        // The worker must reopen a fresh epoch after the emergency release.
        assertTrue(h.transport.inputNoteOn(8L, 1, 75))
        assertEquals(1f, h.render(1)[0], 0f)
        assertTrue(h.transport.inputNoteOff(8L, 1, 75))
        assertEquals(0f, h.render(1)[0], 0f)
    }

    @Test
    fun normalInterleavedControlsAndNotesRemainBitExactToLargeFifoCapacity() {
        fun run(cap: Int): Pair<List<FloatArray>, Pair<List<Triple<Long, Int, Int>>, List<Triple<Long, Int, Int>>>> {
            val h = Harness(capacity = cap)
            val pcm = ArrayList<FloatArray>()
            h.transport.onWorkerStart()
            h.transport.inputNoteOn(7L, 2, 60)
            pcm += h.render(192)
            h.transport.setEnabled(true)
            h.transport.setTempo(171)
            h.transport.inputNoteOn(7L, 2, 64)
            pcm += h.render(1_024)
            h.transport.setRate(8)
            h.transport.setGate(0.52)
            h.transport.inputNoteOff(7L, 2, 60)
            pcm += h.render(7_000)
            h.transport.setEnabled(false)
            h.transport.inputNoteOff(7L, 2, 64)
            pcm += h.render(192)
            h.transport.onWorkerStop()
            return pcm to (h.attacks.toList() to h.releases.toList())
        }

        val legacyCapacity = run(100_000)
        val bounded = run(32)
        assertEquals(legacyCapacity.first.size, bounded.first.size)
        for (i in legacyCapacity.first.indices) {
            assertTrue("ordinary FIFO controls changed actual stereo sample bits at block " + i,
                legacyCapacity.first[i].contentEquals(bounded.first[i]))
        }
        assertEquals("source/channel/midi attacks changed", legacyCapacity.second.first,
            bounded.second.first)
        assertEquals("source/channel/midi releases changed", legacyCapacity.second.second,
            bounded.second.second)
    }

    @Test
    fun delayedControlProducerFromOldWorkerCannotResetNewWorkerPhrase() {
        val pauseNext = AtomicBoolean(false)
        val producerPaused = CountDownLatch(1)
        val resumeProducer = CountDownLatch(1)
        val producerFailure = AtomicReference<Throwable?>()
        val h = Harness(beforeControlEnqueue = {
            if (pauseNext.getAndSet(false)) {
                producerPaused.countDown()
                if (!resumeProducer.await(5, TimeUnit.SECONDS)) {
                    error("old-worker control producer was not resumed")
                }
            }
        })
        h.transport.setEnabled(true)
        h.transport.onWorkerStart()
        pauseNext.set(true)
        val producer = Thread {
            try { assertTrue(h.transport.setTempo(280)) }
            catch (error: Throwable) { producerFailure.set(error) }
        }
        producer.start()

        try {
            assertTrue(producerPaused.await(5, TimeUnit.SECONDS))
            // Desired 280 BPM is already published but its queue command is not.
            h.transport.onWorkerStop()
            h.transport.onWorkerStart()
            assertTrue(h.transport.inputNoteOn(3L, 0, 60))
            assertEquals(1f, h.render(1)[0], 0f)
        } finally {
            resumeProducer.countDown()
            producer.join(5_000)
        }
        assertFalse(producer.isAlive)
        assertEquals(null, producerFailure.get())
        assertEquals(280, h.transport.tempo)
        assertEquals("restarted worker configured latest version before stale offer",
            h.transport.requestedSettingsControlRevision,
            h.transport.appliedSettingsControlRevision)
        val releasesBefore = h.releases.size
        assertEquals(1f, h.render(1)[0], 0f)
        assertEquals("late old-worker control must not reset an active generated note",
            releasesBefore, h.releases.size)
        h.transport.panic()
        h.render(1)
        assertTrue(h.held.isEmpty())
    }

    @Test
    fun producerRacingOverflowSnapshotCannotLoseNewestRevision() {
        val workerAtSnapshot = CountDownLatch(1)
        val resumeWorker = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        val h = Harness(capacity = 1, beforeOverflowSnapshot = {
            workerAtSnapshot.countDown()
            if (!resumeWorker.await(5, TimeUnit.SECONDS)) {
                error("overflow worker did not resume")
            }
        })
        h.transport.onWorkerStart()
        assertTrue(h.transport.setTempo(121)) // queued revision 1
        assertTrue(h.transport.setTempo(122)) // coalesced revision 2
        assertEquals(1, h.transport.pendingControlCommandCount)

        val worker = Thread {
            try { h.render(1) }
            catch (error: Throwable) { workerFailure.set(error) }
        }
        worker.start()
        try {
            assertTrue("worker never reached getAndSet(false) handoff",
                workerAtSnapshot.await(5, TimeUnit.SECONDS))
            assertTrue(h.transport.setTempo(279)) // after dirty flag cleared
        } finally {
            resumeWorker.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        assertEquals(null, workerFailure.get())
        h.render(1)
        assertEquals(279, h.transport.tempo)
        assertEquals(h.transport.requestedSettingsControlRevision,
            h.transport.appliedSettingsControlRevision)
        assertEquals(0, h.transport.pendingControlCommandCount)
    }

    @Test
    fun abortedStartDrainsBoundedControlsButKeepsLatestDesiredSnapshot() {
        val h = Harness(capacity = 3)
        h.transport.armInputForPendingWorker()
        repeat(100_000) { i -> h.transport.setTempo(20 + i % 281) }
        h.transport.setEnabled(true)
        assertTrue(h.transport.pendingControlCommandCount <= 3)
        assertTrue(h.transport.inputNoteOn(-1L, 0, 61))
        h.transport.cancelPendingWorkerInput()
        assertEquals(0, h.transport.pendingPhysicalInputCount)
        assertEquals(0, h.transport.pendingControlCommandCount)
        h.transport.armInputForPendingWorker()
        h.transport.onWorkerStart()
        assertTrue(h.transport.enabled)
        assertEquals(h.transport.requestedSettingsControlRevision,
            h.transport.appliedSettingsControlRevision)
        assertEquals(h.transport.requestedEnabledControlRevision,
            h.transport.appliedEnabledControlRevision)
        assertEquals(0f, h.render(1)[0], 0f)
        assertTrue(h.held.isEmpty())
        h.transport.onWorkerStop()
    }
}
