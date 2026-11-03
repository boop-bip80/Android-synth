package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.dsp.processing.LfoShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A static, responsive, sound-design garden for the eight real LFO snapshots.
 * No UI oscillator clock, FFT, random-state claim, PCM monitoring, or audio thread
 * work. The bright trace is configured LFO voltage versus phase, NOT its live phase.
 * Depth is routed per *cable*, never represented as a nonexistent LFO parameter.
 */
@Composable
internal fun LfoConfigurationGarden(
    lfos: List<ModulationLfoConfig>,
    routes: List<ModulationRouteConfig>,
    tempoBpm: Float,
    onLfoChange: (Int, ModulationLfoConfig) -> Unit,
    onRouteChange: (Int, ModulationRouteConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lanes = remember(lfos, routes, tempoBpm) {
        List(8) { slot ->
            LfoConfigurationGardenModel.lane(
                slot, lfos.getOrNull(slot) ?: ModulationLfoConfig(), routes, tempoBpm
            )
        }
    }
    var selected by remember { mutableIntStateOf(0) }
    var routeCursor by remember(selected) { mutableIntStateOf(0) }
    val lane = lanes[selected]
    val config = lane.config
    val tint = if (lane.config.enabled) OrbitalPalette.Cyan else OrbitalPalette.Violet
    val cursor = routeCursor.coerceIn(0, (lane.liveCables.size - 1).coerceAtLeast(0))
    val cable = lane.liveCables.getOrNull(cursor)
    val actualCable = cable?.let { routes.getOrNull(it.slot) }

    Column(
        modifier
            .fillMaxWidth()
            .testTag("lfoConfigurationGarden")
            .border(1.dp, OrbitalPalette.Violet.copy(alpha = .45f), RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(listOf(
                    Color(0xFF130A28), Color(0xFF030715), Color(0xFF07172B),
                )), RoundedCornerShape(18.dp),
            )
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column {
            Text("L F O   /   V O L T A G E   G A R D E N",
                color = OrbitalPalette.Cyan, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("8 CONFIGURED SOURCES · TOUCH A NODE TO SCULPT ITS ACTUAL DSP SETTINGS",
                color = OrbitalPalette.Muted, fontSize = 9.sp)
        }

        repeat(2) { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(4) { col ->
                    val index = row * 4 + col
                    val item = lanes[index]
                    LfoGardenNode(
                        lane = item, focused = selected == index,
                        onClick = { selected = index },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("SOURCE  " + (selected + 1).toString().padStart(2, '0') +
                    "  /  " + config.shape.name.replace('_', ' '),
                    color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
                Text(
                    if (lane.synchronized)
                        config.beatsPerCycle.toString() + " BEATS / CYCLE  ·  " +
                            formatRate(lane.rateHz) + " HZ AT CURRENT TEMPO"
                    else formatRate(lane.rateHz) + " HZ  ·  FREE CLOCK",
                    color = OrbitalPalette.Gold, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("lfoGardenEffectiveRate"),
                )
            }
            Text(
                if (config.enabled) "ARMED" else "PAUSED",
                color = if (config.enabled) OrbitalPalette.Teal else OrbitalPalette.Muted,
                fontSize = 10.sp, fontWeight = FontWeight.Bold,
            )
        }

        LfoGardenShapeDiagram(lane, Modifier.fillMaxWidth().height(130.dp))

        Text(
            if (config.shape == LfoShape.SAMPLE_HOLD)
                "CONFIG SHAPE / two illustrative held voltages · actual seeded audio samples are not exposed."
            else "CONFIG SHAPE / two normalized cycles · static voltage diagram, not measured PCM or live phase.",
            color = OrbitalPalette.Muted, fontSize = 9.sp,
            modifier = Modifier.testTag("lfoGardenTruthLegend"),
        )

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            LfoShape.entries.forEach { shape ->
                GardenAction(
                    label = when (shape) {
                        LfoShape.SINE -> "SIN"
                        LfoShape.TRIANGLE -> "TRI"
                        LfoShape.SAW_UP -> "UP"
                        LfoShape.SAW_DOWN -> "DOWN"
                        LfoShape.SQUARE -> "SQR"
                        LfoShape.SAMPLE_HOLD -> "S&H"
                    },
                    description = "Set LFO " + (selected + 1) + " shape to " +
                        shape.name.replace('_', ' '),
                    tint = if (config.shape == shape) OrbitalPalette.Gold else OrbitalPalette.Cyan,
                    tag = "lfoGardenShape" + shape.name,
                    onClick = { onLfoChange(selected, config.copy(shape = shape)) },
                )
            }
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            GardenAction(
                if (config.enabled) "ON" else "OFF",
                "Toggle LFO " + (selected + 1) + " audio source",
                if (config.enabled) OrbitalPalette.Teal else OrbitalPalette.Muted,
                "lfoGardenEnabled",
            ) { onLfoChange(selected, config.copy(enabled = !config.enabled)) }
            GardenAction(
                if (lane.synchronized) "SYNC" else "FREE",
                "Change LFO " + (selected + 1) + " between beat sync and free clock",
                OrbitalPalette.Gold, "lfoGardenSync",
            ) {
                onLfoChange(selected, LfoConfigurationGardenModel.toggleSync(config, tempoBpm))
            }
            GardenAction("SLOWER", "Halve LFO speed or double beats per cycle",
                OrbitalPalette.Violet, "lfoGardenSlower") {
                onLfoChange(selected, LfoConfigurationGardenModel.faster(config, false))
            }
            GardenAction("FASTER", "Double LFO speed or halve beats per cycle",
                OrbitalPalette.Cyan, "lfoGardenFaster") {
                onLfoChange(selected, LfoConfigurationGardenModel.faster(config, true))
            }
            GardenAction(
                if (config.retriggerOnNote) "RETRIG" else "FREE RUN",
                "Toggle retrigger of LFO " + (selected + 1) + " on note gate",
                OrbitalPalette.Gold, "lfoGardenRetrigger",
            ) { onLfoChange(selected, config.copy(retriggerOnNote = !config.retriggerOnNote)) }
        }

        Column(
            Modifier.fillMaxWidth()
                .border(1.dp, OrbitalPalette.Gold.copy(alpha = .32f), RoundedCornerShape(12.dp))
                .background(Color(0xFF081225), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "CONNECTED ROUTES  /  " + lane.connectedCount +
                    " CONFIGURED  ·  " + lane.liveCables.size + " DSP TARGETS" +
                    if (lane.reservedCount > 0) "  ·  " + lane.reservedCount + " RESERVED" else "",
                color = OrbitalPalette.Gold, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("lfoGardenCableStatus"),
            )
            if (cable == null || actualCable == null) {
                Text(
                    "NO CONNECTED DSP ROUTE FROM THIS LFO. Connect an enabled cable to PITCH, MORPH, CUTOFF, RESONANCE, AMP OR PAN in the 64-cord patch bay to edit its depth.",
                    color = OrbitalPalette.Muted, fontSize = 10.sp,
                    modifier = Modifier.testTag("lfoGardenNoLiveCable"),
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "CORD " + (cable.slot + 1).toString().padStart(2, '0') +
                            " → " + cable.destination.name.replace('_', ' ') +
                            "   " + formatDepth(cable.depth) + "×",
                        color = if (cable.depth >= 0f) OrbitalPalette.Cyan
                            else OrbitalPalette.Violet,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("lfoGardenEditingCable"),
                    )
                    if (lane.liveCables.size > 1) {
                        GardenAction("NEXT", "Select next configured DSP-target cable for LFO " + (selected + 1),
                            OrbitalPalette.Gold, "lfoGardenNextCable") {
                            routeCursor = (cursor + 1) % lane.liveCables.size
                        }
                    }
                }
                Slider(
                    value = cable.depth,
                    onValueChange = { next ->
                        // This changes exactly the selected EXISTING route;
                        // the unrelated LFO config and all other cables survive.
                        onRouteChange(cable.slot, actualCable.copy(depth = next.coerceIn(-4f, 4f)))
                    },
                    valueRange = -4f..4f,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Depth of real modulation cable " +
                                (cable.slot + 1) + " to " + cable.destination.name
                        }
                        .testTag("lfoGardenRouteDepth"),
                    colors = SliderDefaults.colors(
                        thumbColor = OrbitalPalette.Gold,
                        activeTrackColor = OrbitalPalette.Cyan,
                        inactiveTrackColor = OrbitalPalette.DeepBlue,
                    ),
                )
                Text("BIPOLAR ROUTE DEPTH ±4× · NOT AN LFO AMPLITUDE CONTROL.",
                    color = OrbitalPalette.Muted, fontSize = 9.sp)
                if (!config.enabled || cable.depth == 0f) {
                    Text(
                        when {
                            !config.enabled && cable.depth == 0f ->
                                "CONFIGURED ONLY · LFO PAUSED AND CABLE DEPTH ZERO."
                            !config.enabled ->
                                "CONFIGURED ONLY · LFO SOURCE PAUSED, NO CURRENT MODULATION."
                            else -> "CONFIGURED ONLY · CABLE DEPTH ZERO, NO MODULATION."
                        },
                        color = OrbitalPalette.Muted,
                        fontSize = 9.sp,
                        modifier = Modifier.testTag("lfoGardenCableAudibility"),
                    )
                }
            }
        }
    }
}

private fun formatRate(rate: Float): String =
    java.lang.String.format(java.util.Locale.US, "%.2f", rate)

private fun formatDepth(depth: Float): String =
    java.lang.String.format(java.util.Locale.US, "%+.2f", depth)

@Composable
private fun LfoGardenNode(
    lane: GardenLane,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val tint = if (!lane.config.enabled) OrbitalPalette.Muted
        else if (focused) OrbitalPalette.Gold else OrbitalPalette.Cyan
    Column(
        modifier
            .heightIn(min = 84.dp)
            .border(if (focused) 1.5.dp else .8.dp,
                tint.copy(alpha = if (focused) .93f else .44f), RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(listOf(
                    tint.copy(alpha = if (focused) .22f else .07f),
                    Color(0xFF040A1C),
                )), RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "Select LFO " + (lane.slot + 1) + ", " +
                    lane.config.shape.name.replace('_', ' ') + ", " +
                    (if (lane.config.enabled) "enabled" else "paused") +
                    ", " + formatRate(lane.rateHz) + " hertz, " +
                    lane.liveCables.size + " configured DSP-target outgoing cables"
            }
            .testTag("lfoGardenNode" + lane.slot)
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(45.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) * .31f
            drawCircle(tint.copy(alpha = .075f), radius * 1.65f, center)
            drawCircle(tint.copy(alpha = if (focused) .56f else .25f),
                radius, center, style = Stroke(width = 1.2.dp.toPx()))
            val path = Path()
            val depthBloom = .18f + lane.maxLiveDepth.coerceIn(0f, 4f) * .04f
            for (i in 0..48) {
                val angle = 2.0 * PI * i / 48.0
                val voltage = lane.trace[i * 2].voltage
                val rim = radius * (1f + voltage * depthBloom)
                val x = center.x + cos(angle).toFloat() * rim
                val y = center.y + sin(angle).toFloat() * rim
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, tint.copy(alpha = if (focused) .25f else .1f),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, tint.copy(alpha = if (focused) .98f else .64f),
                style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(if (focused) OrbitalPalette.Gold else OrbitalPalette.Violet,
                radius = 2.dp.toPx(), center = center)
        }
        Text((lane.slot + 1).toString().padStart(2, '0'),
            color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LfoGardenShapeDiagram(lane: GardenLane, modifier: Modifier) {
    Box(
        modifier
            .border(1.dp, OrbitalPalette.Cyan.copy(alpha = .42f), RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(
                Color(0xFF0A1732), Color(0xFF020510), Color(0xFF07152C),
            )), RoundedCornerShape(14.dp))
            .semantics {
                contentDescription = "Static configuration drawing for LFO " +
                    (lane.slot + 1) + ", " + lane.config.shape.name +
                    ", " + formatRate(lane.rateHz) + " hertz. " +
                    (if (lane.config.shape == LfoShape.SAMPLE_HOLD)
                        "Sample and hold voltages are illustrative, not actual random audio values."
                     else "Two exact normalized shape cycles, not a live phase or measured PCM.")
            }
            .testTag("lfoGardenPreview"),
    ) {
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val w = size.width
            val h = size.height
            if (w < 10f || h < 10f) return@Canvas
            for (line in 1..5) {
                val y = h * line / 6f
                drawLine(OrbitalPalette.Blue.copy(alpha = .12f),
                    Offset(0f, y), Offset(w, y), strokeWidth = .7.dp.toPx())
            }
            for (line in 1..7) {
                val x = w * line / 8f
                drawLine(OrbitalPalette.Blue.copy(alpha = .10f),
                    Offset(x, 0f), Offset(x, h), strokeWidth = .7.dp.toPx())
            }
            drawCircle(OrbitalPalette.Violet.copy(alpha = .09f), radius = w * .35f,
                center = Offset(w * .77f, h * .55f))
            drawLine(OrbitalPalette.Gold.copy(alpha = .32f),
                Offset(0f, h * .5f), Offset(w, h * .5f),
                strokeWidth = 1.dp.toPx())

            val path = Path()
            val padX = 12.dp.toPx()
            val plotWidth = w - padX * 2f
            lane.trace.forEachIndexed { index, point ->
                val x = padX + point.x * plotWidth
                val y = h * (.5f - point.voltage * .36f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val bright = if (lane.config.enabled) 1f else .24f
            drawPath(path, OrbitalPalette.Violet.copy(alpha = .24f * bright),
                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, OrbitalPalette.Cyan.copy(alpha = .42f * bright),
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, OrbitalPalette.Cyan.copy(alpha = .96f * bright),
                style = Stroke(width = 1.55.dp.toPx(), cap = StrokeCap.Round))
        }
        Text(
            "±1 V  /  CONFIG",
            Modifier.align(Alignment.TopStart).padding(9.dp),
            color = OrbitalPalette.Gold,
            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
        )
        Text(
            "NO LIVE PHASE",
            Modifier.align(Alignment.BottomEnd).padding(9.dp),
            color = OrbitalPalette.Muted,
            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun GardenAction(
    label: String,
    description: String,
    tint: Color,
    tag: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
            .border(.8.dp, tint.copy(alpha = .48f), RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = .11f), RoundedCornerShape(9.dp))
            .semantics { contentDescription = description }
            .testTag(tag),
    ) {
        Text(label, color = tint, fontSize = 10.sp, maxLines = 1,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}
