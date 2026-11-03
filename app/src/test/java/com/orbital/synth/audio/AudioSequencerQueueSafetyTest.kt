package com.orbital.synth.audio

import com.orbital.synth.core.music.SequencerNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Priority STOP and bounded edits, independent of unstable hardware timing. */
class AudioSequencerQueueSafetyTest {
    private class Harness {
        val held = linkedSetOf<Int>()
        var noteOns = 0
        var noteOffs = 0
        val transport = AudioSequencerTransport(
            sampleRate = 48_000,
            renderFrames = { buffer, frames ->
                for (index in 0 until frames * 2) buffer[index] = held.size.toFloat()
            },
            noteOn = { note, _ ->
                noteOns++
                held.add(note)
            },
            noteOff = { note ->
                noteOffs++
                held.remove(note)
            }
        )

        fun render(frames: Int = 192) {
            transport.renderBlock(FloatArray(frames * 2), FloatArray(frames * 2), frames)
        }
    }

    @Test
    fun stopReleasesAllHeldNotesNextBufferEvenBehind2048StepEdits() {
        val h = Harness()
        h.transport.setPlaying(true)
        h.transport.onWorkerStart()
        h.render(1)
        assertTrue(h.transport.isPlaying)
        assertTrue(h.held.isNotEmpty())
        val heldBeforeStop = h.held.size
        val offsBeforeStop = h.noteOffs

        var lastTicket = 0L
        repeat(2_048) { index ->
            lastTicket = checkNotNull(h.transport.setStepEnabled(1, index % 2 == 0))
        }
        assertEquals(2_048, h.transport.pendingEditCount)
        assertNull("never acknowledge an edit that could not be queued",
            h.transport.setStepEnabled(1, true))

        // No sequencer Play/Stop command sits behind those 2048 edits.
        h.transport.setPlaying(false)
        h.render(1)
        assertFalse(h.transport.isPlaying)
        assertEquals(-1, h.transport.playhead)
        assertTrue("STOP must release held notes in the FIRST buffer", h.held.isEmpty())
        assertTrue(h.noteOffs >= offsBeforeStop + heldBeforeStop)
        assertEquals(2_048 - 128, h.transport.pendingEditCount)

        repeat(15) { h.render(1) }
        assertEquals(0, h.transport.pendingEditCount)
        assertEquals(lastTicket, h.transport.selection.appliedStepToggleId)
        assertTrue(h.held.isEmpty())
        assertFalse(h.transport.isPlaying)
    }

    @Test
    fun workerRejectsOverflowWithoutDroppingAcceptedPatternEdits() {
        val h = Harness()
        val beforeRevision = h.transport.selection.revision
        assertTrue(h.transport.selectFactoryPattern("comet"))
        repeat(2_047) { assertNotNull(h.transport.setStepEnabled(0, it % 2 == 0)) }
        assertEquals(2_048, h.transport.pendingEditCount)
        assertFalse(h.transport.selectFactoryPattern("ion-bass"))
        assertFalse(h.transport.setStepCount(64))
        assertFalse(h.transport.setStepNotes(0, listOf(SequencerNote(80))))
        assertFalse(h.transport.setTempo(300))
        assertEquals(2_048, h.transport.pendingEditCount)

        repeat(16) { h.render(1) }
        assertEquals(0, h.transport.pendingEditCount)
        assertEquals("comet", h.transport.selectedPatternId)
        assertTrue(h.transport.selection.revision > beforeRevision)
        assertEquals(138, h.transport.tempo)
        assertTrue(h.transport.selectFactoryPattern("ion-bass"))
        h.render(1)
        assertEquals("ion-bass", h.transport.selectedPatternId)
    }

    @Test
    fun playStopStormUsesOnlyLatestRequestAndNeverGrowsEditQueue() {
        val h = Harness()
        h.transport.onWorkerStart()
        repeat(100_000) { h.transport.setPlaying(it % 2 == 0) }
        assertEquals(0, h.transport.pendingEditCount)
        h.render(1)
        assertFalse(h.transport.isPlaying)
        assertTrue(h.held.isEmpty())

        h.transport.setPlaying(true)
        h.render(1)
        assertTrue(h.transport.isPlaying)
        assertTrue(h.held.isNotEmpty())
        h.transport.setPlaying(false)
        h.render(1)
        assertFalse(h.transport.isPlaying)
        assertTrue(h.held.isEmpty())
        assertEquals(0, h.transport.pendingEditCount)
    }

    @Test
    fun stopThenPlayInsideOneRenderIntervalStillResetsPhrase() {
        val h = Harness()
        h.transport.setPlaying(true)
        h.transport.onWorkerStart()
        h.render(8_000)
        assertEquals(1, h.transport.playhead)

        // Both requests arrive between callbacks; STOP must not be coalesced
        // away merely because the final requested state is PLAY.
        h.transport.setPlaying(false)
        h.transport.setPlaying(true)
        h.render(1)
        assertTrue(h.transport.isPlaying)
        assertEquals(0, h.transport.playhead)
        assertEquals(0, h.transport.pendingEditCount)
    }

    @Test
    fun workerLifecycleStopReleasesNotesWithPendingBoundedEdits() {
        val h = Harness()
        h.transport.setPlaying(true)
        h.transport.onWorkerStart()
        h.render(1)
        assertTrue(h.held.isNotEmpty())
        repeat(2_048) { assertNotNull(h.transport.setStepEnabled(1, it % 2 == 0)) }
        h.transport.onWorkerStop()
        assertTrue(h.held.isEmpty())
        assertFalse(h.transport.isPlaying)
        assertEquals(2_048, h.transport.pendingEditCount)
        h.transport.setPlaying(false)
        h.transport.onWorkerStart()
        repeat(16) { h.render(1) }
        assertEquals(0, h.transport.pendingEditCount)
        assertTrue(h.held.isEmpty())
        assertFalse(h.transport.isPlaying)
    }

    @Test
    fun concurrentStepProducersCannotExceedAcceptedEditBound() {
        val h = Harness()
        h.transport.onWorkerStart()
        val ready = CountDownLatch(8)
        val begin = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val threads = (0 until 8).map { worker ->
            Thread {
                try {
                    ready.countDown()
                    if (!begin.await(5, TimeUnit.SECONDS)) error("start timeout")
                    repeat(1_500) { index ->
                        h.transport.setStepEnabled((index + worker) % 16, index % 2 == 0)
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }.also { it.start() }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        begin.countDown()
        threads.forEach { it.join(10_000) }
        assertTrue(threads.none { it.isAlive })
        assertEquals(null, failure.get())
        assertEquals(2_048, h.transport.pendingEditCount)

        repeat(16) { h.render(1) }
        assertEquals(0, h.transport.pendingEditCount)
        assertTrue(h.transport.selection.appliedStepToggleId > 0L)
    }

    @Test
    fun playingCanBeStoppedAndRestartedEvenWhileEditsAreSaturated() {
        val h = Harness()
        h.transport.onWorkerStart()
        repeat(2_048) { assertNotNull(h.transport.setStepEnabled(2, it % 2 == 0)) }
        h.transport.setPlaying(true)
        h.render(1)
        assertTrue(h.transport.isPlaying)
        h.transport.setPlaying(false)
        h.render(1)
        assertFalse(h.transport.isPlaying)
        assertTrue(h.held.isEmpty())
        h.transport.setPlaying(true)
        h.render(1)
        assertTrue(h.transport.isPlaying)
        h.transport.setPlaying(false)
        h.render(1)
        assertTrue(h.held.isEmpty())
    }
}
