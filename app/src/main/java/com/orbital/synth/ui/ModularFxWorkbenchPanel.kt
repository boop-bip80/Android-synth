package com.orbital.synth.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.dsp.processing.FxModuleConfig
import com.orbital.synth.core.dsp.processing.FxModuleType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A visually tactile editor for an ordered, HOST-OWNED [FxModuleConfig] snapshot.
 *
 * The seven stereo processors are real DSP implementations in ModularFxChain. They
 * are not automatically attached to the live instrument merely because this
 * editor is mounted: the host must forward callbacks into the audio block worker.
 * This component never creates an audio engine, PCM stream, or phantom FFT.
 *
 * Host contract: provide 1..8 slots; use [onSlotChange] for configuration,
 * creation, bypass, removal and [onMoveSlot] for moving a complete stage.
 * Configurations are immutable, and the parent screen owns vertical scrolling.
 * Reordering active DSP stages can click; the host may defer topology changes to
 * a silent block or fade the output before invoking moveSlot().
 *
 * Note: FxModuleConfig() is the canonical empty-slot marker. A bypassed module
 * normally retains its wet/depth/rate/stereo settings and stays configured.
 */
@Composable
fun ModularFxWorkbenchPanel(
    slots: List<FxModuleConfig>,
    onSlotChange: (Int, FxModuleConfig) -> Unit,
    onMoveSlot: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = slots.take(8)
    val total = ordered.size
    val modules = ordered.count(ModularFxUiModel::configured)
    val audible = ordered.count { ModularFxUiModel.configured(it) && it.enabled }
    val empty = ModularFxUiModel.firstFreeIndex(ordered)
    var selectedSlot by remember { mutableIntStateOf(0) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var typeMenuOpen by remember { mutableStateOf(false) }
    val currentIndex = selectedSlot.coerceIn(0, (total - 1).coerceAtLeast(0))
    val current = ordered.getOrNull(currentIndex)
    val configured = current?.let(ModularFxUiModel::configured) == true
    val canMove = remember { ValueAnimator.areAnimatorsEnabled() }
    var phase = 0f
    if (canMove && audible > 0) {
        val clock = rememberInfiniteTransition(label = "modular-fx-topology")
        val currentPhase by clock.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "configured-fx-routing",
        )
        phase = currentPhase
    }

    Column(
        modifier
            .testTag("modularFxWorkbenchPanel")
            .border(1.dp, OrbitalPalette.PanelEdge, RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF081326), Color(0xFF02040B), Color(0xFF081023)),
                ),
                RoundedCornerShape(20.dp),
            )
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "F X   /   L U M I N O U S   C H A I N",
                    color = OrbitalPalette.Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = .4.sp,
                )
                Text(
                    modules.toString() + " MODULES   •   " + audible + " ENGAGED   /   " + total + " SLOTS",
                    color = OrbitalPalette.Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
            Box {
                FxActionButton(
                    label = "+ ADD",
                    description = "Add an effect to the next free slot",
                    tint = OrbitalPalette.Teal,
                    tag = "fxAddButton",
                    enabled = empty >= 0,
                ) { addMenuOpen = true }
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false },
                    modifier = Modifier.background(OrbitalPalette.Midnight),
                ) {
                    FxModuleType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        ModularFxUiModel.title(type),
                                        color = OrbitalPalette.Text,
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        ModularFxUiModel.description(type),
                                        color = OrbitalPalette.Muted,
                                        fontSize = 10.sp,
                                    )
                                }
                            },
                            onClick = {
                                addMenuOpen = false
                                if (empty >= 0) {
                                    selectedSlot = empty
                                    onSlotChange(empty, ModularFxUiModel.newModule(type))
                                }
                            },
                            modifier = Modifier.testTag("fxAddType" + type.name),
                        )
                    }
                }
            }
        }

        FxSignalTopology(
            ordered = ordered,
            selectedSlot = currentIndex,
            phase = phase,
            modifier = Modifier.fillMaxWidth().height(158.dp),
        )
        Text(
            "LIVE PATCH TOPOLOGY  /  Glow traces configured effects, not an audio spectrum.",
            color = OrbitalPalette.Muted,
            fontSize = 9.sp,
        )

        Text(
            "ORDERED FX MODULES   /   SWIPE AND TAP TO EDIT",
            color = OrbitalPalette.Text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = .6.sp,
        )
        if (total == 0) {
            Text(
                "Connect a 1–8 slot ModularFxChain snapshot to enable editing.",
                color = OrbitalPalette.Gold,
                fontSize = 12.sp,
            )
        } else {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("fxOrderedModules"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ordered.forEachIndexed { index, slot ->
                    FxModuleCard(
                        index = index,
                        slot = slot,
                        selected = currentIndex == index,
                        onClick = { selectedSlot = index },
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth()
                    .border(1.dp, OrbitalPalette.Blue.copy(alpha = .6f), RoundedCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF0A1730), Color(0xFF07101D), Color(0xFF0B1324)),
                        ),
                        RoundedCornerShape(16.dp),
                    )
                    .padding(12.dp)
                    .testTag("fxSelectedEditor"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!configured || current == null) {
                    Text(
                        "SLOT " + (currentIndex + 1) + "  /  AVAILABLE",
                        color = OrbitalPalette.Gold,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Tap + ADD to install a processor. Each slot has independent DSP state.",
                        color = OrbitalPalette.Muted,
                        fontSize = 12.sp,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "SLOT " + (currentIndex + 1).toString().padStart(2, '0'),
                                color = OrbitalPalette.Gold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                ModularFxUiModel.title(current.type),
                                color = fxTint(current.type),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FxActionButton(
                            label = if (current.enabled) "BYPASS" else "ENGAGE",
                            description = "Toggle bypass for effect slot " + (currentIndex + 1),
                            tint = if (current.enabled) OrbitalPalette.Gold else OrbitalPalette.Teal,
                            tag = "fxBypassButton",
                        ) {
                            onSlotChange(currentIndex, current.copy(enabled = !current.enabled))
                        }
                    }

                    Text(
                        ModularFxUiModel.description(current.type),
                        color = OrbitalPalette.Muted,
                        fontSize = 11.sp,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            FxActionButton(
                                label = "TYPE  ▾",
                                description = "Select effect processor type",
                                tint = fxTint(current.type),
                                tag = "fxTypeSelector",
                            ) { typeMenuOpen = true }
                            DropdownMenu(
                                expanded = typeMenuOpen,
                                onDismissRequest = { typeMenuOpen = false },
                                modifier = Modifier.background(OrbitalPalette.Midnight),
                            ) {
                                FxModuleType.entries.forEach { type ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                ModularFxUiModel.title(type),
                                                color = fxTint(type),
                                            )
                                        },
                                        onClick = {
                                            typeMenuOpen = false
                                            onSlotChange(currentIndex, current.copy(type = type))
                                        },
                                        modifier = Modifier.testTag("fxType" + type.name),
                                    )
                                }
                            }
                        }
                        FxActionButton(
                            label = "◀",
                            description = "Move effect slot " + (currentIndex + 1) + " left",
                            tint = OrbitalPalette.Blue,
                            tag = "fxMoveLeft",
                            enabled = currentIndex > 0,
                        ) {
                            onMoveSlot(currentIndex, currentIndex - 1)
                            selectedSlot = currentIndex - 1
                        }
                        FxActionButton(
                            label = "▶",
                            description = "Move effect slot " + (currentIndex + 1) + " right",
                            tint = OrbitalPalette.Blue,
                            tag = "fxMoveRight",
                            enabled = currentIndex < total - 1,
                        ) {
                            onMoveSlot(currentIndex, currentIndex + 1)
                            selectedSlot = currentIndex + 1
                        }
                        FxActionButton(
                            label = "REMOVE",
                            description = "Remove effect at slot " + (currentIndex + 1),
                            tint = OrbitalPalette.Gold,
                            tag = "fxRemoveButton",
                        ) {
                            onSlotChange(currentIndex, FxModuleConfig())
                        }
                    }

                    FxParameterSlider(
                        title = "DRY / WET",
                        detail = ModularFxUiModel.wetDisplay(current.wet),
                        value = current.wet,
                        tint = OrbitalPalette.Cyan,
                        slot = currentIndex,
                        tag = "fxWetSlider",
                    ) { onSlotChange(currentIndex, current.copy(wet = it)) }

                    FxParameterSlider(
                        title = ModularFxUiModel.depthName(current.type),
                        detail = ModularFxUiModel.depthDisplay(current.type, current.depth),
                        value = current.depth,
                        tint = OrbitalPalette.Violet,
                        slot = currentIndex,
                        tag = "fxDepthSlider",
                    ) { onSlotChange(currentIndex, current.copy(depth = it)) }

                    FxParameterSlider(
                        title = ModularFxUiModel.rateName(current.type),
                        detail = ModularFxUiModel.rateDisplay(current.type, current.rate),
                        value = current.rate,
                        tint = OrbitalPalette.Teal,
                        slot = currentIndex,
                        tag = "fxRateSlider",
                        enabled = ModularFxUiModel.usesRate(current.type),
                    ) { onSlotChange(currentIndex, current.copy(rate = it)) }

                    FxParameterSlider(
                        title = ModularFxUiModel.stereoName(current.type),
                        detail = ModularFxUiModel.stereoDisplay(current.type, current.stereo),
                        value = current.stereo,
                        tint = OrbitalPalette.Gold,
                        slot = currentIndex,
                        tag = "fxStereoSlider",
                        enabled = ModularFxUiModel.usesStereo(current.type),
                    ) { onSlotChange(currentIndex, current.copy(stereo = it)) }

                    Text(
                        "Changes are sent to the host DSP snapshot. Reorder engaged modules only with a host-side fade or during silence.",
                        color = OrbitalPalette.Muted,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun FxModuleCard(
    index: Int,
    slot: FxModuleConfig,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val exists = ModularFxUiModel.configured(slot)
    val tint = if (exists) fxTint(slot.type) else OrbitalPalette.Muted
    Column(
        Modifier.width(139.dp).height(134.dp)
            .border(
                if (selected) 1.6.dp else .7.dp,
                if (selected) OrbitalPalette.Gold else tint.copy(alpha = .45f),
                RoundedCornerShape(13.dp),
            )
            .background(
                Brush.verticalGradient(
                    listOf(
                        tint.copy(alpha = if (exists && slot.enabled) .26f else .075f),
                        Color(0xFF020610),
                    ),
                ),
                RoundedCornerShape(13.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "Effect slot " + (index + 1) +
                    if (!exists) ", empty" else ", " + ModularFxUiModel.shortName(slot.type) +
                    if (slot.enabled) ", enabled" else ", bypassed"
            }
            .testTag("fxSlot" + index)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (index + 1).toString().padStart(2, '0'),
                color = if (selected) OrbitalPalette.Gold else OrbitalPalette.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Text(
                if (!exists) "EMPTY" else if (slot.enabled) "ON" else "BYPASS",
                color = if (slot.enabled && exists) OrbitalPalette.Teal else OrbitalPalette.Muted,
                fontSize = 9.sp,
            )
        }
        Text(
            if (exists) ModularFxUiModel.shortName(slot.type) else "＋",
            color = tint,
            fontSize = if (exists) 17.sp else 25.sp,
            fontWeight = FontWeight.Bold,
        )
        Column {
            Text(
                if (exists) "MIX  " + ModularFxUiModel.wetDisplay(slot.wet) else "FREE SLOT",
                color = OrbitalPalette.Text.copy(alpha = if (exists) .91f else .52f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Box(
                Modifier.fillMaxWidth().height(3.dp)
                    .background(OrbitalPalette.DeepBlue, RoundedCornerShape(4.dp)),
            ) {
                if (exists) {
                    Box(
                        Modifier.fillMaxWidth(ModularFxUiModel.sanitized(slot.wet).coerceAtLeast(.008f))
                            .height(3.dp)
                            .background(tint, RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun FxActionButton(
    label: String,
    description: String,
    tint: Color,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .border(.8.dp, tint.copy(alpha = if (enabled) .55f else .20f), RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = if (enabled) .11f else .03f), RoundedCornerShape(9.dp))
            .semantics { contentDescription = description }
            .testTag(tag),
    ) {
        Text(
            label,
            color = tint.copy(alpha = if (enabled) 1f else .35f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun FxParameterSlider(
    title: String,
    detail: String,
    value: Float,
    tint: Color,
    slot: Int,
    tag: String,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                title,
                color = OrbitalPalette.Text.copy(alpha = if (enabled) 1f else .4f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = .6.sp,
            )
            Text(
                detail,
                color = tint.copy(alpha = if (enabled) 1f else .42f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
        }
        Slider(
            value = ModularFxUiModel.sanitized(value),
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = title + " for effect slot " + (slot + 1) }
                .testTag(tag),
            colors = SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
                inactiveTrackColor = OrbitalPalette.DeepBlue,
            ),
        )
    }
}

@Composable
private fun FxSignalTopology(
    ordered: List<FxModuleConfig>,
    selectedSlot: Int,
    phase: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.testTag("modularFxSignalMap")) {
        val width = size.width
        val height = size.height
        if (width < 1f || height < 1f) return@Canvas
        val baseline = height * .57f
        val left = width * .025f
        val right = width * .975f
        val count = ordered.size
        val step = (right - left) / (count + 1).coerceAtLeast(1)
        val cyan = OrbitalPalette.Cyan
        val violet = OrbitalPalette.Violet
        val gold = OrbitalPalette.Gold
        val teal = OrbitalPalette.Teal
        val blue = OrbitalPalette.Blue
        drawRect(Color(0xFF030815))

        // These are deterministic CONFIGURATION cables, not microphone levels.
        val path = Path()
        path.moveTo(left, baseline)
        for (i in 0..count) {
            val nextX = left + step * (i + 1)
            val config = ordered.getOrNull(i)
            val energy = if (config != null && config.enabled &&
                ModularFxUiModel.configured(config)
            ) ModularFxUiModel.sanitized(config.wet) else 0f
            val arch = height * energy * .22f
            val priorX = nextX - step
            val modulation = sin(phase * (2f * PI).toFloat() + i * 1.4f) * arch * .28f
            path.cubicTo(
                priorX + step * .36f, baseline - arch - modulation,
                nextX - step * .36f, baseline + arch * .62f + modulation,
                nextX, baseline,
            )
        }
        drawPath(path, blue.copy(alpha = .095f), style = Stroke(width = 21f, cap = StrokeCap.Round))
        drawPath(path, violet.copy(alpha = .20f), style = Stroke(width = 8f, cap = StrokeCap.Round))
        drawPath(path, cyan.copy(alpha = .76f), style = Stroke(width = 1.7f, cap = StrokeCap.Round))

        // Topological nodes illuminate the selected effect and the real parameters.
        for (index in ordered.indices) {
            val config = ordered[index]
            val exists = ModularFxUiModel.configured(config)
            val active = exists && config.enabled
            val tint = if (exists) fxTint(config.type) else blue
            val center = Offset(left + step * (index + 1), baseline)
            val intensity = if (active) ModularFxUiModel.sanitized(config.wet) else 0f
            val r = 9f + intensity * 9f + if (index == selectedSlot) 4f else 0f
            drawCircle(
                tint.copy(alpha = if (active) .16f else .036f),
                radius = r * 3.1f,
                center = center,
            )
            drawCircle(
                tint.copy(alpha = if (active) .49f else .13f),
                radius = r * 1.45f,
                center = center,
                style = Stroke(width = if (index == selectedSlot) 1.8f else .8f),
            )
            drawCircle(
                if (index == selectedSlot) gold else tint,
                radius = if (active) 4.4f else 2.2f,
                center = center,
            )
            if (active) {
                val ring = Path()
                val samples = 24
                for (sample in 0..samples) {
                    val theta = sample * (2.0 * PI / samples)
                    val swell = 1.0 + .06 * sin(theta * (2.0 + config.rate.toDouble() * 6.0) +
                        phase.toDouble() * 2.0 * PI + index)
                    val rr = r * 2.1 * swell
                    val px = center.x + (cos(theta) * rr).toFloat()
                    val py = center.y + (sin(theta) * rr * .56).toFloat()
                    if (sample == 0) ring.moveTo(px, py) else ring.lineTo(px, py)
                }
                drawPath(
                    ring,
                    tint.copy(alpha = (.22f + intensity * .37f).coerceIn(0f, 1f)),
                    style = Stroke(width = 1.1f, cap = StrokeCap.Round),
                )
            }
        }

        // Gentle fixed stars add depth without hiding the black stage.
        for (dot in 0..31) {
            val x = (dot * 1171 % 1031).toFloat() / 1031f * width
            val y = (dot * 523 % 857).toFloat() / 857f * height
            drawCircle(
                (if (dot % 5 == 0) gold else teal).copy(alpha = .13f),
                radius = if (dot % 7 == 0) 1.1f else .55f,
                center = Offset(x, y),
            )
        }
    }
}

private fun fxTint(type: FxModuleType): Color = when (type) {
    FxModuleType.SATURATION -> OrbitalPalette.Gold
    FxModuleType.TREMOLO -> OrbitalPalette.Cyan
    FxModuleType.CHORUS -> OrbitalPalette.Violet
    FxModuleType.DELAY -> OrbitalPalette.Teal
    FxModuleType.REVERB -> Color(0xFF8AA8FF)
    FxModuleType.PHASER -> Color(0xFF60E4C8)
    FxModuleType.BITCRUSH -> Color(0xFFFF6D98)
}
