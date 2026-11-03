package com.orbital.synth.audio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.ParameterId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the actual UI-slot publication -> running OrbitalSynth worker ->
 * Android AudioTrack.write -> acknowledged-PCM telemetry path.
 *
 * Brightness is a waveform-slope proxy, not an FFT or a microphone recording.
 * AudioVisualTelemetry only updates after AudioTrack accepted a COMPLETE write;
 * offline synth renders and UI-only state assertions cannot satisfy this test.
 */
@RunWith(AndroidJUnit4::class)
class OscillatorSlotHostBridgeAudioTrackTest {
    private companion object {
        const val SOURCE_ID = 0x6A91L
        const val SAMPLE_RATE = 48_000
    }

    @Test fun completeV6SnapshotValidatesAllControlsAndRetainsNoiseFields() {
        val synth = OrbitalSynth(sampleRate = SAMPLE_RATE, maxVoices = 2)
        val complete = OscillatorSlot(
            enabled = true, waveform = .65f, morph = .75f, level = .8f,
            coarseSemitones = -12, fineCents = 8f,
            noiseLevel = .9f, noiseColor = .7f,
        )
        assertFalse(OscillatorSlotHostBridge.publish(synth, -1, complete))
        assertFalse(OscillatorSlotHostBridge.publish(synth, 8, complete))
        assertEquals(OscillatorSlot(), synth.oscillatorSlot(0))
        assertFalse(OscillatorSlotHostBridge.publish(
            synth, 0, complete.copy(noiseLevel = Float.NaN)))
        assertFalse(OscillatorSlotHostBridge.publish(
            synth, 0, complete.copy(noiseColor = Float.POSITIVE_INFINITY)))
        assertFalse(OscillatorSlotHostBridge.publish(
            synth, 0, complete.copy(level = Float.NEGATIVE_INFINITY)))
        assertEquals(OscillatorSlot(), synth.oscillatorSlot(0))
        assertTrue(OscillatorSlotHostBridge.publish(synth, 0, complete))
        assertEquals(complete, synth.oscillatorSlot(0))

        // Invalid source values cannot poison the worker; finite controls are
        // clamped by OrbitalSynth's whole-slot validator before publication.
        assertTrue(OscillatorSlotHostBridge.publish(
            synth, 0, complete.copy(noiseLevel = 2f, noiseColor = -1f)))
        assertEquals(1f, synth.oscillatorSlot(0).noiseLevel, 0f)
        assertEquals(0f, synth.oscillatorSlot(0).noiseColor, 0f)
    }

    @Test fun liveTouchNoiseAndColorChangePcmAcceptedByRealAndroidAudioTrack() {
        val synth = OrbitalSynth(sampleRate = SAMPLE_RATE, maxVoices = 4).apply {
            setParameter(ParameterId.WAVE, 0f) // steady reference sine
            setParameter(ParameterId.MORPH, 1f)
            setParameter(ParameterId.CUTOFF, 1f)
            setParameter(ParameterId.RESONANCE, 0f)
            setParameter(ParameterId.ATTACK, 0f)
            setParameter(ParameterId.DECAY, 0f)
            setParameter(ParameterId.SUSTAIN, 1f)
            setParameter(ParameterId.RELEASE, 0f)
            setParameter(ParameterId.MOVEMENT, 0f)
            setParameter(ParameterId.LFO_DEPTH, 0f)
            setParameter(ParameterId.FX_AMOUNT, 0f)
            setParameter(ParameterId.ENGINE, 0f)
        }
        val tonal = OscillatorSlot(
            enabled = true, waveform = 0f, morph = 1f, level = 1f,
            noiseLevel = 0f, noiseColor = 0f
        )
        assertTrue(OscillatorSlotHostBridge.publish(synth, 0, tonal))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = AndroidAudioOutput(context, synth, sampleRate = SAMPLE_RATE)
        try {
            assertTrue("Emulator AudioTrack and focus are required", output.start())
            assertTrue("Real AudioTrack worker failed to run", output.isRunning)
            assertTrue("Actual AudioTrack transport must accept held MIDI note",
                output.noteOn(SOURCE_ID, channel = 0, note = 69, velocity = 127))
            val tone = collectAcceptedBrightness(output, "sine baseline")
            assertTrue("Sine baseline produced no accepted PCM", tone.second > .003f)

            // Same held note, same real worker: publish the COMPLETE V6 slot
            // through the bridge that MainActivity will use for touch edits.
            assertTrue(OscillatorSlotHostBridge.publish(
                synth, 0, tonal.copy(noiseLevel = 1f, noiseColor = 0f)))
            assertEquals(1f, synth.oscillatorSlot(0).noiseLevel, 0f)
            val white = collectAcceptedBrightness(output, "live white noise")

            // Noise color must also reach the engine; this proves an old
            // seven-parameter UI setter cannot be substituted for the bridge.
            assertTrue(OscillatorSlotHostBridge.publish(
                synth, 0, tonal.copy(noiseLevel = 1f, noiseColor = 1f)))
            assertEquals(1f, synth.oscillatorSlot(0).noiseColor, 0f)
            val brown = collectAcceptedBrightness(output, "live brown noise")

            println("ORBITAL_V6_ACCEPTED_PCM toneBrightness=${tone.first} " +
                "whiteBrightness=${white.first} brownBrightness=${brown.first} " +
                "toneRms=${tone.second} whiteRms=${white.second} brownRms=${brown.second} " +
                "frames=${output.diagnostics.framesRendered} encoding=${output.diagnostics.encoding}")
            assertTrue(
                "Accepted AudioTrack PCM must brighten when the finger enables white noise: " +
                    "tone=${tone.first} white=${white.first}",
                white.first > tone.first + .055f,
            )
            assertTrue(
                "Accepted AudioTrack PCM must darken when the finger changes noise color: " +
                    "white=${white.first} brown=${brown.first}",
                white.first > brown.first + .035f,
            )
            assertTrue("White/brown controls must remain audible",
                white.second > .003f && brown.second > .003f)
            assertTrue("No unexpected fatal Android AudioTrack error",
                output.fatalErrorMessage == null)
            output.noteOff(SOURCE_ID, channel = 0, note = 69)
        } finally {
            output.allNotesOff(SOURCE_ID)
            output.close()
        }
    }

    /**
     * Advance ≥8192 frames between measurements and use the public meter only
     * after those frames were accepted by the real AudioTrack streaming worker.
     * Wait a further 16k frames on each mode change for the 8ms de-click ramp.
     * No constant fake meter or synthetic PCM render can pass this assertion.
     */
    private fun collectAcceptedBrightness(
        output: AndroidAudioOutput,
        label: String,
    ): Pair<Float, Float> {
        awaitAcceptedFrames(output, 16_384, "$label post-edit settle")
        var brightness = 0f
        var rms = 0f
        repeat(6) {
            awaitAcceptedFrames(output, 8_192, "$label measured window $it")
            val meter = output.audioVisualSnapshot
            assertTrue("$label: accepted PCM meter must report a held note, rms=${meter.rms}",
                meter.rms > .003f)
            assertTrue("$label: accepted PCM amplitude must be finite and bounded",
                meter.rms.isFinite() && meter.peak.isFinite() &&
                    meter.peak in 0f..1f && meter.brightness in 0f..1f)
            brightness += meter.brightness
            rms += meter.rms
        }
        return Pair(brightness / 6f, rms / 6f)
    }

    private fun awaitAcceptedFrames(
        output: AndroidAudioOutput,
        increment: Long,
        label: String,
    ) {
        val start = output.diagnostics.framesRendered
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (output.isRunning &&
            output.diagnostics.framesRendered - start < increment &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(20L)
        }
        val after = output.diagnostics.framesRendered
        assertTrue("$label: real AudioTrack worker stopped unexpectedly",
            output.isRunning)
        assertTrue("$label: expected $increment accepted-render frames, got ${after - start}",
            after - start >= increment)
    }
}
