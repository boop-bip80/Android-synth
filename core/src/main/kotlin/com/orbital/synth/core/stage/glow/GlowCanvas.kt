package com.orbital.synth.core.stage.glow

/**
 * The tiny drawing vocabulary of the Aurora stage. The simulation draws only through
 * this interface so the very same code renders on Android (Compose `DrawScope`) and
 * in the offline preview tool (Java2D). Colours are opaque `0xRRGGBB` ints plus a
 * separate alpha, which keeps the hot path free of colour objects.
 *
 * "Additive" drawing adds light to what is underneath (like neon in the dark) instead
 * of covering it; it is what makes overlapping glows bloom rather than muddy.
 */
interface GlowCanvas {
    val width: Float
    val height: Float

    /** Straight segment with round caps. */
    fun line(
        x1: Float, y1: Float, x2: Float, y2: Float,
        rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean = true,
    )

    /** Connected segments through the first [count] points, round joins and caps. */
    fun polyline(
        xs: FloatArray, ys: FloatArray, count: Int,
        rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean = true,
    )

    fun circle(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, additive: Boolean = true)

    fun ring(
        cx: Float, cy: Float, radius: Float,
        rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean = true,
    )

    /** Soft radial light: [alpha] at the centre fading smoothly to nothing at [radius]. */
    fun glow(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, additive: Boolean = true)

    /** Soft horizontal band centred on [cy]: [alpha] at the middle fading to zero at +-[halfHeight]. */
    fun band(
        left: Float, right: Float, cy: Float, halfHeight: Float,
        rgb: Int, alpha: Float, additive: Boolean = true,
    )

    /** Soft vertical band centred on [cx] spanning [top]..[bottom]: [alpha] in the middle, zero at +-[halfWidth]. */
    fun vband(
        cx: Float, halfWidth: Float, top: Float, bottom: Float,
        rgb: Int, alpha: Float, additive: Boolean = true,
    )

    /** Text anchored at its left edge ([align] = 0), centre (1) or right edge (2); y is the baseline. */
    fun text(
        value: String, x: Float, y: Float, sizePx: Float,
        rgb: Int, alpha: Float, bold: Boolean = false, align: Int = 0,
    )
}

/** Small colour helpers shared by the simulation and previews (no Android/Compose types). */
object GlowColor {
    fun rgb(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun red(rgb: Int): Int = (rgb shr 16) and 0xFF
    fun green(rgb: Int): Int = (rgb shr 8) and 0xFF
    fun blue(rgb: Int): Int = rgb and 0xFF

    /** Linear blend of two opaque colours; [t] is clamped to 0..1. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return rgb(
            (red(a) + (red(b) - red(a)) * k).toInt(),
            (green(a) + (green(b) - green(a)) * k).toInt(),
            (blue(a) + (blue(b) - blue(a)) * k).toInt(),
        )
    }

    /** HSL (hue in degrees, saturation and lightness 0..1) to opaque RGB. */
    fun hsl(hue: Float, saturation: Float, lightness: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return rgb(((r + m) * 255f + 0.5f).toInt(), ((g + m) * 255f + 0.5f).toInt(), ((b + m) * 255f + 0.5f).toInt())
    }

    const val WHITE = 0xFFFFFF
    const val GOLD = 0xFFC857
}
