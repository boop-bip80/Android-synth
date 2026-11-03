package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.OscillatorSlot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private val LayerAccents = listOf(
    Color(0xFF39E6FF), Color(0xFFB694FF), Color(0xFFFFC972),
    Color(0xFF4AF0C7), Color(0xFFFF78C6), Color(0xFF79A8FF),
    Color(0xFFE9F274), Color(0xFFFF9981),
)
private val OscNames = listOf("SINE", "TRI", "SAW", "PULSE", "GLASS")

/**
 * A real audio-rack editor, backed by OrbitalSynth.setOscillatorSlot().
 *
 * These extra oscillators are additive layers over the selected synth architecture.
 * An unoccupied slot generates no sound or CPU work. The host owns persistence and
 * audio-thread handoff: this composable only edits immutable snapshots.
 */
@Composable
fun OscillatorRackPanel(
    slots: List<OscillatorSlot>,
    onSlotChange: (index: Int, next: OscillatorSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (slots.isEmpty()) return
    var selectedLayer by remember { mutableIntStateOf(0) }
    val selected = selectedLayer.coerceIn(0, slots.lastIndex)
    val layer = slots[selected]
    val activeCount = slots.count { it.enabled }
    val nextEmpty = slots.indexOfFirst { !it.enabled }

    Column(
        modifier
            .fillMaxWidth()
            .testTag("oscillatorRack")
            .border(1.dp, OrbitalPalette.Cyan.copy(alpha = .34f), RoundedCornerShape(19.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF061528), Color(0xFF090B1B), Color(0xFF030812)),
                ),
                RoundedCornerShape(19.dp),
            )
            .padding(horizontal = 11.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "OSCILLATOR CONSTELLATION",
                    color = OrbitalPalette.Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.25.sp,
                )
                Text(
                    "EXPAND THE SOUND / INDEPENDENT LAYERS",
                    color = OrbitalPalette.Muted,
                    fontSize = 8.sp,
                    letterSpacing = .9.sp,
                )
            }
            Text(
                activeCount.toString() + " / " + slots.size,
                color = OrbitalPalette.Gold,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            slots.forEachIndexed { index, slot ->
                val tint = LayerAccents[index % LayerAccents.size]
                val focused = index == selected
                val borderColor = if (focused) tint else tint.copy(alpha = .20f)
                Column(
                    Modifier
                        .width(49.dp)
                        .border(1.dp, borderColor, RoundedCornerShape(11.dp))
                        .background(
                            if (focused) tint.copy(alpha = .19f)
                            else Color(0xFF071224),
                            RoundedCornerShape(11.dp),
                        )
                        .clickable(role = Role.Button) { selectedLayer = index }
                        .semantics { contentDescription =
                            "Oscillator " + (index + 1) + if (slot.enabled) ", active" else ", empty"
                        }
                        .testTag("oscillatorLayer" + index)
                        .heightIn(min = 51.dp)
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        if (slot.enabled) "◉" else "+",
                        color = if (slot.enabled) tint else OrbitalPalette.Muted,
                        fontSize = 14.sp,
                    )
                    Text(
                        (index + 1).toString().padStart(2, '0'),
                        color = if (focused) OrbitalPalette.Text else OrbitalPalette.Muted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        if (layer.enabled) {
            val tint = LayerAccents[selected % LayerAccents.size]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "SOURCE " + (selected + 1).toString().padStart(2, '0'),
                    color = tint, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.25.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onSlotChange(selected, layer.copy(enabled = false)) },
                    modifier = Modifier.testTag("oscillatorRemove"),
                ) { Text("REMOVE", color = OrbitalPalette.Muted, fontSize = 10.sp) }
            }

            OscillatorWavePreview(
                slot = layer,
                accent = tint,
                modifier = Modifier.fillMaxWidth().height(96.dp),
                onTouch = { blend, gain ->
                    onSlotChange(selected, layer.copy(morph = blend, level = gain))
                },
            )

            Text(
                "DRAG THE WAVE  /  X: BLEND    Y: LEVEL",
                color = tint.copy(alpha = .8f), fontSize = 8.sp, letterSpacing = .55.sp,
            )
            Text(
                "WAVEFORM  /  TOUCH A HARMONIC FAMILY",
                color = OrbitalPalette.Muted,
                fontSize = 9.sp, letterSpacing = .8.sp,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val waveIndex = (layer.waveform.coerceIn(0f, 1f) * 4f).roundToInt()
                OscNames.forEachIndexed { index, label ->
                    val active = index == waveIndex
                    Box(
                        Modifier
                            .border(
                                1.dp, tint.copy(alpha = if (active) .85f else .24f),
                                RoundedCornerShape(9.dp),
                            )
                            .background(
                                tint.copy(alpha = if (active) .24f else .035f),
                                RoundedCornerShape(9.dp),
                            )
                            .clickable(role = Role.Button) {
                                onSlotChange(selected, layer.copy(waveform = index / 4f))
                            }
                            .testTag("oscillatorWave" + index)
                            .heightIn(min = 43.dp)
                            .padding(horizontal = 10.dp, vertical = 13.dp),
                    ) {
                        Text(
                            label, color = if (active) tint else OrbitalPalette.Muted,
                            fontSize = 9.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            RackParameterSlider(
                "LEVEL", (layer.level * 100f).roundToInt().toString() + "%",
                layer.level.coerceIn(0f, 1f), 0f..1f, tint,
                { onSlotChange(selected, layer.copy(level = it)) },
            )
            RackParameterSlider(
                "SPECTRAL BLEND",
                (layer.morph * 100f).roundToInt().toString() + "%",
                layer.morph.coerceIn(0f, 1f), 0f..1f, OrbitalPalette.Violet,
                { onSlotChange(selected, layer.copy(morph = it)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "COARSE TUNE",
                    color = OrbitalPalette.Muted,
                    fontSize = 10.sp, letterSpacing = .9.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        onSlotChange(
                            selected,
                            layer.copy(coarseSemitones = (layer.coarseSemitones - 1).coerceAtLeast(-24)),
                        )
                    },
                    modifier = Modifier.testTag("oscillatorCoarseDown"),
                ) { Text("−", fontSize = 22.sp, color = tint) }
                Text(
                    (if (layer.coarseSemitones > 0) "+" else "") +
                        layer.coarseSemitones.toString() + " ST",
                    color = OrbitalPalette.Text, fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(57.dp),
                )
                TextButton(
                    onClick = {
                        onSlotChange(
                            selected,
                            layer.copy(coarseSemitones = (layer.coarseSemitones + 1).coerceAtMost(24)),
                        )
                    },
                    modifier = Modifier.testTag("oscillatorCoarseUp"),
                ) { Text("+", fontSize = 20.sp, color = tint) }
            }
            RackParameterSlider(
                "FINE TUNE",
                layer.fineCents.roundToInt().toString() + " CENTS",
                layer.fineCents.coerceIn(-100f, 100f), -100f..100f, OrbitalPalette.Gold,
                { onSlotChange(selected, layer.copy(fineCents = it)) },
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp, OrbitalPalette.PanelEdge.copy(alpha = .48f),
                        RoundedCornerShape(14.dp),
                    )
                    .background(Color(0xFF070D1E), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("EMPTY SOUND SOURCE", color = OrbitalPalette.Muted,
                    fontSize = 10.sp, letterSpacing = 1.4.sp)
                Text("Add another oscillator to shape a richer sound.",
                    color = OrbitalPalette.Text, fontSize = 11.sp,
                    textAlign = TextAlign.Center)
                TextButton(
                    onClick = {
                        onSlotChange(
                            selected,
                            layer.copy(enabled = true, level = .28f, morph = 1f),
                        )
                    },
                    modifier = Modifier.testTag("oscillatorEnable"),
                ) { Text("+ ADD OSCILLATOR", color = OrbitalPalette.Cyan, fontSize = 13.sp) }
            }
        }

        if (nextEmpty >= 0 && activeCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ACTIVE SOURCES MIX INTO THE SAME VOICE",
                    color = OrbitalPalette.Muted, fontSize = 8.sp,
                    letterSpacing = .4.sp, modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        selectedLayer = nextEmpty
                        val empty = slots[nextEmpty]
                        onSlotChange(
                            nextEmpty,
                            empty.copy(enabled = true, level = .25f, morph = 1f),
                        )
                    },
                    modifier = Modifier.testTag("oscillatorAdd"),
                ) { Text("+ OSC", color = OrbitalPalette.Cyan, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun RackParameterSlider(
    name: String,
    display: String,
    value: Float,
    bounds: ClosedFloatingPointRange<Float>,
    tint: Color,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = OrbitalPalette.Muted, fontSize = 9.sp,
                letterSpacing = .85.sp, modifier = Modifier.weight(1f))
            Text(display, color = tint, fontSize = 10.sp,
                fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.coerceIn(bounds.start, bounds.endInclusive),
            valueRange = bounds,
            onValueChange = onValueChange,
            colors = SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
                inactiveTrackColor = OrbitalPalette.PanelEdge,
            ),
            modifier = Modifier.semantics { contentDescription = name + ": " + display },
        )
    }
}

/** Parameter-dependent waveform image, rendered directly by Compose (never a bitmap mockup). */
@Composable
private fun OscillatorWavePreview(
    slot: OscillatorSlot,
    accent: Color,
    modifier: Modifier = Modifier,
    onTouch: (morph: Float, level: Float) -> Unit,
) {
    val touch by rememberUpdatedState(onTouch)
    val shape = (slot.waveform.coerceIn(0f, 1f) * 4f).roundToInt()
    Canvas(
        modifier
            .border(1.dp, accent.copy(alpha = .24f), RoundedCornerShape(12.dp))
            .background(Color(0xFF030814), RoundedCornerShape(12.dp))
            .testTag("oscillatorWaveCanvas")
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    touch(
                        (position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        (1f - position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                    )
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { start ->
                    touch(
                        (start.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        (1f - start.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                    )
                }) { change, _ ->
                    touch(
                        (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        (1f - change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                    )
                    change.consume()
                }
            },
    ) {
        val centerY = size.height * .5f
        val amplitude = size.height * (.14f + slot.level.coerceIn(0f, 1f) * .24f)
        val count = 180
        val path = Path()
        for (i in 0..count) {
            val t = i.toFloat() / count.toFloat() * 3f
            val cycle = t % 1f
            val pure = sin(2.0 * PI * cycle).toFloat()
            val body = when (shape) {
                0 -> pure
                1 -> 1f - 4f * abs(cycle - .5f)
                2 -> 2f * cycle - 1f
                3 -> if (cycle < .5f) 1f else -1f
                else -> (
                    .68f * pure +
                        .21f * sin(6.0 * PI * cycle).toFloat() +
                        .11f * sin(14.0 * PI * cycle).toFloat()
                    )
            }
            val sample = pure * (1f - slot.morph.coerceIn(0f, 1f)) +
                body * slot.morph.coerceIn(0f, 1f)
            val x = i.toFloat() / count.toFloat() * size.width
            val y = centerY - sample * amplitude
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(accent.copy(alpha = .12f), Offset(0f, centerY),
            Offset(size.width, centerY), 1f)
        drawPath(path, accent.copy(alpha = .09f), style = Stroke(width = 15f))
        drawPath(path, accent.copy(alpha = .26f), style = Stroke(width = 6f))
        drawPath(path, accent, style = Stroke(width = 2.6f, cap = StrokeCap.Round))
    }
}
