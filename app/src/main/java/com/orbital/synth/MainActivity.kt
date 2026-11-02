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
import com.orbital.synth.core.preset.FactoryPresets
import com.orbital.synth.core.preset.loadPatch
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

    private var patchIndex = 0
    private var screenState by mutableStateOf(OrbitalUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        synth = OrbitalSynth(sampleRate = 48_000, maxVoices = 16)
        selectFactoryPatch(0)

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
                // Standard global ±2-semitone bend. Per-note MPE bend requires
                // channel-associated voices and is intentionally not claimed yet.
                val bend = (value.coerceIn(0, 16_383) - 8_192) / 8_192f
                synth.setPitchBend(bend * 2f)
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

    private fun selectFactoryPatch(index: Int) {
        val patches = FactoryPresets.ALL
        if (patches.isEmpty()) return
        patchIndex = ((index % patches.size) + patches.size) % patches.size
        val patch = patches[patchIndex]
        synth.allNotesOff()
        synth.loadPatch(patch)
        screenState = screenState.copy(
            patchName = patch.name,
            parameters = ParameterId.entries.associateWith { synth.parameter(it) }
        )
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
