package com.orbital.synth.preview

import com.orbital.synth.core.stage.glow.GlowCanvas
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Composite
import java.awt.CompositeContext
import java.awt.Font
import java.awt.LinearGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.awt.image.Raster
import java.awt.image.WritableRaster

/** Adds light to the destination, like neon in the dark (src.rgb * src.alpha * alpha + dst). */
private class AdditiveComposite(private val alpha: Float) : Composite {
    override fun createContext(src: ColorModel, dst: ColorModel, hints: RenderingHints?): CompositeContext =
        object : CompositeContext {
            override fun dispose() {}
            override fun compose(srcIn: Raster, dstIn: Raster, dstOut: WritableRaster) {
                val w = minOf(srcIn.width, dstIn.width)
                val h = minOf(srcIn.height, dstIn.height)
                val s = IntArray(4)
                val d = IntArray(4)
                for (y in 0 until h) for (x in 0 until w) {
                    srcIn.getPixel(x, y, s)
                    dstIn.getPixel(x, y, d)
                    // Source pixels are non-premultiplied (r, g, b, a).
                    val k = (if (s.size > 3) s[3] / 255f else 1f) * alpha
                    d[0] = minOf(255, d[0] + (s[0] * k).toInt())
                    d[1] = minOf(255, d[1] + (s[1] * k).toInt())
                    d[2] = minOf(255, d[2] + (s[2] * k).toInt())
                    if (d.size > 3) d[3] = 255
                    dstOut.setPixel(x, y, d)
                }
            }
        }
}

/** Offline [GlowCanvas]: the same drawing vocabulary the Android app implements with Compose. */
class Java2dGlowCanvas(private val image: BufferedImage) : GlowCanvas {
    private val g = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }
    override val width: Float = image.width.toFloat()
    override val height: Float = image.height.toFloat()
    private val path = Path2D.Float()

    private fun color(rgb: Int, alpha: Float) =
        Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt())

    private fun compose(additive: Boolean) {
        g.composite = if (additive) AdditiveComposite(1f) else AlphaComposite.SrcOver
    }

    fun dispose() = g.dispose()

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean) {
        compose(additive)
        g.color = color(rgb, alpha)
        g.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2)
        g.draw(path)
    }

    override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean) {
        if (count < 2) return
        compose(additive)
        g.color = color(rgb, alpha)
        g.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        path.reset(); path.moveTo(xs[0], ys[0])
        for (i in 1 until count) path.lineTo(xs[i], ys[i])
        g.draw(path)
    }

    override fun circle(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, additive: Boolean) {
        compose(additive)
        g.color = color(rgb, alpha)
        g.fill(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2))
    }

    override fun ring(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean) {
        compose(additive)
        g.color = color(rgb, alpha)
        g.stroke = BasicStroke(strokeWidth)
        g.draw(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2))
    }

    override fun glow(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, additive: Boolean) {
        if (radius < 0.5f || alpha <= 0.001f) return
        compose(additive)
        g.paint = RadialGradientPaint(
            Point2D.Float(cx, cy), radius, floatArrayOf(0f, 0.35f, 1f),
            arrayOf(color(rgb, alpha), color(rgb, alpha * 0.4f), color(rgb, 0f)),
        )
        g.fill(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2))
    }

    override fun band(left: Float, right: Float, cy: Float, halfHeight: Float, rgb: Int, alpha: Float, additive: Boolean) {
        if (halfHeight < 0.5f || alpha <= 0.001f) return
        compose(additive)
        g.paint = LinearGradientPaint(
            Point2D.Float(0f, cy - halfHeight), Point2D.Float(0f, cy + halfHeight),
            floatArrayOf(0f, 0.5f, 1f), arrayOf(color(rgb, 0f), color(rgb, alpha), color(rgb, 0f)),
        )
        g.fill(Rectangle2D.Float(left, cy - halfHeight, right - left, halfHeight * 2))
    }

    override fun vband(cx: Float, halfWidth: Float, top: Float, bottom: Float, rgb: Int, alpha: Float, additive: Boolean) {
        if (halfWidth < 0.5f || alpha <= 0.001f) return
        compose(additive)
        g.paint = LinearGradientPaint(
            Point2D.Float(cx - halfWidth, 0f), Point2D.Float(cx + halfWidth, 0f),
            floatArrayOf(0f, 0.5f, 1f), arrayOf(color(rgb, 0f), color(rgb, alpha), color(rgb, 0f)),
        )
        g.fill(Rectangle2D.Float(cx - halfWidth, top, halfWidth * 2, bottom - top))
    }

    override fun text(value: String, x: Float, y: Float, sizePx: Float, rgb: Int, alpha: Float, bold: Boolean, align: Int) {
        compose(false)
        g.font = Font("DejaVu Sans", if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(sizePx)
        g.color = color(rgb, alpha)
        val wText = g.fontMetrics.stringWidth(value)
        val left = when (align) { 1 -> x - wText / 2f; 2 -> x - wText; else -> x }
        g.drawString(value, left, y)
    }
}
