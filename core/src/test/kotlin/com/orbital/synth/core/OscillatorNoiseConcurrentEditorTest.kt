package com.orbital.synth.core

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wave UI and the noise XY pad are independent asynchronous publishers.
 * A lost CAS write makes one finger erase settings from the other controller.
 */
class OscillatorNoiseConcurrentEditorTest {
    @Test fun concurrentLegacyWaveAndNoiseEditorsPreserveBothLastValuesEveryRound() {
        val synth = OrbitalSynth(sampleRate = 8_000, maxVoices = 1)
        val iterations = 96
        val barrier = CyclicBarrier(3)
        val failure = AtomicReference<Throwable?>()

        fun launchEditor(update: (Int) -> Unit) = thread(isDaemon = true) {
            try {
                for (round in 0 until iterations) {
                    barrier.await(15, TimeUnit.SECONDS)
                    update(round)
                    barrier.await(15, TimeUnit.SECONDS)
                }
            } catch (problem: Throwable) {
                failure.compareAndSet(null, problem)
                barrier.reset()
            }
        }

        val waveEditor = launchEditor { round ->
            synth.setOscillatorSlot(
                index = 0, enabled = true, waveform = round / 95f,
                level = 0.64f, coarseSemitones = -12,
                fineCents = 0.25f, morph = 0.2f,
            )
        }
        val noiseEditor = launchEditor { round ->
            synth.setOscillatorNoise(
                index = 0, noiseLevel = (round + 1) / 97f,
                noiseColor = round / 95f,
            )
        }

        try {
            for (round in 0 until iterations) {
                barrier.await(15, TimeUnit.SECONDS)
                barrier.await(15, TimeUnit.SECONDS)
                failure.get()?.let { throw AssertionError("Editor worker failed", it) }

                // Both commits finished, but neither producer may start the
                // following round until the observer releases the next barrier.
                val slot = synth.oscillatorSlot(0)
                assertEquals("Legacy wave edit lost during noise touch, round $round",
                    round / 95f, slot.waveform, 0f)
                assertEquals("Noise mix edit lost during wave touch, round $round",
                    (round + 1) / 97f, slot.noiseLevel, 0f)
                assertEquals("Noise color edit lost during wave touch, round $round",
                    round / 95f, slot.noiseColor, 0f)
                assertEquals(-12, slot.coarseSemitones)
                assertEquals(0.64f, slot.level, 0f)
            }
        } finally {
            waveEditor.join(20_000)
            noiseEditor.join(20_000)
        }
        assertFalse("Wave editor still waiting", waveEditor.isAlive)
        assertFalse("Noise editor still waiting", noiseEditor.isAlive)
        assertNull("Unexpected worker failure", failure.get())
    }

    @Test fun malformedTouchInputIsIgnoredAndOutOfRangeValuesAreClamped() {
        val synth = OrbitalSynth(sampleRate = 8_000, maxVoices = 1)
        synth.setOscillatorNoise(7, 0.7f, 0.4f)
        synth.setOscillatorNoise(7, Float.NaN, 0.9f)
        synth.setOscillatorNoise(7, 0.1f, Float.POSITIVE_INFINITY)
        synth.setOscillatorSlot(7, OscillatorSlot(enabled = true, noiseLevel = Float.NaN))
        assertEquals(0.7f, synth.oscillatorSlot(7).noiseLevel, 0f)
        assertEquals(0.4f, synth.oscillatorSlot(7).noiseColor, 0f)

        synth.setOscillatorNoise(7, -12f, 42f)
        assertEquals(0f, synth.oscillatorSlot(7).noiseLevel, 0f)
        assertEquals(1f, synth.oscillatorSlot(7).noiseColor, 0f)
    }
}
