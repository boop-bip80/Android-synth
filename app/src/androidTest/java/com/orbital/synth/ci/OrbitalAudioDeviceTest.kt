package com.orbital.synth.ci

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbital.synth.audio.AndroidAudioOutput
import com.orbital.synth.core.OrbitalSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An on-emulator integration check for Android AudioTrack ownership and queued
 * sequencer commands. Unlike host JVM tests, this executes against Android's
 * audio services. The virtual device is permitted to lack an output sink:
 * no hardware audio availability or low-latency claim is inferred.
 */
@RunWith(AndroidJUnit4::class)
class OrbitalAudioDeviceTest {
    @Test
    fun audioOutputPreservesLifecycleAndDrainsSequenceCommandsWhenDeviceAllowsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val availableRoutes = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        Log.i("OrbitalDeviceTest", "Available output routes: " + availableRoutes.size)

        val output = AndroidAudioOutput(context, OrbitalSynth())
        assertFalse(output.isRunning)
        assertEquals(48_000, output.diagnostics.sampleRate)
        assertFalse(output.selectFactoryPattern("nonexistent-factory-sequence"))

        val selected = output.sequencerFactoryPatterns.last()
        assertTrue(output.selectFactoryPattern(selected.id))
        output.setSequencerTempo(137)
        output.setSequencerPlaying(true)
        try {
            val started = output.start()
            Log.i("OrbitalDeviceTest", "Audio output start accepted: " + started)
            if (started) {
                // Commands are processed on the audio worker, not immediately on
                // the instrumentation or UI thread. Poll for at most two seconds.
                val deadline = SystemClock.uptimeMillis() + 2_000L
                var commandsApplied = false
                while (SystemClock.uptimeMillis() < deadline && output.isRunning) {
                    if (output.sequencerPatternId == selected.id &&
                        output.sequencerTempo == 137
                    ) {
                        commandsApplied = true
                        break
                    }
                    Thread.sleep(20)
                }
                if (output.isRunning) {
                    assertTrue(
                        "Running audio worker must consume queued sequence selection/tempo",
                        commandsApplied,
                    )
                } else {
                    Log.w(
                        "OrbitalDeviceTest",
                        "Virtual audio route stopped during test; no streaming guarantee claimed",
                    )
                }
            } else {
                // A virtual device without an audio sink is a supported failure.
                assertFalse(output.isRunning)
            }
        } finally {
            output.setSequencerPlaying(false)
            output.close()
        }

        assertFalse("Close must stop the audio worker", output.isRunning)
        assertFalse("Disposed AudioTrack owner must not restart", output.start())
    }
}
