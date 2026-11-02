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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.pow
import kotlin.math.roundToInt

enum class PlayMode { POLY, MONO, LEGATO }

/** The persistence layer owns IDs and patch data; UI needs only selectable metadata. */
data class PatchChoice(val id: String, val name: String, val isFactory: Boolean)

data class OrbitalUiState(
    val patchName: String = "AURORA",
    val playMode: PlayMode = PlayMode.POLY,
    val parameters: Map<ParameterId, Float> = emptyMap(),
    val octave: Int = 4,
    val sequencerRunning: Boolean = false,
    val enabledSteps: Set<Int> = emptySet(),
    val sequencerStep: Int = -1,
    val sequencerTempo: Int = 112,
    val presetIndex: Int = 0,
    val presetCount: Int = 0,
    val patchChoices: List<PatchChoice> = emptyList(),
    val activePatchId: String? = null,
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
    val steps = remember {
        mutableStateListOf(
            true, false, false, true,
            false, true, false, false,
            true, false, true, false,
            false, true, false, true,
        )
    }

    // A self-contained 16-step live pattern. Stop/cancellation sends note-off.
    LaunchedEffect(externalSequencer, sequencerPlaying, tempo, state.octave) {
        if (externalSequencer || !sequencerPlaying) {
            playhead = -1
            return@LaunchedEffect
        }
        val stepMs = (60_000L / tempo / 4L).coerceAtLeast(45L)
        while (isActive) {
            playhead = (playhead + 1) % steps.size
            val current = playhead
            val enabled = steps[current]
            val note = (((state.octave + 1) * 12) + intArrayOf(
                0, 3, 7, 10, 12, 10, 7, 3,
                0, 5, 7, 12, 10, 7, 5, 3,
            )[current]).coerceIn(0, 127)
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
                if (externalSequencer) List(16) { it in state.enabledSteps } else steps,
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

@Composable
private fun SequencerPanel(
    steps: List<Boolean>,
    playhead: Int,
    playing: Boolean,
    onTogglePlaying: () -> Unit,
    bpm: Int,
    onTempoChange: (Int) -> Unit,
    onStepToggle: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("16-STEP ORBIT", color = OrbitalPalette.Text, fontSize = 13.sp, letterSpacing = 1.9.sp)
            Spacer(Modifier.weight(1f))
            Pill(if (playing) "STOP" else "PLAY ▶", playing, onTogglePlaying)
        }
        Label("Tap any step to enable/disable it. The running pattern plays the synthesizer.")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 8 columns are too narrow to touch reliably on a 360dp phone.
            val columns = if (maxWidth >= 480.dp) 8 else 4
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in 0 until 16 / columns) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in row * columns until row * columns + columns) {
                    val enabled = steps[i]
                    val current = playhead == i
                    Box(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .border(
                                1.dp,
                                if (current) OrbitalPalette.Gold
                                else if (enabled) OrbitalPalette.Cyan
                                else OrbitalPalette.PanelEdge,
                                RoundedCornerShape(8.dp),
                            )
                            .background(
                                if (enabled) OrbitalPalette.Cyan.copy(alpha = if (current) .36f else .14f)
                                else OrbitalPalette.Panel,
                                RoundedCornerShape(8.dp),
                            )
                            .toggleable(value = enabled, role = Role.Checkbox) { onStepToggle(i) }
                            .semantics {
                                contentDescription = "Step ${i + 1}, ${if (enabled) "on" else "off"}" +
                                    (if (current) ", playing" else "")
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            (i + 1).toString().padStart(2, '0'),
                            color = if (enabled) OrbitalPalette.Text else OrbitalPalette.Muted,
                            fontSize = 11.sp,
                        )
                    }
                        }
                    }
                }
            }
        }
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
                "16 steps • 1/16 notes • one-bar loop. The sequence runs only while this screen is open and PLAY is enabled.",
                color = OrbitalPalette.Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
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
}

private fun percent(value: Float): String = (value.coerceIn(0f, 1f) * 100f).roundToInt().toString() + "%"
private fun cutoffLabel(value: Float): String {
    val hz = 40.0 * 400.0.pow(value.coerceIn(0f, 1f).toDouble())
    return if (hz >= 1000.0) "%.1f kHz".format(java.util.Locale.US, hz / 1000.0)
    else hz.roundToInt().toString() + " Hz"
}
