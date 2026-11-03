package com.orbital.synth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.ModulationLfoConfig
import com.orbital.synth.core.ModulationRouteConfig
import com.orbital.synth.core.ParameterId
import kotlin.math.roundToInt

private val HeroObsidian = Color(0xFF01050F)
private val HeroGlass = Color(0xA507142C)

/**
 * The visible plasma and overlaid XY ribbon form a continuous, folded Y
 * gesture surface. Both zones cover the full 0..1 MOVEMENT range: the orb
 * rises toward the XY seam and the ribbon falls away from it. Crossing the
 * seam never jumps or disconnects an ongoing finger gesture.
 */
internal class ReferenceHeroGestureGate {
    private var revision = 0L
    fun ticket(): Long = revision
    fun invalidate() { revision++ }
    fun allows(ticket: Long): Boolean = ticket == revision
}

internal object ReferenceHeroTouch {
    fun movementAt(y: Float, stageHeight: Float, ribbonHeight: Float): Float {
        val safeHeight = stageHeight.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safeRibbon = ribbonHeight.takeIf { it.isFinite() && it > 0f }
            ?.coerceAtMost(safeHeight) ?: (safeHeight * .38f)
        val seam = (safeHeight - safeRibbon).coerceAtLeast(.001f)
        return if (y <= seam) {
            ReferenceSoundUiModel.normalized(y / seam)
        } else {
            ReferenceSoundUiModel.normalized(1f - (y - seam) / safeRibbon)
        }
    }
}

/**
 * Portrait SOUND composition, reconstructed from the *actual original* PNG.
 *
 * One actual GPU plasma orb plus ONE translucent XY ribbon overlays its lower
 * edge, then five legitimate host-driven macro knobs and four honest saved
 * configuration previews. No separate synth, fake FFT, synthetic meter,
 * decorative control that pretends to affect DSP, or duplicate rendering.
 *
 * This reusable block intentionally does not own MainActivity, transport,
 * keypad, patch persistence, tabs, or any other host integration.
 */
@Composable
fun ReferenceSoundHero(
    parameters: Map<ParameterId, Float>,
    lfos: List<ModulationLfoConfig>,
    routes: List<ModulationRouteConfig>,
    onParameterChange: (ParameterId, Float) -> Unit,
    modifier: Modifier = Modifier,
    audioRms: Float = 0f,
    audioPeak: Float = 0f,
    compact: Boolean = true,
    patchName: String = "AURORA",
    engineName: String = "ORBITAL WT",
    /** Repository patch ID: distinct patches may share the visible name. */
    patchId: String? = null,
    /** Optional recall epoch for replacing the same selected patch. */
    patchEpoch: Long = 0L,
    onOpenPatchLibrary: (() -> Unit)? = null,
    onPreviousPatch: (() -> Unit)? = null,
    onNextPatch: (() -> Unit)? = null,
    onOpenEngine: (() -> Unit)? = null,
    /** Optional hosted XY selection. Null keeps standalone previews interactive. */
    xyMode: ReferenceXyMode? = null,
    onXyModeSelected: ((ReferenceXyMode) -> Unit)? = null,
    synthSampleRate: Int = 48_000,
) {
    var localModeName by rememberSaveable {
        mutableStateOf(ReferenceXyMode.PERFORMANCE.name)
    }
    val engineValue = ReferenceXyRouting.normalizeValue(
        parameters[ParameterId.ENGINE] ?: 0f,
    )
    val requestedMode = if (onXyModeSelected != null) {
        xyMode ?: ReferenceXyMode.PERFORMANCE
    } else {
        ReferenceXyMode.entries.firstOrNull { it.name == localModeName }
            ?: ReferenceXyMode.PERFORMANCE
    }
    val activeMode = requestedMode.resolveFallback(engineValue)
    LaunchedEffect(requestedMode, activeMode) {
        if (requestedMode != activeMode) {
            // Engine 3's quantized FM wave and engine 4's stepped PD
            // cannot silently inherit an unsupported continuous XY mode.
            if (onXyModeSelected == null) localModeName = activeMode.name
            else onXyModeSelected(activeMode)
        }
    }
    val safeX = ReferenceXyRouting.normalizeValue(
        parameters[activeMode.xParameterId] ?: .5f,
    )
    val safeY = ReferenceXyRouting.normalizeValue(
        parameters[activeMode.yParameterId] ?: .5f,
    )
    val xActionName = if (activeMode == ReferenceXyMode.PERFORMANCE) "morph"
        else activeMode.xLabel
    val yActionName = if (activeMode == ReferenceXyMode.PERFORMANCE) "movement"
        else activeMode.yLabel
    // The shader is *parameter-driven abstract art* plus genuine PCM energy,
    // not a frequency analyzer or a second audio-processing engine.
    val latestChange by rememberUpdatedState(onParameterChange)
    val gate = remember { ReferenceHeroGestureGate() }
    DisposableEffect(activeMode, patchId, patchEpoch, engineName) {
        onDispose { gate.invalidate() } // Old queued gestures never edit newly routed sound.
    }
    var navigationEpoch by remember { mutableIntStateOf(0) }
    var fingerPressed by remember { mutableStateOf(false) }
    var fingerSession by remember { mutableLongStateOf(0L) }
    var touchX by remember { mutableFloatStateOf(.5f) }
    var touchY by remember { mutableFloatStateOf(.5f) }
    val height: Dp = if (compact) 230.dp else 278.dp
    val ribbonHeight: Dp = if (compact) 88.dp else 110.dp
    val density = LocalDensity.current
    val heightPx = with(density) { height.toPx() }
    val ribbonPx = with(density) { ribbonHeight.toPx() }

    fun haltGestureAndNavigate(action: () -> Unit) {
        gate.invalidate() // Suppress held-finger moves before patch mutation.
        fingerSession++ // Old coroutine cleanup must not darken a NEW finger.
        navigationEpoch++
        fingerPressed = false
        action()
    }

    fun update(x: Float, y: Float) {
        val point = ReferenceXyRouting.normalizePoint(x, y)
        touchX = point.x
        touchY = point.y
        // BOTH axes edit real, currently selected host DSP ParameterIds.
        // FOCUS keeps independent configured touch-performance routing.
        latestChange(activeMode.xParameterId, point.x)
        latestChange(activeMode.yParameterId, point.y)
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(HeroObsidian)
            .testTag("referenceSoundHero"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .testTag("referenceSoundHeroOrb"),
        ) {
            OrbitalPlasmaField(
                morph = safeX,
                movement = safeY,
                audioRms = audioRms,
                audioPeak = audioPeak,
                touchX = touchX,
                touchY = touchY,
                pressed = fingerPressed,
                modifier = Modifier.fillMaxSize(),
                decorative = true, // Render once, expose only one actionable orb TalkBack target.
            )
            // Above plasma, below real clickable HUD and real interactive XY:
            // any finger on the orb edits the same audible normalized parameters.
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("referenceHeroOrbInput")
                    .semantics {
                        stateDescription = if (fingerPressed) "Touch engaged" else "Touch ready"
                        contentDescription = "Touch glowing synth orb, $xActionName " +
                            (safeX * 100f).roundToInt() +
                            " percent, $yActionName " + (safeY * 100f).roundToInt() +
                            " percent. Swipe or use four edits. " + activeMode.guidance +
                            " Visual geometry follows real selected parameters and PCM brightness."
                        customActions = listOf(
                            CustomAccessibilityAction("Increase $xActionName") {
                                latestChange(activeMode.xParameterId, (safeX + .05f).coerceAtMost(1f))
                                true
                            },
                            CustomAccessibilityAction("Decrease $xActionName") {
                                latestChange(activeMode.xParameterId, (safeX - .05f).coerceAtLeast(0f))
                                true
                            },
                            CustomAccessibilityAction("Increase $yActionName") {
                                latestChange(activeMode.yParameterId, (safeY + .05f).coerceAtMost(1f))
                                true
                            },
                            CustomAccessibilityAction("Decrease $yActionName") {
                                latestChange(activeMode.yParameterId, (safeY - .05f).coerceAtLeast(0f))
                                true
                            },
                        )
                    }
                    .pointerInput(compact, patchId ?: patchName, patchEpoch, engineName, navigationEpoch, activeMode) {
                        // A patch recall cancels the prior recognizer while a
                        // physical finger can still be DOWN. awaitEachGesture
                        // waits for *all* stale fingers to lift before allowing
                        // a newly pressed finger, silently ignoring a real new
                        // attack. Accept only genuinely new down transitions.
                        val ribbonPixels = ribbonHeight.toPx()
                        awaitPointerEventScope {
                            var ownerId: PointerId? = null
                            var lastOwnerPosition: Offset? = null
                            var ownerTicket = gate.ticket()
                            var ownerSession = 0L
                            try {
                                while (true) {
                                    val frame = awaitPointerEvent()
                                    val previousId = ownerId
                                    if (previousId != null) {
                                        val owner = frame.changes.firstOrNull {
                                            it.id == previousId
                                        }
                                        if (owner == null || !owner.pressed ||
                                            !gate.allows(ownerTicket)
                                        ) {
                                            if (fingerSession == ownerSession &&
                                                gate.allows(ownerTicket)
                                            ) fingerPressed = false
                                            ownerId = null
                                            lastOwnerPosition = null
                                        } else {
                                            // Android batches ALL pointers into a
                                            // frame. An old A UP may carry held C
                                            // unchanged; it must not re-emit both
                                            // CUTOFF and RESONANCE for C. Only a
                                            // movement of THIS owning finger edits.
                                            if (lastOwnerPosition != owner.position) {
                                                lastOwnerPosition = owner.position
                                                update(
                                                    owner.position.x / size.width,
                                                    ReferenceHeroTouch.movementAt(
                                                        owner.position.y,
                                                        size.height.toFloat(),
                                                        ribbonPixels,
                                                    ),
                                                )
                                            }
                                            owner.consume()
                                        }
                                    }
                                    if (ownerId == null) {
                                        // Ignore unknown pointers that were
                                        // already held before the new patch's
                                        // recognizer existed (previousPressed).
                                        // Fresh pointer 2 may be admitted while
                                        // stale pointer 0 is still on glass.
                                        val freshDown = frame.changes.firstOrNull {
                                            it.pressed && !it.previousPressed && !it.isConsumed
                                        }
                                        if (freshDown != null) {
                                            ownerId = freshDown.id
                                            lastOwnerPosition = freshDown.position
                                            ownerTicket = gate.ticket()
                                            fingerSession++
                                            ownerSession = fingerSession
                                            fingerPressed = true
                                            if (gate.allows(ownerTicket)) update(
                                                freshDown.position.x / size.width,
                                                ReferenceHeroTouch.movementAt(
                                                    freshDown.position.y, size.height.toFloat(),
                                                    ribbonPixels,
                                                ),
                                            )
                                            freshDown.consume()
                                        }
                                    }
                                }
                            } finally {
                                // Stale cancelled coroutine must NEVER clear
                                // a newer pointer's glow across patch/engine
                                // changes or 2-finger overlap.
                                if (fingerSession == ownerSession &&
                                    gate.allows(ownerTicket)
                                ) fingerPressed = false
                            }
                        }
                    },
            )
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 9.dp, vertical = 6.dp)
                    .testTag("referenceHeroPatchEngineHud"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    Modifier.weight(1f).padding(end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .then(
                                if (onOpenPatchLibrary != null) Modifier.clickable(
                                    role = Role.Button, onClick = { haltGestureAndNavigate(onOpenPatchLibrary) },
                                ) else Modifier,
                            )
                            .semantics {
                                contentDescription = if (onOpenPatchLibrary != null) {
                                    "Open patch library: $patchName"
                                } else {
                                    "Current patch: $patchName"
                                }
                            }
                            .testTag("referenceHeroPatchName"),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "SOUND / PATCH",
                            color = OrbitalPalette.Muted,
                            fontSize = 9.5.sp,
                            letterSpacing = .65.sp,
                        )
                        Text(
                            patchName,
                            color = OrbitalPalette.Text,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (onPreviousPatch != null) {
                            ReferenceHeroArrow("‹", "Previous patch",
                                { haltGestureAndNavigate(onPreviousPatch) },
                                "referenceHeroPreviousPatch")
                        }
                        if (onNextPatch != null) {
                            ReferenceHeroArrow("›", "Next patch",
                                { haltGestureAndNavigate(onNextPatch) },
                                "referenceHeroNextPatch")
                        }
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 6.dp)
                        .heightIn(min = 48.dp)
                        .then(
                            if (onOpenEngine != null) Modifier.clickable(
                                role = Role.Button, onClick = { haltGestureAndNavigate(onOpenEngine) },
                            ) else Modifier,
                        )
                        .semantics {
                            contentDescription = if (onOpenEngine != null) {
                                "Open synth engine: $engineName"
                            } else {
                                "Current synth engine: $engineName"
                            }
                        }
                        .testTag("referenceHeroEngineName"),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "ENGINE",
                        color = OrbitalPalette.Muted,
                        fontSize = 9.5.sp,
                        letterSpacing = .65.sp,
                    )
                    Text(
                        engineName,
                        color = OrbitalPalette.Cyan,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // An XY finger has its own recognizer; dispose it on patch/engine
            // switches too. Ticket check blocks delayed callbacks even before
            // Compose processes the new key in the next frame.
            val ribbonTicket = gate.ticket()
            key(navigationEpoch, patchId ?: patchName, patchEpoch, engineName, activeMode) {
                // Two separate tagged semantics nodes: outer positioning Box
                // and inner Canvas real interactive "referenceExpressionRibbon".
                // Never attach two testTags to the *same* Canvas modifier chain.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 7.dp)
                        .testTag("referenceHeroXYUnderOrb"),
                ) {
                    ReferenceExpressionRibbon(
                        morph = safeX,
                        movement = safeY,
                        onChange = { x, y ->
                            if (gate.allows(ribbonTicket)) update(x, y)
                        },
                        onRawTouch = { rawX, rawY ->
                            // Ribbon-originated fingers cross into the upper
                            // orb continuously; the original folded Y seam
                            // has no false jump or half-screen clamp.
                            if (gate.allows(ribbonTicket)) update(
                                rawX,
                                ReferenceHeroTouch.movementAt(
                                    heightPx - ribbonPx + rawY * ribbonPx,
                                    heightPx, ribbonPx,
                                ),
                            )
                        },
                        xyMode = activeMode,
                        gestureResetEpoch = navigationEpoch.toLong(),
                        audioRms = audioRms,
                        transparentBackground = true,
                        height = ribbonHeight,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // Original-inspired slim translucent rail stays INSIDE the existing
            // orb/ribbon bounds. The six audibly real modes appear only when
            // expanded; neither the five knobs, four minis nor anchored
            // keyboard moves down by even one dp.
            ReferenceXyModeRail(
                selectedMode = activeMode,
                normalizedEngine = engineValue,
                onModeSelected = { selected ->
                    if (onXyModeSelected == null) localModeName = selected.name
                    else onXyModeSelected(selected)
                },
                ribbonHeight = ribbonHeight,
                onBeforeOpen = { haltGestureAndNavigate {} },
                onBeforeSelect = { haltGestureAndNavigate {} },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Do not render a second XY card under the orb. All five macros and
        // four tiny previews remain accessible and connected to the host.
        ReferenceSoundControls(
            parameters = parameters,
            lfos = lfos,
            routes = routes,
            onParameterChange = onParameterChange,
            audioRms = audioRms,
            compact = compact,
            showExpressionRibbon = false,
            synthSampleRate = synthSampleRate,
        )
    }
}

@Composable
private fun ReferenceHeroArrow(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    tag: String,
) {
    Box(
        Modifier
            .size(48.dp)
            .background(HeroGlass, RoundedCornerShape(11.dp))
            .border(1.dp, OrbitalPalette.Cyan.copy(alpha = .32f),
                RoundedCornerShape(11.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = OrbitalPalette.Cyan, fontSize = 23.sp)
    }
}
