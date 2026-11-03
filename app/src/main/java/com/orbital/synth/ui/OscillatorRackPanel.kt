package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
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
                        .semantics {
                            contentDescription =
                                "Oscillator " + (index + 1) +
                                    if (slot.enabled) ", active" else ", empty"
                            this.selected = focused
                            stateDescription =
                                if (focused) "Selected editor layer" else "Unselected editor layer"
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

            LuminousOscillatorWaveEditor(
                slot = layer,
                accent = tint,
                modifier = Modifier.fillMaxWidth(),
                // Preserve the FULL V6 layer including noise, tune, waveform
                // and enable state when any X/Y or precision control changes.
                onSlotChange = { next -> onSlotChange(selected, next) },
            )

            Text(
                "WAVEFORM  /  TOUCH A HARMONIC FAMILY",
                color = OrbitalPalette.Muted,
                fontSize = 9.sp, letterSpacing = .8.sp,
            )
            // All five waveform families fit in a single accessible row on
            // phones. Avoid nesting a horizontal scroller inside the vertical
            // SOUND editor: offscreen chips must scroll into view on tap/TalkBack.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                            .semantics {
                                contentDescription = "$label waveform"
                                this.selected = active
                                stateDescription =
                                    if (active) "Current waveform" else "Not selected waveform"
                            }
                            .testTag("oscillatorWave" + index)
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 6.dp, vertical = 13.dp),
                    ) {
                        Text(
                            label, color = if (active) tint else OrbitalPalette.Muted,
                            fontSize = 9.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // A real per-layer noise oscillator, blended before the selected
            // voice filter. Touch the spectrum directly or use accessible
            // white/pink/brown presets and precision sliders.
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, tint.copy(alpha = .27f), RoundedCornerShape(13.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF07152C), Color(0xFF0C0920), Color(0xFF040B19)),
                        ),
                        RoundedCornerShape(13.dp),
                    )
                    .padding(9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "NOISE FABRIC  /  SPECTRAL MORPH",
                        color = OrbitalPalette.Cyan,
                        fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp, modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (layer.noiseLevel == 0f) "OFF" else "LIVE",
                        color = if (layer.noiseLevel == 0f) OrbitalPalette.Muted
                            else OrbitalPalette.Gold,
                        fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                NoiseSpectrumPad(
                    slot = layer,
                    accent = tint,
                    modifier = Modifier.fillMaxWidth().height(118.dp),
                    onTouch = { color, blend ->
                        onSlotChange(selected, layer.copy(
                            noiseColor = color, noiseLevel = blend,
                        ))
                    },
                )
                Text(
                    "ILLUSTRATIVE SPECTRUM  /  TOUCH X: COLOR   Y: NOISE MIX",
                    color = OrbitalPalette.Muted,
                    fontSize = 8.sp, letterSpacing = .35.sp,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    listOf("WHITE", "PINK", "BROWN").forEachIndexed { index, name ->
                        val colorTarget = index * .5f
                        val selectedColor = abs(layer.noiseColor - colorTarget) < .11f
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .border(
                                    1.dp,
                                    tint.copy(alpha = if (selectedColor) .88f else .22f),
                                    RoundedCornerShape(9.dp),
                                )
                                .background(
                                    tint.copy(alpha = if (selectedColor) .17f else .035f),
                                    RoundedCornerShape(9.dp),
                                )
                                .clickable(role = Role.Button) {
                                    onSlotChange(selected, layer.copy(
                                        noiseColor = colorTarget,
                                        noiseLevel = if (layer.noiseLevel == 0f)
                                            .55f else layer.noiseLevel,
                                    ))
                                }
                                .testTag("oscillatorNoise" + name)
                                .semantics {
                                    contentDescription = "$name noise color"
                                }
                                .padding(horizontal = 7.dp, vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                name,
                                color = if (selectedColor) tint else OrbitalPalette.Muted,
                                fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                RackParameterSlider(
                    "NOISE MIX",
                    (layer.noiseLevel * 100f).roundToInt().toString() + "%",
                    layer.noiseLevel.coerceIn(0f, 1f), 0f..1f,
                    OrbitalPalette.Cyan,
                    { onSlotChange(selected, layer.copy(noiseLevel = it)) },
                    testTag = "oscillatorNoiseLevel",
                )
                RackParameterSlider(
                    "NOISE COLOR",
                    when {
                        layer.noiseColor < .17f -> "WHITE"
                        layer.noiseColor < .39f -> "WHITE ↔ PINK"
                        layer.noiseColor < .61f -> "PINK"
                        layer.noiseColor < .83f -> "PINK ↔ BROWN"
                        else -> "BROWN"
                    },
                    layer.noiseColor.coerceIn(0f, 1f), 0f..1f,
                    OrbitalPalette.Violet,
                    { onSlotChange(selected, layer.copy(noiseColor = it)) },
                    testTag = "oscillatorNoiseColor",
                )
            }
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
                            layer.copy(coarseSemitones = (layer.coarseSemitones - 1).coerceAtLeast(-48)),
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
                            layer.copy(coarseSemitones = (layer.coarseSemitones + 1).coerceAtMost(48)),
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
    testTag: String? = null,
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
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = name + ": " + display }
                .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        )
    }
}

/**
 * A touch-programmable visual color field. Bars are a deliberately illustrative
 * spectrum whose tilt and density reflect the selected noise color and blend;
 * they are not mislabeled as a live microphone/FFT analysis.
 */
@Composable
private fun NoiseSpectrumPad(
    slot: OscillatorSlot,
    accent: Color,
    modifier: Modifier = Modifier,
    onTouch: (color: Float, blend: Float) -> Unit,
) {
    val touch by rememberUpdatedState(onTouch)
    Canvas(
        modifier
            .border(1.dp, accent.copy(alpha = .28f), RoundedCornerShape(10.dp))
            .background(Color(0xFF020711), RoundedCornerShape(10.dp))
            .testTag("oscillatorNoiseCanvas")
            .semantics {
                contentDescription =
                    "Noise spectrum touch field. Horizontal changes white pink brown color; vertical changes mix."
            }
            .pointerInput(Unit) {
                // One stable Compose gesture owner consumes pointer changes
                // in the Main pass before the enclosing vertical scroller.
                // The first finger controls XY; extra fingers cannot steal it.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id = down.id
                    fun publish(position: Offset) {
                        if (!position.x.isFinite() || !position.y.isFinite()) return
                        touch(
                            (position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            (1f - position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                        )
                    }
                    publish(down.position)
                    down.consume()
                    // Only the first finger owns X/Y expression, while all
                    // pointers are consumed until every finger lifts so the
                    // enclosing editor cannot steal a secondary-pointer drag.
                    var ownerActive = true
                    var anyPressed = true
                    while (anyPressed) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pointer = event.changes.firstOrNull { it.id == id }
                        if (ownerActive && pointer?.pressed == true) publish(pointer.position)
                        if (pointer != null && !pointer.pressed) ownerActive = false
                        event.changes.forEach { change ->
                            if (change.pressed || change.previousPressed) change.consume()
                        }
                        anyPressed = event.changes.any { it.pressed }
                    }
                }
            },
    ) {
        val mix = slot.noiseLevel.coerceIn(0f, 1f)
        val color = slot.noiseColor.coerceIn(0f, 1f)
        val mid = size.height * .73f
        drawLine(
            accent.copy(alpha = .13f),
            Offset(0f, mid), Offset(size.width, mid), 1f,
        )
        for (band in 0 until 48) {
            val x = size.width * (band + .5f) / 48f
            val position = band / 47f
            // Increasing brownness rolls off upper bands; selected noise mix
            // increases visual energy without ever implying microphone input.
            val tilt = (1f - position * color * .92f).coerceAtLeast(.06f)
            val sparkle = abs(sin(
                (position * 11.0 + color * 3.7 + mix * 1.9) * PI
            )).toFloat()
            val intensity = .09f + mix * .77f * tilt * (.48f + .52f * sparkle)
            val height = size.height * intensity
            val top = mid - height
            val bottom = mid + height * .15f
            drawLine(
                accent.copy(alpha = .10f + mix * .13f),
                Offset(x, top), Offset(x, bottom), 7f,
                cap = StrokeCap.Round,
            )
            drawLine(
                if (position > .65f) OrbitalPalette.Violet.copy(alpha = .38f + mix * .5f)
                else accent.copy(alpha = .4f + mix * .53f),
                Offset(x, top), Offset(x, bottom),
                size.width / 85f, cap = StrokeCap.Round,
            )
        }
        val cursor = Offset(size.width * color, size.height * (1f - mix))
        drawCircle(
            OrbitalPalette.Cyan.copy(alpha = .16f),
            radius = 17f, center = cursor,
        )
        drawCircle(
            OrbitalPalette.Cyan.copy(alpha = .88f),
            radius = 3.2f, center = cursor,
        )
    }
}
