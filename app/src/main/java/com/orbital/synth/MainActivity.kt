package com.orbital.synth

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import com.orbital.synth.audio.AndroidAudioOutput
import com.orbital.synth.audio.AndroidMidiManager
import com.orbital.synth.audio.MidiMessageListener
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.VoiceMode
import com.orbital.synth.ui.OrbitalScreen
import com.orbital.synth.ui.OrbitalUiState
import com.orbital.synth.ui.PlayMode

/**
 * Composes the UI around one long-lived synthesizer and one Android audio output.
 *
 * No audio rendering occurs on the main thread. The DSP engine queues all note
 * and parameter mutations until the audio thread consumes them.
 */
class MainActivity : ComponentActivity() {
    private lateinit var synth: OrbitalSynth
    private lateinit var audioOutput: AndroidAudioOutput
    private lateinit var midiManager: AndroidMidiManager

    private var screenState by mutableStateOf(
        OrbitalUiState(
            parameters = mapOf(
                ParameterId.WAVE to 0.42f,
                ParameterId.MORPH to 0.42f,
                ParameterId.CUTOFF to 0.65f,
                ParameterId.RESONANCE to 0.35f,
                ParameterId.MOVEMENT to 0.62f,
                ParameterId.FX_AMOUNT to 0.28f,
                ParameterId.ATTACK to 0.05f,
                ParameterId.DECAY to 0.35f,
                ParameterId.SUSTAIN to 0.70f,
                ParameterId.RELEASE to 0.28f,
                ParameterId.LFO_RATE to 0.30f,
                ParameterId.LFO_DEPTH to 0.20f,
                ParameterId.GLIDE to 0.05f
            )
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        synth = OrbitalSynth(sampleRate = 48_000, maxVoices = 16)
        for ((parameter, value) in screenState.parameters) {
            synth.setParameter(parameter, value)
        }

        audioOutput = AndroidAudioOutput(applicationContext, synth)
        midiManager = AndroidMidiManager(applicationContext, object : MidiMessageListener {
            override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
                if (velocity == 0) {
                    synth.noteOff(note)
                } else {
                    synth.noteOn(note, velocity.coerceIn(0, 127) / 127f)
                }
            }

            override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
                synth.noteOff(note)
            }

            override fun onControlChange(channel: Int, controller: Int, value: Int) {
                val parameter = when (controller) {
                    1 -> ParameterId.MOVEMENT
                    71 -> ParameterId.RESONANCE
                    74 -> ParameterId.CUTOFF
                    91 -> ParameterId.FX_AMOUNT
                    else -> null
                } ?: return
                runOnUiThread {
                    updateParameter(parameter, value.coerceIn(0, 127) / 127f)
                }
            }

            override fun onPitchBend(channel: Int, value: Int) {
                // Pitch-bend routing is enabled when the DSP engine supports
                // per-channel bend; ignoring it is safer than retuning all notes.
            }
        })

        setContent {
            MaterialTheme {
                OrbitalScreen(
                    state = screenState,
                    onNoteOn = { note ->
                        if (note in 0..127) synth.noteOn(note)
                    },
                    onNoteOff = { note ->
                        if (note in 0..127) synth.noteOff(note)
                    },
                    onParameterChange = ::updateParameter,
                    onModeChange = ::updatePlayMode,
                    onOctaveChange = { octave ->
                        synth.allNotesOff()
                        screenState = screenState.copy(octave = octave.coerceIn(1, 7))
                    }
                )
            }
        }
    }

    private fun updateParameter(parameter: ParameterId, value: Float) {
        if (!value.isFinite()) return
        val normalized = value.coerceIn(0f, 1f)
        synth.setParameter(parameter, normalized)
        screenState = screenState.copy(
            parameters = screenState.parameters + (parameter to normalized)
        )
    }

    private fun updatePlayMode(mode: PlayMode) {
        val voiceMode = when (mode) {
            PlayMode.POLY -> VoiceMode.POLY
            PlayMode.MONO -> VoiceMode.MONO
            PlayMode.LEGATO -> VoiceMode.LEGATO
        }
        synth.setVoiceMode(voiceMode)
        screenState = screenState.copy(playMode = mode)
    }

    override fun onStart() {
        super.onStart()
        if (!audioOutput.start()) {
            Toast.makeText(
                this,
                "Audio output unavailable. Check your device audio settings.",
                Toast.LENGTH_LONG
            ).show()
        }
        midiManager.start()
    }

    override fun onStop() {
        synth.allNotesOff()
        midiManager.stop()
        audioOutput.stop()
        super.onStop()
    }

    override fun onDestroy() {
        midiManager.close()
        audioOutput.close()
        super.onDestroy()
    }
}
