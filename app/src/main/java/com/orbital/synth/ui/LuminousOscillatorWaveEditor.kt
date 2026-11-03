package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.OscillatorSlot
import kotlin.math.abs
import kotlin.math.roundToInt

private val WaveGlass = Color(0xFF071324)
private val WaveCyan = Color(0xFF54EAFF)
private val WaveViolet = Color(0xFFBB81FF)
private val WaveGold = Color(0xFFFFCB78)
private val WaveInk = Color(0xFFE9F6FF)
private val WaveMute = Color(0xFF7B90B2)

/**
 * A touch-first reference-cycle editor for ONE real additive oscillator lane.
 *
 * Input edits publish a complete immutable [OscillatorSlot] snapshot; this
 * composable owns no audio worker, notes, FX, patch persistence or FFT. The
 * reference trace uses core-matched WavetableBank family coefficients at a
 * fixed 32-harmonic A4 preview level; the audible engine selects pitch-safe mip
 * tables independently. This panel is intentionally NEVER labeled a live scope.
 *
 * Rendering reuses both Paths, a precomputed Fourier lookup and static brushes.
 * A touch draw only samples primitive values and fills the paths in-place.
 * Every gesture owns its pointer until release; no unbounded event queues or
 * worker-side allocations are introduced.
 */
@Composable
fun LuminousOscillatorWaveEditor(
    slot: OscillatorSlot,
    onSlotChange: (OscillatorSlot) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = WaveCyan,
    /** 350dp landscape: retain ≥48dp rails while fitting the touch field. */
    compact: Boolean = false,
) {
    val currentSlot by rememberUpdatedState(slot)
    val currentCallback by rememberUpdatedState(onSlotChange)
    var touching by remember { mutableStateOf(false) }
    val wavePath = remember { Path() }
    val referencePath = remember { Path() }
    val lightBrush = remember(accent) {
        Brush.horizontalGradient(listOf(accent, WaveViolet, WaveGold, accent))
    }
    val shell = remember {
        Brush.verticalGradient(
            listOf(Color(0xFF10213A), Color(0xFF08152A), Color(0xFF030814))
        )
    }
    // Stroke styles are reused across 240-point Canvas redraws; only the
    // active finger state changes their widths, never the audio worker.
    val referenceStroke = remember { Stroke(width = 1.4f, cap = StrokeCap.Round) }
    val bloomStroke = remember(touching) {
        Stroke(width = if (touching) 30f else 24f, cap = StrokeCap.Round)
    }
    val haloStroke = remember { Stroke(width = 12f, cap = StrokeCap.Round) }
    val coreStroke = remember { Stroke(width = 5.5f, cap = StrokeCap.Round) }
    val lightStroke = remember(touching) {
        Stroke(width = if (touching) 3.5f else 2.7f, cap = StrokeCap.Round)
    }
    val selectedWave = (slot.waveform.coerceIn(0f, 1f) * 4f).roundToInt()
    val family = when (selectedWave) {
        0 -> "SINE"
        1 -> "TRIANGLE"
        2 -> "SAW"
        3 -> "PULSE"
        else -> "GLASS"
    }
    val morphPercent = (slot.morph.coerceIn(0f, 1f) * 100).roundToInt()
    val gainPercent = (slot.level.coerceIn(0f, 1f) * 100).roundToInt()

    Column(
        modifier
            .fillMaxWidth()
            .testTag("luminousWaveEditor")
            .clip(RoundedCornerShape(18.dp))
            .background(shell)
            .border(1.dp, accent.copy(alpha = if (touching) .83f else .33f),
                RoundedCornerShape(18.dp))
            .padding(if (compact) 6.dp else 11.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (compact) "REFERENCE WAVE  ·  NOT LIVE FFT"
                    else "HARMONIC SHAPE  //  REFERENCE WAVE",
                    color = WaveCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                if (!compact) {
                    Text(
                        "A4 WAVETABLE PREVIEW  ·  NOT LIVE AUDIO ANALYSIS",
                        color = WaveMute, fontSize = 8.sp, letterSpacing = .55.sp,
                    )
                }
            }
            Text(
                family,
                color = WaveGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
            )
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 96.dp else 172.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(WaveGlass)
                .border(1.dp, accent.copy(alpha = .23f), RoundedCornerShape(13.dp))
                // Preserve the existing OSC editor's public UI test contract.
                .testTag("oscillatorWaveCanvas")
                .semantics {
                    contentDescription =
                        "Oscillator waveform reference touch field. Drag horizontally for spectral blend and vertically for oscillator level."
                    stateDescription =
                        "Blend $morphPercent percent, level $gainPercent percent"
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val owner = down.id
                        touching = true
                        fun publish(position: Offset) {
                            if (!position.x.isFinite() || !position.y.isFinite()) return
                            val blend = (position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val gain = (1f - position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            currentCallback(currentSlot.copy(morph = blend, level = gain))
                        }
                        publish(down.position)
                        down.consume()
                        var ownerIsPressed = true
                        var anyPressed = true
                        try {
                            while (anyPressed) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val finger = event.changes.firstOrNull { it.id == owner }
                                if (ownerIsPressed && finger?.pressed == true) {
                                    publish(finger.position)
                                }
                                if (finger != null && !finger.pressed) ownerIsPressed = false
                                event.changes.forEach { change ->
                                    if (change.pressed || change.previousPressed) change.consume()
                                }
                                anyPressed = event.changes.any { it.pressed }
                            }
                        } finally {
                            touching = false
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val centerY = h * .50f
            val level = slot.level.coerceIn(0f, 1f)
            val morph = slot.morph.coerceIn(0f, 1f)
            val count = 240
            val amplitude = h * .36f * level
            wavePath.reset()
            referencePath.reset()

            // Glass-grid and small harmonic divisions are purely decorative,
            // never presented as input RMS or a measured spectral analysis.
            for (column in 1..11) {
                val x = w * column / 12f
                drawLine(
                    WaveViolet.copy(alpha = if (column % 3 == 0) .21f else .08f),
                    Offset(x, 0f), Offset(x, h), 1f,
                )
            }
            for (row in 1..5) {
                val y = h * row / 6f
                drawLine(
                    WaveCyan.copy(alpha = if (row == 3) .25f else .08f),
                    Offset(0f, y), Offset(w, y), 1f,
                )
            }

            // All samples come from the precomputed source-family Fourier
            // reference; the sine overlay makes morph motion legible.
            for (step in 0..count) {
                val fraction = step.toFloat() / count
                val phase = fraction * 3f
                val chosen = LayerWaveDisplaySampler.sample(phase, slot.waveform, morph)
                val sine = LayerWaveDisplaySampler.sample(phase, 0f, 0f)
                val x = fraction * w
                val waveY = centerY - chosen * amplitude
                val sineY = centerY - sine * amplitude * .52f
                if (step == 0) {
                    wavePath.moveTo(x, waveY)
                    referencePath.moveTo(x, sineY)
                } else {
                    wavePath.lineTo(x, waveY)
                    referencePath.lineTo(x, sineY)
                }
            }

            // Four concentric strokes create the light-bloom effect without
            // Android bitmap blur, RenderEffect allocations or per-frame arrays.
            drawPath(
                referencePath, WaveGold.copy(alpha = .24f),
                style = referenceStroke,
            )
            drawPath(
                wavePath, accent.copy(alpha = if (touching) .15f else .07f),
                style = bloomStroke,
            )
            drawPath(
                wavePath, WaveViolet.copy(alpha = if (touching) .34f else .20f),
                style = haloStroke,
            )
            drawPath(
                wavePath, accent.copy(alpha = .40f),
                style = coreStroke,
            )
            drawPath(
                wavePath, lightBrush,
                style = lightStroke,
            )

            val control = Offset(
                (morph * w).coerceIn(8f, (w - 8f).coerceAtLeast(8f)),
                ((1f - level) * h).coerceIn(8f, (h - 8f).coerceAtLeast(8f)),
            )
            drawLine(
                WaveGold.copy(alpha = .32f), Offset(control.x, 0f),
                Offset(control.x, h), 1f,
            )
            drawLine(
                WaveCyan.copy(alpha = .21f), Offset(0f, control.y),
                Offset(w, control.y), 1f,
            )
            drawCircle(
                accent.copy(alpha = if (touching) .25f else .10f),
                radius = if (touching) 23f else 18f, center = control,
            )
            drawCircle(
                WaveGold.copy(alpha = if (touching) .80f else .58f),
                radius = 6f, center = control,
            )
            drawCircle(WaveInk, radius = 2.7f, center = control)
        }

        if (!compact) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "X  BLEND", color = WaveViolet, fontSize = 8.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (touching) "● TOUCH SCULPTING" else "DRAG TO SCULPT WAVE",
                color = if (touching) WaveGold else WaveMute,
                fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .65.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "LEVEL  Y", color = WaveCyan, fontSize = 8.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LuminousValueRail(
                name = "SPECTRAL BLEND",
                value = slot.morph,
                tint = WaveViolet,
                modifier = Modifier.weight(1f),
                tag = "luminousMorphControl",
                compact = compact,
                onValueChange = { currentCallback(currentSlot.copy(morph = it)) },
            )
            LuminousValueRail(
                name = "OSC LEVEL",
                value = slot.level,
                tint = accent,
                modifier = Modifier.weight(1f),
                tag = "luminousLevelControl",
                compact = compact,
                onValueChange = { currentCallback(currentSlot.copy(level = it)) },
            )
        }
    }
}

/**
 * Fully custom luminous tactile rail (70dp portrait / 52dp compact). TalkBack users can invoke SetProgress
 * from any value 0..1; precision and drag both publish the real slot parameter.
 * The pointer owner consumes MOVE/DOWN so the enclosing SOUND scroller cannot
 * steal or leave a held/frozen control after ACTION_UP or cancellation.
 */
@Composable
private fun LuminousValueRail(
    name: String,
    value: Float,
    tint: Color,
    modifier: Modifier,
    tag: String,
    compact: Boolean,
    onValueChange: (Float) -> Unit,
) {
    val currentCallback by rememberUpdatedState(onValueChange)
    var pressed by remember { mutableStateOf(false) }
    val current = value.coerceIn(0f, 1f)
    val percentage = (current * 100f).roundToInt()
    val shape = RoundedCornerShape(12.dp)
    val glass = remember {
        Brush.verticalGradient(listOf(Color(0xFF12213A), Color(0xFF060D20)))
    }
    Box(
        modifier
            .height(if (compact) 52.dp else 70.dp)
            .clip(shape)
            .background(glass)
            .border(1.dp, tint.copy(alpha = if (pressed) .82f else .28f), shape),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .testTag(tag)
                .semantics {
                    contentDescription = "$name oscillator control"
                    stateDescription = "$percentage percent"
                    progressBarRangeInfo = ProgressBarRangeInfo(current, 0f..1f)
                    setProgress { proposed ->
                        if (!proposed.isFinite()) false else {
                            currentCallback(proposed.coerceIn(0f, 1f))
                            true
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val owner = down.id
                        pressed = true
                        fun publish(position: Offset) {
                            if (!position.x.isFinite()) return
                            currentCallback(
                                (position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            )
                        }
                        publish(down.position)
                        down.consume()
                        var ownerIsPressed = true
                        var anyPressed = true
                        try {
                            while (anyPressed) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val finger = event.changes.firstOrNull { it.id == owner }
                                if (ownerIsPressed && finger?.pressed == true) publish(finger.position)
                                if (finger != null && !finger.pressed) ownerIsPressed = false
                                event.changes.forEach { change ->
                                    if (change.pressed || change.previousPressed) change.consume()
                                }
                                anyPressed = event.changes.any { it.pressed }
                            }
                        } finally {
                            pressed = false
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val trackY = h * .74f
            val x = w * (.07f + current * .86f)
            drawLine(
                WaveMute.copy(alpha = .20f),
                Offset(w * .07f, trackY),
                Offset(w * .93f, trackY),
                5f, cap = StrokeCap.Round,
            )
            drawLine(
                tint.copy(alpha = .18f),
                Offset(w * .07f, trackY),
                Offset(x, trackY),
                12f, cap = StrokeCap.Round,
            )
            drawLine(
                tint.copy(alpha = .91f),
                Offset(w * .07f, trackY),
                Offset(x, trackY),
                3.6f, cap = StrokeCap.Round,
            )
            for (tick in 0..8) {
                val tickX = w * (.07f + tick * .86f / 8)
                drawLine(
                    WaveInk.copy(alpha = if (tick / 8f <= current) .50f else .16f),
                    Offset(tickX, trackY + 9f),
                    Offset(tickX, trackY + 13f),
                    1f,
                )
            }
            drawCircle(
                tint.copy(alpha = if (pressed) .29f else .16f),
                radius = 15f, center = Offset(x, trackY),
            )
            drawCircle(WaveInk, radius = 4.6f, center = Offset(x, trackY))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                color = WaveMute,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .6.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$percentage%",
                color = tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
