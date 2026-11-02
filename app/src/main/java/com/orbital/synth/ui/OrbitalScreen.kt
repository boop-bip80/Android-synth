package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

data class OrbitalUiState(
    val patchName: String = "AURORA",
    val playMode: PlayMode = PlayMode.POLY,
    val parameters: Map<ParameterId, Float> = emptyMap(),
    val octave: Int = 4,
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
) {
    var selectedTab by remember { mutableStateOf(Tab.SOUND) }
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
    LaunchedEffect(sequencerPlaying, tempo, state.octave) {
        if (!sequencerPlaying) {
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MainGradient)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(state.patchName)
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
                        .clickable { selectedTab = tab }
                        .padding(vertical = 9.dp),
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

        when (selectedTab) {
            Tab.SOUND -> {
                Box(Modifier.fillMaxWidth().height(244.dp)) {
                    OrbitalDisplay(morph, move, Modifier.fillMaxSize())
                    Column(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 18.dp, start = 9.dp),
                    ) {
                        Label("WAVETABLE")
                        Text(
                            state.patchName,
                            color = OrbitalPalette.Text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 2.4.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                    Box(Modifier.weight(1f).clickable { selectedTab = Tab.FX }) {
                        SignalTile("ENV", parameter(ParameterId.ATTACK), OrbitalPalette.Violet, Modifier.fillMaxWidth())
                    }
                    Box(Modifier.weight(1f).clickable { selectedTab = Tab.FX }) {
                        SignalTile("LFO", parameter(ParameterId.LFO_RATE), OrbitalPalette.Cyan, Modifier.fillMaxWidth())
                    }
                    Box(Modifier.weight(1f).clickable { selectedTab = Tab.PLAY }) {
                        SignalTile("MPE", parameter(ParameterId.GLIDE), OrbitalPalette.Gold, Modifier.fillMaxWidth())
                    }
                    Box(Modifier.weight(1f).clickable { selectedTab = Tab.SEQ }) {
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
                steps,
                playhead,
                sequencerPlaying,
                { sequencerPlaying = !sequencerPlaying },
                tempo,
                { tempo = it },
            )
            Tab.FX -> FxPanel(parameter, onParameterChange)
        }

        // The keyboard is always available while editing SOUND, PLAY, SEQ or FX.
        OrbitalKeyboard(
            octave = state.octave.coerceIn(0, 8),
            onNoteOn = onNoteOn,
            onNoteOff = onNoteOff,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Label("OCT")
            Pill("−", false, { onOctaveChange((state.octave - 1).coerceAtLeast(0)) })
            Text(
                state.octave.toString(),
                color = OrbitalPalette.Text,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            Pill("+", false, { onOctaveChange((state.octave + 1).coerceAtMost(8)) })
            Spacer(Modifier.weight(1f))
            PlayMode.entries.forEach { mode ->
                Pill(mode.name, state.playMode == mode, { onModeChange(mode) })
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Header(patch: String) {
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
            modifier = Modifier.semantics { contentDescription = "Synthesizer ready, patch $patch" })
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 9.sp, letterSpacing = .95.sp, color = OrbitalPalette.Muted)
}

@Composable
private fun Pill(label: String, active: Boolean, onClick: () -> Unit) {
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
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
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
                .pointerInput(Unit) {
                    fun update(x: Float, y: Float) {
                        onParameterChange(ParameterId.MORPH, (x / size.width).coerceIn(0f, 1f))
                        onParameterChange(ParameterId.MOVEMENT, (1f - y / size.height).coerceIn(0f, 1f))
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
                Pill(option.name, mode == option) { onModeChange(option) }
            }
        }
        ParameterSlider("GLIDE", glide, ParameterId.GLIDE, onParameterChange, "Slow pitch transition")
    }
}

@Composable
private fun SequencerPanel(
    steps: androidx.compose.runtime.snapshots.SnapshotStateList<Boolean>,
    playhead: Int,
    playing: Boolean,
    onTogglePlaying: () -> Unit,
    bpm: Int,
    onTempoChange: (Int) -> Unit,
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
        for (row in 0..1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in row * 8 until row * 8 + 8) {
                    val enabled = steps[i]
                    val current = playhead == i
                    Box(
                        Modifier
                            .weight(1f)
                            .height(68.dp)
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
                            .clickable { steps[i] = !steps[i] },
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
        Label("TEMPO  /  " + bpm + " BPM")
        Slider(
            value = bpm.toFloat(),
            onValueChange = { onTempoChange(it.roundToInt().coerceIn(60, 180)) },
            valueRange = 60f..180f,
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
