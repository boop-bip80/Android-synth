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
        val transport = PerformanceArpTransport(
            sampleRate = 48_000,
            renderFrames = { samples, frames ->
                for (frame in 0 until frames) {
                    samples[frame * 2] = held.size.toFloat()
                    samples[frame * 2 + 1] = held.size.toFloat()
                }
            },
            noteOn = { source, channel, note, _ ->
                held += Triple(source, channel, note)
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
            harness.transport.onWorkerStart()
            assertTrue(harness.transport.inputNoteOn(23, 3, 69))
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
}
