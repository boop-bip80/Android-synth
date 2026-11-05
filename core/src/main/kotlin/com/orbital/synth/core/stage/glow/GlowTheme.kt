package com.orbital.synth.core.stage.glow

import kotlin.math.max
import kotlin.math.min

/**
 * The world the stage glows in.
 *
 *  - [DARK] is the original look: coloured light *added* to black, white-hot highlights.
 *  - [LIGHT] ("normal mode") keeps the same simulation but turns it into ink on paper: the
 *    neon hues become deep saturated inks, white-hot cores become the darkest ink, and light
 *    is laid down with ordinary alpha instead of being added (adding light to a pale backdrop
 *    would simply vanish).
 *
 * The simulation is untouched. It still draws only through [GlowCanvas]; a theme just wraps the
 * canvas, so Android (Compose) and the Java2D preview tool both get it for free.
 */
enum class GlowTheme(
    /** Opaque `0xRRGGBB` at the top and bottom of the stage background. */
    val backdropTop: Int,
    val backdropBottom: Int,
) {
    DARK(0x000000, 0x000000),
    LIGHT(0xF7F4EC, 0xE4EBF3);

    /** [canvas] itself for [DARK]; a canvas that re-inks everything for [LIGHT]. */
    fun wrap(canvas: GlowCanvas): GlowCanvas = if (this == DARK) canvas else LightGlowCanvas(canvas)
}

/** Colour and alpha mapping from the neon vocabulary to inks on a pale background. */
object LightInk {
    /** The darkest ink: what white-hot highlights become. */
    const val INK = 0x101B33

    /** Highest channel (0..255) a fully saturated ink may reach. */
    private const val VALUE = 168f

    /**
     * Perceived brightness (Rec. 601 luma, 0..255) an ink may not exceed. Greens and yellows are
     * perceptually much brighter than blues at the same channel value, so without this cap they
     * would be too faint on paper.
     */
    private const val LUMA = 88f

    /** Fraction of a colour's white content that is stripped to make the ink more saturated. */
    private const val STRIP = 0.75f

    /** Light has to be laid down a little stronger on paper than added on black. */
    private const val ALPHA_GAIN = 1.25f

    /**
     * Allocation-free: pure hues keep their hue at a deep, saturated value of roughly equal
     * perceived darkness (cyan stays cyan, gold becomes amber); the whiter a colour is, the more
     * it slides to [INK].
     */
    fun ink(rgb: Int): Int {
        val r = GlowColor.red(rgb)
        val g = GlowColor.green(rgb)
        val b = GlowColor.blue(rgb)
        val hi = max(r, max(g, b)).toFloat()
        val lo = min(r, min(g, b)).toFloat()
        val strip = lo * STRIP
        val stripped = 0.30f * (r - strip) + 0.59f * (g - strip) + 0.11f * (b - strip)
        val scale = min(VALUE / max(hi - strip, 1f), LUMA / max(stripped, 1f))
        var nr = (r - strip) * scale
        var ng = (g - strip) * scale
        var nb = (b - strip) * scale
        // Smoothstep from "mostly colour" to "mostly white" decides how far toward ink to go.
        val white = lo / 255f
        val t = ((white - 0.35f) / 0.55f).coerceIn(0f, 1f)
        val toInk = t * t * (3f - 2f * t)
        nr += (GlowColor.red(INK) - nr) * toInk
        ng += (GlowColor.green(INK) - ng) * toInk
        nb += (GlowColor.blue(INK) - nb) * toInk
        return GlowColor.rgb(nr.toInt(), ng.toInt(), nb.toInt())
    }

    fun alpha(value: Float): Float = (value * ALPHA_GAIN).coerceIn(0f, 1f)
}

/** Draws the simulation's light as ink: see [GlowTheme.LIGHT]. */
class LightGlowCanvas(private val target: GlowCanvas) : GlowCanvas {
    override val width: Float get() = target.width
    override val height: Float get() = target.height

    override fun line(
        x1: Float, y1: Float, x2: Float, y2: Float,
        rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean,
    ) = target.line(x1, y1, x2, y2, LightInk.ink(rgb), LightInk.alpha(alpha), strokeWidth, false)

    override fun polyline(
        xs: FloatArray, ys: FloatArray, count: Int,
        rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean,
    ) = target.polyline(xs, ys, count, LightInk.ink(rgb), LightInk.alpha(alpha), strokeWidth, false)

    override fun circle(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, additive: Boolean) =
        target.circle(cx, cy, radius, LightInk.ink(rgb), LightInk.alpha(alpha), false)

    override fun ring(
        cx: Float, cy: Float, radius: Float,
        rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean,
    ) = target.ring(cx, cy, radius, LightInk.ink(rgb), LightInk.alpha(alpha), strokeWidth, false)

    override fun glow(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, additive: Boolean) =
        target.glow(cx, cy, radius, LightInk.ink(rgb), LightInk.alpha(alpha), false)

    override fun band(
        left: Float, right: Float, cy: Float, halfHeight: Float,
        rgb: Int, alpha: Float, additive: Boolean,
    ) = target.band(left, right, cy, halfHeight, LightInk.ink(rgb), LightInk.alpha(alpha), false)

    override fun vband(
        cx: Float, halfWidth: Float, top: Float, bottom: Float,
        rgb: Int, alpha: Float, additive: Boolean,
    ) = target.vband(cx, halfWidth, top, bottom, LightInk.ink(rgb), LightInk.alpha(alpha), false)

    override fun text(
        value: String, x: Float, y: Float, sizePx: Float,
        rgb: Int, alpha: Float, bold: Boolean, align: Int,
    ) = target.text(value, x, y, sizePx, LightInk.ink(rgb), LightInk.alpha(alpha), bold, align)
}
