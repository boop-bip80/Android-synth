package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.music.SequencerNote
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.pow
import kotlin.math.roundToInt

enum class PlayMode { POLY, MONO, LEGATO }

/** The persistence layer owns IDs and patch data; UI needs only selectable metadata. */
data class PatchChoice(val id: String, val name: String, val isFactory: Boolean)

/** Stable transport IDs are supplied by the host; the UI never imports factory pattern data. */
data class SequenceChoice(val id: String, val name: String)

data class OrbitalUiState(
    val patchName: String = "AURORA",
    val playMode: PlayMode = PlayMode.POLY,
    val parameters: Map<ParameterId, Float> = emptyMap(),
    val octave: Int = 4,
    val sequencerRunning: Boolean = false,
    val enabledSteps: Set<Int> = emptySet(),
    val sequencerStep: Int = -1,
    val sequencerStepCount: Int = 16,
    val sequencerTempo: Int = 112,
    /** Immutable sequencer notes supplied by the render-worker snapshot. */
    val sequenceNotesByStep: List<List<SequencerNote>> = emptyList(),
    val presetIndex: Int = 0,
    val presetCount: Int = 0,
    val patchChoices: List<PatchChoice> = emptyList(),
    val activePatchId: String? = null,
    val sequenceChoices: List<SequenceChoice> = emptyList(),
    val activeSequenceId: String? = null,
    val patchFeedback: String? = null,
)

private enum class Tab(val title: String) {
    SOUND("SOUND"), PLAY("PLAY"), SEQ("SEQ"), FX("FX")
}

private val MainGradient = Brush.verticalGradient(
    listOf(OrbitalPalette.Void, OrbitalPalette.Midnight, OrbitalPalette.Void),
)

/**
 * Public UI entry point. Everything audible crosses the supplied callbacks;
 * UI and DSP never secretly instantiate separate audio engines.
 */
@Composable
fun OrbitalScreen(
    state: OrbitalUiState,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    onParameterChange: (ParameterId, Float) -> Unit,
    onModeChange: (PlayMode) -> Unit,
    onOctaveChange: (Int) -> Unit,
    onSequencerToggle: (() -> Unit)? = null,
    onStepToggle: ((Int) -> Unit)? = null,
    onSequencerTempoChange: ((Int) -> Unit)? = null,
    onPreviousPatch: (() -> Unit)? = null,
    onNextPatch: (() -> Unit)? = null,
    onSavePatch: ((String) -> Unit)? = null,
    onSelectPatch: ((String) -> Unit)? = null,
    onSelectSequence: ((String) -> Unit)? = null,
    onSequencerLengthChange: ((Int) -> Unit)? = null,
    onSequencerStepNotesChange: ((Int, List<SequencerNote>) -> Unit)? = null,
    onSaveSequence: ((String) -> Unit)? = null,
) {
    var selectedTab by remember { mutableStateOf(Tab.SOUND) }
    var showPatchLibrary by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var newPatchName by remember { mutableStateOf("") }
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.patchFeedback) {
        state.patchFeedback?.takeIf { it.isNotBlank() }?.let { snackbarHost.showSnackbar(it) }
    }
    val externalSequencer = onSequencerToggle != null && onStepToggle != null
    val parameter: (ParameterId) -> Float = { id ->
        (state.parameters[id] ?: defaultValue(id)).coerceIn(0f, 1f)
    }
    val morph = parameter(ParameterId.MORPH)
    val move = parameter(ParameterId.MOVEMENT)
    val currentOn by rememberUpdatedState(onNoteOn)
    val currentOff by rememberUpdatedState(onNoteOff)
    var sequencerPlaying by remember { mutableStateOf(false) }
    var tempo by remember { mutableIntStateOf(112) }
    var playhead by remember { mutableIntStateOf(-1) }
    // The standalone demo also supports longer patterns when no audio transport is supplied.
    val visibleStepCount = state.sequencerStepCount.coerceIn(1, 64)
    val steps = remember {
        mutableStateListOf<Boolean>().apply {
            val initialHits = setOf(0, 3, 5, 8, 10, 13, 15)
            repeat(64) { index -> add(index % 16 in initialHits) }
        }
    }

    // Stop/cancellation always sends note-off, including while the pattern length changes.
    LaunchedEffect(externalSequencer, sequencerPlaying, tempo, state.octave, visibleStepCount) {
        if (externalSequencer || !sequencerPlaying) {
            playhead = -1
            return@LaunchedEffect
        }
        val stepMs = (60_000L / tempo / 4L).coerceAtLeast(45L)
        while (isActive) {
            playhead = (playhead + 1) % visibleStepCount
            val current = playhead
            val enabled = steps[current]
            val note = (((state.octave + 1) * 12) + intArrayOf(
                0, 3, 7, 10, 12, 10, 7, 3,
                0, 5, 7, 12, 10, 7, 5, 3,
            )[current % 16]).coerceIn(0, 127)
            if (enabled) currentOn(note)
            try {
                delay(stepMs * 3 / 4)
            } finally {
                if (enabled) currentOff(note)
            }
            delay(stepMs / 4)
        }
    }

    val editorScroll = rememberScrollState()
    LaunchedEffect(selectedTab) { editorScroll.scrollTo(0) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MainGradient)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val compactHeight = maxHeight < 500.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactHeight) 6.dp else 12.dp),
        ) {
        Header(
            patch = state.patchName,
            onOpenLibrary = { showPatchLibrary = true },
            libraryEnabled = onSavePatch != null || onSelectPatch != null,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Tab.entries.forEach { tab ->
                val active = selectedTab == tab
                Column(
                    Modifier
                        .weight(1f)
                        .semantics {
                            selected = active
                            contentDescription = "Tab " + tab.title
                        }
                        .clickable(role = Role.Tab) { selectedTab = tab }
                        .heightIn(min = 48.dp)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        tab.title,
                        color = if (active) OrbitalPalette.Text else OrbitalPalette.Muted,
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(.70f)
                            .height(2.dp)
                            .background(if (active) OrbitalPalette.Cyan else Color.Transparent),
                    )
                }
            }
        }

        // Editor area is the only vertical scroller. Performance controls remain anchored below it.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(editorScroll)
                .padding(bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        when (selectedTab) {
            Tab.SOUND -> {
                BoxWithConstraints(Modifier.fillMaxWidth().height(244.dp)) {
                    val layoutWidth = maxWidth
                    OrbitalDisplay(
                        morph = morph,
                        movement = move,
                        modifier = Modifier.fillMaxSize(),
                        onExpressionChange = { nextMorph, nextMovement ->
                            onParameterChange(ParameterId.MORPH, nextMorph)
                            onParameterChange(ParameterId.MOVEMENT, nextMovement)
                        },
                    )
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp)) {
                        Label("DRAG ORBIT  /  X: MORPH  /  Y: MOTION")
                    }
                    Column(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 18.dp, start = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Label("WAVETABLE  /  FACTORY BANK")
                        Row(
                            Modifier.width(layoutWidth * .67f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            PresetArrow(
                                symbol = "‹",
                                description = "Previous factory preset",
                                enabled = onPreviousPatch != null,
                                onClick = { onPreviousPatch?.invoke() },
                            )
                            Text(
                                state.patchName,
                                modifier = Modifier.weight(1f),
                                color = OrbitalPalette.Text,
                                textAlign = TextAlign.Center,
                                fontSize = if (layoutWidth < 380.dp) 14.sp else 17.sp,
                                fontWeight = FontWeight.Light,
                                letterSpacing = if (layoutWidth < 380.dp) 1.sp else 2.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            PresetArrow(
                                symbol = "›",
                                description = "Next factory preset",
                                enabled = onNextPatch != null,
                                onClick = { onNextPatch?.invoke() },
                            )
                        }
                        if (state.presetCount > 0) {
                            Label(
                                "PATCH " +
                                    (state.presetIndex.coerceIn(0, state.presetCount - 1) + 1) +
                                    " / " + state.presetCount,
                            )
                        }
                    }
                    Column(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 19.dp, end = 8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Label("ENGINE")
                        Label("WAVETABLE")
                        Label("SPECTRAL")
                        Label("HARMONIC")
                    }
                }
                ModulationGraph(morph, move, Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    val knobSize = 55.dp
                    NeonKnob("WAVE", parameter(ParameterId.WAVE), percent(parameter(ParameterId.WAVE)), OrbitalPalette.Violet,
                        { onParameterChange(ParameterId.WAVE, it) }, Modifier.weight(1f), knobSize)
                    NeonKnob("CUTOFF", parameter(ParameterId.CUTOFF), cutoffLabel(parameter(ParameterId.CUTOFF)), OrbitalPalette.Cyan,
                        { onParameterChange(ParameterId.CUTOFF, it) }, Modifier.weight(1f), knobSize)
                    NeonKnob("RESO", parameter(ParameterId.RESONANCE), percent(parameter(ParameterId.RESONANCE)), OrbitalPalette.Gold,
                        { onParameterChange(ParameterId.RESONANCE, it) }, Modifier.weight(1f), knobSize)
                    NeonKnob("MOTION", move, percent(move), OrbitalPalette.Violet,
                        { onParameterChange(ParameterId.MOVEMENT, it) }, Modifier.weight(1f), knobSize)
                    NeonKnob("FX", parameter(ParameterId.FX_AMOUNT), percent(parameter(ParameterId.FX_AMOUNT)), OrbitalPalette.Cyan,
                        { onParameterChange(ParameterId.FX_AMOUNT, it) }, Modifier.weight(1f), knobSize)
                }
                // Voice engine parameters appear as luminous knobs when present.
                val voiceParameters = listOf("UNISON", "DETUNE").mapNotNull { name ->
                    ParameterId.entries.firstOrNull { it.name == name }?.let { id -> name to id }
                }
                if (voiceParameters.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        voiceParameters.forEach { (name, id) ->
                            NeonKnob(
                                name, parameter(id), percent(parameter(id)),
                                if (name == "UNISON") OrbitalPalette.Gold else OrbitalPalette.Violet,
                                { onParameterChange(id, it) }, Modifier.weight(1f), 62.dp,
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.weight(1f).clickable(role = Role.Button) { selectedTab = Tab.FX }
                        .semantics { contentDescription = "Edit envelope" }) {
                        SignalTile("ENV", parameter(ParameterId.ATTACK), OrbitalPalette.Violet, Modifier.fillMaxWidth())
                    }
                    Box(Modifier.weight(1f).clickable(role = Role.Button) { selectedTab = Tab.FX }
                        .semantics { contentDescription = "Edit LFO" }) {
                        SignalTile("LFO", parameter(ParameterId.LFO_RATE), OrbitalPalette.Cyan, Modifier.fillMaxWidth())
                    }
                    Box(Modifier.weight(1f).clickable(role = Role.Button) { selectedTab = Tab.PLAY }
                        .semantics { contentDescription = "Edit glide" }) {
                        SignalTile("GLIDE", parameter(ParameterId.GLIDE), OrbitalPalette.Gold, Modifier.fillMaxWidth())
                    }
                    Box(Modifier.weight(1f).clickable(role = Role.Button) { selectedTab = Tab.SEQ }
                        .semantics { contentDescription = "Edit sequencer" }) {
                        SignalTile("MOD", move, OrbitalPalette.Violet, Modifier.fillMaxWidth())
                    }
                }
            }
            Tab.PLAY -> PlayPanel(
                state.playMode,
                morph,
                move,
                parameter(ParameterId.GLIDE),
                onModeChange,
                onParameterChange,
            )
            Tab.SEQ -> SequencerPanel(
                if (externalSequencer) List(visibleStepCount) { it in state.enabledSteps }
                else steps.take(visibleStepCount),
                if (externalSequencer) state.sequencerStep else playhead,
                if (externalSequencer) state.sequencerRunning else sequencerPlaying,
                {
                    if (externalSequencer) onSequencerToggle?.invoke()
                    else sequencerPlaying = !sequencerPlaying
                },
                if (externalSequencer) state.sequencerTempo else tempo,
                {
                    if (externalSequencer) onSequencerTempoChange?.invoke(it)
                    else tempo = it
                },
                {
                    if (externalSequencer) onStepToggle?.invoke(it)
                    else steps[it] = !steps[it]
                },
                state.sequenceChoices,
                state.activeSequenceId,
                onSelectSequence,
                onSequencerLengthChange,
                state.sequenceNotesByStep,
                onSequencerStepNotesChange,
                ((state.octave + 1) * 12).coerceIn(0, 127),
                onSaveSequence,
            )
            Tab.FX -> FxPanel(parameter, onParameterChange)
        }
        }

        // The keyboard stays reachable in portrait and compact landscape layouts.
        OrbitalKeyboard(
            octave = state.octave.coerceIn(0, 8),
            onNoteOn = onNoteOn,
            onNoteOff = onNoteOff,
            modifier = Modifier.fillMaxWidth(),
            preferredHeight = if (compactHeight) 94.dp else null,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Label("OCT")
            Pill("−", false, { onOctaveChange((state.octave - 1).coerceAtLeast(0)) }, description = "Lower octave")
            Text(
                state.octave.toString(),
                color = OrbitalPalette.Text,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            Pill("+", false, { onOctaveChange((state.octave + 1).coerceAtMost(8)) }, description = "Raise octave")
            Spacer(Modifier.weight(1f))
            PlayMode.entries.forEach { mode ->
                Pill(mode.name, state.playMode == mode, { onModeChange(mode) }, role = Role.RadioButton)
            }
        }
        Spacer(Modifier.height(if (compactHeight) 0.dp else 6.dp))
        }
        SnackbarHost(hostState = snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (showPatchLibrary) {
        PatchLibraryDialog(
            patches = state.patchChoices,
            activeId = state.activePatchId,
            canSelect = onSelectPatch != null,
            canSave = onSavePatch != null,
            onDismiss = { showPatchLibrary = false },
            onSaveAs = {
                showPatchLibrary = false
                newPatchName = state.patchName.take(64)
                showSaveDialog = true
            },
            onSelect = { id ->
                showPatchLibrary = false
                onSelectPatch?.invoke(id)
            },
        )
    }
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = OrbitalPalette.Panel,
            title = { Text("SAVE PATCH", color = OrbitalPalette.Text, letterSpacing = 1.5.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Label("SAVE THE CURRENT SOUND UNDER A NEW NAME")
                    OutlinedTextField(
                        value = newPatchName,
                        onValueChange = { newPatchName = it.take(64) },
                        singleLine = true,
                        label = { Text("Patch name") },
                        modifier = Modifier.fillMaxWidth().testTag("patchNameInput"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newPatchName.isNotBlank(),
                    onClick = {
                        val name = newPatchName.trim()
                        if (name.isNotEmpty()) {
                            showSaveDialog = false
                            onSavePatch?.invoke(name)
                        }
                    },
                ) { Text("SAVE AS", color = OrbitalPalette.Cyan) }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("CANCEL") } },
        )
    }
}

/** User patches and factory presets use the same selectable bank, but never share IDs. */
@Composable
private fun PatchLibraryDialog(
    patches: List<PatchChoice>,
    activeId: String?,
    canSelect: Boolean,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSaveAs: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Keep the patch list scrollable without letting the dialog cover a short landscape display.
        val listMaxHeight = (maxHeight - 200.dp).coerceIn(100.dp, 430.dp)
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
            shape = RoundedCornerShape(22.dp),
            color = OrbitalPalette.Panel,
            shadowElevation = 14.dp,
        ) {
            Column(
                Modifier.padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "PATCH LIBRARY",
                        color = OrbitalPalette.Text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.6.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("CLOSE") }
                }
                Label("FACTORY + PERSONAL SOUNDS")
                Column(
                    Modifier.heightIn(max = listMaxHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (patches.isEmpty()) {
                        Text(
                            "No patches loaded yet. Save your current sound to begin a personal bank.",
                            color = OrbitalPalette.Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    listOf(true, false).forEach { factory ->
                        val group = patches.filter { it.isFactory == factory }
                        if (group.isNotEmpty()) {
                            Text(
                                if (factory) "FACTORY" else "MY PATCHES",
                                color = if (factory) OrbitalPalette.Cyan else OrbitalPalette.Gold,
                                fontSize = 10.sp,
                                letterSpacing = 1.2.sp,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                            group.forEach { choice ->
                                val active = choice.id == activeId
                                val tint = if (choice.isFactory) OrbitalPalette.Cyan else OrbitalPalette.Gold
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .border(
                                            1.dp,
                                            if (active) tint else OrbitalPalette.PanelEdge,
                                            RoundedCornerShape(12.dp),
                                        )
                                        .background(
                                            if (active) tint.copy(alpha = .15f) else OrbitalPalette.Void,
                                            RoundedCornerShape(12.dp),
                                        )
                                        .clickable(enabled = canSelect, role = Role.Button) { onSelect(choice.id) }
                                        .semantics {
                                            contentDescription =
                                                "Load ${choice.name}, ${if (choice.isFactory) "factory" else "user"} patch"
                                            selected = active
                                        }
                                        .padding(horizontal = 13.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("◉", color = tint, fontSize = 11.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        choice.name,
                                        modifier = Modifier.weight(1f),
                                        color = OrbitalPalette.Text,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (active) Text("ACTIVE", color = tint, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
                if (canSave) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .border(1.dp, OrbitalPalette.Gold, RoundedCornerShape(12.dp))
                            .background(OrbitalPalette.Gold.copy(alpha = .12f), RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button, onClick = onSaveAs)
                            .semantics { contentDescription = "Save current sound as a new patch" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+ SAVE CURRENT SOUND", color = OrbitalPalette.Gold,
                            letterSpacing = 1.sp, fontSize = 12.sp)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun PresetArrow(
    symbol: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .border(
                1.dp,
                if (enabled) OrbitalPalette.Cyan.copy(alpha = .60f) else OrbitalPalette.PanelEdge,
                RoundedCornerShape(12.dp),
            )
            .background(
                if (enabled) OrbitalPalette.Blue.copy(alpha = .16f)
                else OrbitalPalette.Panel.copy(alpha = .42f),
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            color = if (enabled) OrbitalPalette.Cyan else OrbitalPalette.Muted.copy(alpha = .45f),
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Header(patch: String, onOpenLibrary: () -> Unit, libraryEnabled: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(31.dp)) {
            drawCircle(OrbitalPalette.Blue, style = Stroke(1.3.dp.toPx()))
            drawLine(
                OrbitalPalette.Cyan,
                Offset(size.width * .2f, size.height * .8f),
                Offset(size.width * .8f, size.height * .2f),
                strokeWidth = 1.3.dp.toPx(),
            )
            drawCircle(OrbitalPalette.Gold, radius = 2.dp.toPx())
        }
        Spacer(Modifier.weight(1f))
        Text(
            "O R B I T A L",
            color = OrbitalPalette.Text,
            fontSize = 19.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            letterSpacing = 2.0.sp,
        )
        Spacer(Modifier.weight(1f))
        Text("●", color = OrbitalPalette.Teal, fontSize = 10.sp,
            modifier = Modifier.semantics { contentDescription = "Patch loaded: $patch" })
        Box(
            Modifier
                .size(48.dp)
                .clickable(enabled = libraryEnabled, role = Role.Button, onClick = onOpenLibrary)
                .semantics { contentDescription = "Open patch library" },
            contentAlignment = Alignment.Center,
        ) {
            Text("☰", color = if (libraryEnabled) OrbitalPalette.Cyan else OrbitalPalette.Muted,
                fontSize = 22.sp)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 9.sp, letterSpacing = .95.sp, color = OrbitalPalette.Muted)
}

@Composable
private fun Pill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    description: String? = null,
    role: Role = Role.Button,
) {
    Box(
        Modifier
            .border(
                1.dp,
                if (active) OrbitalPalette.Cyan else OrbitalPalette.PanelEdge,
                RoundedCornerShape(50),
            )
            .background(
                if (active) OrbitalPalette.Cyan.copy(alpha = .16f)
                else OrbitalPalette.Panel.copy(alpha = .45f),
                RoundedCornerShape(50),
            )
            .clickable(role = role, onClick = onClick)
            .semantics {
                if (role == Role.RadioButton) selected = active
                if (description != null) contentDescription = description
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 9.sp,
            letterSpacing = .65.sp,
            color = if (active) Color.White else OrbitalPalette.Muted,
        )
    }
}

@Composable
private fun PlayPanel(
    mode: PlayMode,
    morph: Float,
    movement: Float,
    glide: Float,
    onModeChange: (PlayMode) -> Unit,
    onParameterChange: (ParameterId, Float) -> Unit,
) {
    val updateParameter by rememberUpdatedState(onParameterChange)
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("PERFORMANCE", color = OrbitalPalette.Text, fontSize = 13.sp, letterSpacing = 2.sp)
        OrbitalDisplay(morph, movement, Modifier.fillMaxWidth().height(188.dp))
        Label("XY EXPRESSION  /  HORIZONTAL: MORPH  /  VERTICAL: MOVEMENT")
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(168.dp)
                .border(1.dp, OrbitalPalette.PanelEdge, RoundedCornerShape(16.dp))
                .background(OrbitalPalette.Panel, RoundedCornerShape(16.dp))
                .semantics {
                    contentDescription = "Expression pad. Morph ${percent(morph)}; movement ${percent(movement)}."
                    customActions = listOf(
                        CustomAccessibilityAction("Increase morph") {
                            updateParameter(ParameterId.MORPH, (morph + .05f).coerceAtMost(1f)); true
                        },
                        CustomAccessibilityAction("Decrease morph") {
                            updateParameter(ParameterId.MORPH, (morph - .05f).coerceAtLeast(0f)); true
                        },
                        CustomAccessibilityAction("Increase movement") {
                            updateParameter(ParameterId.MOVEMENT, (movement + .05f).coerceAtMost(1f)); true
                        },
                        CustomAccessibilityAction("Decrease movement") {
                            updateParameter(ParameterId.MOVEMENT, (movement - .05f).coerceAtLeast(0f)); true
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { point ->
                        val p = expressionCoordinates(
                            point.x, point.y, size.width.toFloat(), size.height.toFloat(),
                        )
                        updateParameter(ParameterId.MORPH, p.first)
                        updateParameter(ParameterId.MOVEMENT, p.second)
                    })
                }
                .pointerInput(Unit) {
                    fun update(x: Float, y: Float) {
                        val p = expressionCoordinates(x, y, size.width.toFloat(), size.height.toFloat())
                        updateParameter(ParameterId.MORPH, p.first)
                        updateParameter(ParameterId.MOVEMENT, p.second)
                    }
                    detectDragGestures(
                        onDragStart = { update(it.x, it.y) },
                        onDrag = { change, _ ->
                            update(change.position.x, change.position.y)
                            change.consume()
                        },
                    )
                },
        ) {
            for (i in 1..7) {
                drawLine(OrbitalPalette.Blue.copy(alpha = .17f),
                    Offset(size.width * i / 8f, 0f),
                    Offset(size.width * i / 8f, size.height))
            }
            for (i in 1..4) {
                drawLine(OrbitalPalette.Blue.copy(alpha = .17f),
                    Offset(0f, size.height * i / 5f),
                    Offset(size.width, size.height * i / 5f))
            }
            val p = Offset(size.width * morph, size.height * (1f - movement))
            drawLine(OrbitalPalette.Cyan.copy(alpha = .50f), Offset(p.x, 0f), Offset(p.x, size.height))
            drawLine(OrbitalPalette.Violet.copy(alpha = .50f), Offset(0f, p.y), Offset(size.width, p.y))
            drawCircle(OrbitalPalette.Cyan.copy(alpha = .21f), 20.dp.toPx(), p)
            drawCircle(OrbitalPalette.Cyan, 7.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
        Text("VOICE MODE", color = OrbitalPalette.Muted, fontSize = 10.sp, letterSpacing = 1.2.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayMode.entries.forEach { option ->
                Pill(option.name, mode == option, { onModeChange(option) }, role = Role.RadioButton)
            }
        }
        ParameterSlider("GLIDE", glide, ParameterId.GLIDE, onParameterChange, "Slow pitch transition")
    }
}

/** Pointer math is independent of display density and clamps drags off the canvas. */
internal fun expressionCoordinates(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
): Pair<Float, Float> {
    if (!x.isFinite() || !y.isFinite() ||
        !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f
    ) {
        return .5f to .5f
    }
    return (x / width).coerceIn(0f, 1f) to (1f - y / height).coerceIn(0f, 1f)
}

/** Keep only unique, named entries; never dispatch a stale or invalid ID from the view. */
internal fun validSequenceChoices(choices: List<SequenceChoice>): List<SequenceChoice> =
    choices.filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }

@Composable
private fun SequenceSelector(
    choices: List<SequenceChoice>,
    activeId: String?,
    onSelect: ((String) -> Unit)?,
) {
    val presented = validSequenceChoices(choices)
    val selected = presented.firstOrNull { it.id == activeId }
    val enabled = onSelect != null && presented.isNotEmpty()
    var showingList by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) showingList = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("SEQUENCE LIBRARY")
            Spacer(Modifier.weight(1f))
            Label(if (presented.isEmpty()) "NO PATTERNS" else "${presented.size} AVAILABLE")
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .border(
                    1.dp,
                    if (enabled) OrbitalPalette.Cyan.copy(alpha = .6f) else OrbitalPalette.PanelEdge,
                    RoundedCornerShape(12.dp),
                )
                .background(OrbitalPalette.Panel, RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, role = Role.Button) { showingList = true }
                .semantics {
                    contentDescription = if (presented.isEmpty()) "Factory sequences unavailable"
                    else "Choose factory sequence"
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("◉", color = if (enabled) OrbitalPalette.Gold else OrbitalPalette.Muted, fontSize = 19.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    selected?.name ?: if (presented.isEmpty()) "PATTERNS UNAVAILABLE" else "CHOOSE PATTERN",
                    color = if (selected != null) OrbitalPalette.Text else OrbitalPalette.Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Label(if (selected != null) "ACTIVE PATTERN" else "BUILT-IN ARRANGEMENTS")
            }
            Text("⌄", color = if (enabled) OrbitalPalette.Cyan else OrbitalPalette.Muted, fontSize = 22.sp)
        }
    }
    if (showingList && enabled) {
        Dialog(onDismissRequest = { showingList = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
                shape = RoundedCornerShape(18.dp),
                color = OrbitalPalette.Panel,
            ) {
                Column(
                    Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        "CHOOSE SEQUENCE",
                        color = OrbitalPalette.Text,
                        fontSize = 14.sp,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(bottom = 9.dp),
                    )
                    presented.forEach { choice ->
                        val isActive = choice.id == activeId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .border(
                                    1.dp,
                                    if (isActive) OrbitalPalette.Cyan else OrbitalPalette.PanelEdge,
                                    RoundedCornerShape(10.dp),
                                )
                                .background(
                                    if (isActive) OrbitalPalette.Cyan.copy(alpha = .15f)
                                    else OrbitalPalette.Void.copy(alpha = .40f),
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable(role = Role.RadioButton) {
                                    showingList = false
                                    // Recheck membership because entries can change while the dialog is open.
                                    if (choice.id != activeId && validSequenceChoices(choices).any { it.id == choice.id }) {
                                        onSelect?.invoke(choice.id)
                                    }
                                }
                                .semantics {
                                    selected = isActive
                                    contentDescription = "Select factory sequence ${choice.name}"
                                }
                                .padding(horizontal = 13.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(if (isActive) "●" else "○", color = if (isActive) OrbitalPalette.Cyan else OrbitalPalette.Muted)
                            Text(
                                choice.name,
                                color = OrbitalPalette.Text,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                            )
                            if (isActive) Label("ACTIVE")
                        }
                    }
                }
            }
        }
    }
}

/** Length requests are declarative: only the host transport changes the actual pattern. */
@Composable
private fun SequenceLengthPanel(
    stepCount: Int,
    onLengthChange: ((Int) -> Unit)?,
) {
    val count = stepCount.coerceIn(1, 64)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("PATTERN LENGTH")
            Spacer(Modifier.weight(1f))
            Label(count.toString() + " STEPS")
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(16, 32, 64).forEach { target ->
                val active = count == target
                val canChoose = onLengthChange != null && !active
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .border(
                            1.dp,
                            if (active) OrbitalPalette.Gold else OrbitalPalette.PanelEdge,
                            RoundedCornerShape(12.dp),
                        )
                        .background(
                            if (active) OrbitalPalette.Gold.copy(alpha = .13f)
                            else OrbitalPalette.Panel.copy(alpha = .65f),
                            RoundedCornerShape(12.dp),
                        )
                        .clickable(enabled = canChoose, role = Role.RadioButton) {
                            onLengthChange?.invoke(target)
                        }
                        .semantics {
                            selected = active
                            contentDescription = "Set sequence length to " + target + " steps"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        target.toString(),
                        color = if (active) OrbitalPalette.Gold else OrbitalPalette.Text,
                        fontSize = 12.sp,
                    )
                }
            }
            PresetArrow(
                symbol = "−",
                description = "Decrease sequence length",
                enabled = onLengthChange != null && count > 1,
                onClick = { if (count > 1) onLengthChange?.invoke(count - 1) },
            )
            PresetArrow(
                symbol = "+",
                description = "Increase sequence length",
                enabled = onLengthChange != null && count < 64,
                onClick = { if (count < 64) onLengthChange?.invoke(count + 1) },
            )
        }
    }
}

@Composable
private fun SequencerPanel(
    steps: List<Boolean>,
    playhead: Int,
    playing: Boolean,
    onTogglePlaying: () -> Unit,
    bpm: Int,
    onTempoChange: (Int) -> Unit,
    onStepToggle: (Int) -> Unit,
    sequenceChoices: List<SequenceChoice>,
    activeSequenceId: String?,
    onSelectSequence: ((String) -> Unit)?,
    onSequencerLengthChange: ((Int) -> Unit)?,
    sequenceNotesByStep: List<List<SequencerNote>>,
    onSequencerStepNotesChange: ((Int, List<SequencerNote>) -> Unit)?,
    defaultMidiNote: Int,
    onSaveSequence: ((String) -> Unit)?,
) {
    var selectedStep by remember(activeSequenceId, steps.size) { mutableIntStateOf(0) }
    var selectedNoteIndex by remember(activeSequenceId, selectedStep) { mutableIntStateOf(0) }
    var showSaveSequenceDialog by remember { mutableStateOf(false) }
    var sequenceName by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${steps.size}-STEP ORBIT", color = OrbitalPalette.Text, fontSize = 13.sp, letterSpacing = 1.9.sp)
            Spacer(Modifier.weight(1f))
            Pill(if (playing) "STOP" else "PLAY ▶", playing, onTogglePlaying)
        }
        SequenceSelector(sequenceChoices, activeSequenceId, onSelectSequence)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label("PERSIST THIS PATTERN")
            TextButton(
                enabled = onSaveSequence != null,
                onClick = {
                    sequenceName = ""
                    showSaveSequenceDialog = true
                },
                modifier = Modifier.semantics { contentDescription = "Save sequence as a new pattern" },
            ) { Text("SAVE SEQUENCE", color = OrbitalPalette.Gold) }
        }
        SequenceLengthPanel(steps.size, onSequencerLengthChange)
        Label("Tap to toggle a step. STEP DETAIL offers pitch, velocity, gate, chance and ties.")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Four 56dp touch targets per row on a phone, eight on a wider display.
            // The parent editor scrolls vertically, independently of the anchored keyboard.
            val columns = if (maxWidth >= 480.dp) 8 else 4
            val rowCount = (steps.size + columns - 1) / columns
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(rowCount) { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val firstIndex = row * columns
                        val countInRow = minOf(columns, steps.size - firstIndex)
                        repeat(countInRow) { column ->
                            val index = firstIndex + column
                            val enabled = steps[index]
                            val current = playhead == index
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .border(
                                        1.dp,
                                        if (current) OrbitalPalette.Gold
                                        else if (selectedStep == index) OrbitalPalette.Violet
                                        else if (enabled) OrbitalPalette.Cyan
                                        else OrbitalPalette.PanelEdge,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .background(
                                        if (enabled) OrbitalPalette.Cyan.copy(
                                            alpha = if (current) .36f else .14f,
                                        ) else OrbitalPalette.Panel,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .toggleable(value = enabled, role = Role.Checkbox) {
                                        onStepToggle(index)
                                    }
                                    .semantics {
                                        contentDescription = "Step ${index + 1} of ${steps.size}, " +
                                            (if (enabled) "on" else "off") +
                                            (if (current) ", playing" else "")
                                        customActions = listOf(
                                            CustomAccessibilityAction("Edit step " + (index + 1)) {
                                                selectedStep = index
                                                true
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        (index + 1).toString().padStart(2, '0'),
                                        color = if (enabled) OrbitalPalette.Text else OrbitalPalette.Muted,
                                        fontSize = 11.sp,
                                    )
                                    val voiceCount = sequenceNotesByStep.getOrNull(index)?.size ?: 0
                                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        repeat(voiceCount.coerceAtMost(4)) { voiceIndex ->
                                            Box(
                                                Modifier
                                                    .size(4.dp)
                                                    .background(
                                                        if (voiceIndex == 0) OrbitalPalette.Cyan
                                                        else if (voiceIndex == 1) OrbitalPalette.Violet
                                                        else OrbitalPalette.Gold,
                                                        RoundedCornerShape(50),
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // Preserve identical cell widths when the final row is partial.
                        repeat(columns - countInRow) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (showSaveSequenceDialog) {
            AlertDialog(
                onDismissRequest = { showSaveSequenceDialog = false },
                title = { Text("SAVE SEQUENCE") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sequenceName,
                            onValueChange = { sequenceName = it.take(80) },
                            label = { Text("Pattern name") },
                            singleLine = true,
                            modifier = Modifier.testTag("sequenceNameInput"),
                        )
                        Text(
                            "Creates a saved copy of the current sequence; factory patterns stay unchanged.",
                            fontSize = 12.sp,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = onSaveSequence != null && sequenceName.trim().isNotEmpty(),
                        onClick = {
                            val name = sequenceName.trim()
                            showSaveSequenceDialog = false
                            sequenceName = ""
                            onSaveSequence?.invoke(name)
                        },
                    ) { Text("SAVE") }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveSequenceDialog = false }) { Text("CANCEL") }
                },
            )
        }
        StepNoteInspector(
            step = selectedStep,
            stepCount = steps.size,
            stepNotes = sequenceNotesByStep.getOrNull(selectedStep).orEmpty(),
            selectedNoteIndex = selectedNoteIndex,
            onSelectNote = { selectedNoteIndex = it },
            onSelectStep = { selectedStep = it.coerceIn(0, steps.lastIndex) },
            onStepEdit = onSequencerStepNotesChange,
            defaultMidiNote = defaultMidiNote,
        )
        Label("TEMPO  /  " + bpm + " BPM")
        Slider(
            value = bpm.toFloat(),
            onValueChange = { onTempoChange(it.roundToInt().coerceIn(60, 180)) },
            valueRange = 60f..180f,
            modifier = Modifier.semantics { contentDescription = "Sequencer tempo, $bpm beats per minute" },
            colors = SliderDefaults.colors(
                thumbColor = OrbitalPalette.Cyan,
                activeTrackColor = OrbitalPalette.Cyan,
                inactiveTrackColor = OrbitalPalette.PanelEdge,
            ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, OrbitalPalette.PanelEdge, RoundedCornerShape(12.dp))
                .padding(14.dp),
        ) {
            Text(
                "Tap to mute/unmute. Use STEP DETAIL arrows to select notes and edit " +
                    "each part of the chord; connected audio transport owns playback timing.",
                color = OrbitalPalette.Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}


// Pitch, velocity, gate, probability and ties are edited as immutable note lists.
// The host queues changes to its audio worker; Compose never owns sequencer timing.
@Composable
private fun StepNoteInspector(
    step: Int,
    stepCount: Int,
    stepNotes: List<SequencerNote>,
    selectedNoteIndex: Int,
    onSelectNote: (Int) -> Unit,
    onSelectStep: (Int) -> Unit,
    onStepEdit: ((Int, List<SequencerNote>) -> Unit)?,
    defaultMidiNote: Int,
) {
    val editable = onStepEdit != null
    val voiceIndex = selectedNoteIndex.coerceIn(0, (stepNotes.size - 1).coerceAtLeast(0))
    val voice = stepNotes.getOrNull(voiceIndex)
    val occupied = stepNotes.map { it.midiNote }.toSet()
    val safeDefault = defaultMidiNote.coerceIn(0, 127)
    val freePitch = (safeDefault..127).firstOrNull { it !in occupied }
        ?: (0 until safeDefault).firstOrNull { it !in occupied }

    fun editVoice(updated: SequencerNote) {
        if (editable && voice != null &&
            (updated.midiNote == voice.midiNote ||
                stepNotes.indices.none { it != voiceIndex && stepNotes[it].midiNote == updated.midiNote })
        ) {
            onStepEdit?.invoke(step, stepNotes.mapIndexed { index, original ->
                if (index == voiceIndex) updated else original
            })
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, OrbitalPalette.Violet.copy(alpha = .55f), RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    listOf(OrbitalPalette.Violet.copy(alpha = .13f), OrbitalPalette.Panel),
                ),
                RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("STEP DETAIL", color = OrbitalPalette.Cyan, fontSize = 11.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.weight(1f))
            Text(
                (step + 1).toString().padStart(2, '0') + " / " + stepCount.toString().padStart(2, '0'),
                color = OrbitalPalette.Gold, fontSize = 12.sp,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PresetArrow("←", "Previous editable step", step > 0) { onSelectStep(step - 1) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (stepNotes.isEmpty()) "REST" else stepNotes.size.toString() + " NOTE VOICE(S)",
                    color = OrbitalPalette.Text,
                    fontSize = 11.sp, letterSpacing = .7.sp,
                )
            }
            PresetArrow("→", "Next editable step", step < stepCount - 1) {
                onSelectStep(step + 1)
            }
        }
        if (!editable) {
            Text(
                "Per-note editing requires a connected sequencer host.",
                color = OrbitalPalette.Muted,
                fontSize = 11.sp,
            )
        }
        if (voice == null) {
            Text("No stored notes on this step", color = OrbitalPalette.Muted, fontSize = 11.sp)
            TextButton(
                enabled = editable && freePitch != null,
                onClick = { freePitch?.let { onStepEdit?.invoke(step, listOf(SequencerNote(it))) } },
                modifier = Modifier.semantics { contentDescription = "Add note to selected step" },
            ) { Text("＋ ADD NOTE", color = if (editable) OrbitalPalette.Cyan else OrbitalPalette.Muted) }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("CHORD VOICE " + (voiceIndex + 1) + " / " + stepNotes.size)
                Spacer(Modifier.weight(1f))
                PresetArrow("‹", "Previous chord voice", voiceIndex > 0) {
                    onSelectNote(voiceIndex - 1)
                }
                Spacer(Modifier.width(6.dp))
                PresetArrow("›", "Next chord voice", voiceIndex < stepNotes.lastIndex) {
                    onSelectNote(voiceIndex + 1)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PresetArrow("−", "Decrease step note pitch", editable && voice.midiNote > 0 &&
                    voice.midiNote - 1 !in (occupied - voice.midiNote)) {
                    editVoice(voice.copy(midiNote = voice.midiNote - 1))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        midiPitchLabel(voice.midiNote),
                        color = OrbitalPalette.Text,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                    )
                }
                PresetArrow("+", "Increase step note pitch", editable && voice.midiNote < 127 &&
                    voice.midiNote + 1 !in (occupied - voice.midiNote)) {
                    editVoice(voice.copy(midiNote = voice.midiNote + 1))
                }
            }
            StepValueSlider(
                title = "VELOCITY",
                description = "Selected step note velocity",
                value = voice.velocity.toFloat(),
                range = 1f..127f,
                valueText = voice.velocity.toString() + " / 127",
                enabled = editable,
                onValueChange = { editVoice(voice.copy(velocity = it.roundToInt().coerceIn(1, 127))) },
            )
            StepValueSlider(
                title = "GATE",
                description = "Selected step note gate",
                value = voice.gate.toFloat(),
                range = .01f..1f,
                valueText = (voice.gate * 100).roundToInt().toString() + "%",
                enabled = editable,
                onValueChange = {
                    editVoice(voice.copy(gate = it.coerceIn(.01f, 1f).toDouble()))
                },
            )
            StepValueSlider(
                title = "CHANCE",
                description = "Selected step note probability",
                value = voice.probability.toFloat(),
                range = 0f..1f,
                valueText = (voice.probability * 100).roundToInt().toString() + "%",
                enabled = editable,
                onValueChange = {
                    editVoice(voice.copy(probability = it.coerceIn(0f, 1f).toDouble()))
                },
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (voice.tie) "TIE ON  ·  sustain from previous step" else "TIE OFF",
                    color = if (voice.tie) OrbitalPalette.Gold else OrbitalPalette.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = editable,
                    onClick = { editVoice(voice.copy(tie = !voice.tie)) },
                    modifier = Modifier.semantics { contentDescription = "Toggle selected note tie" },
                ) { Text(if (voice.tie) "UNTIE" else "TIE", color = OrbitalPalette.Cyan) }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    enabled = editable && stepNotes.size < 16 && freePitch != null,
                    onClick = {
                        freePitch?.let {
                            onSelectNote(stepNotes.size)
                            onStepEdit?.invoke(step, stepNotes + SequencerNote(it))
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "Add chord voice to selected step" },
                ) { Text("＋ ADD VOICE", color = OrbitalPalette.Cyan) }
                TextButton(
                    enabled = editable,
                    onClick = {
                        onSelectNote((voiceIndex - 1).coerceAtLeast(0))
                        onStepEdit?.invoke(step, stepNotes.filterIndexed { index, _ -> index != voiceIndex })
                    },
                    modifier = Modifier.semantics { contentDescription = "Remove selected chord voice" },
                ) { Text("REMOVE", color = OrbitalPalette.Gold) }
            }
        }
    }
}

private fun midiPitchLabel(pitch: Int): String {
    val names = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    return names[pitch % 12] + (pitch / 12 - 1) + "  •  MIDI " + pitch
}

@Composable
private fun StepValueSlider(
    title: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(title)
            Spacer(Modifier.weight(1f))
            Text(valueText, color = OrbitalPalette.Gold, fontSize = 11.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = description },
            colors = SliderDefaults.colors(
                thumbColor = OrbitalPalette.Cyan,
                activeTrackColor = OrbitalPalette.Violet,
                inactiveTrackColor = OrbitalPalette.PanelEdge,
            ),
        )
    }
}

@Composable
private fun FxPanel(
    parameter: (ParameterId) -> Float,
    onParameterChange: (ParameterId, Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ENVELOPES  /  EFFECTS", color = OrbitalPalette.Text, fontSize = 13.sp, letterSpacing = 1.8.sp)
        SignalTile("ENV", parameter(ParameterId.ATTACK), OrbitalPalette.Violet, Modifier.fillMaxWidth())
        ParameterSlider("ATTACK", parameter(ParameterId.ATTACK), ParameterId.ATTACK, onParameterChange, "Fade-in time")
        ParameterSlider("DECAY", parameter(ParameterId.DECAY), ParameterId.DECAY, onParameterChange, "Transient fall")
        ParameterSlider("SUSTAIN", parameter(ParameterId.SUSTAIN), ParameterId.SUSTAIN, onParameterChange, "Held volume")
        ParameterSlider("RELEASE", parameter(ParameterId.RELEASE), ParameterId.RELEASE, onParameterChange, "Fade-out time")
        Spacer(Modifier.height(3.dp))
        SignalTile("LFO", parameter(ParameterId.LFO_RATE), OrbitalPalette.Cyan, Modifier.fillMaxWidth())
        ParameterSlider("LFO RATE", parameter(ParameterId.LFO_RATE), ParameterId.LFO_RATE, onParameterChange, "Modulation speed")
        ParameterSlider("LFO DEPTH", parameter(ParameterId.LFO_DEPTH), ParameterId.LFO_DEPTH, onParameterChange, "Modulation strength")
        ParameterSlider("FX AMOUNT", parameter(ParameterId.FX_AMOUNT), ParameterId.FX_AMOUNT, onParameterChange, "Stereo space and texture")
    }
}

@Composable
private fun ParameterSlider(
    title: String,
    value: Float,
    id: ParameterId,
    onParameterChange: (ParameterId, Float) -> Unit,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = OrbitalPalette.Text, fontSize = 10.sp, letterSpacing = .9.sp)
            Spacer(Modifier.weight(1f))
            Text(percent(value), color = OrbitalPalette.Muted, fontSize = 10.sp)
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = { onParameterChange(id, it) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = OrbitalPalette.Cyan,
                activeTrackColor = OrbitalPalette.Violet,
                inactiveTrackColor = OrbitalPalette.PanelEdge,
            ),
            modifier = Modifier.semantics { contentDescription = title + ". " + description },
        )
    }
}

private fun defaultValue(id: ParameterId): Float = when (id) {
    ParameterId.WAVE -> .42f
    ParameterId.MORPH -> .55f
    ParameterId.CUTOFF -> .61f
    ParameterId.RESONANCE -> .35f
    ParameterId.MOVEMENT -> .62f
    ParameterId.FX_AMOUNT -> .32f
    ParameterId.ATTACK -> .09f
    ParameterId.DECAY -> .34f
    ParameterId.SUSTAIN -> .73f
    ParameterId.RELEASE -> .42f
    ParameterId.LFO_RATE -> .39f
    ParameterId.LFO_DEPTH -> .21f
    ParameterId.GLIDE -> .12f
    // Compatible with optional new voice-engine parameters on merged builds.
    else -> .25f
}

private fun percent(value: Float): String = (value.coerceIn(0f, 1f) * 100f).roundToInt().toString() + "%"
private fun cutoffLabel(value: Float): String {
    val hz = 40.0 * 400.0.pow(value.coerceIn(0f, 1f).toDouble())
    return if (hz >= 1000.0) "%.1f kHz".format(java.util.Locale.US, hz / 1000.0)
    else hz.roundToInt().toString() + " Hz"
}
