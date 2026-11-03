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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RailVoid = Color(0xFF01050F)
private val RailGlass = Color(0xDB07142B)
private val RailViolet = Color(0xFFBE88FF)
private val RailCyan = Color(0xFF73F8F2)
private val RailGold = Color(0xFFF1C97E)

/**
 * Purely visual, height-neutral control to choose a real XY audio destination.
 *
 * Place this AFTER the live orb/ribbon inside their existing Box, with
 * Modifier.fillMaxSize(). It draws a slim touch target at the left edge of
 * the ribbon. Its six 48dp mode targets exist only while its in-stage palette
 * is expanded, so the normal orb, waveform and anchored keys retain their
 * original geometry and almost all their continuous touch area.
 *
 * [onBeforeOpen] and [onBeforeSelect] let the host invalidate BOTH underlying
 * XY recognizers synchronously before a mode/menu transition. The host still
 * owns the actual [ReferenceXyMode] and [ReferenceXyMode.resolveFallback].
 * A held finger must never be repurposed into a new mode: hosts remount
 * recognizers and admit ONLY a genuine fresh-down pointer after invalidation.
 *
 * There are NO fake waveforms, parameter writes, modulation cables or PAN
 * assignments here. Only the supplied callback changes a mode.
 */
@Composable
fun ReferenceXyModeRail(
    selectedMode: ReferenceXyMode,
    normalizedEngine: Float,
    onModeSelected: (ReferenceXyMode) -> Unit,
    modifier: Modifier = Modifier,
    ribbonHeight: Dp = 88.dp,
    onBeforeOpen: () -> Unit = {},
    onBeforeSelect: () -> Unit = {},
) {
    val activeMode = selectedMode.resolveFallback(normalizedEngine)
    var expanded by remember { mutableStateOf(false) }
    val railShape = RoundedCornerShape(12.dp)
    val menuShape = RoundedCornerShape(15.dp)

    Box(modifier.testTag("referenceXyModeRail")) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 7.dp, bottom = 7.dp)
                .width(56.dp)
                .height((ribbonHeight - 14.dp).coerceAtLeast(48.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(RailViolet.copy(alpha = .30f), RailGlass, RailCyan.copy(alpha = .20f)),
                    ),
                    railShape,
                )
                .border(1.dp, RailCyan.copy(alpha = .74f), railShape)
                .clickable(role = Role.Button) {
                    if (expanded) {
                        expanded = false
                    } else {
                        onBeforeOpen()
                        expanded = true
                    }
                }
                .semantics {
                    contentDescription =
                        "Choose XY routing mode. Current: ${activeMode.title}. " +
                        "Six modes route touch to real synthesizer controls."
                    stateDescription = if (expanded) "Modes open" else "Modes closed"
                }
                .testTag("referenceXyModeButton"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("XY", color = RailGold, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp)
                Text(
                    when (activeMode) {
                        ReferenceXyMode.PERFORMANCE -> "PERF"
                        ReferenceXyMode.FILTER -> "FILT"
                        ReferenceXyMode.CORE_VIBRATO -> "VIB"
                        ReferenceXyMode.WAVE_SHAPE -> "WAVE"
                        ReferenceXyMode.FX_SPACE -> "FX"
                        ReferenceXyMode.FM_COLOR -> "FM"
                    },
                    color = RailCyan, fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (expanded) {
            // In-tree overlay rather than a new Android popup/window: no
            // focus-stealing, expensive keyboard resize or Stage render break.
            // A tap outside the palette closes it.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(RailVoid.copy(alpha = .16f))
                    .clickable(role = Role.Button) { expanded = false }
                    .semantics { contentDescription = "Dismiss XY routing mode choices" }
                    .testTag("referenceXyModeBackdrop"),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 65.dp, end = 7.dp, bottom = 7.dp)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xF0081630), Color(0xF4020716))),
                        menuShape,
                    )
                    .border(1.dp, RailViolet.copy(alpha = .62f), menuShape)
                    .padding(4.dp)
                    .testTag("referenceXyModePalette"),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                ReferenceXyMode.entries.chunked(2).forEach { rowModes ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        rowModes.forEach { mode ->
                            val available = mode.enabledForEngine(normalizedEngine)
                            val chosen = mode == activeMode
                            val tile = RoundedCornerShape(8.dp)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .background(
                                        if (chosen) RailViolet.copy(alpha = .25f)
                                        else RailGlass.copy(alpha = .94f),
                                        tile,
                                    )
                                    .border(
                                        1.dp,
                                        when {
                                            chosen -> RailGold.copy(alpha = .88f)
                                            available -> RailCyan.copy(alpha = .38f)
                                            else -> Color.Gray.copy(alpha = .17f)
                                        },
                                        tile,
                                    )
                                    .selectable(
                                        selected = chosen,
                                        enabled = available,
                                        role = Role.RadioButton,
                                        onClick = {
                                            onBeforeSelect()
                                            onModeSelected(mode)
                                            expanded = false
                                        },
                                    )
                                    .semantics {
                                        contentDescription =
                                            "${mode.title} XY routing: horizontal ${mode.xLabel}; " +
                                            "vertical ${mode.yLabel}. ${mode.guidance}"
                                        if (!available) {
                                            stateDescription = "Unavailable on current engine"
                                        }
                                    }
                                    .testTag("referenceXyMode${mode.name}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    mode.title,
                                    color = when {
                                        !available -> Color(0xFF687189)
                                        chosen -> RailGold
                                        else -> RailCyan
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
