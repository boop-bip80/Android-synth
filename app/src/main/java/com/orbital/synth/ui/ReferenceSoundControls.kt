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
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
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
    synthSampleRate: Int = 48_000,
    /** Existing destinations only; null leaves standalone previews read-only. */
    onOpenMiniSignal: ((ReferenceMiniSignalDestination) -> Unit)? = null,
    canEditModulationRack: Boolean = false,
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
                val value = ReferenceSoundValuePresentation.format(
                    id, param(id), synthSampleRate,
                )
                val fullLabel = when (id) {
                    ParameterId.FX_AMOUNT -> "FX AMOUNT"
                    else -> id.name
                }
                val visualLabel = if (compact) when (id) {
                    ParameterId.RESONANCE -> "RESO"
                    ParameterId.FX_AMOUNT -> "FX MIX"
                    else -> fullLabel
                } else fullLabel
                ReferenceNeonKnob(
                    label = fullLabel,
                    value = param(id),
                    tint = tints[index],
                    tag = "referenceKnob" + id.name,
                    onValueChange = { onParameterChange(id, it) },
                    modifier = Modifier.weight(1f),
                    diameter = if (compact) 48.dp else 51.dp,
                    visualLabel = visualLabel,
                    displayValue = value.visual,
                    spokenValue = value.spoken,
                    compactTypography = compact,
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
                editorLabel = "Edit ADSR envelope",
                modifier = Modifier.weight(1f),
                height = if (compact) 64.dp else 80.dp,
                onOpen = onOpenMiniSignal
                    ?.takeIf { ReferenceMiniSignalDestination.ENVELOPE.canOpen(canEditModulationRack) }
                    ?.let { handler -> { handler(ReferenceMiniSignalDestination.ENVELOPE) } },
            )
            ReferenceMiniSignal(
                title = "LFO",
                caption = if (!canEditModulationRack && onOpenMiniSignal != null)
                    "CORE" // Wires to the legacy core LFO when rack edits are disconnected.
                else if (lfo == null) "OFF" else lfo.shape.name.take(5),
                values = lfoPreview,
                tint = OrbitalPalette.Cyan,
                tag = "referenceMiniLfo",
                editorLabel = if (canEditModulationRack)
                    "Edit matching configured eight-LFO source"
                else "Edit core LFO rate and depth",
                modifier = Modifier.weight(1f),
                height = if (compact) 64.dp else 80.dp,
                onOpen = onOpenMiniSignal
                    ?.takeIf { ReferenceMiniSignalDestination.LFO.canOpen(canEditModulationRack) }
                    ?.let { handler -> { handler(ReferenceMiniSignalDestination.LFO) } },
            )
            ReferenceMiniSignal(
                title = "EXPR",
                caption = "X/Y",
                values = expressionPreview,
                tint = OrbitalPalette.Gold,
                tag = "referenceMiniExpr",
                editorLabel = "Edit XY routing modes",
                modifier = Modifier.weight(1f),
                height = if (compact) 64.dp else 80.dp,
                onOpen = onOpenMiniSignal
                    ?.takeIf { ReferenceMiniSignalDestination.EXPRESSION.canOpen(canEditModulationRack) }
                    ?.let { handler -> { handler(ReferenceMiniSignalDestination.EXPRESSION) } },
            )
            ReferenceMiniSignal(
                title = "MOD",
                caption = liveRouteCount.toString() + " LIVE",
                values = routePreview,
                tint = OrbitalPalette.Violet,
                tag = "referenceMiniMod",
                editorLabel = "Edit 64-route modulation matrix",
                modifier = Modifier.weight(1f),
                height = if (compact) 64.dp else 80.dp,
                onOpen = onOpenMiniSignal
                    ?.takeIf { ReferenceMiniSignalDestination.MODULATION.canOpen(canEditModulationRack) }
                    ?.let { handler -> { handler(ReferenceMiniSignalDestination.MODULATION) } },
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
    /** Existing names morph/movement retain binary compatibility but hold selected X/Y values. */
    xyMode: ReferenceXyMode = ReferenceXyMode.PERFORMANCE,
    /** Invalidates a held recognizer when the host switches routing mode or patch. */
    gestureResetEpoch: Long = 0L,
    /** Optional unbounded normalized local XY for continuous folded seam crossing. */
    onRawTouch: ((Float, Float) -> Unit)? = null,
) {
    val x = ReferenceXyRouting.normalizeValue(morph)
    val y = ReferenceXyRouting.normalizeValue(movement)
    val liveEnergy = ReferenceSoundUiModel.normalized(audioRms * 5f)
    val currentChange by rememberUpdatedState(onChange)
    val currentRawTouch by rememberUpdatedState(onRawTouch)
    val xLabel = if (xyMode == ReferenceXyMode.PERFORMANCE) "morph" else xyMode.xLabel
    val yLabel = if (xyMode == ReferenceXyMode.PERFORMANCE) "movement" else xyMode.yLabel
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
                contentDescription = "Touch expression ribbon, $xLabel " +
                    (x * 100f).roundToInt() + " percent, $yLabel " +
                    (y * 100f).roundToInt() +
                    " percent. Parameter-driven shape preview, not FFT audio. " +
                    xyMode.guidance
                customActions = listOf(
                    CustomAccessibilityAction("Increase $xLabel") {
                        currentChange((currentX + .05f).coerceIn(0f, 1f), currentY)
                        true
                    },
                    CustomAccessibilityAction("Decrease $xLabel") {
                        currentChange((currentX - .05f).coerceIn(0f, 1f), currentY)
                        true
                    },
                    CustomAccessibilityAction("Increase $yLabel") {
                        currentChange(currentX, (currentY + .05f).coerceIn(0f, 1f))
                        true
                    },
                    CustomAccessibilityAction("Decrease $yLabel") {
                        currentChange(currentX, (currentY - .05f).coerceIn(0f, 1f))
                        true
                    },
                )
            }
            .pointerInput(gestureResetEpoch, xyMode) {
                // Unlike two competing tap/drag detectors, this ONE owner
                // admits only a genuinely new Android pointer DOWN.
                // Old A stays excluded after patch/engine/mode remount while
                // independent newly-down B may begin expressive editing.
                awaitPointerEventScope {
                    var ownerId: PointerId? = null
                    var lastOwnerPosition: Offset? = null
                    fun deliver(position: Offset) {
                        val rawX = position.x / size.width.coerceAtLeast(1)
                        val rawY = position.y / size.height.coerceAtLeast(1)
                        val raw = currentRawTouch
                        if (raw != null) raw(rawX, rawY)
                        else currentChange(
                            ReferenceXyRouting.normalizeValue(rawX),
                            ReferenceXyRouting.normalizeValue(1f - rawY),
                        )
                    }
                    while (true) {
                        val frame = awaitPointerEvent()
                        val previous = ownerId
                        if (previous != null) {
                            val held = frame.changes.firstOrNull { it.id == previous }
                            if (held == null || !held.pressed) {
                                ownerId = null
                                lastOwnerPosition = null
                            } else {
                                // Other fingers lifting may produce another
                                // frame with this finger unchanged. Never
                                // duplicate a real control edit on that UP.
                                if (lastOwnerPosition != held.position) {
                                    lastOwnerPosition = held.position
                                    deliver(held.position)
                                }
                                held.consume()
                            }
                        }
                        if (ownerId == null) {
                            val fresh = frame.changes.firstOrNull {
                                it.pressed && !it.previousPressed && !it.isConsumed
                            }
                            if (fresh != null) {
                                ownerId = fresh.id
                                lastOwnerPosition = fresh.position
                                deliver(fresh.position)
                                fresh.consume()
                            }
                        }
                    }
                }
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

        // Volumetric *parameter-illustration*, not an invented FFT. Transparent
        // mesh ribbons overlap like refracted light under glass; both their
        // geometry and brightness come only from MORPH, MOVEMENT and accepted
        // post-AudioTrack RMS. Rendering stays outside the audio worker.
        val energyGlow = .82f + .18f * liveEnergy
        val spectralBrush = Brush.horizontalGradient(
            listOf(
                violet.copy(alpha = .80f),
                cyan.copy(alpha = .98f),
                gold.copy(alpha = .92f),
                Color(0xFF52EFFF),
                violet.copy(alpha = .82f),
            ),
        )
        val layerColors = listOf(
            Color(0xFFB15DFF), Color(0xFF28D9FF), Color(0xFFFFC579),
            Color(0xFF6B8FFF), Color(0xFF37CFFF),
        )
        for (layer in 0..4) {
            val path = Path()
            val volume = Path()
            val segments = 96
            val thickness = height * (.033f + (layer % 3) * .010f +
                .014f * liveEnergy)
            for (index in 0..segments) {
                val u = index / segments.toFloat()
                val ordinate = ReferenceSoundUiModel.ribbonAt(u, layer, x, y)
                val point = Offset(u * width, ordinate * height)
                if (index == 0) path.moveTo(point.x, point.y)
                else path.lineTo(point.x, point.y)
                val outer = thickness * (.65f + .35f * (1f -
                    abs(2f * u - 1f))) * (1f + .12f * sin(u * 15f + layer))
                if (index == 0) volume.moveTo(point.x, point.y - outer)
                else volume.lineTo(point.x, point.y - outer)
            }
            for (index in segments downTo 0) {
                val u = index / segments.toFloat()
                val ordinate = ReferenceSoundUiModel.ribbonAt(u, layer, x, y)
                val outer = thickness * (.65f + .35f * (1f -
                    abs(2f * u - 1f))) * (1f + .12f * sin(u * 15f + layer))
                volume.lineTo(u * width, ordinate * height + outer)
            }
            volume.close()
            val tint = layerColors[layer]
            val volumetricBrush = Brush.horizontalGradient(
                listOf(
                    tint.copy(alpha = .12f),
                    tint.copy(alpha = .64f),
                    if (layer == 2) gold.copy(alpha = .85f)
                    else Color(0xFF89E9FF).copy(alpha = .64f),
                    tint.copy(alpha = .61f),
                    tint.copy(alpha = .11f),
                ),
            )
            drawPath(volume, volumetricBrush, alpha = .41f * energyGlow)
            drawPath(path, tint.copy(alpha = .13f),
                style = Stroke(width = (22f + liveEnergy * 7f),
                    cap = StrokeCap.Round))
            drawPath(path, spectralBrush,
                style = Stroke(width = (8f + 2f * liveEnergy),
                    cap = StrokeCap.Round),
                alpha = .19f * energyGlow)
            drawPath(path, spectralBrush,
                style = Stroke(width = if (layer == 2) 2.7f else 1.55f,
                    cap = StrokeCap.Round),
                alpha = if (layer == 2) .96f else .75f)
        }
        // Sparse glints are anchored to the same deterministic ribbon curves.
        // They are honest design particles, not purported spectral bins.
        repeat(22) { spark ->
            val u = .035f + spark * .043f
            val layer = spark % 5
            val pt = Offset(width * u,
                ReferenceSoundUiModel.ribbonAt(u, layer, x, y) * height)
            val tint = if (spark % 5 == 2) gold else if (spark % 3 == 0)
                Color.White else cyan
            if (spark % 6 == 0) {
                drawCircle(tint.copy(alpha = .17f + liveEnergy * .10f),
                    radius = 5.dp.toPx(), center = pt)
            }
            drawCircle(tint.copy(alpha = if (spark % 6 == 0) .94f else .44f),
                radius = if (spark % 6 == 0) 1.5.dp.toPx()
                else .65.dp.toPx(), center = pt)
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
    visualLabel: String = label,
    displayValue: String? = null,
    spokenValue: String? = null,
    compactTypography: Boolean = false,
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
                    contentDescription = label + ", " + (spokenValue ?:
                        ("normalized " + (safe * 100f).roundToInt() + " percent"))
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
            // Brushed-glass inner bezel and tiny cold specular highlight give
            // the five functional macros physical depth without new controls.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF2C3D54), Color(0xFF101725),
                        Color(0xFF050814)),
                    center = Offset(center.x - radius * .17f,
                        center.y - radius * .22f),
                    radius = radius * .99f,
                ),
                radius = radius * .69f,
            )
            drawCircle(Color(0xFF96AED8).copy(alpha = .17f),
                radius = radius * .61f,
                style = Stroke(width = .7.dp.toPx()))
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
            visualLabel,
            modifier = Modifier.testTag(tag + "Label"),
            color = Color(0xFFE8F3FF),
            fontSize = if (compactTypography) 9.sp else 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            letterSpacing = .15.sp,
        )
        Text(
            displayValue ?: "%.2f".format(java.util.Locale.US, safe),
            modifier = Modifier.testTag(tag + "Value"),
            color = tint,
            fontSize = if (compactTypography) 9.5.sp else 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    editorLabel: String,
    modifier: Modifier,
    height: Dp,
    onOpen: (() -> Unit)?,
) {
    // SOUND PCM telemetry updates at 20Hz. Keep one physical detector across
    // those recompositions; only swap its latest routing callback.
    val latestOpen by rememberUpdatedState(onOpen)
    Column(
        modifier
            .height(height)
            .border(
                1.dp, tint.copy(alpha = if (onOpen != null) .72f else .42f),
                RoundedCornerShape(8.dp),
            )
            .background(
                Brush.verticalGradient(listOf(ReferenceGlass, ReferenceBlack)),
                RoundedCornerShape(8.dp),
            )
            // A real full-card finger target. Use a separate semantics click
            // so the original caption Text nodes remain discoverable to tests
            // and assistive navigation (clickable would merge them away).
            .then(
                if (onOpen != null) Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = { latestOpen?.invoke() })
                } else Modifier
            )
            .padding(horizontal = 5.dp, vertical = 5.dp)
            .testTag(tag)
            .semantics {
                contentDescription = title + " configured preview, " + caption +
                    ". This is not sampled audio. " +
                    (if (onOpen != null) "Double tap to " + editorLabel + "." else
                        "Editing unavailable in this view.")
                if (onOpen != null) {
                    role = Role.Button
                    onClick(label = editorLabel) {
                        val action = latestOpen ?: return@onClick false
                        action()
                        true
                    }
                }
            },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color(0xFFE8F3FF),
                fontSize = if (height <= 64.dp) 9.sp else 10.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Text(caption, color = tint, fontSize = 9.sp, maxLines = 1,
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
