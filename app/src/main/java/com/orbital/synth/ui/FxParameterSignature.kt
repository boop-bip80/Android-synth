package com.orbital.synth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbital.synth.core.dsp.processing.FxModuleConfig

/**
 * A glass viewport for *saved DSP configuration*, not captured audio.
 *
 * One deterministic model is built only when [config] changes. The Canvas
 * draws at most four precomputed 49-point curves and has no animation clock,
 * signal sampling, per-frame trigonometric loop or FFT.
 */
@Composable
internal fun FxParameterSignature(
    config: FxModuleConfig,
    modifier: Modifier,
    compact: Boolean,
    tag: String,
) {
    val schema = remember(config) { FxParameterSignatureModel.build(config) }
    val corner = RoundedCornerShape(if (compact) 8.dp else 16.dp)
    val engagedAlpha = if (schema.engaged) 1f else .28f
    Box(
        modifier
            .clip(corner)
            .border(
                if (compact) .7.dp else 1.dp,
                OrbitalPalette.Cyan.copy(alpha = if (schema.engaged) .38f else .16f),
                corner,
            )
            .background(
                Brush.verticalGradient(listOf(
                    Color(0xFF10223D), Color(0xFF040815), Color(0xFF070E21)
                ))
            )
            .then(
                if (compact) Modifier.clearAndSetSemantics { }
                else Modifier.semantics(mergeDescendants = true) {
                    contentDescription = schema.accessibleDescription
                }
            )
            .testTag(tag),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            if (w <= 1f || h <= 1f) return@Canvas
            val padX = if (compact) 5.dp.toPx() else 16.dp.toPx()
            val padY = if (compact) 3.dp.toPx() else 14.dp.toPx()
            val plotWidth = (w - padX * 2).coerceAtLeast(1f)
            val plotHeight = (h - padY * 2).coerceAtLeast(1f)

            // A shallow glass grid denotes normalized parameter space, not
            // a time-frequency spectrum or incoming audio signal.
            if (!compact) {
                for (line in 1..5) {
                    val y = h * line / 6f
                    drawLine(OrbitalPalette.Blue.copy(alpha = .11f),
                        Offset(padX, y), Offset(w - padX, y), strokeWidth = .6f)
                }
                for (line in 1..7) {
                    val x = w * line / 8f
                    drawLine(OrbitalPalette.Blue.copy(alpha = .07f),
                        Offset(x, padY), Offset(x, h - padY), strokeWidth = .6f)
                }
                drawCircle(
                    brush = Brush.radialGradient(listOf(
                        OrbitalPalette.Violet.copy(alpha = .12f * engagedAlpha),
                        Color.Transparent,
                    ), center = Offset(w * .65f, h * .52f), radius = w * .45f),
                    center = Offset(w * .65f, h * .52f), radius = w * .45f,
                )
            }

            schema.traces.forEach { trace ->
                val tint = when (trace.ink) {
                    FxSignatureInk.CYAN -> OrbitalPalette.Cyan
                    FxSignatureInk.VIOLET -> OrbitalPalette.Violet
                    FxSignatureInk.GOLD -> OrbitalPalette.Gold
                }
                val path = Path()
                trace.points.forEachIndexed { index, point ->
                    val x = padX + point.x * plotWidth
                    val y = padY + point.y * plotHeight
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, tint.copy(alpha = engagedAlpha * .13f),
                    style = Stroke(
                        width = if (compact) 6.dp.toPx() else 11.dp.toPx(),
                        cap = StrokeCap.Round,
                    ))
                drawPath(path, tint.copy(alpha = engagedAlpha * .30f),
                    style = Stroke(
                        width = if (compact) 2.6.dp.toPx() else 4.6.dp.toPx(),
                        cap = StrokeCap.Round,
                    ))
                drawPath(path, tint.copy(alpha = .15f + engagedAlpha * .78f),
                    style = Stroke(
                        width = if (compact) .9.dp.toPx() else 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    ))
            }
        }

        if (!compact) {
            Column(
                Modifier.align(Alignment.TopStart)
                    .fillMaxWidth().padding(horizontal = 13.dp, vertical = 8.dp),
            ) {
                Text(
                    "PARAMETER FIELD / " + schema.heading,
                    color = OrbitalPalette.Cyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    schema.conceptualLabel,
                    color = OrbitalPalette.Text.copy(alpha = .76f),
                    fontSize = 8.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (schema.engaged) "CONFIGURED / ENGAGED · NOT PCM"
                else "DRY OR BYPASSED · PARAMETER SCHEMATIC",
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                color = if (schema.engaged) OrbitalPalette.Gold else OrbitalPalette.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
