package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One directly tappable, physically-sized saved modulation cable.
 *
 * Three static Bezier strokes give real configured depth a glass/luminescent
 * appearance. They intentionally do not move with a fictional FFT or LFO
 * position; only the supplied route state decides the color and intensity.
 */
@Composable
internal fun PatchCableTraceCard(
    trace: PatchCableTrace,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = when {
        trace.status == PatchCableTraceStatus.RESERVED -> OrbitalPalette.Gold
        trace.polarity == PatchCablePolarity.INVERTED -> OrbitalPalette.Violet
        trace.polarity == PatchCablePolarity.ZERO -> OrbitalPalette.Gold
        else -> OrbitalPalette.Cyan
    }
    val activity = when (trace.status) {
        PatchCableTraceStatus.PATCHED -> 1f
        PatchCableTraceStatus.DORMANT_SOURCE -> .35f
        PatchCableTraceStatus.ZERO_DEPTH -> .24f
        PatchCableTraceStatus.RESERVED -> .24f
        PatchCableTraceStatus.BYPASSED -> .14f
    }
    val corner = RoundedCornerShape(13.dp)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .border(1.dp,
                if (selected) OrbitalPalette.Gold.copy(alpha = .88f)
                else tint.copy(alpha = .30f * activity + .17f),
                corner)
            .clip(corner)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1B2B), Color(0xFF030915), Color(0xFF060E1C))
                )
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = trace.accessibleLabel
            }
            .testTag("patchRoute" + trace.slot),
    ) {
        Canvas(Modifier.matchParentSize().testTag("patchCableTrace" + trace.slot)) {
            val from = Offset(12.dp.toPx(), size.height * .68f)
            val to = Offset(size.width - 12.dp.toPx(), size.height * .33f)
            val cable = Path().apply {
                moveTo(from.x, from.y)
                cubicTo(size.width * .36f, size.height * .30f,
                    size.width * .62f, size.height * .82f, to.x, to.y)
            }
            // The halo is configuration-dependent, not audio-reactive.
            val glow = activity * (.28f + trace.strength * .72f)
            drawPath(cable, tint.copy(alpha = glow * .10f),
                style = Stroke(width = 15.dp.toPx(), cap = StrokeCap.Round))
            drawPath(cable, tint.copy(alpha = glow * .34f),
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
            drawPath(cable, tint.copy(alpha = glow * .90f),
                style = Stroke(width = if (selected) 1.8.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round))
            drawCircle(tint.copy(alpha = activity * .82f),
                radius = if (selected) 4.5.dp.toPx() else 3.dp.toPx(),
                center = from)
            drawCircle(if (selected) OrbitalPalette.Gold else tint.copy(alpha = activity),
                radius = if (selected) 4.5.dp.toPx() else 3.dp.toPx(),
                center = to)
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Row {
                Text("C" + trace.number,
                    color = if (selected) OrbitalPalette.Gold else tint,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
                Text(trace.source.name,
                    color = OrbitalPalette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Text("→ " + trace.destination.name + " / " + trace.curve.name,
                color = OrbitalPalette.Text.copy(alpha = .91f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace)
            Row {
                Text(trace.depthCaption + "  " + trace.polarity.caption,
                    color = tint.copy(alpha = .65f + activity * .35f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
            }
            Text(trace.status.caption,
                color = if (trace.status == PatchCableTraceStatus.PATCHED)
                    OrbitalPalette.Teal else OrbitalPalette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 7.5.sp)
        }
    }
}
