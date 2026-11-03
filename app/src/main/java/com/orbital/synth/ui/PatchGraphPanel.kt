package com.orbital.synth.ui

import android.animation.ValueAnimator
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
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
import com.orbital.synth.core.OscillatorSlot
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import com.orbital.synth.core.dsp.processing.ModCurve
import com.orbital.synth.core.dsp.processing.ModDestination
import com.orbital.synth.core.dsp.processing.ModSource
import com.orbital.synth.core.dsp.processing.ModulationStepConfig
import kotlin.math.PI
import kotlin.math.sin

/**
 * A physically honest, interactive patch canvas for the ONE live OrbitalSynth.
 *
 * The backbone is FIXED by DSP architecture; arbitrary oscillator-to-effect
 * cables are intentionally unsupported. Existing LFO/STEP -> six live bus routes
 * and queued ordered FX slots are editable via onRouteChange/onMoveFxSlot.
 * Reserved modulation IDs stay stored/visible as RESERVED and are never
 * misrepresented as processed audio. Bright cables are real configuration,
 * NOT PCM FFT data, microphone input, or a live spectrum.
 *
 * All vertical scrolling belongs to the host OrbitalScreen. The fallback
 * button controls below Canvas give every patch action a 48dp TalkBack target
 * even when a visually dense connection is awkward to drag on a 360dp phone.
 */
@Composable
fun PatchGraphPanel(
    oscillators: List<OscillatorSlot>,
    lfos: List<ModulationLfoConfig>,
    routes: List<ModulationRouteConfig>,
    fxSlots: List<FxModuleConfig>,
    onRouteChange: (Int, ModulationRouteConfig) -> Unit,
    onRouteClear: (Int) -> Unit,
    onMoveFxSlot: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    onOscillatorSlotChange: ((Int, OscillatorSlot) -> Unit)? = null,
    stepConfig: ModulationStepConfig? = null,
) {
    val routeBank = remember(routes) {
        List(64) { routes.getOrNull(it) ?: ModulationRouteConfig() }
    }
    var targetBank by remember { mutableStateOf(PatchGraphTargetBank.GLOBAL) }
    val graph = remember(oscillators, lfos, routes, fxSlots, stepConfig, targetBank) {
        PatchGraphUiModel.build(oscillators, lfos, routes, fxSlots, stepConfig, targetBank)
    }
    var source by remember { mutableStateOf(ModSource.LFO1) }
    var selectedBus by remember { mutableStateOf(ModDestination.MORPH) }
    var selectedRoute by remember { mutableIntStateOf(0) }
    var routePage by remember { mutableIntStateOf(0) }
    var selectedFx by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf(
        "Select a source and a LIVE destination bank. Every visible cable is a real saved route."
    ) }

    fun connect(from: ModSource, target: ModDestination) {
        source = from
        selectedBus = target
        targetBank = PatchGraphUiModel.bankForTarget(target)
        val result = PatchGraphUiModel.findOrCreateLiveRoute(routeBank, from, target)
        if (result == null) {
            notice = "No empty patch slots available, or this is not a supported live source/target."
            return
        }
        selectedRoute = result.first
        routePage = result.first / 8
        if (routeBank[result.first] == result.second) {
            notice = "Selected existing live route " + (result.first + 1) + " for editing."
        } else {
            onRouteChange(result.first, result.second)
            notice = "Patched " + from.name + " to " + target.name +
                " using real route " + (result.first + 1) + "."
        }
    }

    fun toggleOscillator(index: Int) {
        if (index !in 0..7) return
        val config = oscillators.getOrNull(index) ?: OscillatorSlot()
        if (onOscillatorSlotChange != null) {
            onOscillatorSlotChange(
                index,
                config.copy(
                    enabled = !config.enabled,
                    level = if (!config.enabled && config.level <= 0f) .45f else config.level,
                ),
            )
            notice = "OSC " + (index + 1) + " " +
                if (config.enabled) "bypassed." else "engaged in the real voice mixer."
        } else {
            notice = "OSC " + (index + 1) + " requires a host edit callback."
        }
    }

    fun tapNode(node: PatchGraphNode?) {
        when (node?.kind) {
            PatchNodeKind.LFO, PatchNodeKind.STEP -> {
                source = node.source ?: source
                notice = "Selected " + node.label + ". Tap/drag to a real DSP bus." +
                    (if (node.kind == PatchNodeKind.STEP && !node.active)
                        " Enable STEP first for an audible signal." else "")
            }
            PatchNodeKind.MOD_BUS -> node.destination?.let { connect(source, it) }
            PatchNodeKind.FX_SLOT -> {
                selectedFx = node.slot
                notice = "Selected real FX stage " + (node.slot + 1) + ". Drag to reorder."
            }
            PatchNodeKind.OSC_LAYER -> toggleOscillator(node.slot)
            PatchNodeKind.CORE_OSC, PatchNodeKind.VOICE_MIX, PatchNodeKind.FILTER,
            PatchNodeKind.VOICE_AMP_PAN, PatchNodeKind.MASTER_FX, PatchNodeKind.AUDIO_OUT -> {
                notice = "This " + node.label + " node is fixed in the live audio topology."
            }
            null -> Unit
        }
    }

    val fxIndex = selectedFx.coerceIn(0, (fxSlots.size - 1).coerceAtLeast(0))
    val routeIndex = selectedRoute.coerceIn(0, 63)
    val editedRoute = routeBank[routeIndex]
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    var phase = 0f
    if (animationsEnabled &&
        (graph.patchCables.any { it.active } ||
            fxSlots.any { it.enabled && it.wet > .01f })
    ) {
        val transition = rememberInfiniteTransition(label = "true-patch-network")
        val animatedPhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(8_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "network-cable-glow",
        )
        phase = animatedPhase
    }
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }
    val startNodeState = remember { mutableStateOf<PatchGraphNode?>(null) }
    val endPointState = remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier
            .testTag("patchGraphPanel")
            .border(1.dp, OrbitalPalette.PanelEdge, RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF071221), Color(0xFF01030A), Color(0xFF071022)),
                ),
                RoundedCornerShape(20.dp),
            )
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "P A T C H   /   S I G N A L   A T L A S",
            color = OrbitalPalette.Cyan,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        Text(
            "8 OSC LAYERS  •  8 LFO SOURCES" +
                (if (stepConfig != null) " + STEP" else "") + "  •  " + fxSlots.size +
                " ORDERED FX  /  " + graph.liveRouteCount + " CONFIGURED DSP ROUTES",
            color = OrbitalPalette.Muted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "Fixed signal path: CORE + OSC → MIX → FILTER → AMP/PAN → MASTER → FX → OUT",
            color = OrbitalPalette.Gold,
            fontSize = 10.sp,
        )

        Canvas(
            Modifier.fillMaxWidth().height(790.dp)
                .testTag("patchGraphCanvas")
                .semantics {
                    contentDescription = "Interactive signal atlas. Touch an LFO and a " +
                        "live modulation bus to patch. Drag FX processors to reorder. " +
                        "All fixed signal routing is real; no microphone spectrum."
                }
                .pointerInput(graph, oscillators, onOscillatorSlotChange, source, routes) {
                    detectTapGestures { point ->
                        tapNode(
                            PatchGraphUiModel.hitNode(
                                graph,
                                normalizedX = point.x / size.width.toFloat(),
                                normalizedY = point.y / size.height.toFloat(),
                                widthPx = size.width.toFloat(),
                                heightPx = size.height.toFloat(),
                                touchDiameterPx = 48.dp.toPx(),
                            )
                        )
                    }
                }
                .pointerInput(graph, fxSlots, source, routes) {
                    detectDragGestures(
                        onDragStart = { point ->
                            startNodeState.value = PatchGraphUiModel.hitNode(
                                graph, point.x / size.width.toFloat(),
                                point.y / size.height.toFloat(),
                                size.width.toFloat(), size.height.toFloat(),
                                48.dp.toPx(),
                            )
                            endPointState.value = point
                        },
                        onDragEnd = {
                            val start = startNodeState.value
                            val end = PatchGraphUiModel.hitNode(
                                graph,
                                endPointState.value.x / size.width.toFloat(),
                                endPointState.value.y / size.height.toFloat(),
                                size.width.toFloat(), size.height.toFloat(),
                                48.dp.toPx(),
                            )
                            if ((start?.kind == PatchNodeKind.LFO ||
                                    start?.kind == PatchNodeKind.STEP) &&
                                end?.kind == PatchNodeKind.MOD_BUS
                            ) {
                                val origin = start.source
                                val target = end.destination
                                if (origin != null && target != null) connect(origin, target)
                            } else if (start?.kind == PatchNodeKind.FX_SLOT &&
                                end?.kind == PatchNodeKind.FX_SLOT &&
                                start.slot != end.slot &&
                                start.slot in fxSlots.indices && end.slot in fxSlots.indices
                            ) {
                                onMoveFxSlot(start.slot, end.slot)
                                selectedFx = end.slot
                                notice = "Moved real FX stage " + (start.slot + 1) +
                                    " to " + (end.slot + 1) + "."
                            }
                            startNodeState.value = null
                        },
                        onDragCancel = { startNodeState.value = null },
                        onDrag = { change, _ ->
                            endPointState.value = change.position
                            if (startNodeState.value?.kind == PatchNodeKind.FX_SLOT ||
                                startNodeState.value?.kind == PatchNodeKind.LFO ||
                                startNodeState.value?.kind == PatchNodeKind.STEP
                            ) change.consume()
                        },
                    )
                },
        ) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas
            val glow = (.85f + .15f * sin(phase * (2f * PI).toFloat())).coerceIn(0f, 1f)
            val cyan = OrbitalPalette.Cyan
            val gold = OrbitalPalette.Gold
            drawRect(Color(0xFF01040B))

            // Bounded deterministic star dust, no random/FFT/PCM fabrications.
            for (dot in 0..71) {
                val x = (dot * 157 % 571).toFloat() / 571f * width
                val y = (dot * 313 % 983).toFloat() / 983f * height
                val tint = if (dot % 7 == 0) gold else cyan
                drawCircle(tint.copy(alpha = if (dot % 4 == 0) .17f else .055f),
                    radius = if (dot % 9 == 0) 1.25f else .6f, center = Offset(x, y))
            }
            val mid = Offset(width * .50f, height * .37f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        OrbitalPalette.Violet.copy(alpha = .13f * glow),
                        OrbitalPalette.Blue.copy(alpha = .045f),
                        Color.Transparent,
                    ),
                    center = mid,
                    radius = width * .88f,
                ),
                center = mid, radius = width * .88f,
            )

            // Only graph edges that exist in the actual OrbitalSynth render path.
            for (wire in graph.fixedAudio) {
                val a = Offset(wire.from.x * width, wire.from.y * height)
                val b = Offset(wire.to.x * width, wire.to.y * height)
                val p = Path().apply {
                    moveTo(a.x, a.y)
                    cubicTo(a.x, a.y + (b.y - a.y) * .43f,
                        b.x, b.y - (b.y - a.y) * .43f, b.x, b.y)
                }
                val tint = if (wire.active) cyan else OrbitalPalette.Muted
                if (wire.active) {
                    drawPath(p, tint.copy(alpha = .15f * glow),
                        style = Stroke(width = 12f, cap = StrokeCap.Round))
                }
                drawPath(
                    p, tint.copy(alpha = if (wire.active) .68f else .15f),
                    style = Stroke(
                        width = if (wire.active) 1.6f else .85f,
                        cap = StrokeCap.Round,
                        pathEffect = if (wire.active) null
                            else PathEffect.dashPathEffect(floatArrayOf(5f, 9f)),
                    ),
                )
            }

            // Show only current bank's real LFO/STEP→DSP cables, while the
            // truth status below counts other-bank routes (never reserved).
            // A source/layer with no possible audible output draws dormant.
            for (wire in graph.patchCables) {
                val a = Offset(wire.from.x * width, wire.from.y * height)
                val b = Offset(wire.to.x * width, wire.to.y * height)
                val path = Path().apply {
                    moveTo(a.x, a.y)
                    cubicTo(
                        a.x + (b.x - a.x) * .16f,
                        a.y + (b.y - a.y) * .38f,
                        b.x - (b.x - a.x) * .16f,
                        b.y - (b.y - a.y) * .38f,
                        b.x, b.y,
                    )
                }
                val tint = if (wire.routeSlot % 3 == 0) OrbitalPalette.Gold
                    else if (wire.routeSlot % 3 == 1) OrbitalPalette.Violet
                    else OrbitalPalette.Teal
                drawPath(path, tint.copy(alpha = if (wire.active) .12f * glow else .035f),
                    style = Stroke(width = 10f))
                drawPath(path, tint.copy(alpha = if (wire.active) .80f else .26f),
                    style = Stroke(
                        width = if (wire.routeSlot == routeIndex) 2.6f else 1.45f,
                        cap = StrokeCap.Round,
                        pathEffect = if (wire.active) null
                            else PathEffect.dashPathEffect(floatArrayOf(5f, 9f)),
                    ))
            }

            // The renderer emits a soft near-black physical-looking bloom, but
            // NEVER labels it a spectrum or measured waveform.
            graph.nodes.forEach { node ->
                val center = Offset(node.x * width, node.y * height)
                val tint = patchNodeTint(node)
                val selected = when (node.kind) {
                    PatchNodeKind.LFO, PatchNodeKind.STEP -> node.source == source
                    PatchNodeKind.MOD_BUS -> node.destination == selectedBus
                    PatchNodeKind.FX_SLOT -> node.slot == fxIndex
                    else -> false
                }
                val small = node.kind == PatchNodeKind.OSC_LAYER ||
                    node.kind == PatchNodeKind.FX_SLOT ||
                    node.kind == PatchNodeKind.LFO ||
                    node.kind == PatchNodeKind.STEP ||
                    node.kind == PatchNodeKind.MOD_BUS
                val radius = (if (small) 16.dp else 20.dp).toPx() +
                    if (selected) 3.dp.toPx() else 0f
                val brightness = if (node.active) 1f else .27f
                drawCircle(
                    tint.copy(alpha = .095f * brightness * glow),
                    center = center, radius = radius * 2.6f,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            tint.copy(alpha = .32f * brightness),
                            Color(0xFF081324),
                            Color(0xFF020610),
                        ),
                        center = center,
                        radius = radius * 1.12f,
                    ),
                    radius = radius, center = center,
                )
                drawCircle(
                    color = if (selected) OrbitalPalette.Gold else tint.copy(alpha = brightness),
                    radius = radius,
                    center = center,
                    style = Stroke(
                        width = if (selected) 2.4f else 1.2f,
                        cap = StrokeCap.Round,
                    ),
                )
                if (selected) drawCircle(
                    color = OrbitalPalette.Gold.copy(alpha = .45f),
                    radius = radius + 6.dp.toPx(), center = center,
                    style = Stroke(width = .8f),
                )
            }
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                graph.nodes.forEach { node ->
                    val radius = when (node.kind) {
                        PatchNodeKind.OSC_LAYER, PatchNodeKind.LFO, PatchNodeKind.STEP,
                        PatchNodeKind.FX_SLOT, PatchNodeKind.MOD_BUS -> 16.dp.toPx()
                        else -> 20.dp.toPx()
                    }
                    val centerX = node.x * width
                    val centerY = node.y * height
                    labelPaint.color = android.graphics.Color.WHITE
                    labelPaint.textSize = (if (node.kind == PatchNodeKind.FX_SLOT) 9.sp
                        else if (node.kind == PatchNodeKind.CORE_OSC) 11.sp
                        else 10.sp).toPx()
                    val word = when (node.kind) {
                        PatchNodeKind.FX_SLOT -> (node.slot + 1).toString()
                        PatchNodeKind.MOD_BUS -> node.label.take(
                            if (PatchGraphUiModel.oscillatorTargetIndex(
                                    node.destination ?: ModDestination.MORPH
                                ) >= 0) 5 else 3
                        )
                        else -> node.label
                    }
                    native.drawText(
                        word, centerX, centerY + labelPaint.textSize * .31f,
                        labelPaint,
                    )
                    if (node.kind == PatchNodeKind.FX_SLOT) {
                        labelPaint.textSize = 8.sp.toPx()
                        labelPaint.color = patchNodeTint(node).toArgb()
                        native.drawText(
                            node.fxType?.let(PatchGraphUiModel::slotName) ?: "BYPASS",
                            centerX, centerY + radius + 12.dp.toPx(), labelPaint,
                        )
                    }
                }
            }
        }

        Text(notice, color = OrbitalPalette.Teal, fontSize = 10.sp,
            modifier = Modifier.testTag("patchGraphNotice"))
        Text(
            graph.patchCables.size.toString() + " LFO/STEP CABLES DRAWN IN " +
                targetBank.label + " • " +
                graph.hiddenLiveRouteCount + " CONFIGURED DSP ROUTES IN OTHER BANKS • " +
                graph.stepRouteCount + " STEP ROUTES (" +
                graph.dormantStepRouteCount + " DORMANT) • " +
                graph.externalSourceRouteCount + " OTHER SOURCES NOT DRAWN • " +
                graph.dormantLfoRouteCount + " DORMANT LFO SOURCES • " +
                graph.dormantOscillatorTargetRouteCount + " SILENT OSC TARGETS • " +
                graph.reservedRouteCount + " RESERVED (NOT AUDIBLE)",
            color = OrbitalPalette.Muted,
            fontSize = 9.sp,
            modifier = Modifier.testTag("patchGraphTruthStatus"),
        )

        Text("CORE VOICE + EIGHT OPTIONAL OSCILLATOR LAYERS",
            color = OrbitalPalette.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Column(
            Modifier.fillMaxWidth().testTag("patchGraphOscillatorChoices"),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(2) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(4) { column ->
                        val index = row * 4 + column
                        val config = oscillators.getOrNull(index) ?: OscillatorSlot()
                        Box(Modifier.weight(1f)) {
                            PatchChip(
                                label = "OSC " + (index + 1),
                                selected = config.enabled,
                                tint = if (config.enabled) OrbitalPalette.Cyan else OrbitalPalette.Muted,
                                description = "Toggle live oscillator layer " + (index + 1) +
                                    if (config.enabled) ", engaged" else ", bypassed",
                                tag = "patchOscillator" + index,
                                fill = true,
                                enabled = onOscillatorSlotChange != null,
                            ) { toggleOscillator(index) }
                        }
                    }
                }
            }
        }

        Text("PATCH A SOURCE INTO REAL MODULATION BUS", color = OrbitalPalette.Text,
            fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Column(
            Modifier.fillMaxWidth().testTag("patchGraphLfoChoices"),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(2) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(4) { column ->
                        val index = row * 4 + column
                        val item = PatchGraphUiModel.lfoSources[index]
                        Box(Modifier.weight(1f)) {
                            PatchChip(
                                label = "LFO " + (index + 1),
                                selected = source == item,
                                tint = OrbitalPalette.Violet,
                                description = "Select LFO " + (index + 1) + " as patch source",
                                tag = "patchLfo" + index,
                                fill = true,
                            ) {
                                source = item
                                notice = "Selected LFO " + (index + 1) +
                                    ". Choose a live target below."
                            }
                        }
                    }
                }
            }
        }
        if (stepConfig != null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                PatchChip(
                    label = "STEP / CLOCK",
                    selected = source == ModSource.STEP,
                    tint = OrbitalPalette.Gold,
                    description = "Select real audio-clocked STEP modulation source",
                    tag = "patchStepSource",
                ) {
                    source = ModSource.STEP
                    notice = "STEP selected. Connect to a live bus. " +
                        (if (stepConfig.enabled) "Clocked step source is enabled."
                        else "Enable STEP in modulation editor before expecting audio.")
                }
                Text(
                    if (stepConfig.enabled) "AUDIO-CLOCKED / ACTIVE"
                    else "STEP SEQUENCER DISABLED / CABLING DORMANT",
                    color = if (stepConfig.enabled) OrbitalPalette.Teal else OrbitalPalette.Muted,
                    fontSize = 9.sp,
                    modifier = Modifier.weight(1f).testTag("patchStepSourceStatus"),
                )
            }
        }
        Text(
            "REAL DSP TARGET BANK  /  SIX GLOBAL + EIGHT INDEPENDENT OSC LAYERS",
            color = OrbitalPalette.Gold, fontSize = 10.sp,
        )
        // A 350dp phone cannot show 22 separate 48dp Canvas hit zones below
        // the LFO field. Each of these nine semantic buttons opens an honest,
        // reachable view of six global or exactly two per-layer DSP ports.
        Column(
            Modifier.fillMaxWidth().testTag("patchGraphDestinationBanks"),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PatchGraphTargetBank.entries.chunked(3).forEach { banks ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    banks.forEach { bankChoice ->
                        Box(Modifier.weight(1f)) {
                            PatchChip(
                                label = bankChoice.label,
                                selected = targetBank == bankChoice,
                                tint = if (bankChoice == PatchGraphTargetBank.GLOBAL)
                                    OrbitalPalette.Cyan else OrbitalPalette.Violet,
                                description = "Show real " + bankChoice.label +
                                    " modulation destination bank",
                                tag = "patchGraphBank" + bankChoice.name,
                                fill = true,
                            ) {
                                targetBank = bankChoice
                                if (selectedBus !in PatchGraphUiModel.visibleTargets(bankChoice)) {
                                    selectedBus = PatchGraphUiModel.visibleTargets(bankChoice).first()
                                }
                                notice = "Showing real DSP destination bank " +
                                    bankChoice.label + ". Other configured routes remain saved."
                            }
                        }
                    }
                }
            }
        }
        Text(
            "VISIBLE " + targetBank.label + " BUS PORTS • " +
                (if (targetBank == PatchGraphTargetBank.GLOBAL)
                    "PITCH · MORPH · CUTOFF · RESONANCE · AMP · PAN"
                 else "GAIN 0–2× · PITCH ±6 SEMITONES") +
                " • 48dp semantic touch controls",
            color = OrbitalPalette.Teal, fontSize = 10.sp,
            modifier = Modifier.testTag("patchGraphTargetBankStatus"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PatchGraphUiModel.visibleTargets(targetBank)
                .chunked(if (targetBank == PatchGraphTargetBank.GLOBAL) 3 else 2)
                .forEach { targets ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        targets.forEach { target ->
                            val oscillatorIndex = PatchGraphUiModel.oscillatorTargetIndex(target)
                            val layerAudible = oscillatorIndex < 0 ||
                                oscillators.getOrNull(oscillatorIndex)?.let {
                                    it.enabled && it.level > 0f
                                } == true
                            val label = if (oscillatorIndex < 0) target.name
                                else "OSC " + (oscillatorIndex + 1) + " " +
                                    PatchGraphUiModel.targetLabel(target)
                            Box(Modifier.weight(1f)) {
                                PatchChip(
                                    label = label,
                                    selected = selectedBus == target,
                                    tint = if (oscillatorIndex < 0) OrbitalPalette.Cyan
                                        else if (target.name.endsWith("_GAIN"))
                                            OrbitalPalette.Gold else OrbitalPalette.Violet,
                                    description = "Connect " + source.name +
                                        " to real DSP " + label +
                                        (if (layerAudible) "" else
                                            "; this oscillator is bypassed or level zero"),
                                    tag = "patchBus" + target.name,
                                    fill = true,
                                ) { connect(source, target) }
                            }
                        }
                    }
                }
        }
        Text(
            "All 22 destinations are DSP-capable, but require a driving source " +
                "and nonzero depth. Disabled oscillator layers stay silent. " +
                "Reserved route IDs are never shown as patchable buses.",
            color = OrbitalPalette.Gold, fontSize = 10.sp,
        )

        Text(
            "ROUTE BANK  /  SELECT ANY OF 64 SAVED CABLES",
            color = OrbitalPalette.Text, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        )
        Row(
            Modifier.fillMaxWidth().testTag("patchRoutePageControls"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PatchChip(
                label = "◀ PREV 8",
                selected = false,
                tint = OrbitalPalette.Cyan,
                description = "Previous eight saved modulation routes",
                tag = "patchRoutePagePrev",
                enabled = routePage > 0,
            ) { routePage = (routePage - 1).coerceAtLeast(0) }
            Text(
                "BANK " + (routePage + 1) + " / 8  •  " +
                    (routePage * 8 + 1).toString().padStart(2, '0') + "–" +
                    (routePage * 8 + 8).toString().padStart(2, '0'),
                color = OrbitalPalette.Gold,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.testTag("patchRoutePageStatus"),
            )
            PatchChip(
                label = "NEXT 8 ▶",
                selected = false,
                tint = OrbitalPalette.Cyan,
                description = "Next eight saved modulation routes",
                tag = "patchRoutePageNext",
                enabled = routePage < 7,
            ) { routePage = (routePage + 1).coerceAtMost(7) }
        }
        // Every one of the 64 saved routes now has a real, reachable cable
        // trace card (two per row, eight per bank) instead of number-only keys.
        // The card is static and truthfully distinguishes inverse polarity,
        // zero-depth silence, dormant sources and reserved non-audio targets.
        Column(
            Modifier.fillMaxWidth().testTag("patchGraphRouteBank"),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            repeat(4) { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    repeat(2) { column ->
                        val index = routePage * 8 + row * 2 + column
                        val cable = PatchCableTraceModel.describe(
                            index, routeBank[index], lfos, stepConfig,
                        )
                        Box(Modifier.weight(1f)) {
                            PatchCableTraceCard(
                                trace = cable,
                                selected = routeIndex == index,
                                onClick = {
                                    selectedRoute = index
                                    val savedDestination = routeBank[index].destination
                                    if (savedDestination in PatchGraphUiModel.liveTargets) {
                                        targetBank = PatchGraphUiModel.bankForTarget(savedDestination)
                                        selectedBus = savedDestination
                                    }
                                    notice = "Editing saved route " + (index + 1) +
                                        " / " + cable.status.caption + "."
                                },
                            )
                        }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth()
                .border(1.dp, OrbitalPalette.Blue.copy(alpha = .6f), RoundedCornerShape(13.dp))
                .background(Color(0xFF071426), RoundedCornerShape(13.dp))
                .padding(10.dp)
                .testTag("patchGraphRouteEditor"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "ROUTE " + (routeIndex + 1).toString().padStart(2, '0') +
                    "  •  " + editedRoute.source.name + " → " +
                    editedRoute.destination.name,
                color = OrbitalPalette.Gold,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 11.sp,
            )
            Text(
                if (!editedRoute.enabled) "EMPTY / BYPASSED"
                else if (editedRoute.destination in PatchGraphUiModel.liveTargets) {
                    val oscillatorIndex =
                        PatchGraphUiModel.oscillatorTargetIndex(editedRoute.destination)
                    if (oscillatorIndex >= 0 &&
                        oscillators.getOrNull(oscillatorIndex)?.let {
                            it.enabled && it.level > 0f
                        } != true
                    ) "DSP-CAPABLE TARGET • OSC " + (oscillatorIndex + 1) +
                        " BYPASSED OR LEVEL ZERO; CABLE DORMANT UNTIL AUDIBLE"
                    else "DSP-CAPABLE TARGET • SIGNAL REQUIRES SOURCE + DEPTH • " +
                        PatchGraphUiModel.destinationEndpoint(editedRoute.destination)
                } else "RESERVED METADATA • THIS CABLE HAS NO AUDIO EFFECT",
                color = if (editedRoute.enabled &&
                    editedRoute.destination in PatchGraphUiModel.liveTargets
                ) OrbitalPalette.Teal else OrbitalPalette.Muted,
                fontSize = 10.sp,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PatchRoutePicker(
                    title = "SOURCE",
                    selected = editedRoute.source,
                    choices = ModSource.entries,
                    display = { ModulationSourceAvailability.pickerLabel(it) },
                    tag = "patchGraphRouteSource",
                    modifier = Modifier.weight(1f),
                ) { chosenSource ->
                    onRouteChange(routeIndex, editedRoute.copy(source = chosenSource, enabled = true))
                }
                PatchRoutePicker(
                    title = "LIVE DESTINATION",
                    selected = editedRoute.destination,
                    choices = PatchGraphUiModel.liveTargets,
                    display = {
                        it.name + if (it !in PatchGraphUiModel.liveTargets) " RESERVED" else ""
                    },
                    tag = "patchGraphRouteDestination",
                    modifier = Modifier.weight(1f),
                ) { target ->
                    targetBank = PatchGraphUiModel.bankForTarget(target)
                    selectedBus = target
                    onRouteChange(routeIndex, editedRoute.copy(destination = target, enabled = true))
                }
            }
            PatchRoutePicker(
                title = "MODULATION CURVE",
                selected = editedRoute.curve,
                choices = ModCurve.entries,
                display = { it.name },
                tag = "patchGraphRouteCurve",
            ) { curve ->
                onRouteChange(routeIndex, editedRoute.copy(curve = curve, enabled = true))
            }
            Text(
                ModulationSourceAvailability.sourceLabel(editedRoute.source) +
                    " · " + ModulationSourceAvailability.routeStatus(
                        editedRoute, lfos, stepConfig
                    ),
                color = if (ModulationSourceAvailability.isUnwired(editedRoute.source))
                    OrbitalPalette.Gold else OrbitalPalette.Muted,
                fontSize = 9.sp,
                modifier = Modifier.testTag("patchGraphSourceTruth"),
            )
            Text(
                "All 24 source IDs stay saved/editable. Eight LFOs and STEP are " +
                    "internally generated; TOUCH requires an actual gesture. All 22 " +
                    "destinations reach DSP only when driven at nonzero depth; " +
                    "bypassed or zero-level oscillator layers stay silent.",
                color = OrbitalPalette.Muted, fontSize = 9.sp,
            )
            Text(
                "DEPTH  " + "%.2f".format(PatchGraphUiModel.safeDepth(editedRoute.depth)) + "×",
                color = OrbitalPalette.Cyan, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Slider(
                value = PatchGraphUiModel.safeDepth(editedRoute.depth),
                valueRange = -4f..4f,
                onValueChange = {
                    onRouteChange(routeIndex, editedRoute.copy(depth = it, enabled = true))
                },
                modifier = Modifier.fillMaxWidth()
                    .semantics {
                        contentDescription = "Modulation depth for route " + (routeIndex + 1)
                    }
                    .testTag("patchGraphDepthSlider"),
                colors = SliderDefaults.colors(
                    thumbColor = OrbitalPalette.Gold,
                    activeTrackColor = OrbitalPalette.Violet,
                    inactiveTrackColor = OrbitalPalette.DeepBlue,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PatchChip(
                    label = if (editedRoute.enabled) "BYPASS ROUTE" else "ENABLE ROUTE",
                    selected = editedRoute.enabled,
                    tint = OrbitalPalette.Teal,
                    description = "Toggle route " + (routeIndex + 1),
                    tag = "patchGraphToggleRoute",
                ) {
                    onRouteChange(routeIndex, editedRoute.copy(enabled = !editedRoute.enabled))
                }
                PatchChip(
                    label = "CLEAR",
                    selected = false,
                    tint = OrbitalPalette.Gold,
                    description = "Clear route " + (routeIndex + 1),
                    tag = "patchGraphClearRoute",
                ) { onRouteClear(routeIndex) }
            }
        }

        Text("FX SIGNAL ORDER  /  DRAG NODES OR USE ACCESSIBLE MOVE KEYS",
            color = OrbitalPalette.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (fxSlots.isEmpty()) {
            Text("No live modular FX slots provided by the host.",
                color = OrbitalPalette.Muted, fontSize = 11.sp)
        } else {
            Column(
                Modifier.fillMaxWidth().testTag("patchGraphFxChoices"),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat((fxSlots.take(8).size + 3) / 4) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(4) { column ->
                            val index = row * 4 + column
                            Box(Modifier.weight(1f)) {
                                if (index in fxSlots.indices && index < 8) {
                                    val config = fxSlots[index]
                                    PatchChip(
                                        label = (index + 1).toString() + " " +
                                            if (config == FxModuleConfig()) "DRY"
                                            else PatchGraphUiModel.slotName(config.type),
                                        selected = index == fxIndex,
                                        tint = if (config.enabled) OrbitalPalette.Gold
                                            else OrbitalPalette.Muted,
                                        description = "Select effect slot " + (index + 1) +
                                            if (config.enabled) ", engaged" else ", bypassed",
                                        tag = "patchFx" + index,
                                        fill = true,
                                    ) { selectedFx = index }
                                }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PatchChip(
                    label = "MOVE ◀",
                    selected = false,
                    tint = OrbitalPalette.Cyan,
                    description = "Move selected effect stage left",
                    tag = "patchFxMoveLeft",
                    enabled = fxIndex > 0,
                ) {
                    onMoveFxSlot(fxIndex, fxIndex - 1)
                    selectedFx = fxIndex - 1
                }
                PatchChip(
                    label = "MOVE ▶",
                    selected = false,
                    tint = OrbitalPalette.Cyan,
                    description = "Move selected effect stage right",
                    tag = "patchFxMoveRight",
                    enabled = fxIndex < fxSlots.size - 1,
                ) {
                    onMoveFxSlot(fxIndex, fxIndex + 1)
                    selectedFx = fxIndex + 1
                }
            }
        }
        Text(
            "Graph glow represents configured routing only. It does not sample audio or imply unsupported free patch cables.",
            color = OrbitalPalette.Muted, fontSize = 10.sp,
        )
    }
}

/**
 * Explicitly exposes the real typed source, all 22 DSP destinations and
 * modulation shape. Large semantic targets keep route editing usable on phone.
 */
@Composable
private fun <T> PatchRoutePicker(
    title: String,
    selected: T,
    choices: List<T>,
    display: (T) -> String,
    tag: String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(title, color = OrbitalPalette.Muted, fontSize = 9.sp)
        Box {
            TextButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, OrbitalPalette.Blue.copy(alpha = .65f),
                        RoundedCornerShape(8.dp))
                    .background(Color(0xFF08172B), RoundedCornerShape(8.dp))
                    .semantics {
                        contentDescription = title + ": " + display(selected) + ". Change patch route"
                    }
                    .testTag(tag),
            ) {
                Text(
                    display(selected),
                    color = OrbitalPalette.Cyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                choices.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Text(display(item), color = if (item == selected)
                                OrbitalPalette.Cyan else OrbitalPalette.Text)
                        },
                        onClick = { open = false; onSelect(item) },
                        modifier = Modifier.testTag(tag + "Option" + item.toString()),
                    )
                }
            }
        }
    }
}

@Composable
private fun PatchChip(
    label: String,
    selected: Boolean,
    tint: Color,
    description: String,
    tag: String,
    enabled: Boolean = true,
    fill: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier.widthIn(min = 50.dp))
            .heightIn(min = 48.dp)
            .border(
                if (selected) 1.4.dp else .6.dp,
                if (selected) OrbitalPalette.Gold else tint.copy(alpha = .50f),
                RoundedCornerShape(9.dp),
            )
            .background(
                tint.copy(alpha = if (selected) .23f else .085f),
                RoundedCornerShape(9.dp),
            )
            .semantics { contentDescription = description }
            .testTag(tag),
    ) {
        Text(
            label,
            color = tint.copy(alpha = if (enabled) 1f else .3f),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

private fun patchNodeTint(node: PatchGraphNode): Color = when (node.kind) {
    PatchNodeKind.CORE_OSC, PatchNodeKind.AUDIO_OUT -> OrbitalPalette.Gold
    PatchNodeKind.OSC_LAYER, PatchNodeKind.VOICE_MIX -> OrbitalPalette.Cyan
    PatchNodeKind.FILTER, PatchNodeKind.VOICE_AMP_PAN -> OrbitalPalette.Teal
    PatchNodeKind.MASTER_FX -> OrbitalPalette.Blue
    PatchNodeKind.LFO -> OrbitalPalette.Violet
    PatchNodeKind.STEP -> OrbitalPalette.Gold
    PatchNodeKind.MOD_BUS -> OrbitalPalette.Cyan
    PatchNodeKind.FX_SLOT -> when (node.fxType) {
        FxModuleType.SATURATION -> OrbitalPalette.Gold
        FxModuleType.TREMOLO -> OrbitalPalette.Cyan
        FxModuleType.CHORUS -> OrbitalPalette.Violet
        FxModuleType.DELAY -> OrbitalPalette.Teal
        FxModuleType.REVERB -> OrbitalPalette.Blue
        FxModuleType.PHASER -> OrbitalPalette.Cyan
        FxModuleType.BITCRUSH -> OrbitalPalette.Gold
        null -> OrbitalPalette.Muted
        else -> OrbitalPalette.Violet
    }
}
