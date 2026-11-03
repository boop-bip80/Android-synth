package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val ReferenceBlack = Color(0xFF010713)
private val ReferenceGlass = Color(0xFF061325)

/**
 * The configuration-driven controls immediately below the hero plasma orb.
 * Every XY and knob action crosses the supplied callback into the ONE live
 * synthesizer; this file owns no audio engine or DSP.
 *
 * The ribbon and four mini panels are deliberately illustrative previews of
 * stored synth parameters and active route metadata. They are NOT an FFT,
 * fake live audio waveform, microphone input or per-voice modulation phase.
 */
@Composable
fun ReferenceSoundControls(
    parameters: Map<ParameterId, Float>,
    lfos: List<ModulationLfoConfig>,
    routes: List<ModulationRouteConfig>,
    onParameterChange: (ParameterId, Float) -> Unit,
    modifier: Modifier = Modifier,
    audioRms: Float = 0f,
    compact: Boolean = false,
    showExpressionRibbon: Boolean = true,
) {
    fun param(id: ParameterId): Float =
        ReferenceSoundUiModel.normalized(parameters[id] ?: .5f)

    val morph = param(ParameterId.MORPH)
    val movement = param(ParameterId.MOVEMENT)
    val attack = param(ParameterId.ATTACK)
    val decay = param(ParameterId.DECAY)
    val sustain = param(ParameterId.SUSTAIN)
    val release = param(ParameterId.RELEASE)

    Column(
        modifier
            .fillMaxWidth()
            .testTag("referenceSoundControls")
            .background(ReferenceBlack),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showExpressionRibbon) {
            ReferenceExpressionRibbon(
                morph = morph,
                movement = movement,
                audioRms = audioRms,
                height = if (compact) 88.dp else 112.dp,
                onChange = { nextMorph, nextMove ->
                    onParameterChange(ParameterId.MORPH, nextMorph)
                    onParameterChange(ParameterId.MOVEMENT, nextMove)
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().testTag("referenceKnobRack"),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val tints = listOf(
                OrbitalPalette.Violet, OrbitalPalette.Cyan, OrbitalPalette.Gold,
                OrbitalPalette.Violet, OrbitalPalette.Cyan,
            )
            ReferenceSoundUiModel.knobOrder.forEachIndexed { index, id ->
                ReferenceNeonKnob(
                    label = when (id) {
                        ParameterId.FX_AMOUNT -> "FX AMOUNT"
                        else -> id.name
                    },
                    value = param(id),
                    tint = tints[index],
                    tag = "referenceKnob" + id.name,
                    onValueChange = { onParameterChange(id, it) },
                    modifier = Modifier.weight(1f),
                    diameter = if (compact) 48.dp else 51.dp,
                )
            }
        }

        val envelope = remember(attack, decay, sustain, release) {
            List(48) { index ->
                ReferenceSoundUiModel.envelopeAt(index / 47f, attack, decay, sustain, release)
            }
        }
        val lfo = lfos.firstOrNull { it.enabled }
        val lfoPreview = remember(lfo) {
            List(48) { index ->
                if (lfo == null) .5f
                else .5f + .44f * ReferenceSoundUiModel.lfoAt(lfo.shape, index / 47f)
            }
        }
        val expressionPreview = remember(morph, movement) {
            List(48) { index ->
                (.48f + .25f * movement *
                    sin((index / 47f * 2.2f + morph) * (2f * PI.toFloat())) +
                    (morph - .5f) * .16f).coerceIn(0f, 1f)
            }
        }
        val liveRouteCount = remember(routes) {
            ReferenceSoundUiModel.liveModulatedCount(routes)
        }
        val routePreview = remember(routes) {
            val active = routes.take(64).filter {
                it.enabled &&
                    it.destination in ModulationConstellationModel.liveDestinations &&
                    it.depth.isFinite() && abs(it.depth) > .0001f
            }
            List(48) { index ->
                if (active.isEmpty()) .5f
                else {
                    val route = active[(index * active.size / 48).coerceIn(0, active.lastIndex)]
                    (.5f + route.depth.coerceIn(-4f, 4f) * .11f).coerceIn(.06f, .94f)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().testTag("referenceMiniSignals"),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ReferenceMiniSignal(
                title = "ENV",
                caption = "ADSR",
                values = envelope,
                tint = OrbitalPalette.Violet,
                tag = "referenceMiniEnv",
                modifier = Modifier.weight(1f),
                height = if (compact) 64.dp else 80.dp,
            )
            ReferenceMiniSignal(
                title = "LFO",
                caption = if (lfo == null) "OFF" else lfo.shape.name.take(5),
                values = lfoPreview,
                tint = OrbitalPalette.Cyan,
                tag = "referenceMiniLfo",
                modifier = Modifier.weight(1f),
                height = if (compact) 64.dp else 80.dp,
            )
            ReferenceMiniSignal(
                title = "EXPR",
                caption = "X/Y",
                values = expressionPreview,
                tint = OrbitalPalette.Gold,
                tag = "referenceMiniExpr",
                modifier = Modifier.weight(1f),
                height = if (compact) 64.dp else 80.dp,
            )
            ReferenceMiniSignal(
                title = "MOD",
                caption = liveRouteCount.toString() + " LIVE",
                values = routePreview,
                tint = OrbitalPalette.Violet,
                tag = "referenceMiniMod",
                modifier = Modifier.weight(1f),
                height = if (compact) 64.dp else 80.dp,
            )
        }
    }
}

/**
 * Touch-drawn wave ribbon is **not** a spectral FFT. Its position manipulates
 * the live MORPH/MOVEMENT parameters; trace curves are deterministic functions
 * of those parameters and an optional real PCM output RMS brightness value.
 */
@Composable
fun ReferenceExpressionRibbon(
    morph: Float,
    movement: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    audioRms: Float = 0f,
    height: Dp = 112.dp,
    transparentBackground: Boolean = false,
) {
    val x = ReferenceSoundUiModel.normalized(morph)
    val y = ReferenceSoundUiModel.normalized(movement)
    val liveEnergy = ReferenceSoundUiModel.normalized(audioRms * 5f)
    val currentChange by rememberUpdatedState(onChange)
    val currentX by rememberUpdatedState(x)
    val currentY by rememberUpdatedState(y)
    val shape = RoundedCornerShape(13.dp)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    if (transparentBackground) listOf(
                        Color(0xC509182F), Color(0xDD010713),
                    ) else listOf(Color(0xFF09182F), ReferenceBlack),
                ),
                shape,
            )
            .border(1.dp, OrbitalPalette.Blue.copy(alpha = .64f), shape)
            .testTag("referenceExpressionRibbon")
            .semantics {
                contentDescription = "Touch expression ribbon, morph " +
                    (x * 100f).roundToInt() + " percent, movement " +
                    (y * 100f).roundToInt() +
                    " percent. This is a parameter-driven shape preview, not FFT audio"
                customActions = listOf(
                    CustomAccessibilityAction("Increase morph") {
                        currentChange((currentX + .05f).coerceIn(0f, 1f), currentY)
                        true
                    },
                    CustomAccessibilityAction("Decrease morph") {
                        currentChange((currentX - .05f).coerceIn(0f, 1f), currentY)
                        true
                    },
                    CustomAccessibilityAction("Increase movement") {
                        currentChange(currentX, (currentY + .05f).coerceIn(0f, 1f))
                        true
                    },
                    CustomAccessibilityAction("Decrease movement") {
                        currentChange(currentX, (currentY - .05f).coerceIn(0f, 1f))
                        true
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    currentChange(
                        (position.x / size.width).coerceIn(0f, 1f),
                        (1f - position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { position ->
                        currentChange(
                            (position.x / size.width).coerceIn(0f, 1f),
                            (1f - position.y / size.height).coerceIn(0f, 1f),
                        )
                    },
                    onDrag = { change, _ ->
                        currentChange(
                            (change.position.x / size.width).coerceIn(0f, 1f),
                            (1f - change.position.y / size.height).coerceIn(0f, 1f),
                        )
                        change.consume()
                    },
                )
            },
    ) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val cyan = OrbitalPalette.Cyan
        val violet = OrbitalPalette.Violet
        val gold = OrbitalPalette.Gold

        for (index in 1..7) {
            val px = width * index / 8f
            drawLine(cyan.copy(alpha = .065f), Offset(px, 0f), Offset(px, height),
                strokeWidth = .7f)
        }
        for (index in 1..4) {
            val py = height * index / 5f
            drawLine(violet.copy(alpha = .075f), Offset(0f, py), Offset(width, py),
                strokeWidth = .7f)
        }
        val gradient = Brush.horizontalGradient(
            listOf(violet.copy(alpha = .8f), cyan, gold.copy(alpha = .78f), violet),
        )
        val energyGlow = .82f + .18f * liveEnergy
        for (layer in 0..4) {
            val path = Path()
            val segments = 76
            for (index in 0..segments) {
                val u = index / segments.toFloat()
                val ordinate = ReferenceSoundUiModel.ribbonAt(u, layer, x, y)
                val point = Offset(u * width, ordinate * height)
                if (index == 0) path.moveTo(point.x, point.y)
                else path.lineTo(point.x, point.y)
            }
            drawPath(path, gradient,
                style = Stroke(width = (7f + 2f * liveEnergy) * energyGlow,
                    cap = StrokeCap.Round),
                alpha = .085f)
            drawPath(path, gradient,
                style = Stroke(width = if (layer == 2) 2.25f else 1.25f,
                    cap = StrokeCap.Round),
                alpha = if (layer == 2) .94f else .49f)
        }
        val node = Offset(width * x, height * (1f - y))
        drawCircle(
            Brush.radialGradient(
                listOf(cyan.copy(alpha = .46f + .24f * liveEnergy), Color.Transparent),
                center = node, radius = 35.dp.toPx(),
            ),
            radius = 35.dp.toPx(), center = node,
        )
        drawCircle(cyan.copy(alpha = .65f), radius = 9.dp.toPx(),
            style = Stroke(width = 1.5.dp.toPx()), center = node)
        drawCircle(Color.White, radius = 2.2.dp.toPx(), center = node)
        // Decorative sample markers are fixed by parameterized ribbons, not
        // invented spectral bins or audio-reactive analysis.
        for (i in 1..4) {
            val u = i / 5f
            val center = Offset(width * u,
                ReferenceSoundUiModel.ribbonAt(u, i, x, y) * height)
            drawCircle(if (i % 2 == 0) gold else violet, radius = 3.2.dp.toPx(),
                center = center, style = Stroke(width = 1.1.dp.toPx()))
        }
    }
}

/**
 * Accessible analog-style knob with real drag/tap and semantic setProgress.
 * Parameter snapshots remain host-owned; no optimistic hidden local state.
 */
@Composable
fun ReferenceNeonKnob(
    label: String,
    value: Float,
    tint: Color,
    tag: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 51.dp,
) {
    val safe = ReferenceSoundUiModel.normalized(value)
    val update by rememberUpdatedState(onValueChange)
    val current by rememberUpdatedState(safe)

    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Canvas(
            Modifier
                .size(diameter)
                .testTag(tag)
                .semantics {
                    contentDescription = label + ", normalized " +
                        (safe * 100f).roundToInt() + " percent"
                    progressBarRangeInfo = ProgressBarRangeInfo(safe, 0f..1f)
                    setProgress { next ->
                        update(ReferenceSoundUiModel.normalized(next))
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { point ->
                        val angle = atan2(
                            point.y - size.height / 2f, point.x - size.width / 2f,
                        ) * 180f / PI.toFloat()
                        val swept = (angle - 135f + 360f) % 360f
                        update((swept / 270f).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    // Maintain a gesture-local accumulator so fast touch
                    // events remain cumulative even before Compose can
                    // publish a new immutable host parameter snapshot.
                    var accumulated = current
                    detectDragGestures(
                        onDragStart = { accumulated = current },
                        onDrag = { change, delta ->
                            accumulated = (accumulated - delta.y * .008f +
                                delta.x * .002f).coerceIn(0f, 1f)
                            update(accumulated)
                            change.consume()
                        },
                    )
                },
        ) {
            val radius = size.minDimension * .37f
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            drawCircle(Brush.radialGradient(
                listOf(Color(0xFF18283E), Color(0xFF060A16), Color.Black),
                center = center, radius = radius * 1.22f,
            ), radius = radius * 1.22f)
            drawArc(tint.copy(alpha = .18f), 135f, 270f, false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
            drawArc(tint.copy(alpha = .20f), 135f, 270f * safe, false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round))
            drawArc(tint, 135f, 270f * safe, false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(Color(0xFF0B111D), radius * .69f)
            drawCircle(tint.copy(alpha = .26f), radius * .70f,
                style = Stroke(width = .8.dp.toPx()))
            val angle = (135f + 270f * safe) * PI.toFloat() / 180f
            drawLine(tint, Offset(
                center.x + cos(angle) * radius * .42f,
                center.y + sin(angle) * radius * .42f,
            ), Offset(
                center.x + cos(angle) * radius * .73f,
                center.y + sin(angle) * radius * .73f,
            ), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color.White.copy(alpha = .22f), radius * .21f,
                center = Offset(center.x - radius * .2f, center.y - radius * .2f))
        }
        Text(
            label,
            color = OrbitalPalette.Text,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            letterSpacing = .3.sp,
        )
        Text(
            "%.2f".format(safe),
            color = tint,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

/** Compact graph tiles encode *saved settings* and live-route counts only. */
@Composable
private fun ReferenceMiniSignal(
    title: String,
    caption: String,
    values: List<Float>,
    tint: Color,
    tag: String,
    modifier: Modifier,
    height: Dp,
) {
    Column(
        modifier
            .height(height)
            .border(1.dp, tint.copy(alpha = .42f), RoundedCornerShape(8.dp))
            .background(
                Brush.verticalGradient(listOf(ReferenceGlass, ReferenceBlack)),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 5.dp, vertical = 5.dp)
            .testTag(tag)
            .semantics {
                contentDescription = title + " configured preview, " + caption +
                    ". This is not sampled audio"
            },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = OrbitalPalette.Text, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(caption, color = tint, fontSize = 7.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Canvas(Modifier.fillMaxWidth().height(if (height <= 64.dp) 29.dp else 45.dp)) {
            if (values.size < 2) return@Canvas
            val path = Path()
            values.forEachIndexed { index, raw ->
                val px = index.toFloat() / (values.size - 1) * size.width
                val py = size.height * (1f - ReferenceSoundUiModel.normalized(raw))
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawLine(tint.copy(alpha = .11f),
                Offset(0f, size.height * .5f),
                Offset(size.width, size.height * .5f), .7f)
            drawPath(path, tint.copy(alpha = .16f),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, tint,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
