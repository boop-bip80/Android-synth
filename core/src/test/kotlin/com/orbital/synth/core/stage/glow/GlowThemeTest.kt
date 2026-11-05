package com.orbital.synth.core.stage.glow

import com.orbital.synth.core.music.MusicalScales
import com.orbital.synth.core.stage.StageKey
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlowThemeTest {
    /** Records every call so the wrapper's mapping can be asserted. */
    private class Recorder : GlowCanvas {
        override val width = 100f
        override val height = 200f
        class Call(val kind: String, val rgb: Int, val alpha: Float, val additive: Boolean)
        val calls = ArrayList<Call>()
        override fun line(x1: Float, y1: Float, x2: Float, y2: Float, rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean) { calls += Call("line", rgb, alpha, additive) }
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean) { calls += Call("polyline", rgb, alpha, additive) }
        override fun circle(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, additive: Boolean) { calls += Call("circle", rgb, alpha, additive) }
        override fun ring(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean) { calls += Call("ring", rgb, alpha, additive) }
        override fun glow(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, additive: Boolean) { calls += Call("glow", rgb, alpha, additive) }
        override fun band(left: Float, right: Float, cy: Float, halfHeight: Float, rgb: Int, alpha: Float, additive: Boolean) { calls += Call("band", rgb, alpha, additive) }
        override fun vband(cx: Float, halfWidth: Float, top: Float, bottom: Float, rgb: Int, alpha: Float, additive: Boolean) { calls += Call("vband", rgb, alpha, additive) }
        override fun text(value: String, x: Float, y: Float, sizePx: Float, rgb: Int, alpha: Float, bold: Boolean, align: Int) { calls += Call("text", rgb, alpha, false) }
    }

    private fun linear(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    private fun luminance(rgb: Int) =
        0.2126 * linear(GlowColor.red(rgb)) + 0.7152 * linear(GlowColor.green(rgb)) + 0.0722 * linear(GlowColor.blue(rgb))
    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = luminance(a).let { x -> luminance(b).let { y -> maxOf(x, y) to minOf(x, y) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun darkIsTheOriginalCanvasUntouchedAndLightWrapsIt() {
        val rec = Recorder()
        assertSame(rec, GlowTheme.DARK.wrap(rec))
        val light = GlowTheme.LIGHT.wrap(rec)
        assertEquals(100f, light.width, 0f)
        assertEquals(200f, light.height, 0f)
        assertEquals(0x000000, GlowTheme.DARK.backdropTop)
        assertTrue("normal mode has a pale backdrop", luminance(GlowTheme.LIGHT.backdropTop) > 0.8 && luminance(GlowTheme.LIGHT.backdropBottom) > 0.7)
    }

    @Test fun lightNeverAddsLightAndReInksEveryPrimitive() {
        val rec = Recorder()
        val c = GlowTheme.LIGHT.wrap(rec)
        val xs = floatArrayOf(0f, 1f); val ys = floatArrayOf(0f, 1f)
        c.line(0f, 0f, 1f, 1f, GlowColor.WHITE, 0.4f, 2f, additive = true)
        c.polyline(xs, ys, 2, GlowColor.GOLD, 0.4f, 2f, additive = true)
        c.circle(0f, 0f, 3f, 0x34DCFF, 0.4f, additive = true)
        c.ring(0f, 0f, 3f, 0x34DCFF, 0.4f, 1f, additive = true)
        c.glow(0f, 0f, 9f, 0x34DCFF, 0.4f, additive = true)
        c.band(0f, 5f, 2f, 3f, 0x34DCFF, 0.4f, additive = true)
        c.vband(2f, 3f, 0f, 9f, 0x34DCFF, 0.4f, additive = true)
        c.text("A", 0f, 0f, 12f, GlowColor.WHITE, 0.4f)
        assertEquals(8, rec.calls.size)
        for (call in rec.calls) {
            assertFalse("${call.kind} must not be additive on paper", call.additive)
            assertEquals("${call.kind} alpha", 0.5f, call.alpha, 1e-6f)
            assertEquals("${call.kind} ink", LightInk.ink(if (call.kind == "polyline") GlowColor.GOLD else if (call.kind in setOf("line", "text")) GlowColor.WHITE else 0x34DCFF), call.rgb)
        }
        // Alpha is clamped, never boosted past opaque.
        rec.calls.clear()
        c.circle(0f, 0f, 3f, 0x34DCFF, 0.95f, additive = true)
        assertEquals(1f, rec.calls.single().alpha, 0f)
    }

    @Test fun whiteHotHighlightsBecomeTheDarkestInkAndHuesKeepTheirCharacter() {
        assertEquals(LightInk.INK, LightInk.ink(GlowColor.WHITE))
        // Gold turns amber: still warm (red > green > blue) but much darker than the original.
        val amber = LightInk.ink(GlowColor.GOLD)
        assertTrue(GlowColor.red(amber) > GlowColor.green(amber) && GlowColor.green(amber) > GlowColor.blue(amber))
        assertTrue(luminance(amber) < luminance(GlowColor.GOLD) / 2)
        // Cyan stays cyan: blue and green dominate red.
        val cyan = LightInk.ink(0x34DCFF)
        assertTrue(GlowColor.red(cyan) < GlowColor.green(cyan) && GlowColor.red(cyan) < GlowColor.blue(cyan))
        // The whiter a colour is, the darker its ink: highlights are the strongest marks on paper.
        val plain = LightInk.ink(0x34DCFF)
        val halfWhite = LightInk.ink(GlowColor.mix(0x34DCFF, GlowColor.WHITE, 0.5f))
        assertTrue(luminance(halfWhite) < luminance(plain))
        // Pure black and grey inputs stay in range and do not divide by zero.
        for (grey in listOf(0x000000, 0x404040, 0x808080, 0xC0C0C0)) {
            val ink = LightInk.ink(grey)
            assertTrue(ink in 0..0xFFFFFF)
        }
    }

    @Test fun inkAlwaysStaysInRangeForTheWholeColourCube() {
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val ink = LightInk.ink(GlowColor.rgb(r, g, b))
            assertEquals("0x%06X".format(ink), ink and 0xFFFFFF, ink)
            // Never lighter than the paper: ink must stay a mark, not a highlight.
            assertTrue("0x%06X".format(ink), luminance(ink) < 0.30)
        }
    }

    @Test fun everyKeyColourIsLegibleOnPaperInBothModes() {
        var worst = Double.MAX_VALUE
        for (tonic in 0..11) for (scale in listOf(MusicalScales.MAJOR, MusicalScales.NATURAL_MINOR)) {
            val palette = AuroraPalette(StageKey(tonic, scale))
            for (rgb in listOf(palette.root, palette.fifth, palette.third, palette.color, palette.gold)) {
                for (backdrop in listOf(GlowTheme.LIGHT.backdropTop, GlowTheme.LIGHT.backdropBottom)) {
                    val ratio = contrast(LightInk.ink(rgb), backdrop)
                    worst = minOf(worst, ratio)
                    assertTrue("key $tonic ${scale.id} colour 0x%06X contrast %.2f".format(rgb, ratio), ratio >= 3.0)
                }
            }
        }
        println("worst light-mode ink contrast across all 12 keys: %.2f:1".format(worst))
    }
}
