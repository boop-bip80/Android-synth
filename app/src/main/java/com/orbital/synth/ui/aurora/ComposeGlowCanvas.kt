package com.orbital.synth.ui.aurora

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import com.orbital.synth.core.stage.glow.GlowCanvas
import kotlin.math.roundToInt

/**
 * Draws the Aurora simulation through Compose. One instance is reused for every frame:
 * [bind] points it at the current [DrawScope], so the hot path allocates only the small
 * brush/colour values Compose itself needs. Text layouts are cached because laying text out
 * every frame would dominate the cost of the whole stage.
 */
internal class ComposeGlowCanvas(
    private val textMeasurer: TextMeasurer,
    private val density: Density,
) : GlowCanvas {
    private var scope: DrawScope? = null
    private val path = Path()
    private val textCache = HashMap<TextKey, TextLayoutResult>()

    private data class TextKey(val value: String, val sizePx: Int, val bold: Boolean)

    fun bind(drawScope: DrawScope) { scope = drawScope }

    fun unbind() { scope = null }

    override val width: Float get() = scope?.size?.width ?: 0f
    override val height: Float get() = scope?.size?.height ?: 0f

    private fun color(rgb: Int, alpha: Float): Color =
        Color(0xFF000000.toInt() or (rgb and 0xFFFFFF)).copy(alpha = alpha.coerceIn(0f, 1f))

    private fun blend(additive: Boolean): BlendMode = if (additive) BlendMode.Plus else BlendMode.SrcOver

    override fun line(
        x1: Float, y1: Float, x2: Float, y2: Float,
        rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean,
    ) {
        scope?.drawLine(
            color = color(rgb, alpha),
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
            blendMode = blend(additive),
        )
    }

    override fun polyline(
        xs: FloatArray, ys: FloatArray, count: Int,
        rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean,
    ) {
        if (count < 2) return
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until count) path.lineTo(xs[i], ys[i])
        scope?.drawPath(
            path = path,
            color = color(rgb, alpha),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = blend(additive),
        )
    }

    override fun circle(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, additive: Boolean) {
        if (radius <= 0f) return
        scope?.drawCircle(
            color = color(rgb, alpha),
            radius = radius,
            center = Offset(cx, cy),
            blendMode = blend(additive),
        )
    }

    override fun ring(
        cx: Float, cy: Float, radius: Float,
        rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean,
    ) {
        if (radius <= 0f) return
        scope?.drawCircle(
            color = color(rgb, alpha),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = strokeWidth),
            blendMode = blend(additive),
        )
    }

    override fun glow(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, additive: Boolean) {
        if (radius < 0.5f || alpha <= 0.002f) return
        val center = Offset(cx, cy)
        scope?.drawCircle(
            brush = Brush.radialGradient(
                0f to color(rgb, alpha),
                0.35f to color(rgb, alpha * 0.4f),
                1f to color(rgb, 0f),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
            blendMode = blend(additive),
        )
    }

    override fun band(
        left: Float, right: Float, cy: Float, halfHeight: Float,
        rgb: Int, alpha: Float, additive: Boolean,
    ) {
        if (halfHeight < 0.5f || alpha <= 0.002f || right <= left) return
        scope?.drawRect(
            brush = Brush.verticalGradient(
                0f to color(rgb, 0f),
                0.5f to color(rgb, alpha),
                1f to color(rgb, 0f),
                startY = cy - halfHeight,
                endY = cy + halfHeight,
            ),
            topLeft = Offset(left, cy - halfHeight),
            size = Size(right - left, halfHeight * 2f),
            blendMode = blend(additive),
        )
    }

    override fun vband(
        cx: Float, halfWidth: Float, top: Float, bottom: Float,
        rgb: Int, alpha: Float, additive: Boolean,
    ) {
        if (halfWidth < 0.5f || alpha <= 0.002f || bottom <= top) return
        scope?.drawRect(
            brush = Brush.horizontalGradient(
                0f to color(rgb, 0f),
                0.5f to color(rgb, alpha),
                1f to color(rgb, 0f),
                startX = cx - halfWidth,
                endX = cx + halfWidth,
            ),
            topLeft = Offset(cx - halfWidth, top),
            size = Size(halfWidth * 2f, bottom - top),
            blendMode = blend(additive),
        )
    }

    override fun text(
        value: String, x: Float, y: Float, sizePx: Float,
        rgb: Int, alpha: Float, bold: Boolean, align: Int,
    ) {
        val drawScope = scope ?: return
        if (value.isEmpty() || alpha <= 0.01f) return
        val key = TextKey(value, sizePx.roundToInt().coerceAtLeast(1), bold)
        var layout = textCache[key]
        if (layout == null) {
            if (textCache.size > 220) textCache.clear()
            layout = textMeasurer.measure(
                text = AnnotatedString(value),
                style = TextStyle(
                    color = Color.White,
                    // Pixel size converted to sp so labels keep their size whatever the font scale.
                    fontSize = with(density) { key.sizePx.toSp() },
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                ),
            )
            textCache[key] = layout
        }
        val textWidth = layout.size.width.toFloat()
        val left = when (align) {
            1 -> x - textWidth / 2f
            2 -> x - textWidth
            else -> x
        }
        drawScope.drawText(
            textLayoutResult = layout,
            color = color(rgb, 1f),
            topLeft = Offset(left, y - layout.firstBaseline),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }
}
