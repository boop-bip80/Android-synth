package com.orbital.synth.audio

import com.orbital.synth.core.performance.ArpeggiatorDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceArpTransportTest {
    private class Fake {
        val held = LinkedHashMap<Triple<Long, Int, Int>, Int>()
        val transport = PerformanceArpTransport(
            sampleRate = 48_000,
            renderFrames = { buffer, frames ->
                val value = held.values.sum().toFloat()
                for (frame in 0 until frames) {
                    buffer[frame * 2] = value
                    buffer[frame * 2 + 1] = value
                }
            },
            noteOn = { source, channel, note, _ ->
                val key = Triple(source, channel, note)
                held[key] = (held[key] ?: 0) + 1
            },
            noteOff = { source, channel, note ->
                val key = Triple(source, channel, note)
                val current = held[key] ?: 0
                if (current <= 1) held.remove(key) else held[key] = current - 1
            }
        )

        fun render(frames: Int = 192): FloatArray {
            val result = FloatArray(frames * 2)
            transport.renderBlock(result, FloatArray(result.size), frames)
            return result
        }
    }

    @Test
    fun disabledModePreservesDirectSourceAwarePlaying() {
        val fake = Fake()
        fake.transport.onWorkerStart()
        assertTrue(fake.transport.inputNoteOn(11, 2, 60, 100))
        assertEquals(1f, fake.render()[0], 0f)
        assertEquals(1, fake.held[Triple(11L, 2, 60)])
        assertTrue(fake.transport.inputNoteOff(11, 2, 60))
        assertEquals(0f, fake.render()[0], 0f)
        assertTrue(fake.held.isEmpty())
    }

    @Test
    fun enabledModeUsesOnlyDedicatedArpeggiatorSource() {
        val fake = Fake()
        fake.transport.setEnabled(true)
        fake.transport.onWorkerStart()
        fake.transport.inputNoteOn(-1, 0, 60, 111)
        assertEquals(1f, fake.render(1)[0], 0f)
        assertEquals(
            setOf(Triple(PerformanceArpTransport.ARP_SOURCE_ID, 0, 60)),
            fake.held.keys.toSet()
        )
        fake.transport.inputNoteOff(-1, 0, 60)
        assertEquals(0f, fake.render(1)[0], 0f)
        assertTrue(fake.held.isEmpty())
    }

    @Test
    fun noteOffAndOnHappenAtExactSampleGatesInOneRenderCall() {
        val fake = Fake()
        fake.transport.setEnabled(true)
        fake.transport.onWorkerStart()
        fake.transport.inputNoteOn(1, 0, 60)
        val pcm = fake.render(7_000)
        assertEquals(1f, pcm[0], 0f)
        assertEquals(1f, pcm[4_799 * 2], 0f)
        assertEquals(0f, pcm[4_800 * 2], 0f)
        assertEquals(0f, pcm[5_999 * 2], 0f)
        assertEquals(1f, pcm[6_000 * 2], 0f)
    }

    @Test
    fun enablingWhileDirectKeysAreHeldReleasesOnlyThoseKeys() {
        val fake = Fake()
        fake.transport.onWorkerStart()
        fake.transport.inputNoteOn(-1, 0, 60)
        fake.transport.inputNoteOn(2, 1, 64)
        fake.render()
        assertEquals(2, fake.held.size)
        fake.transport.setEnabled(true)
        assertEquals(0f, fake.render()[0], 0f)
        assertTrue(fake.held.isEmpty())
        fake.transport.inputNoteOn(-1, 0, 67)
        // A key pressed between clock ticks enters on the next quantized pulse.
        val nextPulse = fake.render(6_000)
        assertEquals(0f, nextPulse[0], 0f)
        assertEquals(1f, nextPulse[(6_000 - 192) * 2], 0f)
        assertEquals(
            setOf(Triple(PerformanceArpTransport.ARP_SOURCE_ID, 0, 67)),
            fake.held.keys.toSet()
        )
        fake.transport.setEnabled(false)
        fake.render(1)
        assertTrue(fake.held.isEmpty())
    }

    @Test
    fun distinctMidiInputPortsMustBeReleasedIndependently() {
        val fake = Fake()
        fake.transport.setEnabled(true)
        fake.transport.onWorkerStart()
        fake.transport.inputNoteOn(11, 0, 60)
        fake.transport.inputNoteOn(22, 0, 60)
        fake.render(1)
        fake.transport.inputSourceAllNotesOff(11)
        fake.render(1)
        assertEquals(1, fake.held.size)
        fake.transport.inputSourceAllNotesOff(22)
        fake.render(1)
        assertTrue(fake.held.isEmpty())
    }

    @Test
    fun changingModeOrRateReleasesOldGeneratedNotes() {
        val fake = Fake()
        fake.transport.setEnabled(true)
        fake.transport.onWorkerStart()
        fake.transport.inputNoteOn(1, 0, 60)
        fake.render(1)
        assertEquals(1, fake.held.size)
        fake.transport.setDirection(ArpeggiatorDirection.RANDOM)
        fake.transport.setRate(8)
        fake.render(1)
        assertEquals(8, fake.transport.subdivisionsPerBeat)
        assertEquals(ArpeggiatorDirection.RANDOM, fake.transport.direction)
        assertEquals(1, fake.held.size)
        assertFalse(fake.transport.setRate(0))
        assertFalse(fake.transport.setTempo(301))
        assertEquals(8, fake.transport.subdivisionsPerBeat)
    }

    @Test
    fun workerStopClearsHeldAndQueuedInputWithoutLosingUserMode() {
        val fake = Fake()
        fake.transport.setEnabled(true)
        fake.transport.onWorkerStart()
        fake.transport.inputNoteOn(-1, 0, 72)
        fake.render(1)
        assertEquals(1, fake.held.size)
        fake.transport.inputNoteOn(-1, 0, 76)
        fake.transport.onWorkerStop()
        assertTrue(fake.held.isEmpty())
        assertTrue(fake.transport.enabled)
        fake.transport.onWorkerStart()
        assertEquals(0f, fake.render(2_000)[0], 0f)
        assertTrue(fake.held.isEmpty())
    }

    @Test
    fun latchedInputIsCutOffByPanicAndNeverRestartsWithoutNewKeys() {
        val fake = Fake()
        fake.transport.setEnabled(true)
        fake.transport.setLatch(true)
        fake.transport.onWorkerStart()
        fake.transport.inputNoteOn(-1, 0, 60)
        fake.render(1)
        fake.transport.inputNoteOff(-1, 0, 60)
        fake.render(1)
        assertEquals(1, fake.held.size)
        fake.transport.panic()
        assertEquals(0f, fake.render(1)[0], 0f)
        assertTrue(fake.held.isEmpty())
        assertEquals(0f, fake.render(12_000)[0], 0f)
    }

    @Test
    fun extraPerformanceKnobsValidateBoundsAndPublishRequestedValues() {
        val fake = Fake()
        assertTrue(fake.transport.setOctaves(3))
        assertTrue(fake.transport.setRatchets(4))
        assertTrue(fake.transport.setGate(0.35))
        assertTrue(fake.transport.setProbability(0.75))
        assertFalse(fake.transport.setOctaves(0))
        assertFalse(fake.transport.setRatchets(9))
        assertFalse(fake.transport.setGate(Double.NaN))
        assertFalse(fake.transport.setProbability(-0.1))
        assertEquals(3, fake.transport.octaves)
        assertEquals(4, fake.transport.ratchets)
        assertEquals(0.35, fake.transport.gate, 0.0)
        assertEquals(0.75, fake.transport.probability, 0.0)
    }

    @Test
    fun invalidInputIsRejectedWithoutWorkerSideEffects() {
        val fake = Fake()
        fake.transport.onWorkerStart()
        assertFalse(fake.transport.inputNoteOn(1, 16, 60))
        assertFalse(fake.transport.inputNoteOn(1, 0, 128))
        assertFalse(fake.transport.inputNoteOff(1, -1, 60))
        assertFalse(fake.transport.setSwing(Double.POSITIVE_INFINITY))
        assertEquals(0f, fake.render()[0], 0f)
        assertTrue(fake.held.isEmpty())
    }
}
