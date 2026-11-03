package com.orbital.synth.audio

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbital.synth.MainActivity
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.ParameterId
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * True production-host AudioTrack acceptance proof for the new three-band
 * telemetry. No headless AudioTrack, synth.renderOffline(), synthetic metering,
 * microphone, or FFT is involved. MainActivity owns the same running output and
 * synth that the actual user's SOUND and FOCUS experiences read.
 */
@RunWith(AndroidJUnit4::class)
class AcceptedPcmBandAudioTrackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private companion object {
        const val SOURCE_ID = 0x77B4L
        const val SAMPLE_RATE = 48_000
    }

    @Test fun liveMainActivityAcceptedPcmPublishesRealBandsAndStopBlacksThemOut() {
        val synth = liveSynth().apply {
            clearOscillatorRack()
            clearFxRack()
            for (index in 0 until modulationRouteCount) clearModulationRoute(index)
            setParameter(ParameterId.WAVE, 0f)
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
            setOscillatorSlot(0, OscillatorSlot(
                enabled = true,
                waveform = 0f,
                morph = 1f,
                level = 1f,
                noiseLevel = 0f,
                noiseColor = 0f,
            ))
        }
        val output = liveOutput()
        assertSame("Telemetry MUST originate in the edited host synth",
            synth, liveOutputSynth(output))
        assertEquals(SAMPLE_RATE, synth.sampleRate)
        compose.waitUntil(10_000L) { output.isRunning }
        assertTrue("Must have real foreground Android AudioTrack", output.isRunning)
        try {
            // Ensure the render worker is alive and accepting hardware PCM.
            awaitAcceptedFrames(output, 8_192, "silent pre-note baseline")
            val before = output.audioVisualSnapshot
            assertTrue(before.rms.isFinite() && before.rms in 0f..1f)

            assertTrue("MIDI48 must enter the real production transport",
                output.noteOn(SOURCE_ID, channel = 0, note = 48, velocity = 127))
            val low = collectAcceptedBands(output, "live MIDI48 bass")
            output.noteOff(SOURCE_ID, channel = 0, note = 48)
            awaitAcceptedFrames(output, 16_384, "release bass voice")

            assertTrue("MIDI96 must enter the same real production transport",
                output.noteOn(SOURCE_ID, channel = 0, note = 96, velocity = 127))
            val higher = collectAcceptedBands(output, "live MIDI96 upper")
            output.noteOff(SOURCE_ID, channel = 0, note = 96)

            val separation = abs(low.lowBand - higher.lowBand) +
                abs(low.midBand - higher.midBand) +
                abs(low.highBand - higher.highBand)
            println(
                "ORBITAL_REAL_ACCEPTED_PCM_BANDS bass=$low upper=$higher " +
                    "distance=$separation frames=${output.diagnostics.framesRendered} " +
                    "encoding=${output.diagnostics.encoding}",
            )
            assertTrue(
                "Real post-AudioTrack PCM must change relative LOW/MID/HIGH " +
                    "spatial timbre between distant live notes; difference=$separation",
                separation > .25f,
            )
            assertTrue(
                "Bass note should contribute measurably more measured LOW power",
                low.lowBand > higher.lowBand + .12f,
            )
            assertTrue("Actual live worker must not report a fatal output failure",
                output.fatalErrorMessage == null)

            // A real host stop must immediately gate the publication even when
            // its render worker is asynchronously winding down.
            output.stop()
            assertEquals(
                "On-stop FOCUS cannot show stale accepted PCM or old timbre",
                AudioVisualSnapshot(),
                output.audioVisualSnapshot,
            )
        } finally {
            if (output.isRunning) {
                output.allNotesOff(SOURCE_ID)
                output.stop()
            }
            // This is the Activity-owned output; its normal onStop/onDestroy
            // cleanup still executes when Compose's ActivityScenario closes.
        }
    }

    private fun collectAcceptedBands(
        output: AndroidAudioOutput,
        label: String,
    ): AudioVisualSnapshot {
        awaitAcceptedFrames(output, 16_384, "$label settle")
        var rms = 0f
        var low = 0f
        var mid = 0f
        var high = 0f
        repeat(5) { iteration ->
            awaitAcceptedFrames(output, 8_192, "$label window $iteration")
            val frame = output.audioVisualSnapshot
            assertTrue("$label must have real audible accepted PCM, rms=${frame.rms}",
                frame.rms > .003f)
            assertTrue("$label levels and bands must be finite and normalized",
                frame.rms.isFinite() && frame.lowBand.isFinite() &&
                    frame.midBand.isFinite() && frame.highBand.isFinite() &&
                    frame.rms in 0f..1f && frame.lowBand in 0f..1f &&
                    frame.midBand in 0f..1f && frame.highBand in 0f..1f)
            assertEquals("$label one coherent frequency-band publication",
                1f, frame.lowBand + frame.midBand + frame.highBand, .012f)
            rms += frame.rms
            low += frame.lowBand
            mid += frame.midBand
            high += frame.highBand
        }
        return AudioVisualSnapshot(
            rms = rms / 5f,
            lowBand = low / 5f,
            midBand = mid / 5f,
            highBand = high / 5f,
        )
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
        val gained = output.diagnostics.framesRendered - start
        assertTrue("$label: AudioTrack stopped before expected render frames",
            output.isRunning)
        assertTrue("$label: expected >= $increment real accepted frames, got $gained",
            gained >= increment)
    }

    private fun liveSynth(): OrbitalSynth {
        val field = MainActivity::class.java.getDeclaredField("synth")
        field.isAccessible = true
        return field.get(compose.activity) as OrbitalSynth
    }

    private fun liveOutput(): AndroidAudioOutput {
        val field = MainActivity::class.java.getDeclaredField("audioOutput")
        field.isAccessible = true
        return field.get(compose.activity) as AndroidAudioOutput
    }

    private fun liveOutputSynth(output: AndroidAudioOutput): OrbitalSynth {
        val field = AndroidAudioOutput::class.java.getDeclaredField("synth")
        field.isAccessible = true
        return field.get(output) as OrbitalSynth
    }
}
