package com.orbital.synth

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import com.orbital.synth.audio.AndroidAudioOutput
import com.orbital.synth.audio.AndroidMidiManager
import com.orbital.synth.audio.SourceAwareMidiMessageListener
import com.orbital.synth.core.OrbitalSynth
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.VoiceMode
import com.orbital.synth.core.music.SequenceDirectoryStore
import com.orbital.synth.core.music.SequencerNote
import com.orbital.synth.core.music.SequencerStep
import com.orbital.synth.core.music.StepPattern
import com.orbital.synth.core.music.SequencerToggleReconciler
import com.orbital.synth.core.music.UserSequence
import com.orbital.synth.core.preset.FactoryPresets
import com.orbital.synth.core.preset.loadPatch
import com.orbital.synth.preset.PatchOutcome
import com.orbital.synth.preset.UserPatchRepository
import com.orbital.synth.ui.OrbitalScreen
import com.orbital.synth.ui.OrbitalUiState
import com.orbital.synth.ui.PatchChoice
import com.orbital.synth.ui.SequenceChoice
import com.orbital.synth.ui.PlayMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Composes the UI around one long-lived synthesizer and one Android audio output.
 *
 * No audio rendering occurs on the main thread. The DSP engine queues all note
 * and parameter mutations until the audio thread consumes them.
 */
class MainActivity : ComponentActivity() {
    private companion object {
        // MIDI-port source IDs are positive, assigned by AndroidMidiManager.
        // Negative IDs reserve independent app-owned touch and fallback MIDI keys.
        const val TOUCH_SOURCE_ID = -1L
        const val LEGACY_MIDI_SOURCE_ID = -3L
    }

    private lateinit var synth: OrbitalSynth
    private lateinit var audioOutput: AndroidAudioOutput
    private lateinit var midiManager: AndroidMidiManager
    private lateinit var userPatches: UserPatchRepository
    private lateinit var userSequences: SequenceDirectoryStore

    private var patchIndex = 0
    private var screenState by mutableStateOf(OrbitalUiState())
    private var sequenceSelectionToken = 0L
    private var sequenceSaveInFlight = false
    // Keep the editor responsive until the render worker publishes each queued edit.
    // These mutations and the Compose poll both run on the main thread.
    private var pendingStepRevision: Long? = null
    private val pendingStepNotes = mutableMapOf<Int, List<SequencerNote>>()
    private val pendingToggleIntents = SequencerToggleReconciler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        synth = OrbitalSynth(sampleRate = 48_000, maxVoices = 16)
        selectFactoryPatch(0)

        audioOutput = AndroidAudioOutput(applicationContext, synth)
        userPatches = UserPatchRepository(applicationContext, synth)
        userSequences = SequenceDirectoryStore(filesDir.resolve("sequences").toPath())
        refreshPatchChoices()
        val initialSequence = audioOutput.sequencerPatternSelection
        screenState = screenState.copy(
            sequencerTempo = audioOutput.sequencerTempo,
            enabledSteps = initialSequence.enabledSteps,
            sequencerStepCount = initialSequence.stepCount,
            sequenceNotesByStep = initialSequence.sourcePattern.steps.map { it.notes },
            sequenceChoices = audioOutput.sequencerFactoryPatterns.map {
                SequenceChoice(it.id, it.name)
            },
            activeSequenceId = initialSequence.id,
        )
        refreshSequenceChoices()
        midiManager = AndroidMidiManager(applicationContext, object : SourceAwareMidiMessageListener {
            override fun onSourceNoteOn(sourceId: Long, channel: Int, note: Int, velocity: Int) {
                synth.noteOn(sourceId, channel, note, velocity.coerceIn(0, 127) / 127f)
            }

            override fun onSourceNoteOff(sourceId: Long, channel: Int, note: Int, velocity: Int) {
                synth.noteOff(sourceId, channel, note)
            }

            // Preserve direct/legacy listener behavior without sharing touch or port keys.
            override fun onNoteOn(channel: Int, note: Int, velocity: Int) {
                synth.noteOn(LEGACY_MIDI_SOURCE_ID, channel, note, velocity.coerceIn(0, 127) / 127f)
            }

            override fun onNoteOff(channel: Int, note: Int, velocity: Int) {
                synth.noteOff(LEGACY_MIDI_SOURCE_ID, channel, note)
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
            // Poll ONLY for visual playhead feedback; musical note timing is
            // computed at sample offsets on the dedicated audio render worker.
            LaunchedEffect(Unit) {
                while (true) {
                    val step = audioOutput.sequencerPlayhead
                    // Factory selection is queued on the audio thread. Read the
                    // immutable snapshot only after it has actually been applied,
                    // so the active ID and enabled grid cannot belong to different patterns.
                    val sequence = audioOutput.sequencerPatternSelection
                    val tempo = audioOutput.sequencerTempo
                    val actualNotes = sequence.sourcePattern.steps.map { it.notes }
                    if (pendingStepRevision != sequence.revision) {
                        pendingStepNotes.clear()
                        pendingStepRevision = null
                    } else {
                        pendingStepNotes.entries.removeAll { (index, notes) ->
                            actualNotes.getOrNull(index) == notes
                        }
                    }
                    val displayedNotes = actualNotes.mapIndexed { index, notes ->
                        pendingStepNotes[index] ?: notes
                    }
                    val enabledWithPendingNotes = sequence.enabledSteps.toMutableSet().apply {
                        pendingStepNotes.forEach { (index, notes) ->
                            if (notes.isEmpty()) remove(index) else add(index)
                        }
                    }
                    val displayedEnabled = pendingToggleIntents.reconcile(
                        sequence.revision, sequence.appliedStepToggleId,
                        enabledWithPendingNotes, sequence.stepCount,
                    )
                    if (screenState.sequencerStep != step ||
                        screenState.activeSequenceId != sequence.id ||
                        screenState.enabledSteps != displayedEnabled ||
                        screenState.sequencerStepCount != sequence.stepCount ||
                        screenState.sequencerTempo != tempo ||
                        screenState.sequenceNotesByStep != displayedNotes
                    ) {
                        screenState = screenState.copy(
                            sequencerStep = step,
                            activeSequenceId = sequence.id,
                            enabledSteps = displayedEnabled,
                            sequencerStepCount = sequence.stepCount,
                            sequencerTempo = tempo,
                            sequenceNotesByStep = displayedNotes,
                        )
                    }
                    delay(32L)
                }
            }
            MaterialTheme {
                OrbitalScreen(
                    state = screenState,
                    onNoteOn = { note ->
                        if (note in 0..127) synth.noteOn(TOUCH_SOURCE_ID, 0, note)
                    },
                    onNoteOff = { note ->
                        if (note in 0..127) synth.noteOff(TOUCH_SOURCE_ID, 0, note)
                    },
                    onParameterChange = ::updateParameter,
                    onModeChange = ::updatePlayMode,
                    onOctaveChange = { octave ->
                        synth.allNotesOff(TOUCH_SOURCE_ID)
                        screenState = screenState.copy(octave = octave.coerceIn(0, 8))
                    },
                    onSequencerToggle = {
                        val next = !screenState.sequencerRunning
                        audioOutput.setSequencerPlaying(next)
                        screenState = screenState.copy(sequencerRunning = next)
                    },
                    onStepToggle = { step ->
                        val selection = audioOutput.sequencerPatternSelection
                        if (screenState.activeSequenceId == selection.id &&
                            screenState.sequencerStepCount == selection.stepCount &&
                            step in 0 until selection.stepCount
                        ) {
                            val newEnabled = step !in screenState.enabledSteps
                            val ticket = audioOutput.setSequencerStepEnabled(step, newEnabled)
                            if (ticket != null) {
                                pendingToggleIntents.record(
                                    selection.revision, step, newEnabled, ticket,
                                )
                                val updated = screenState.enabledSteps.toMutableSet().apply {
                                    if (newEnabled) add(step) else remove(step)
                                }
                                screenState = screenState.copy(enabledSteps = updated)
                            }
                        }
                    },
                    onSequencerTempoChange = { bpm ->
                        val next = bpm.coerceIn(20, 300)
                        audioOutput.setSequencerTempo(next)
                        screenState = screenState.copy(sequencerTempo = next)
                    },
                    onSequencerLengthChange = { requested ->
                        // Render-worker snapshot remains authoritative. The UI poll
                        // publishes the new length and any truncated step state.
                        audioOutput.setSequencerStepCount(requested)
                    },
                    onSequencerStepNotesChange = ::updateSequenceStepNotes,
                    onSaveSequence = ::saveCurrentSequence,
                    onPreviousPatch = { selectFactoryPatch(patchIndex - 1) },
                    onNextPatch = { selectFactoryPatch(patchIndex + 1) },
                    onSavePatch = ::saveCurrentPatch,
                    onSelectPatch = ::activatePatch,
                    onSelectSequence = ::activateSequence,
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
            parameters = ParameterId.entries.associateWith { synth.parameter(it) },
            presetIndex = patchIndex,
            presetCount = patches.size,
            activePatchId = patch.id,
            patchFeedback = null,
        )
    }

    /** Load the persisted bank without blocking either Compose or the audio thread. */
    private fun refreshPatchChoices() {
        lifecycleScope.launch {
            when (val result = userPatches.listAll()) {
                is PatchOutcome.Success -> {
                    val factoryIds = FactoryPresets.ALL.map { it.id }.toSet()
                    screenState = screenState.copy(
                        patchChoices = result.value.map { patch ->
                            PatchChoice(patch.id, patch.name, patch.id in factoryIds)
                        }
                    )
                }
                is PatchOutcome.Failure -> {
                    screenState = screenState.copy(patchFeedback = result.message)
                }
            }
        }
    }

    /** User patterns are read from app-private storage off the UI/audio threads. */
    private fun refreshSequenceChoices() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { userSequences.list() } }
            result.onSuccess { saved ->
                val factories = audioOutput.sequencerFactoryPatterns.map {
                    SequenceChoice(it.id, it.name)
                }
                screenState = screenState.copy(
                    sequenceChoices = factories + saved.map { SequenceChoice(it.id, it.name) }
                )
            }.onFailure { failure ->
                screenState = screenState.copy(
                    patchFeedback = "Sequence library unavailable: ${failure.message ?: "read error"}"
                )
            }
        }
    }

    /** An immediate UI preview is reconciled with the worker's immutable snapshot. */
    private fun updateSequenceStepNotes(step: Int, notes: List<SequencerNote>) {
        val selection = audioOutput.sequencerPatternSelection
        if (!audioOutput.setSequencerStepNotes(step, notes)) {
            screenState = screenState.copy(patchFeedback = "Invalid step edit; nothing changed.")
            return
        }
        if (pendingStepRevision != selection.revision) pendingStepNotes.clear()
        pendingStepRevision = selection.revision
        // A subsequent note edit determines the step's enable state, superseding
        // any earlier pending toggle on this same step.
        pendingToggleIntents.clearStep(step)
        val frozen = notes.toList()
        pendingStepNotes[step] = frozen
        val visible = screenState.sequenceNotesByStep
        if (step in visible.indices) {
            screenState = screenState.copy(
                sequenceNotesByStep = visible.toMutableList().apply { this[step] = frozen },
                enabledSteps = if (frozen.isEmpty()) screenState.enabledSteps - step
                    else screenState.enabledSteps + step,
            )
        }
    }

    /** Save source notes and mute state independently so hidden notes survive reload. */
    private fun saveCurrentSequence(name: String) {
        if (sequenceSaveInFlight) return
        val selected = audioOutput.sequencerPatternSelection
        if (screenState.activeSequenceId != selected.id ||
            screenState.sequencerStepCount != selected.stepCount
        ) {
            screenState = screenState.copy(patchFeedback = "Wait for the sequence to finish loading.")
            return
        }
        val title = name.trim()
        val bankIds = audioOutput.sequencerFactoryPatterns.map { it.id }.toSet()
        val stored = runCatching {
            // The editor may have submitted a valid edit that has not yet reached
            // the audio worker. Capture that optimistic note snapshot too.
            val notes = selected.sourcePattern.steps.mapIndexed { index, step ->
                SequencerStep(screenState.sequenceNotesByStep.getOrNull(index) ?: step.notes)
            }
            StepPattern(notes, selected.sourcePattern.subdivisionsPerBeat)
        }
        if (stored.isFailure) {
            screenState = screenState.copy(patchFeedback = "Could not capture sequence: ${stored.exceptionOrNull()?.message}")
            return
        }
        val pattern = stored.getOrThrow()
        val savedEnabledSteps = screenState.enabledSteps.toSet()
        val bpm = screenState.sequencerTempo.toDouble()
        val swing = selected.swing
        sequenceSaveInFlight = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val used = bankIds + userSequences.list().map { it.id }
                        val slug = title.lowercase(Locale.ROOT)
                            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)
                            .ifBlank { "user-sequence" }
                        var id = slug
                        var suffix = 2
                        while (id in used) {
                            id = "${slug.take(55)}-${suffix++}"
                        }
                        UserSequence(
                            id, title, pattern, bpm = bpm, swing = swing,
                            enabledSteps = savedEnabledSteps,
                        ).also {
                            userSequences.save(it)
                        }
                    }
                }
                result.onSuccess { saved ->
                    screenState = screenState.copy(patchFeedback = "Saved sequence ${saved.name}")
                    refreshSequenceChoices()
                }.onFailure { failure ->
                    screenState = screenState.copy(
                        patchFeedback = "Could not save sequence: ${failure.message ?: "storage error"}"
                    )
                }
            } finally {
                sequenceSaveInFlight = false
            }
        }
    }

    /** Factory selection is instant; user-pattern reads are cancellable by request order. */
    private fun activateSequence(id: String) {
        val request = ++sequenceSelectionToken
        pendingStepNotes.clear()
        pendingStepRevision = null
        pendingToggleIntents.clear()
        if (audioOutput.sequencerFactoryPatterns.any { it.id == id }) {
            audioOutput.selectFactoryPattern(id)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { userSequences.load(id) } }
            if (request != sequenceSelectionToken) return@launch
            result.onSuccess { saved ->
                if (saved == null || !audioOutput.loadSequencerPattern(
                        saved.id, saved.name, saved.pattern, saved.bpm, saved.swing,
                        saved.enabledSteps,
                    )
                ) {
                    screenState = screenState.copy(patchFeedback = "Sequence not found or invalid.")
                }
            }.onFailure { failure ->
                screenState = screenState.copy(
                    patchFeedback = "Could not load sequence: ${failure.message ?: "read error"}"
                )
            }
        }
    }

    /** Save a snapshot under a collision-safe user patch ID; factory data is immutable. */
    private fun saveCurrentPatch(name: String) {
        lifecycleScope.launch {
            when (val result = userPatches.saveCurrentAs(name)) {
                is PatchOutcome.Success -> {
                    val saved = result.value.patch
                    screenState = screenState.copy(
                        patchName = saved.name,
                        activePatchId = saved.id,
                        patchFeedback = if (result.value.renamedOnCollision) {
                            "Saved as ${saved.name} (new copy)"
                        } else {
                            "Saved ${saved.name}"
                        },
                    )
                    refreshPatchChoices()
                }
                is PatchOutcome.Failure -> {
                    screenState = screenState.copy(patchFeedback = result.message)
                }
            }
        }
    }

    /** Select factory and user patches through the same validated storage interface. */
    private fun activatePatch(id: String) {
        lifecycleScope.launch {
            when (val result = userPatches.activate(id)) {
                is PatchOutcome.Success -> {
                    val patch = result.value
                    synth.allNotesOff()
                    val factoryIndex = FactoryPresets.ALL.indexOfFirst { it.id == patch.id }
                    if (factoryIndex >= 0) patchIndex = factoryIndex
                    screenState = screenState.copy(
                        patchName = patch.name,
                        parameters = ParameterId.entries.associateWith { synth.parameter(it) },
                        presetIndex = patchIndex,
                        presetCount = FactoryPresets.ALL.size,
                        activePatchId = patch.id,
                        patchFeedback = "Loaded ${patch.name}",
                    )
                }
                is PatchOutcome.Failure -> {
                    screenState = screenState.copy(patchFeedback = result.message)
                }
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
        audioOutput.setSequencerPlaying(false)
        screenState = screenState.copy(sequencerRunning = false, sequencerStep = -1)
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
