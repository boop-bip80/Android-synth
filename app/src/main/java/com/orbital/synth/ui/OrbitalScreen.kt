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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.ParameterId
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.music.SequencerNote
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.pow
import kotlin.math.roundToInt

enum class PlayMode { POLY, MONO, LEGATO }

/** UI-level direction; the Android host maps this to the portable arp engine. */
enum class ArpDirection { UP, DOWN, UP_DOWN, AS_PLAYED, RANDOM, CHORD }

/** The persistence layer owns IDs and patch data; UI needs only selectable metadata. */
data class PatchChoice(val id: String, val name: String, val isFactory: Boolean)

/** Stable transport IDs are supplied by the host; the UI never imports factory pattern data. */
data class SequenceChoice(val id: String, val name: String)

data class OrbitalUiState(
    val patchName: String = "AURORA",
    val playMode: PlayMode = PlayMode.POLY,
    val parameters: Map<ParameterId, Float> = emptyMap(),
    /** Live per-voice oscillator layers; Compose only holds immutable audio snapshots. */
    val oscillatorSlots: List<OscillatorSlot> = emptyList(),
    /** Immutable 8-LFO and 64-cable snapshots from the actual audio engine. */
    val modulationLfos: List<ModulationLfoConfig> = emptyList(),
    val modulationRoutes: List<ModulationRouteConfig> = emptyList(),
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
    val arpEnabled: Boolean = false,
    val arpDirection: ArpDirection = ArpDirection.UP,
    /** Rate in steps per quarter-note beat. Supported detents are 1, 2, 4, and 8. */
    val arpRate: Int = 4,
    val arpLatch: Boolean = false,
    /** A normalized 0..1 swing amount, interpreted by the host transport. */
    val arpSwing: Float = 0f,
)

private enum class Tab(val title: String) {
    SOUND("SOUND"), PLAY("PLAY"), SEQ("SEQ"), FX("FX")
}

private val MainGradient = Brush.verticalGradient(
    listOf(
        OrbitalPalette.Void,
        Color(0xFF07152C),
        Color(0xFF100C2A),
        OrbitalPalette.Void,
    ),
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
    onArpEnabledChange: ((Boolean) -> Unit)? = null,
    onArpDirectionChange: ((ArpDirection) -> Unit)? = null,
    onArpRateChange: ((Int) -> Unit)? = null,
    onArpLatchChange: ((Boolean) -> Unit)? = null,
    onArpSwingChange: ((Float) -> Unit)? = null,
    /** Optional pressure-aware stage note-on; old integer-note callers remain compatible. */
    onExpressiveNoteOn: ((Int, Float) -> Unit)? = null,
    /** Passes a selected layer to the host's single live audio engine. */
    onOscillatorSlotChange: ((Int, OscillatorSlot) -> Unit)? = null,
    onModulationLfoChange: ((Int, ModulationLfoConfig) -> Unit)? = null,
    onModulationRouteChange: ((Int, ModulationRouteConfig) -> Unit)? = null,
    onModulationRouteClear: ((Int) -> Unit)? = null,
    onModulationTempoChange: ((Float) -> Unit)? = null,
    /** Sends stage X/Y/pressure to actual expressive modulation sources. */
    onStageModulationTouch: ((Float, Float, Float) -> Unit)? = null,
) {
    var selectedTab by remember { mutableStateOf(Tab.SOUND) }
    var immersiveMode by remember { mutableStateOf(false) }
    var compactGraphExpanded by remember { mutableStateOf(false) }
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
    // The optional engine IDs are linked by the host synthesis engine. Dynamic lookup
    // keeps this layout independently buildable while its audio implementation lands.
    val engineId = remember { ParameterId.entries.firstOrNull { it.name == "ENGINE" } }
    val engineValue = engineId?.let(parameter) ?: 0f
    val engineChoice = EngineArchitectures[engineArchitectureIndex(engineValue)]
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

    if (immersiveMode) {
        ImmersiveStage(
            state = state,
            onNoteOn = onNoteOn,
            onNoteOff = onNoteOff,
            onParameterChange = onParameterChange,
            onExpressiveNoteOn = onExpressiveNoteOn,
            onModulationTouchExpression = onStageModulationTouch,
            onExit = { immersiveMode = false },
        )
    } else {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MainGradient)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val compactHeight = maxHeight < 500.dp
        // Avoid concurrent scroll reset while a 350dp performance viewport changes
        // editor content; preserving scroll still permits deliberate user scrolling.
        LaunchedEffect(selectedTab, compactHeight) {
            if (!compactHeight) editorScroll.scrollTo(0)
        }
        // The short-landscape performance layout has a strict vertical budget.
        // Its waveform panel remains scrollable; the keyboard must retain space.
        // Skip decorative full-screen gradients in that mode to reduce GPU work.
        if (!compactHeight) Canvas(Modifier.fillMaxSize()) {
            val cyanCenter = Offset(size.width * .06f, size.height * .22f)
            val violetCenter = Offset(size.width * .96f, size.height * .46f)
            val lightRadius = maxOf(size.width, size.height) * .72f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(OrbitalPalette.Blue.copy(alpha = .12f), Color.Transparent),
                    center = cyanCenter, radius = lightRadius,
                ),
                radius = lightRadius, center = cyanCenter,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(OrbitalPalette.Violet.copy(alpha = .10f), Color.Transparent),
                    center = violetCenter, radius = lightRadius,
                ),
                radius = lightRadius, center = violetCenter,
            )
            // Subtle technical reticle lines give depth without competing with the keys.
            val spacing = 36.dp.toPx()
            var x = spacing
            while (x < size.width) {
                drawLine(OrbitalPalette.Cyan.copy(alpha = .026f), Offset(x, 0f), Offset(x, size.height))
                x += spacing
            }
        }
        val compactOrbitLayout = compactHeight || maxWidth < 380.dp || LocalDensity.current.fontScale >= 1.2f
        Column(
            Modifier.fillMaxSize().padding(
                horizontal = if (compactHeight) 8.dp else 12.dp,
                vertical = if (compactHeight) 3.dp else 9.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(
                if (compactHeight) 2.dp else if (compactOrbitLayout) 8.dp else 12.dp,
            ),
        ) {
        Header(
            patch = state.patchName,
            onOpenLibrary = { showPatchLibrary = true },
            libraryEnabled = onSavePatch != null || onSelectPatch != null,
            compact = compactHeight,
            onEnterImmersive = { immersiveMode = true },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, OrbitalPalette.PanelEdge.copy(alpha = .55f), RoundedCornerShape(16.dp))
                .background(OrbitalPalette.Void.copy(alpha = .74f), RoundedCornerShape(16.dp))
                .padding(if (compactHeight) 0.dp else 3.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compactHeight) 2.dp else 4.dp),
        ) {
            Tab.entries.forEachIndexed { index, tab ->
                val active = selectedTab == tab
                val activeColor = when (tab) {
                    Tab.SOUND -> OrbitalPalette.Cyan
                    Tab.PLAY -> OrbitalPalette.Gold
                    Tab.SEQ -> OrbitalPalette.Violet
                    Tab.FX -> OrbitalPalette.Teal
                }
                Column(
                    Modifier
                        .weight(1f)
                        .border(
                            1.dp,
                            if (active) activeColor.copy(alpha = .60f) else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        )
                        .background(
                            if (active) Brush.verticalGradient(
                                listOf(activeColor.copy(alpha = .24f), activeColor.copy(alpha = .055f)),
                            ) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
                            RoundedCornerShape(12.dp),
                        )
                        .selectable(
                            selected = active,
                            role = Role.Tab,
                            onClick = { selectedTab = tab },
                        )
                        // A single actionable accessibility node must own its
                        // label, selection, and click action. Nested semantics
                        // otherwise make UIAutomator see a non-clickable label
                        // with selected=false even when the tab is active.
                        .clearAndSetSemantics {
                            contentDescription = "Tab " + tab.title
                            selected = active
                            role = Role.Tab
                            onClick(label = "Open " + tab.title) {
                                selectedTab = tab
                                true
                            }
                        }
                        .heightIn(min = if (compactHeight) 40.dp else 48.dp)
                        .padding(vertical = if (compactHeight) 3.dp else 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "0" + (index + 1),
                        color = if (active) activeColor else OrbitalPalette.PanelEdge,
                        fontSize = 8.sp,
                        letterSpacing = .5.sp,
                    )
                    Text(
                        tab.title,
                        color = if (active) OrbitalPalette.Text else OrbitalPalette.Muted,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        letterSpacing = 1.5.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        // Editor area is the only vertical scroller. Performance controls remain anchored below it.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, OrbitalPalette.PanelEdge.copy(alpha = .42f), RoundedCornerShape(19.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(OrbitalPalette.Panel.copy(alpha = .50f), OrbitalPalette.Void.copy(alpha = .50f)),
                    ),
                    RoundedCornerShape(19.dp),
                )
                .verticalScroll(editorScroll)
                .padding(horizontal = 7.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactOrbitLayout) 8.dp else 12.dp),
        ) {
        when (selectedTab) {
            Tab.SOUND -> {
                CompactPatchHeader(
                    state = state,
                    onPreviousPatch = onPreviousPatch,
                    onNextPatch = onNextPatch,
                    engineName = engineChoice.name,
                )
                if (compactHeight) {
                    CompactSoundPanel(
                        parameter = parameter,
                        engineId = engineId,
                        engineValue = engineValue,
                        onParameterChange = onParameterChange,
                    )
                } else {
                if (compactOrbitLayout) {
                    Box(Modifier.fillMaxWidth().height(148.dp).testTag("compactOrbitViewport")) {
                        OrbitalDisplay(
                            morph = morph,
                            movement = move,
                            modifier = Modifier.fillMaxSize(),
                            onExpressionChange = { nextMorph, nextMovement ->
                                onParameterChange(ParameterId.MORPH, nextMorph)
                                onParameterChange(ParameterId.MOVEMENT, nextMovement)
                            },
                        )
                    }
                    Box(Modifier.fillMaxWidth().testTag("compactOrbitHint")) {
                        Label("DRAG ORBIT: X = MORPH  /  Y = MOTION")
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .testTag("compactModulationToggle")
                            .border(1.dp, OrbitalPalette.PanelEdge, RoundedCornerShape(12.dp))
                            .background(OrbitalPalette.Panel.copy(alpha = .55f), RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { compactGraphExpanded = !compactGraphExpanded }
                            .semantics {
                                contentDescription = if (compactGraphExpanded)
                                    "Hide modulation graph" else "Show modulation graph"
                            }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("MODULATION GRAPH", color = OrbitalPalette.Text,
                            fontSize = 10.sp, letterSpacing = 1.1.sp)
                        Text(if (compactGraphExpanded) "−" else "+",
                            color = OrbitalPalette.Cyan, fontSize = 18.sp)
                    }
                    if (compactGraphExpanded) {
                        Box(Modifier.fillMaxWidth().testTag("compactModulationGraph")) {
                            ModulationGraph(morph, move, Modifier.fillMaxWidth())
                        }
                    }
                    EngineArchitecturePanel(
                        value = engineValue,
                        enabled = engineId != null,
                        onChoose = { value -> engineId?.let { onParameterChange(it, value) } },
                    )
                } else {
                    EngineArchitecturePanel(
                        value = engineValue,
                        enabled = engineId != null,
                        onChoose = { value -> engineId?.let { onParameterChange(it, value) } },
                    )
                    // Patch controls live above the orbit so no caption competes with
                    // the luminous visual field or intercepts XY expression touches.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(244.dp)
                            .border(
                                1.dp, OrbitalPalette.Cyan.copy(alpha = .30f),
                                RoundedCornerShape(18.dp),
                            ),
                    ) {
                        OrbitalDisplay(
                            morph = morph,
                            movement = move,
                            modifier = Modifier.fillMaxSize(),
                            onExpressionChange = { nextMorph, nextMovement ->
                                onParameterChange(ParameterId.MORPH, nextMorph)
                                onParameterChange(ParameterId.MOVEMENT, nextMovement)
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Label("TOUCH ORBIT / X: MORPH / Y: MOTION")
                        Text(
                            percent(morph) + "  /  " + percent(move),
                            color = OrbitalPalette.Cyan,
                            fontSize = 10.sp,
                            letterSpacing = .7.sp,
                        )
                    }
                    ModulationGraph(morph, move, Modifier.fillMaxWidth())
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp, OrbitalPalette.PanelEdge.copy(alpha = .48f),
                            RoundedCornerShape(17.dp),
                        )
                        .background(
                            Brush.verticalGradient(
                                listOf(OrbitalPalette.DeepBlue.copy(alpha = .45f), OrbitalPalette.Panel.copy(alpha = .80f)),
                            ),
                            RoundedCornerShape(17.dp),
                        )
                        .padding(horizontal = 5.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Label("TONE SHAPING  /  LIVE MACROS")
                        Label("01 — 05")
                    }
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
                }
                // Voice engine parameters appear as luminous knobs when present.
                val voiceParameters = listOf("UNISON", "DETUNE", "CHARACTER").mapNotNull { name ->
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
                                when (name) {
                                    "UNISON" -> OrbitalPalette.Gold
                                    "CHARACTER" -> OrbitalPalette.Teal
                                    else -> OrbitalPalette.Violet
                                },
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
                if (state.oscillatorSlots.isNotEmpty() && onOscillatorSlotChange != null) {
                    OscillatorRackPanel(
                        slots = state.oscillatorSlots,
                        onSlotChange = onOscillatorSlotChange,
                    )
                }
            }
            Tab.PLAY -> PlayPanel(
                state.playMode,
                morph,
                move,
                parameter(ParameterId.GLIDE),
                onModeChange,
                onParameterChange,
                state,
                onArpEnabledChange,
                onArpDirectionChange,
                onArpRateChange,
                onArpLatchChange,
                onArpSwingChange,
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
            Tab.FX -> {
                FxPanel(parameter, onParameterChange, compactHeight)
                if (state.modulationLfos.isNotEmpty() &&
                    state.modulationRoutes.isNotEmpty() &&
                    onModulationLfoChange != null &&
                    onModulationRouteChange != null &&
                    onModulationRouteClear != null &&
                    onModulationTempoChange != null
                ) {
                    ModulationConstellationPanel(
                        lfos = state.modulationLfos,
                        routes = state.modulationRoutes,
                        tempoBpm = state.sequencerTempo.toFloat(),
                        onLfoChange = onModulationLfoChange,
                        onRouteChange = onModulationRouteChange,
                        onRouteClear = onModulationRouteClear,
                        onTempoChange = onModulationTempoChange,
                    )
                }
            }
        }
        }

        // A short landscape viewport reserves its last 82dp for actual playable
        // keys. Octave controls become a 36dp utility strip above the keyboard;
        // full 48dp voice-mode targets remain in the PLAY editor.
        if (compactHeight) {
            Row(
                Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Label("OCT")
                CompactOctaveButton(
                    symbol = "−", description = "Lower octave",
                    enabled = state.octave > 0,
                    onClick = { onOctaveChange((state.octave - 1).coerceAtLeast(0)) },
                )
                Text(
                    state.octave.toString(),
                    color = OrbitalPalette.Text,
                    fontSize = 12.sp,
                )
                CompactOctaveButton(
                    symbol = "+", description = "Raise octave",
                    enabled = state.octave < 8,
                    onClick = { onOctaveChange((state.octave + 1).coerceAtMost(8)) },
                )
                Spacer(Modifier.weight(1f))
                Label("MODE / " + state.playMode.name)
            }
        }

        // Editor content scrolls independently; keyboard never becomes scrollable.
        OrbitalKeyboard(
            octave = state.octave.coerceIn(0, 8),
            onNoteOn = onNoteOn,
            onNoteOff = onNoteOff,
            modifier = Modifier.fillMaxWidth(),
            preferredHeight = if (compactHeight) 82.dp else if (compactOrbitLayout) 110.dp else null,
        )
        if (!compactHeight) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Label("OCT")
                Pill("−", false, { onOctaveChange((state.octave - 1).coerceAtLeast(0)) }, description = "Lower octave", enabled = state.octave > 0)
                Text(
                    state.octave.toString(),
                    color = OrbitalPalette.Text,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                Pill("+", false, { onOctaveChange((state.octave + 1).coerceAtMost(8)) }, description = "Raise octave", enabled = state.octave < 8)
                Spacer(Modifier.weight(1f))
                PlayMode.entries.forEach { mode ->
                    Pill(mode.name, state.playMode == mode, { onModeChange(mode) }, role = Role.RadioButton)
                }
            }
        }
        Spacer(Modifier.height(if (compactHeight) 0.dp else 6.dp))
        }
        SnackbarHost(hostState = snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
    }
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

private data class EngineArchitecture(val name: String, val family: String, val value: Float)

// The labels describe synthesis structures, not a claim of exact vintage hardware
// reproduction. A true parameter change always goes through the supplied host.
private val EngineArchitectures = listOf(
    EngineArchitecture("ORBITAL WT", "WAVETABLE", 0f),
    EngineArchitecture("LADDER", "VIRTUAL ANALOG", .25f),
    EngineArchitecture("SEM", "VIRTUAL ANALOG", .50f),
    EngineArchitecture("SIX-OP FM", "DIGITAL FM", .75f),
    EngineArchitecture("PHASE DIST.", "DIGITAL PD", 1f),
)

internal fun engineArchitectureIndex(value: Float): Int =
    (value.coerceIn(0f, 1f) * (EngineArchitectures.size - 1)).roundToInt()
        .coerceIn(0, EngineArchitectures.lastIndex)

@Composable
private fun EngineArchitecturePanel(
    value: Float,
    enabled: Boolean,
    onChoose: (Float) -> Unit,
) {
    val activeIndex = engineArchitectureIndex(value)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("engineArchitecturePanel")
            .border(
                1.dp, OrbitalPalette.Violet.copy(alpha = .40f),
                RoundedCornerShape(16.dp),
            )
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color(0xFF0E1532).copy(alpha = .92f),
                        Color(0xFF0B243A).copy(alpha = .90f),
                        Color(0xFF160F31).copy(alpha = .94f),
                    ),
                ),
                RoundedCornerShape(16.dp),
            )
            .padding(vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SYNTHESIS ARCHITECTURE",
                color = OrbitalPalette.Text,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "05 ENGINES",
                color = OrbitalPalette.Gold,
                fontSize = 8.sp,
                letterSpacing = .8.sp,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EngineArchitectures.forEachIndexed { index, choice ->
                val active = index == activeIndex
                val tint = when (index) {
                    0 -> OrbitalPalette.Cyan
                    1 -> OrbitalPalette.Gold
                    2 -> OrbitalPalette.Teal
                    3 -> OrbitalPalette.Violet
                    else -> OrbitalPalette.Blue
                }
                Column(
                    Modifier
                        .width(112.dp)
                        .heightIn(min = 48.dp)
                        .border(
                            1.dp,
                            if (active) tint.copy(alpha = .95f)
                            else OrbitalPalette.PanelEdge.copy(alpha = .55f),
                            RoundedCornerShape(10.dp),
                        )
                        .background(
                            if (active) tint.copy(alpha = .18f)
                            else OrbitalPalette.Void.copy(alpha = .55f),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable(enabled = enabled, role = Role.RadioButton) {
                            if (!active) onChoose(choice.value)
                        }
                        .semantics {
                            selected = active
                            contentDescription = "Select synth engine " + choice.name +
                                if (enabled) "" else ", unavailable until audio engine is connected"
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        ("%02d".format(java.util.Locale.US, index + 1)) + "  " + choice.name,
                        color = if (active) tint else OrbitalPalette.Text,
                        fontSize = 9.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        choice.family,
                        color = OrbitalPalette.Muted,
                        fontSize = 7.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactPatchHeader(
    state: OrbitalUiState,
    onPreviousPatch: (() -> Unit)?,
    onNextPatch: (() -> Unit)?,
    engineName: String,
) {
    // Keep controls and metadata outside the touch artwork at 360 dp / large fonts.
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("compactPatchHeader")
            .border(1.dp, OrbitalPalette.PanelEdge.copy(alpha = .60f), RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(OrbitalPalette.Panel.copy(alpha = .84f), Color(0xFF171A3A).copy(alpha = .66f)),
                ),
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.testTag("compactBankLabel")) {
                Label("PATCH LIBRARY  /  SOUND BANK")
            }
            Box(Modifier.testTag("compactEngineLabel")) {
                Label("ENGINE")
            }
        }
        Row(
            Modifier.fillMaxWidth().testTag("compactPatchControls"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PresetArrow(
                symbol = "‹",
                description = "Previous factory preset",
                enabled = onPreviousPatch != null,
                onClick = { onPreviousPatch?.invoke() },
            )
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    state.patchName,
                    color = OrbitalPalette.Text,
                    textAlign = TextAlign.Center,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.presetCount > 0) {
                    Label(
                        "PATCH " +
                            (state.presetIndex.coerceIn(0, state.presetCount - 1) + 1) +
                            " / " + state.presetCount,
                    )
                }
            }
            PresetArrow(
                symbol = "›",
                description = "Next factory preset",
                enabled = onNextPatch != null,
                onClick = { onNextPatch?.invoke() },
            )
        }
        Box(Modifier.fillMaxWidth().testTag("compactEngineModes"), contentAlignment = Alignment.CenterEnd) {
            Label("ACTIVE ARCHITECTURE  /  " + engineName)
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
private fun CompactOctaveButton(
    symbol: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(42.dp)
            .height(36.dp)
            .border(
                1.dp,
                if (enabled) OrbitalPalette.Cyan.copy(alpha = .75f) else OrbitalPalette.PanelEdge,
                RoundedCornerShape(10.dp),
            )
            .background(OrbitalPalette.Panel.copy(alpha = .75f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol, color = if (enabled) OrbitalPalette.Cyan else OrbitalPalette.Muted,
            fontSize = 20.sp, fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun Header(
    patch: String,
    onOpenLibrary: () -> Unit,
    libraryEnabled: Boolean,
    compact: Boolean,
    onEnterImmersive: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(if (compact) 28.dp else 34.dp)) {
            val c = center
            val r = size.minDimension * .38f
            drawCircle(OrbitalPalette.Cyan.copy(alpha = .14f), radius = r * 1.24f)
            drawCircle(OrbitalPalette.Blue.copy(alpha = .65f), radius = r, style = Stroke(1.dp.toPx()))
            drawCircle(OrbitalPalette.Violet.copy(alpha = .70f), radius = r * .62f,
                style = Stroke(1.3.dp.toPx()))
            drawLine(
                OrbitalPalette.Cyan,
                Offset(c.x - r * .90f, c.y + r * .78f),
                Offset(c.x + r * .90f, c.y - r * .78f),
                strokeWidth = 1.5.dp.toPx(),
            )
            drawCircle(OrbitalPalette.Gold, radius = 2.4.dp.toPx(), center = c)
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "O R B I T A L",
                color = OrbitalPalette.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                letterSpacing = 1.5.sp,
            )
            Text(
                "SONIC ARCHITECTURE / LIVE",
                color = OrbitalPalette.Cyan,
                fontSize = 7.sp,
                letterSpacing = 1.2.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        Text("●", color = OrbitalPalette.Teal, fontSize = 9.sp,
            modifier = Modifier.semantics { contentDescription = "Patch loaded: $patch" })
        Box(
            Modifier
                .width(if (compact) 54.dp else 64.dp)
                .heightIn(min = 40.dp)
                .testTag("immersiveModeToggle")
                .border(1.dp, OrbitalPalette.Cyan.copy(alpha = .55f), RoundedCornerShape(13.dp))
                .background(OrbitalPalette.Blue.copy(alpha = .18f), RoundedCornerShape(13.dp))
                .clickable(role = Role.Button, onClick = onEnterImmersive)
                .semantics { contentDescription = "Enter immersive performance" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "STAGE",
                color = OrbitalPalette.Cyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
        }
        Spacer(Modifier.width(5.dp))
        Box(
            Modifier
                .size(if (compact) 40.dp else 48.dp)
                .border(1.dp, OrbitalPalette.PanelEdge.copy(alpha = .45f), RoundedCornerShape(14.dp))
                .background(OrbitalPalette.Panel.copy(alpha = .60f), RoundedCornerShape(14.dp))
                .clickable(enabled = libraryEnabled, role = Role.Button, onClick = onOpenLibrary)
                .semantics { contentDescription = "Open patch library" },
            contentAlignment = Alignment.Center,
        ) {
            Text("☰", color = if (libraryEnabled) OrbitalPalette.Cyan else OrbitalPalette.Muted,
                fontSize = 20.sp)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 9.sp, letterSpacing = 1.1.sp, color = OrbitalPalette.Muted, maxLines = 2)
}

@Composable
private fun Pill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    description: String? = null,
    role: Role = Role.Button,
    enabled: Boolean = true,
) {
    val edge = if (!enabled) OrbitalPalette.PanelEdge.copy(alpha = .32f)
        else if (active) OrbitalPalette.Cyan else OrbitalPalette.PanelEdge
    Box(
        Modifier
            .border(1.dp, edge, RoundedCornerShape(12.dp))
            .background(
                if (active) Brush.verticalGradient(
                    listOf(OrbitalPalette.Cyan.copy(alpha = .26f), OrbitalPalette.Blue.copy(alpha = .08f)),
                ) else Brush.verticalGradient(
                    listOf(OrbitalPalette.Panel.copy(alpha = .80f), OrbitalPalette.Void.copy(alpha = .60f)),
                ),
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, role = role, onClick = onClick)
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
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            letterSpacing = .65.sp,
            color = if (!enabled) OrbitalPalette.Muted.copy(alpha = .45f)
            else if (active) Color.White else OrbitalPalette.Muted,
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
    state: OrbitalUiState,
    onArpEnabledChange: ((Boolean) -> Unit)?,
    onArpDirectionChange: ((ArpDirection) -> Unit)?,
    onArpRateChange: ((Int) -> Unit)?,
    onArpLatchChange: ((Boolean) -> Unit)?,
    onArpSwingChange: ((Float) -> Unit)?,
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
            // A concentric, signal-driven cursor gives the XY pad an instrument feel.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(OrbitalPalette.Cyan.copy(alpha = .38f), Color.Transparent),
                    center = p, radius = 40.dp.toPx(),
                ),
                radius = 40.dp.toPx(), center = p,
            )
            drawCircle(OrbitalPalette.Violet.copy(alpha = .38f), 24.dp.toPx(), p,
                style = Stroke(1.dp.toPx()))
            drawCircle(OrbitalPalette.Cyan.copy(alpha = .65f), 13.dp.toPx(), p,
                style = Stroke(1.2.dp.toPx()))
            drawCircle(OrbitalPalette.Cyan, 6.dp.toPx(), p, style = Stroke(2.dp.toPx()))
            drawCircle(Color.White, 2.dp.toPx(), p)
        }
        Text("VOICE MODE", color = OrbitalPalette.Muted, fontSize = 10.sp, letterSpacing = 1.2.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayMode.entries.forEach { option ->
                Pill(option.name, mode == option, { onModeChange(option) }, role = Role.RadioButton)
            }
        }
        ParameterSlider("GLIDE", glide, ParameterId.GLIDE, onParameterChange, "Slow pitch transition")
        ArpeggiatorPanel(
            state = state,
            onEnabledChange = onArpEnabledChange,
            onDirectionChange = onArpDirectionChange,
            onRateChange = onArpRateChange,
            onLatchChange = onArpLatchChange,
            onSwingChange = onArpSwingChange,
        )
    }
}


private val ArpRateDetents = listOf(1, 2, 4, 8)
private val ArpDirectionLabels = listOf(
    ArpDirection.UP to "UP",
    ArpDirection.DOWN to "DOWN",
    ArpDirection.UP_DOWN to "UP–DOWN",
    ArpDirection.AS_PLAYED to "AS PLAYED",
    ArpDirection.RANDOM to "RANDOM",
    ArpDirection.CHORD to "CHORD",
)

/**
 * Fully host-controlled arpeggiator. Buttons without a corresponding host
 * callback are disabled instead of simulating a running sound engine.
 */
@Composable
internal fun ArpeggiatorPanel(
    state: OrbitalUiState,
    onEnabledChange: ((Boolean) -> Unit)?,
    onDirectionChange: ((ArpDirection) -> Unit)?,
    onRateChange: ((Int) -> Unit)?,
    onLatchChange: ((Boolean) -> Unit)?,
    onSwingChange: ((Float) -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("arpeggiatorPanel")
            .border(1.dp, OrbitalPalette.Violet.copy(alpha = .55f), RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF151538), OrbitalPalette.Panel.copy(alpha = .97f), Color(0xFF06122A)),
                ),
                RoundedCornerShape(18.dp),
            )
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "ARPEGGIATOR",
                    color = OrbitalPalette.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                )
                Label("PERFORMANCE / KEYED MOTION")
            }
            Box(
                Modifier
                    .border(
                        1.dp,
                        if (state.arpEnabled) OrbitalPalette.Teal else OrbitalPalette.PanelEdge,
                        RoundedCornerShape(12.dp),
                    )
                    .background(
                        if (state.arpEnabled) OrbitalPalette.Teal.copy(alpha = .20f)
                        else OrbitalPalette.Void.copy(alpha = .60f),
                        RoundedCornerShape(12.dp),
                    )
                    .clickable(enabled = onEnabledChange != null, role = Role.Switch) {
                        onEnabledChange?.invoke(!state.arpEnabled)
                    }
                    .semantics {
                        selected = state.arpEnabled
                        contentDescription = if (state.arpEnabled) "Turn arpeggiator off"
                            else "Turn arpeggiator on"
                    }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.arpEnabled) "ARP ON" else "ARP OFF",
                    color = if (state.arpEnabled) OrbitalPalette.Teal else OrbitalPalette.Muted,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("DIRECTION")
            Spacer(Modifier.weight(1f))
            Label("06 MOTION MODES")
        }
        ArpDirectionLabels.chunked(3).forEach { rowChoices ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowChoices.forEach { (direction, label) ->
                    ArpChoice(
                        label = label,
                        active = state.arpDirection == direction,
                        description = "Arpeggiator direction " + direction.name,
                        onClick = onDirectionChange?.let { callback ->
                            { callback(direction) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("STEPS / BEAT")
            Spacer(Modifier.weight(1f))
            Label(state.arpRate.coerceIn(1, 8).toString() + "× SPEED")
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ArpRateDetents.forEach { rate ->
                ArpChoice(
                    label = rate.toString() + "×",
                    active = state.arpRate == rate,
                    description = "Arpeggiator rate " + rate + " steps per beat",
                    onClick = onRateChange?.let { callback -> { callback(rate) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("LATCH", color = OrbitalPalette.Text, fontSize = 11.sp, letterSpacing = 1.sp)
                Label("KEEP THE HELD NOTE POOL AFTER KEY RELEASE")
            }
            ArpChoice(
                label = if (state.arpLatch) "HOLD ON" else "HOLD OFF",
                active = state.arpLatch,
                description = if (state.arpLatch) "Disable arpeggiator latch"
                    else "Enable arpeggiator latch",
                onClick = onLatchChange?.let { callback ->
                    { callback(!state.arpLatch) }
                },
                modifier = Modifier.width(105.dp),
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SWING", color = OrbitalPalette.Text, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(
                percent(state.arpSwing),
                color = if (onSwingChange == null) OrbitalPalette.Muted else OrbitalPalette.Gold,
                fontSize = 10.sp,
            )
        }
        Slider(
            value = state.arpSwing.coerceIn(0f, 1f),
            onValueChange = { onSwingChange?.invoke(it) },
            enabled = onSwingChange != null,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = OrbitalPalette.Gold,
                activeTrackColor = OrbitalPalette.Violet,
                inactiveTrackColor = OrbitalPalette.PanelEdge,
            ),
            modifier = Modifier.semantics { contentDescription = "Arpeggiator swing" },
        )
        Label("PLAY THE KEYBOARD TO FEED LIVE NOTES  /  NO PATTERN RECORDING")
    }
}

@Composable
private fun ArpChoice(
    label: String,
    active: Boolean,
    description: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .border(
                1.dp,
                if (active) OrbitalPalette.Cyan else OrbitalPalette.PanelEdge.copy(alpha = .70f),
                RoundedCornerShape(11.dp),
            )
            .background(
                if (active) OrbitalPalette.Cyan.copy(alpha = .20f)
                else OrbitalPalette.Void.copy(alpha = .68f),
                RoundedCornerShape(11.dp),
            )
            .clickable(enabled = onClick != null, role = Role.RadioButton) { onClick?.invoke() }
            .semantics {
                selected = active
                contentDescription = description
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) OrbitalPalette.Text
                else if (onClick != null) OrbitalPalette.Muted
                else OrbitalPalette.PanelEdge,
            fontSize = 9.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = .35.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
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
    val activeChoice = presented.firstOrNull { it.id == activeId }
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
                    contentDescription = if (presented.isEmpty()) "Sequence library unavailable"
                    else "Choose sequence"
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("◉", color = if (enabled) OrbitalPalette.Gold else OrbitalPalette.Muted, fontSize = 19.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    activeChoice?.name ?: if (presented.isEmpty()) "PATTERNS UNAVAILABLE" else "CHOOSE PATTERN",
                    color = if (activeChoice != null) OrbitalPalette.Text else OrbitalPalette.Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Label(if (activeChoice != null) "ACTIVE PATTERN" else "BUILT-IN ARRANGEMENTS")
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
                                    contentDescription = "Select sequence ${choice.name}"
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
            ) { Text("SAVE SEQUENCE", color = if (onSaveSequence != null) OrbitalPalette.Gold else OrbitalPalette.Muted) }
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

private enum class CompactSoundPage(val caption: String) {
    LIVE("LIVE"), TONE("TONE"), VOICE("VOICE"), ENGINE("ENGINE")
}

/**
 * A 350dp-high landscape viewport gives the editor very little visible space.
 * Render a lightweight, paged performance rack rather than eagerly creating
 * the full orbit/ribbon graph, knob bank and four signal visualizations.
 * All parameters remain accessible, and the full portrait editor is unchanged.
 */
@Composable
private fun CompactSoundPanel(
    parameter: (ParameterId) -> Float,
    engineId: ParameterId?,
    engineValue: Float,
    onParameterChange: (ParameterId, Float) -> Unit,
) {
    var page by remember { mutableStateOf(CompactSoundPage.LIVE) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            "SONIC RACK  /  STAGE VIEW",
            color = OrbitalPalette.Cyan, fontSize = 11.sp, letterSpacing = 1.5.sp,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            CompactSoundPage.entries.forEach { choice ->
                Pill(
                    label = choice.caption,
                    active = page == choice,
                    onClick = { page = choice },
                    description = "Sound rack " + choice.name,
                    role = Role.RadioButton,
                )
            }
        }
        when (page) {
            CompactSoundPage.LIVE -> {
                CompactFxControl("MORPH", parameter(ParameterId.MORPH), ParameterId.MORPH,
                    onParameterChange, "Cross-wavetable expression")
                CompactFxControl("MOTION", parameter(ParameterId.MOVEMENT), ParameterId.MOVEMENT,
                    onParameterChange, "Modulation movement")
                CompactFxControl("FX", parameter(ParameterId.FX_AMOUNT), ParameterId.FX_AMOUNT,
                    onParameterChange, "Stereo space and texture")
            }
            CompactSoundPage.TONE -> {
                CompactFxControl("WAVE", parameter(ParameterId.WAVE), ParameterId.WAVE,
                    onParameterChange, "Oscillator shape")
                CompactFxControl("CUTOFF", parameter(ParameterId.CUTOFF), ParameterId.CUTOFF,
                    onParameterChange, "Filter cutoff")
                CompactFxControl("RESONANCE", parameter(ParameterId.RESONANCE), ParameterId.RESONANCE,
                    onParameterChange, "Filter resonance")
            }
            CompactSoundPage.VOICE -> {
                val digitalMode = engineArchitectureIndex(engineValue) >= 3
                if (digitalMode) {
                    Text(
                        "UNISON / DETUNE: WT + ANALOG ENGINES",
                        color = OrbitalPalette.Muted, fontSize = 9.sp,
                    )
                }
                val voiceIds = listOf("UNISON", "DETUNE", "CHARACTER")
                    .mapNotNull { name ->
                        ParameterId.entries.firstOrNull { it.name == name }?.let { name to it }
                    }
                voiceIds.forEach { (label, id) ->
                    CompactFxControl(label, parameter(id), id, onParameterChange,
                        "Synthesis voice parameter")
                }
                if (voiceIds.isEmpty()) {
                    Label("VOICE MACROS WILL APPEAR WITH SUPPORTED ENGINES")
                }
            }
            CompactSoundPage.ENGINE -> {
                Label("SYNTHESIS ARCHITECTURE  /  MODELED + INSPIRED")
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EngineArchitectures.forEachIndexed { index, choice ->
                        Pill(
                            label = choice.name,
                            active = engineArchitectureIndex(engineValue) == index,
                            onClick = { engineId?.let { onParameterChange(it, choice.value) } },
                            description = "Select synthesis architecture " + choice.name,
                            role = Role.RadioButton,
                            enabled = engineId != null,
                        )
                    }
                }
                ParameterId.entries.firstOrNull { it.name == "CHARACTER" }?.let { id ->
                    CompactFxControl("CHARACTER", parameter(id), id, onParameterChange,
                        "Engine timbre and model character")
                }
            }
        }
    }
}

private enum class CompactFxPage(val caption: String) {
    QUICK("QUICK"), ENVELOPE("ENV"), MODULATION("LFO"), SPACE("SPACE")
}

/**
 * Short landscape screens cannot afford the full envelope + LFO + FX slider
 * subtree in one eager scrolling column. Page it by effect family, keeping
 * every parameter editable and the 82dp piano keyboard anchored below.
 */
@Composable
private fun FxPanel(
    parameter: (ParameterId) -> Float,
    onParameterChange: (ParameterId, Float) -> Unit,
    compactHeight: Boolean,
) {
    if (compactHeight) {
        var page by remember { mutableStateOf(CompactFxPage.QUICK) }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                "FX  /  PERFORMANCE RACK",
                color = OrbitalPalette.Text, fontSize = 12.sp, letterSpacing = 1.3.sp,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                CompactFxPage.entries.forEach { choice ->
                    Pill(
                        label = choice.caption,
                        active = page == choice,
                        onClick = { page = choice },
                        description = "FX page " + choice.name,
                        role = Role.RadioButton,
                    )
                }
            }
            when (page) {
                CompactFxPage.QUICK -> {
                    CompactFxControl("ATTACK", parameter(ParameterId.ATTACK), ParameterId.ATTACK, onParameterChange, "Fade-in time")
                    CompactFxControl("RELEASE", parameter(ParameterId.RELEASE), ParameterId.RELEASE, onParameterChange, "Fade-out time")
                    CompactFxControl("FX AMOUNT", parameter(ParameterId.FX_AMOUNT), ParameterId.FX_AMOUNT, onParameterChange, "Stereo space and texture")
                }
                CompactFxPage.ENVELOPE -> {
                    CompactFxControl("ATTACK", parameter(ParameterId.ATTACK), ParameterId.ATTACK, onParameterChange, "Fade-in time")
                    CompactFxControl("DECAY", parameter(ParameterId.DECAY), ParameterId.DECAY, onParameterChange, "Transient fall")
                    CompactFxControl("SUSTAIN", parameter(ParameterId.SUSTAIN), ParameterId.SUSTAIN, onParameterChange, "Held volume")
                    CompactFxControl("RELEASE", parameter(ParameterId.RELEASE), ParameterId.RELEASE, onParameterChange, "Fade-out time")
                }
                CompactFxPage.MODULATION -> {
                    CompactFxControl("LFO RATE", parameter(ParameterId.LFO_RATE), ParameterId.LFO_RATE, onParameterChange, "Modulation speed")
                    CompactFxControl("LFO DEPTH", parameter(ParameterId.LFO_DEPTH), ParameterId.LFO_DEPTH, onParameterChange, "Modulation strength")
                }
                CompactFxPage.SPACE ->
                    CompactFxControl("FX AMOUNT", parameter(ParameterId.FX_AMOUNT), ParameterId.FX_AMOUNT, onParameterChange, "Stereo space and texture")
            }
        }
    } else {
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
}

/**
 * Lightweight FX bar for tiny landscape editors: bounded composition,
 * tap/drag gesture control, and accessible incremental adjustments.
 */
@Composable
private fun CompactFxControl(
    title: String,
    value: Float,
    id: ParameterId,
    onParameterChange: (ParameterId, Float) -> Unit,
    description: String,
) {
    val update by rememberUpdatedState(onParameterChange)
    val normalized = value.coerceIn(0f, 1f)
    val tint = when (id) {
        ParameterId.ATTACK, ParameterId.DECAY, ParameterId.SUSTAIN, ParameterId.RELEASE -> OrbitalPalette.Violet
        ParameterId.LFO_RATE, ParameterId.LFO_DEPTH -> OrbitalPalette.Cyan
        else -> OrbitalPalette.Gold
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .border(1.dp, OrbitalPalette.PanelEdge.copy(alpha = .45f), RoundedCornerShape(13.dp))
            .background(OrbitalPalette.Panel.copy(alpha = .40f), RoundedCornerShape(13.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = OrbitalPalette.Text, fontSize = 9.sp,
            letterSpacing = .55.sp, maxLines = 1, modifier = Modifier.width(78.dp))
        Box(
            Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("compactFxBar_" + id.name)
                .semantics {
                    contentDescription = "$title. $description. ${percent(normalized)}."
                    customActions = listOf(
                        CustomAccessibilityAction("Increase $title") {
                            update(id, (normalized + .05f).coerceAtMost(1f)); true
                        },
                        CustomAccessibilityAction("Decrease $title") {
                            update(id, (normalized - .05f).coerceAtLeast(0f)); true
                        },
                    )
                }
                .pointerInput(id) {
                    detectTapGestures { position ->
                        val inset = 8.dp.toPx()
                        val length = (size.width - 2f * inset).coerceAtLeast(1f)
                        update(id, ((position.x - inset) / length).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(id) {
                    detectDragGestures(onDragStart = { offset ->
                        val inset = 8.dp.toPx()
                        val length = (size.width - 2f * inset).coerceAtLeast(1f)
                        update(id, ((offset.x - inset) / length).coerceIn(0f, 1f))
                    }) { change, _ ->
                        val inset = 8.dp.toPx()
                        val length = (size.width - 2f * inset).coerceAtLeast(1f)
                        update(id, ((change.position.x - inset) / length).coerceIn(0f, 1f))
                        change.consume()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                val inset = 8.dp.toPx()
                val usableWidth = (size.width - 2f * inset).coerceAtLeast(1f)
                val y = size.height / 2f
                val trackStart = Offset(inset, y)
                drawLine(OrbitalPalette.PanelEdge.copy(alpha = .85f), trackStart,
                    Offset(inset + usableWidth, y),
                    strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(tint.copy(alpha = .9f), trackStart,
                    Offset(inset + usableWidth * normalized, y),
                    strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(tint.copy(alpha = .20f), radius = 9.dp.toPx(),
                    center = Offset(inset + usableWidth * normalized, y))
                drawCircle(tint, radius = 4.6.dp.toPx(),
                    center = Offset(inset + usableWidth * normalized, y))
            }
        }
        Text(percent(normalized), color = tint, fontSize = 10.sp,
            modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
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
    // Neutral default for optional controls (UNISON/DETUNE) in a fresh demo state.
    else -> 0f
}

private fun percent(value: Float): String = (value.coerceIn(0f, 1f) * 100f).roundToInt().toString() + "%"
private fun cutoffLabel(value: Float): String {
    val hz = 40.0 * 400.0.pow(value.coerceIn(0f, 1f).toDouble())
    return if (hz >= 1000.0) "%.1f kHz".format(java.util.Locale.US, hz / 1000.0)
    else hz.roundToInt().toString() + " Hz"
}
