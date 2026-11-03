package com.orbital.synth.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.LfoShape
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import com.orbital.synth.core.dsp.processing.StepDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Live editor for the synth's immutable 8-LFO / 64-route snapshots.
 * Audio remains exclusively in OrbitalSynth; the host handles every callback.
 * All routes are reachable via the numbered grid; only one detail editor expands.
 * The parent screen owns vertical scrolling (no nested infinite-height scroll).
 */
@Composable
fun ModulationConstellationPanel(
    lfos: List<ModulationLfoConfig>,
    routes: List<ModulationRouteConfig>,
    tempoBpm: Float,
    onLfoChange: (Int, ModulationLfoConfig) -> Unit,
    onRouteChange: (Int, ModulationRouteConfig) -> Unit,
    onRouteClear: (Int) -> Unit,
    onTempoChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** Null means the host has not yet connected the real DSP sequencer. */
    stepConfig: ModulationStepConfig? = null,
    onStepChange: ((ModulationStepConfig) -> Unit)? = null,
    /** Audio-worker playhead; never a guessed UI animation counter. */
    stepPlayhead: Int = 0,
) {
    val lanes = List(8) { lfos.getOrNull(it) ?: ModulationLfoConfig() }
    val patch = List(64) { routes.getOrNull(it) ?: ModulationRouteConfig() }
    var current by remember { mutableIntStateOf(0) }
    val chosen = patch[current]
    val bpm = ModulationConstellationModel.safeTempo(tempoBpm)
    val count = patch.count { it.enabled }

    Column(
        modifier
            .testTag("modulationConstellationPanel")
            .border(1.dp, OrbitalPalette.PanelEdge, RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF061020), Color(0xFF03050C), Color(0xFF08162B))
                ),
                RoundedCornerShape(18.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "M O D U L A T I O N   /   C O N S T E L L A T I O N",
            color = OrbitalPalette.Cyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
        Text(
            "8 LFO ENGINES     " + count + " / 64 PATCH CORDS",
            color = OrbitalPalette.Muted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        ConstellationGraph(lanes, patch, current)
        Text(
            "Animated cables change real audio; dim static cables are saved for future DSP. This graph is not a measured spectrum.",
            color = OrbitalPalette.Muted,
            fontSize = 10.sp,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("MASTER TEMPO", color = OrbitalPalette.Muted, fontSize = 9.sp)
                Text(bpm.toInt().toString() + " BPM", color = OrbitalPalette.Gold,
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Row {
                NeonButton("− 5", "Decrease tempo", OrbitalPalette.Cyan) {
                    onTempoChange((bpm - 5f).coerceAtLeast(20f))
                }
                NeonButton("+ 5", "Increase tempo", OrbitalPalette.Cyan) {
                    onTempoChange((bpm + 5f).coerceAtMost(400f))
                }
            }
        }

        Text("EIGHT LFO OSCILLATORS  /  SWIPE", color = OrbitalPalette.Text,
            fontSize = 11.sp, letterSpacing = .7.sp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .testTag("modulationLfoCarousel"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            lanes.forEachIndexed { index, config ->
                LfoCard(index, config) { onLfoChange(index, it) }
            }
        }

        if (stepConfig != null && onStepChange != null) {
            StepRhythmPanel(stepConfig, stepPlayhead, onStepChange)
        }

        Text("PATCH BAY  /  SELECT ANY OF 64 ROUTES", color = OrbitalPalette.Text,
            fontSize = 11.sp, letterSpacing = .7.sp)
        Column(Modifier.testTag("modulationRouteGrid")) {
            repeat(8) { row ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(8) { col ->
                        val index = row * 8 + col
                        val route = patch[index]
                        val selected = index == current
                        val tint = if (route.enabled) sourceTint(route.source) else OrbitalPalette.Muted
                        val live = route.enabled &&
                            ModulationConstellationModel.audibleTarget(route.destination)
                        Box(
                            Modifier.weight(1f).padding(2.dp).height(39.dp)
                                .border(
                                    if (selected) 1.4.dp else .6.dp,
                                    if (selected) OrbitalPalette.Gold else tint.copy(alpha = .45f),
                                    RoundedCornerShape(7.dp),
                                )
                                .background(
                                    if (live) tint.copy(alpha = .15f)
                                    else if (route.enabled) tint.copy(alpha = .06f)
                                    else Color(0xFF061020), RoundedCornerShape(7.dp),
                                )
                                .clickable(role = Role.Button) { current = index }
                                .semantics {
                                    contentDescription = "Route " + (index + 1) +
                                        (if (!route.enabled) ", empty"
                                        else if (live) ", audible" else ", reserved")
                                }
                                .testTag("modRouteSlot" + index),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                ModulationConstellationModel.slotLabel(index),
                                color = if (selected) OrbitalPalette.Gold else tint,
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().testTag("modulationRouteEditor")
                .border(1.dp, OrbitalPalette.Cyan.copy(alpha = .46f), RoundedCornerShape(14.dp))
                .background(Color(0xFF071428), RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("ROUTE " + ModulationConstellationModel.slotLabel(current),
                        color = OrbitalPalette.Gold, fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(
                        when {
                            !chosen.enabled -> "AVAILABLE SLOT"
                            ModulationConstellationModel.audibleTarget(chosen.destination) ->
                                "LIVE AUDIO CONNECTION"
                            else -> "SAVED / DSP RESERVED"
                        },
                        color = if (chosen.enabled &&
                            ModulationConstellationModel.audibleTarget(chosen.destination))
                            OrbitalPalette.Teal else OrbitalPalette.Muted,
                        fontSize = 9.sp,
                        modifier = Modifier.testTag("modulationConnectionState"),
                    )
                }
                Row {
                    NeonButton(
                        if (chosen.enabled) "DISABLE" else "ENABLE",
                        "Toggle route " + (current + 1),
                        if (chosen.enabled) OrbitalPalette.Gold else OrbitalPalette.Cyan,
                    ) {
                        onRouteChange(current, chosen.copy(enabled = !chosen.enabled))
                    }
                    NeonButton("CLEAR", "Clear route " + (current + 1), OrbitalPalette.Muted) {
                        onRouteClear(current)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EnumPicker(
                    "SOURCE", chosen.source, ModSource.entries,
                    { ModulationConstellationModel.enumLabel(it.name) },
                    { onRouteChange(current, chosen.copy(source = it, enabled = true)) }, Modifier.weight(1f),
                )
                Text("→", color = OrbitalPalette.Gold, fontSize = 22.sp)
                EnumPicker(
                    "DESTINATION", chosen.destination,
                    ModulationConstellationModel.destinationChoices,
                    { ModulationConstellationModel.destinationLabel(it) },
                    { onRouteChange(current, chosen.copy(destination = it, enabled = true)) }, Modifier.weight(1f),
                )
            }
            EnumPicker(
                "MODULATION CURVE", chosen.curve, ModCurve.entries,
                { ModulationConstellationModel.enumLabel(it.name) },
                { onRouteChange(current, chosen.copy(curve = it, enabled = true)) }, Modifier.fillMaxWidth(),
            )
            Text(
                "DEPTH  " + ("%.2f".format(chosen.depth.coerceIn(-4f, 4f))) + "×",
                color = if (chosen.depth >= 0f) OrbitalPalette.Cyan else OrbitalPalette.Violet,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
            Slider(
                value = chosen.depth.coerceIn(-4f, 4f),
                onValueChange = { onRouteChange(current, chosen.copy(depth = it, enabled = true)) },
                valueRange = -4f..4f,
                modifier = Modifier.fillMaxWidth().testTag("modulationDepthSlider"),
                colors = SliderDefaults.colors(
                    thumbColor = OrbitalPalette.Gold,
                    activeTrackColor = OrbitalPalette.Cyan,
                    inactiveTrackColor = OrbitalPalette.DeepBlue,
                ),
            )
            Text(
                ModulationConstellationModel.destinationStatus(chosen.destination),
                color = if (ModulationConstellationModel.audibleTarget(chosen.destination))
                    OrbitalPalette.Teal else OrbitalPalette.Gold,
                fontSize = 10.sp,
                modifier = Modifier.testTag("modulationDestinationStatus"),
            )
        }
        Text(
            "LIVE AUDIO TARGETS  /  PITCH · MORPH · CUTOFF · RESONANCE · AMP · PAN. " +
                "All other destinations remain saved/editable but are not yet audible.",
            color = OrbitalPalette.Muted, fontSize = 10.sp,
            modifier = Modifier.testTag("modulationLiveDestinationsLegend"),
        )
    }
}

/**
 * Touch-drawn, sixteen-pad rhythmic modulation. All interactions are immutable
 * editor callbacks; the visible playhead comes from real DSP frame advancement.
 */
@Composable
private fun StepRhythmPanel(
    config: ModulationStepConfig,
    playhead: Int,
    onChange: (ModulationStepConfig) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().testTag("modStepRhythmPanel")
            .border(1.dp, OrbitalPalette.Violet.copy(alpha = .58f), RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(
                listOf(Color(0xFF140A29), Color(0xFF040710), Color(0xFF071D29))
            ), RoundedCornerShape(16.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("S T E P  /  T O U C H   R H Y T H M",
                    color = OrbitalPalette.Gold, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold)
                Text("16 BIPOLAR PADS  •  REAL AUDIO-CLOCKED MODULATION",
                    color = OrbitalPalette.Muted, fontSize = 9.sp)
            }
            NeonButton(if (config.enabled) "ON" else "OFF",
                "Toggle STEP modulator", OrbitalPalette.Cyan) {
                onChange(config.copy(enabled = !config.enabled))
            }
        }
        repeat(2) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(8) { column ->
                    val index = row * 8 + column
                    StepRhythmPad(
                        index, config, config.enabled && index == playhead,
                        onChange, Modifier.weight(1f),
                    )
                }
            }
        }
        Text(
            if (config.enabled)
                "STEP CLOCK ARMED  •  Tap higher or lower inside a pad to draw bipolar modulation."
            else
                "STEP CLOCK DISARMED  •  Draw a pattern, then tap ON and route STEP to a live destination.",
            color = if (config.enabled) OrbitalPalette.Teal else OrbitalPalette.Muted,
            fontSize = 10.sp,
            modifier = Modifier.testTag("modStepSourceStatus"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            EnumPicker(
                "ORDER", config.direction, StepDirection.entries,
                { ModulationConstellationModel.enumLabel(it.name) },
                { onChange(config.copy(direction = it)) }, Modifier.weight(1f),
            )
            Column(Modifier.weight(1f)) {
                Text("LENGTH  " + config.length, color = OrbitalPalette.Muted, fontSize = 9.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    NeonButton("−", "Shorten STEP pattern", OrbitalPalette.Violet) {
                        onChange(config.copy(length = (config.length - 1).coerceAtLeast(1)))
                    }
                    NeonButton("+", "Lengthen STEP pattern", OrbitalPalette.Cyan) {
                        onChange(config.copy(length = (config.length + 1).coerceAtMost(16)))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text("BEATS / PAD  " + config.beatsPerStep,
                    color = OrbitalPalette.Muted, fontSize = 9.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    NeonButton("÷2", "Faster STEP clock", OrbitalPalette.Violet) {
                        onChange(config.copy(beatsPerStep =
                            ModulationConstellationModel.adjacentBeats(config.beatsPerStep, -1)))
                    }
                    NeonButton("×2", "Slower STEP clock", OrbitalPalette.Cyan) {
                        onChange(config.copy(beatsPerStep =
                            ModulationConstellationModel.adjacentBeats(config.beatsPerStep, 1)
                                .coerceAtMost(8f)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("GLIDE  " + ("%.0f".format(config.glide * 100f)) + "%",
                color = OrbitalPalette.Cyan, fontSize = 10.sp)
            NeonButton(
                if (config.retriggerOnNote) "RETRIGGER ON" else "FREE RUN",
                "Toggle STEP note retrigger", OrbitalPalette.Gold,
            ) { onChange(config.copy(retriggerOnNote = !config.retriggerOnNote)) }
        }
        Slider(
            value = config.glide,
            onValueChange = { onChange(config.copy(glide = it)) },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth().testTag("modStepGlideSlider"),
            colors = SliderDefaults.colors(
                thumbColor = OrbitalPalette.Gold,
                activeTrackColor = OrbitalPalette.Violet,
                inactiveTrackColor = OrbitalPalette.DeepBlue,
            ),
        )
    }
}

@Composable
private fun StepRhythmPad(
    index: Int,
    config: ModulationStepConfig,
    highlighted: Boolean,
    onChange: (ModulationStepConfig) -> Unit,
    modifier: Modifier,
) {
    val latestConfig by androidx.compose.runtime.rememberUpdatedState(config)
    val latestCallback by androidx.compose.runtime.rememberUpdatedState(onChange)
    val value = config.values[index]
    val tint = if (value >= 0f) OrbitalPalette.Cyan else OrbitalPalette.Violet
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxWidth().height(76.dp)
                .testTag("modStepPad" + index)
                .semantics {
                    contentDescription = "STEP pad " + (index + 1) +
                        ", " + ("%.2f".format(value)) + ", tap vertically to edit"
                }
                .pointerInput(index) {
                    detectTapGestures { position ->
                        val y = position.y.coerceIn(0f, size.height.toFloat())
                        val next = (1f - 2f * y / size.height.coerceAtLeast(1)).coerceIn(-1f, 1f)
                        latestCallback(latestConfig.withStep(index, next))
                    }
                }
                .pointerInput(index) {
                    detectDragGestures { change, _ ->
                        val y = change.position.y.coerceIn(0f, size.height.toFloat())
                        val next = (1f - 2f * y / size.height.coerceAtLeast(1)).coerceIn(-1f, 1f)
                        latestCallback(latestConfig.withStep(index, next))
                        change.consume()
                    }
                },
        ) {
            val radius = CornerRadius(7.dp.toPx())
            drawRoundRect(
                if (highlighted) OrbitalPalette.Gold.copy(alpha = .23f)
                else Color(0xFF07152C),
                cornerRadius = radius,
            )
            drawRoundRect(
                tint.copy(alpha = if (highlighted) .95f else .32f),
                cornerRadius = radius, style = Stroke(width =
                    if (highlighted) 1.5.dp.toPx() else .7.dp.toPx()),
            )
            val middle = size.height * .50f
            drawLine(OrbitalPalette.Muted.copy(alpha = .36f),
                Offset(4.dp.toPx(), middle),
                Offset(size.width - 4.dp.toPx(), middle),
                strokeWidth = .65.dp.toPx())
            val extent = size.height * .43f * abs(value)
            val top = if (value >= 0f) middle - extent else middle
            val pad = 6.dp.toPx()
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(tint.copy(alpha = .96f), tint.copy(alpha = .38f))
                ),
                topLeft = Offset(pad, top),
                size = Size((size.width - pad * 2f).coerceAtLeast(1f),
                    extent.coerceAtLeast(.8.dp.toPx())),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            if (highlighted) {
                drawCircle(OrbitalPalette.Gold.copy(alpha = .96f),
                    radius = 2.7.dp.toPx(), center = Offset(size.width / 2f, 7.dp.toPx()))
            }
        }
        Text((index + 1).toString().padStart(2, '0'),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            color = if (highlighted) OrbitalPalette.Gold else OrbitalPalette.Muted,
            fontFamily = FontFamily.Monospace, fontSize = 9.sp)
    }
}

@Composable
private fun LfoCard(
    slot: Int,
    config: ModulationLfoConfig,
    onChange: (ModulationLfoConfig) -> Unit,
) {
    val tint = sourceTint(ModSource.entries[if (slot == 0) 0 else slot + 7])
    val sync = config.beatsPerCycle > 0f
    Column(
        Modifier.width(184.dp)
            .border(1.dp, tint.copy(alpha = if (config.enabled) .64f else .24f),
                RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(listOf(tint.copy(alpha = .15f), Color(0xFF071020))),
                RoundedCornerShape(14.dp),
            )
            .padding(10.dp).testTag("modLfoSlot" + slot),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("LFO " + (slot + 1).toString().padStart(2, '0'),
                color = tint, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace)
            NeonButton(if (config.enabled) "ON" else "OFF",
                "Toggle LFO " + (slot + 1), tint) {
                onChange(config.copy(enabled = !config.enabled))
            }
        }
        LfoWaveform(config.shape, tint, Modifier.fillMaxWidth().height(52.dp))
        EnumPicker(
            "WAVEFORM", config.shape, LfoShape.entries,
            { ModulationConstellationModel.enumLabel(it.name) },
            { onChange(config.copy(shape = it)) }, Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NeonButton(if (sync) "SYNC" else "FREE",
                "Toggle beat synchronization for LFO " + (slot + 1),
                if (sync) OrbitalPalette.Gold else tint) {
                onChange(config.copy(beatsPerCycle = if (sync) 0f else 4f))
            }
            NeonButton(if (config.retriggerOnNote) "RETRIG" else "NO RET",
                "Toggle retrigger for LFO " + (slot + 1),
                if (config.retriggerOnNote) tint else OrbitalPalette.Muted) {
                onChange(config.copy(retriggerOnNote = !config.retriggerOnNote))
            }
        }
        if (sync) {
            Text(config.beatsPerCycle.toString() + " BEATS / CYCLE",
                color = OrbitalPalette.Gold, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                NeonButton("÷ 2", "Shorter LFO cycle", tint) {
                    onChange(config.copy(beatsPerCycle =
                        ModulationConstellationModel.adjacentBeats(config.beatsPerCycle, -1)))
                }
                NeonButton("× 2", "Longer LFO cycle", tint) {
                    onChange(config.copy(beatsPerCycle =
                        ModulationConstellationModel.adjacentBeats(config.beatsPerCycle, 1)))
                }
            }
        } else {
            Text(("%.2f".format(config.rateHz)) + " HZ",
                color = tint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Slider(
                value = ModulationConstellationModel.hzToSlider(config.rateHz),
                onValueChange = {
                    onChange(config.copy(rateHz = ModulationConstellationModel.sliderToHz(it)))
                },
                modifier = Modifier.fillMaxWidth().testTag("modLfoRate" + slot),
                colors = SliderDefaults.colors(
                    thumbColor = tint, activeTrackColor = tint,
                    inactiveTrackColor = OrbitalPalette.DeepBlue,
                ),
            )
        }
    }
}

@Composable
private fun <T> EnumPicker(
    title: String,
    selected: T,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(title, color = OrbitalPalette.Muted, fontSize = 9.sp, letterSpacing = .6.sp)
        Box {
            Text(
                display(selected),
                Modifier.fillMaxWidth()
                    .border(1.dp, OrbitalPalette.PanelEdge, RoundedCornerShape(8.dp))
                    .background(Color(0xFF061221), RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { open = true }
                    .padding(horizontal = 8.dp, vertical = 10.dp)
                    .semantics {
                        contentDescription = title + ": " + display(selected) + ", tap to change"
                    },
                color = OrbitalPalette.Text, fontSize = 10.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Text(display(item), color = if (item == selected)
                                OrbitalPalette.Cyan else OrbitalPalette.Text, fontSize = 12.sp)
                        },
                        onClick = { open = false; onSelect(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NeonButton(
    label: String,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description }) {
        Text(label, color = tint, fontSize = 10.sp,
            fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun LfoWaveform(shape: LfoShape, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path()
        val h = size.height
        val w = size.width
        drawLine(tint.copy(alpha = .14f), Offset(0f, h * .5f), Offset(w, h * .5f))
        for (i in 0..96) {
            val t = i / 96f
            val q = (t * 2f) % 1f
            val v = when (shape) {
                LfoShape.SINE -> sin(t * (PI * 4).toFloat())
                LfoShape.TRIANGLE -> 1f - 4f * abs(q - .5f)
                LfoShape.SAW_UP -> 2f * q - 1f
                LfoShape.SAW_DOWN -> 1f - 2f * q
                LfoShape.SQUARE -> if (q < .5f) 1f else -1f
                LfoShape.SAMPLE_HOLD -> {
                    val index = (t * 11f).toInt()
                    (((index * 31 + 17) % 101) / 50f - 1f).coerceIn(-1f, 1f)
                }
            }
            val x = t * w
            val y = h * (.5f - v * .38f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, tint.copy(alpha = .17f),
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, tint,
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Graph is topology, not a sampled audio signal. Nodes and edges are actual configs. */
@Composable
private fun ConstellationGraph(
    lfos: List<ModulationLfoConfig>,
    routes: List<ModulationRouteConfig>,
    selected: Int,
) {
    val motionAllowed = remember { ValueAnimator.areAnimatorsEnabled() }
    val clock = rememberInfiniteTransition(label = "modulation-clock")
    val phase by clock.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Restart),
        label = "modulation-route-particles",
    )
    val animate = if (motionAllowed) phase else 0f
    Canvas(
        Modifier.fillMaxWidth().height(212.dp)
            .border(1.dp, OrbitalPalette.PanelEdge, RoundedCornerShape(14.dp))
            .background(Color(0xFF020611), RoundedCornerShape(14.dp))
            .testTag("modulationConstellationVisual"),
    ) {
        val w = size.width
        val h = size.height
        if (w < 1f || h < 1f) return@Canvas
        val colors = arrayOf(OrbitalPalette.Cyan, OrbitalPalette.Violet,
            OrbitalPalette.Gold, OrbitalPalette.Teal)
        val center = Offset(w / 2f, h / 2f)
        drawCircle(
            Brush.radialGradient(
                listOf(OrbitalPalette.Blue.copy(alpha = .21f),
                    OrbitalPalette.Violet.copy(alpha = .06f), Color.Transparent),
                center = center, radius = w * .53f,
            ),
            center = center, radius = w * .53f,
        )
        for (index in 0 until 52) {
            val x = w * (((index * 41 + 17) % 103) / 103f)
            val y = h * (((index * 37 + 23) % 107) / 107f)
            drawCircle(colors[index % 4].copy(alpha = .14f + (index % 5) * .035f),
                radius = if (index % 9 == 0) 1.3.dp.toPx() else .55.dp.toPx(),
                center = Offset(x, y))
        }
        for (lane in 0 until 8) {
            val point = Offset(w * .17f, h * (.12f + lane * .105f))
            val tint = colors[lane % 4]
            val enabled = lfos.getOrNull(lane)?.enabled == true
            drawCircle(tint.copy(alpha = if (enabled) .13f else .04f),
                19.dp.toPx(), point)
            drawCircle(tint.copy(alpha = if (enabled) .95f else .24f),
                if (enabled) 4.0.dp.toPx() else 2.3.dp.toPx(), point)
        }
        fun sourceY(source: ModSource): Float {
            val id = if (source.name.startsWith("LFO"))
                source.name.removePrefix("LFO").toIntOrNull() else null
            return if (id != null) h * (.12f + (id - 1).coerceIn(0, 7) * .105f)
            else h * (.12f + source.ordinal.toFloat() /
                (ModSource.entries.size - 1).coerceAtLeast(1) * .74f)
        }
        for (index in routes.indices) {
            val route = routes[index]
            if (!route.enabled) continue
            val sx = w * .17f
            val sy = sourceY(route.source)
            val ex = w * .83f
            val ey = h * (.12f + route.destination.ordinal.toFloat() /
                (ModDestination.entries.size - 1).coerceAtLeast(1) * .74f)
            val control1 = Offset(w * .42f, sy + (ey - sy) * .05f)
            val control2 = Offset(w * .58f, ey - (ey - sy) * .05f)
            val tint = sourceTint(route.source)
            val audible = ModulationConstellationModel.audibleTarget(route.destination)
            val alpha = when {
                index == selected && audible -> .93f
                index == selected -> .58f
                audible -> .54f
                else -> .25f
            }
            val stroke = if (index == selected) 2.0.dp.toPx() else .9.dp.toPx()
            val path = Path().apply {
                moveTo(sx, sy)
                cubicTo(control1.x, control1.y, control2.x, control2.y, ex, ey)
            }
            drawPath(path, tint.copy(alpha = alpha * .15f),
                style = Stroke(width = stroke * 8f))
            drawPath(path, tint.copy(alpha = alpha),
                style = Stroke(width = stroke, cap = StrokeCap.Round))
            drawCircle(tint.copy(alpha = alpha), 2.4.dp.toPx(), Offset(ex, ey))
            // Photons imply an active connection to DSP; reserved cables never
            // show animated audio flow even when their metadata is enabled.
            if (motionAllowed && audible) {
                val t = (animate + index * .17f) % 1f
                val q = 1f - t
                fun bezier(a: Float, b: Float, c: Float, d: Float): Float =
                    q * q * q * a + 3f * q * q * t * b + 3f * q * t * t * c + t * t * t * d
                val photon = Offset(
                    bezier(sx, control1.x, control2.x, ex),
                    bezier(sy, control1.y, control2.y, ey),
                )
                drawCircle(tint.copy(alpha = alpha * .23f), 8.dp.toPx(), photon)
                drawCircle(Color.White.copy(alpha = alpha), 1.2.dp.toPx(), photon)
            }
        }
    }
}

private fun sourceTint(source: ModSource): Color = when (source.ordinal % 4) {
    0 -> OrbitalPalette.Cyan
    1 -> OrbitalPalette.Violet
    2 -> OrbitalPalette.Gold
    else -> OrbitalPalette.Teal
}
