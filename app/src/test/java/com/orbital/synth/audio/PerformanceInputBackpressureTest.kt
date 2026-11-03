package com.orbital.synth.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The producer may run far faster than the 512-command-per-buffer worker. */
class PerformanceInputBackpressureTest {
    private class Harness(
        capacity: Int = 8,
        beforeInputEnqueue: (() -> Unit)? = null,
    ) {
        val held = LinkedHashMap<Triple<Long, Int, Int>, Int>()
        val transport = PerformanceArpTransport(
            sampleRate = 48_000,
            renderFrames = { pcm, frames ->
                for (i in 0 until frames * 2) pcm[i] = held.values.sum().toFloat()
            },
            noteOn = { source, channel, note, _ ->
                val key = Triple(source, channel, note)
                held[key] = (held[key] ?: 0) + 1
            },
            noteOff = { source, channel, note ->
                val key = Triple(source, channel, note)
                val count = held[key] ?: 0
                if (count <= 1) held.remove(key) else held[key] = count - 1
            },
            beforeInputEnqueue = beforeInputEnqueue,
            maxPendingPhysicalCommands = capacity,
        )

        fun render(frames: Int = 192) {
            transport.renderBlock(FloatArray(frames * 2), FloatArray(frames * 2), frames)
        }
    }

    @Test
    fun belowCapacityPreservesNoteOffAndSourceChannelOwnership() {
        val h = Harness(capacity = 8)
        h.transport.onWorkerStart()
        assertTrue(h.transport.inputNoteOn(101, 2, 60))
        assertTrue(h.transport.inputNoteOn(202, 3, 60))
        assertTrue(h.transport.inputNoteOn(101, 5, 64))
        h.render()
        assertEquals(3, h.held.size)

        // SourceOff is counted, ordered, and releases only this input source.
        h.transport.inputSourceAllNotesOff(101)
        h.render()
        assertEquals(setOf(Triple(202L, 3, 60)), h.held.keys.toSet())
        assertTrue(h.transport.inputNoteOff(202, 3, 60))
        h.render()
        assertTrue(h.held.isEmpty())
        assertEquals(0, h.transport.pendingPhysicalInputCount)
    }

    @Test
    fun boundedFloodTriggersGlobalReleaseInsteadOfDroppingHeldNoteOff() {
        val h = Harness(capacity = 8)
        h.transport.onWorkerStart()
        assertTrue(h.transport.inputNoteOn(71, 4, 60))
        assertTrue(h.transport.inputNoteOn(72, 5, 67))
        h.render()
        assertEquals(2, h.held.size)

        repeat(100_000) { index ->
            h.transport.inputNoteOn(1000L + index, index % 16, index % 128)
        }
        assertEquals(8, h.transport.pendingPhysicalInputCount)
        assertFalse("critical release must not silently disappear", h.transport.inputNoteOff(71, 4, 60))
        h.render()

        // Overflow silences ALL sources by design, including unrelated source 72.
        assertTrue("overload must never leave a stuck note", h.held.isEmpty())
        assertEquals(0, h.transport.pendingPhysicalInputCount)
        assertTrue("new epoch accepts fresh input", h.transport.inputNoteOn(71, 4, 60))
        h.render()
        assertEquals(1, h.held[Triple(71L, 4, 60)])
        assertTrue(h.transport.inputNoteOff(71, 4, 60))
        h.render()
        assertTrue(h.held.isEmpty())
    }

    @Test
    fun productionLimitIsExactly2048PhysicalCommands() {
        val h = Harness(capacity = 2_048)
        h.transport.onWorkerStart()
        repeat(2_048) {
            assertTrue(h.transport.inputNoteOn(1, it % 16, it % 128))
        }
        assertEquals(2_048, h.transport.pendingPhysicalInputCount)
        assertFalse("2049th event must trigger emergency instead of growth",
            h.transport.inputNoteOff(1, 0, 60))
        assertEquals(2_048, h.transport.pendingPhysicalInputCount)
        repeat(4) { h.render() }
        assertEquals(0, h.transport.pendingPhysicalInputCount)
        assertTrue(h.held.isEmpty())
        assertTrue(h.transport.inputNoteOn(1, 0, 60))
        h.render()
        assertEquals(1, h.held[Triple(1L, 0, 60)])
    }

    @Test
    fun saturationByNoteOffAlsoReleasesHeldNotesSafely() {
        val h = Harness(capacity = 4)
        h.transport.onWorkerStart()
        assertTrue(h.transport.inputNoteOn(9, 0, 55))
        h.render()
        assertEquals(1, h.held.size)

        repeat(4) { assertTrue(h.transport.inputNoteOff(100, 0, 60 + it)) }
        assertFalse(h.transport.inputNoteOff(9, 0, 55))
        assertEquals(4, h.transport.pendingPhysicalInputCount)
        h.render()
        assertTrue(h.held.isEmpty())
        assertEquals(0, h.transport.pendingPhysicalInputCount)
    }

    @Test
    fun arpLatchOverloadReleasesGeneratedVoiceAndRestartsEmpty() {
        val h = Harness(capacity = 4)
        h.transport.setEnabled(true)
        h.transport.setLatch(true)
        h.transport.onWorkerStart()
        assertTrue(h.transport.inputNoteOn(19, 0, 60))
        h.render(1)
        assertTrue(h.held.isNotEmpty())

        repeat(4) { assertTrue(h.transport.inputNoteOn(30L + it, 0, 70 + it)) }
        assertFalse(h.transport.inputNoteOff(19, 0, 60))
        h.render(1)
        assertTrue(h.held.isEmpty())
        h.render(12_000)
        assertTrue("latched arpeggio must not restart after emergency", h.held.isEmpty())
    }

    @Test
    fun oldNoteOffDelayedAtProducerSeamCannotKillNewEpochKey() {
        val blockNext = AtomicBoolean(false)
        val captured = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val accepted = AtomicBoolean(true)
        val h = Harness(capacity = 4) {
            if (blockNext.getAndSet(false)) {
                captured.countDown()
                if (!resume.await(5, TimeUnit.SECONDS)) error("delayed producer timed out")
            }
        }
        h.transport.onWorkerStart()
        assertTrue(h.transport.inputNoteOn(23, 3, 69))
        h.render()
        assertEquals(1, h.held.size)

        blockNext.set(true)
        val producer = Thread {
            try {
                accepted.set(h.transport.inputNoteOff(23, 3, 69))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        producer.start()
        try {
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            // The blocked old NOTE_OFF holds one reservation.
            repeat(3) { assertTrue(h.transport.inputNoteOn(90L + it, 0, 40 + it)) }
            assertFalse(h.transport.inputNoteOn(99, 0, 50))
            h.render() // global panic, old queue discarded
            assertTrue(h.held.isEmpty())
            assertEquals(1, h.transport.pendingPhysicalInputCount)
            assertTrue(h.transport.inputNoteOn(23, 3, 69))
            h.render()
            assertEquals(1, h.held[Triple(23L, 3, 69)])
        } finally {
            resume.countDown()
            producer.join(5_000)
        }

        assertFalse(producer.isAlive)
        assertEquals(null, failure.get())
        assertFalse("old epoch note-off must be rejected", accepted.get())
        h.render()
        assertEquals(1, h.held[Triple(23L, 3, 69)])
        assertEquals(0, h.transport.pendingPhysicalInputCount)
        assertTrue(h.transport.inputNoteOff(23, 3, 69))
        h.render()
        assertTrue(h.held.isEmpty())
    }

    @Test
    fun manyConcurrentProducersCannotReserveBeyondCap() {
        val h = Harness(capacity = 32)
        h.transport.onWorkerStart()
        assertTrue(h.transport.inputNoteOn(5, 0, 60))
        h.render()
        val start = CountDownLatch(1)
        val workers = (0 until 8).map { worker ->
            Thread {
                start.await()
                repeat(5_000) { i ->
                    h.transport.inputNoteOn(worker.toLong() + 100, i % 16, i % 128)
                    h.transport.inputNoteOff(worker.toLong() + 100, i % 16, i % 128)
                }
            }.also { it.start() }
        }
        start.countDown()
        workers.forEach { it.join(5_000) }
        assertTrue("all producers must terminate", workers.none { it.isAlive })
        assertTrue(h.transport.pendingPhysicalInputCount in 0..32)
        h.render()
        assertEquals(0, h.transport.pendingPhysicalInputCount)
        assertTrue("no source may remain latched after saturation", h.held.isEmpty())
    }

    @Test
    fun failedProducerSeamDoesNotLeakReservation() {
        val failNext = AtomicBoolean(true)
        val h = Harness(capacity = 2) {
            if (failNext.getAndSet(false)) error("injected callback exception")
        }
        h.transport.onWorkerStart()
        try {
            h.transport.inputNoteOn(1, 0, 60)
            fail("seam should throw")
        } catch (expected: IllegalStateException) {
            assertEquals("injected callback exception", expected.message)
        }
        assertEquals(0, h.transport.pendingPhysicalInputCount)
        assertTrue(h.transport.inputNoteOn(1, 0, 60))
        assertTrue(h.transport.inputNoteOff(1, 0, 60))
        h.render()
        assertTrue(h.held.isEmpty())
        assertEquals(0, h.transport.pendingPhysicalInputCount)
    }

    @Test
    fun workerStopDiscardsQueuedCommandsAndReleasesReservations() {
        val h = Harness(capacity = 4)
        h.transport.onWorkerStart()
        repeat(4) { assertTrue(h.transport.inputNoteOn(1, 0, 60 + it)) }
        assertEquals(4, h.transport.pendingPhysicalInputCount)
        h.transport.onWorkerStop()
        assertEquals(0, h.transport.pendingPhysicalInputCount)
        h.transport.onWorkerStart()
        h.render()
        assertTrue(h.held.isEmpty())
        assertTrue(h.transport.inputNoteOn(2, 1, 75))
        h.render()
        assertEquals(1, h.held[Triple(2L, 1, 75)])
    }
}
