package com.orbital.synth.audio

import com.orbital.synth.core.performance.ArpeggiatorDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the real nested transport order used by AndroidAudioOutput. */
class PerformanceArpIntegrationTest {
    @Test
    fun sequencerAndGeneratedArpeggiatorNeverStealEachOthersNotes() {
        val held = linkedSetOf<Pair<Long, Int>>()
        val generatedScratch = FloatArray(192 * 2)
        val seqScratch = FloatArray(192 * 2)
        val output = FloatArray(192 * 2)

        val performance = PerformanceArpTransport(
            sampleRate = 48_000,
            renderFrames = { buffer, frames ->
                for (frame in 0 until frames) {
                    buffer[frame * 2] = held.size.toFloat()
                    buffer[frame * 2 + 1] = held.size.toFloat()
                }
            },
            noteOn = { source, _, note, _ -> held += source to note },
            noteOff = { source, _, note -> held.remove(source to note) }
        )
        val sequence = AudioSequencerTransport(
            sampleRate = 48_000,
            renderFrames = { buffer, frames ->
                performance.renderBlock(buffer, generatedScratch, frames)
            },
            noteOn = { note, _ -> held += -2L to note },
            noteOff = { note -> held.remove(-2L to note) },
        )

        performance.setEnabled(true)
        performance.setDirection(ArpeggiatorDirection.UP)
        performance.onWorkerStart()
        sequence.setPlaying(true)
        sequence.onWorkerStart()
        performance.inputNoteOn(-1L, 0, 72)
        sequence.renderBlock(output, seqScratch, 192)

        assertTrue((-7L to 72) in held)
        assertTrue((-2L to 60) in held)
        assertTrue((-2L to 67) in held)
        assertEquals(3f, output[0], 0f)

        performance.setEnabled(false)
        sequence.renderBlock(output, seqScratch, 192)
        assertFalse((-7L to 72) in held)
        assertTrue((-2L to 60) in held)
        assertTrue((-2L to 67) in held)
        assertEquals(2f, output[0], 0f)

        sequence.onWorkerStop()
        performance.onWorkerStop()
        assertTrue(held.isEmpty())
    }
}
