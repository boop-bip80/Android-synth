package com.orbital.synth.core.stage.glow

import com.orbital.synth.core.music.MusicalScales
import com.orbital.synth.core.stage.DiatonicGrid
import com.orbital.synth.core.stage.GuideTarget
import com.orbital.synth.core.stage.StageKey
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records and validates every draw call so rendering bugs fail on the JVM, not on a phone. */
private class RecordingCanvas(override val width: Float = 1080f, override val height: Float = 2000f) : GlowCanvas {
    var calls = 0
    var visibleCalls = 0
    val texts = ArrayList<String>()
    var rings = 0
    var verticalLines = 0
    var hash = 17L

    private fun check(alpha: Float, vararg values: Float) {
        assertTrue("alpha $alpha", alpha.isFinite() && alpha in -0.001f..1.001f)
        for (v in values) assertTrue("non-finite draw argument $v", v.isFinite())
        calls++
        if (alpha > 0.004f) visibleCalls++
        hash = hash * 31 + (alpha * 1000).toInt()
        for (v in values) hash = hash * 31 + (v * 10).toInt()
    }

    private fun rgb(value: Int) = assertTrue("rgb $value", value in 0..0xFFFFFF)

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean) {
        rgb(rgb); check(alpha, x1, y1, x2, y2, strokeWidth); assertTrue(strokeWidth > 0f)
        if (x1 == x2 && y1 != y2) verticalLines++
    }
    override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean) {
        rgb(rgb); assertTrue(count in 2..xs.size); check(alpha, strokeWidth, xs[0], ys[count - 1]); assertTrue(strokeWidth > 0f)
    }
    override fun circle(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, additive: Boolean) {
        rgb(rgb); check(alpha, cx, cy, radius); assertTrue("radius $radius", radius >= 0f)
    }
    override fun ring(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, strokeWidth: Float, additive: Boolean) {
        rgb(rgb); check(alpha, cx, cy, radius, strokeWidth); assertTrue(radius >= 0f); rings++
    }
    override fun glow(cx: Float, cy: Float, radius: Float, rgb: Int, alpha: Float, additive: Boolean) {
        rgb(rgb); check(alpha, cx, cy, radius); assertTrue("glow radius $radius", radius >= 0f)
    }
    override fun band(left: Float, right: Float, cy: Float, halfHeight: Float, rgb: Int, alpha: Float, additive: Boolean) {
        rgb(rgb); check(alpha, left, right, cy, halfHeight); assertTrue(halfHeight >= 0f)
    }
    override fun vband(cx: Float, halfWidth: Float, top: Float, bottom: Float, rgb: Int, alpha: Float, additive: Boolean) {
        rgb(rgb); check(alpha, cx, halfWidth, top, bottom); assertTrue(halfWidth >= 0f)
    }
    override fun text(value: String, x: Float, y: Float, sizePx: Float, rgb: Int, alpha: Float, bold: Boolean, align: Int) {
        rgb(rgb); check(alpha, x, y, sizePx); assertTrue(sizePx > 0f); assertTrue(align in 0..2); texts += value
    }
}

class AuroraSimTest {
    private val grid = DiatonicGrid(StageKey(0, MusicalScales.MAJOR), 2, 3)

    private fun sim(seed: Long = 5): AuroraSim =
        AuroraSim(seed).also { it.resize(1080f, 2000f, 2.6f); it.setGrid(grid) }

    private fun AuroraSim.settle(seconds: Float) {
        repeat((seconds * 60).toInt()) { step(1f / 60f) }
    }

    private fun draw(sim: AuroraSim): RecordingCanvas = RecordingCanvas().also { sim.draw(it) }

    @Test fun drawingBeforeAGridExistsIsANoOp() {
        val sim = AuroraSim()
        val canvas = RecordingCanvas()
        sim.draw(canvas)
        sim.step(0.016f)
        assertEquals(0, canvas.calls)
        assertFalse(sim.needsFrames)
    }

    @Test fun idleSurfaceIsVisibleAndAwake() {
        val sim = sim()
        sim.settle(1f)
        val canvas = draw(sim)
        assertTrue(canvas.visibleCalls > 40)
        assertTrue("every lane is labelled", canvas.texts.count { it == "C" } >= 3)
        assertTrue(sim.needsFrames)
    }

    @Test fun focusCanvasIsBlackUntilTouched() {
        val sim = sim()
        sim.idleVisibility = 0f
        sim.ambient = false
        sim.settle(1f)
        val idle = draw(sim)
        assertEquals("nothing may light the idle FOCUS canvas", 0, idle.visibleCalls)
        assertFalse(sim.needsFrames)

        sim.strike(1, 10, 0.5f, 0.4f, 0.8f, false, "E", "3rd")
        sim.settle(0.2f)
        val touched = draw(sim)
        assertTrue(touched.visibleCalls > 30)
        sim.release(1)
        sim.settle(6f)
        assertEquals("light fades back to pure black", 0, draw(sim).visibleCalls)
        assertFalse(sim.needsFrames)
    }

    @Test fun oneFingerOwnsOnePopupThatIsRewrittenNotStacked() {
        val sim = sim()
        sim.strike(1, 5, 0.5f, 0.5f, 0.7f, false, "FIRST", "1st")
        sim.settle(0.1f)
        sim.strike(1, 6, 0.5f, 0.45f, 0.7f, true, "SECOND", "2nd")
        sim.settle(0.1f)
        val canvas = draw(sim)
        assertEquals(0, canvas.texts.count { it == "FIRST" })
        assertEquals(1, canvas.texts.count { it == "SECOND" })
        // A second finger gets its own popup.
        sim.strike(2, 12, 0.2f, 0.3f, 0.7f, false, "THIRD", "x")
        sim.settle(0.05f)
        val both = draw(sim)
        assertEquals(1, both.texts.count { it == "SECOND" })
        assertEquals(1, both.texts.count { it == "THIRD" })
    }

    @Test fun chordsDrawAThreadThroughEverySoundingString() {
        val solo = sim()
        solo.strike(1, 7, 0.5f, 0.5f, 0.8f, false, "C", "1st")
        solo.settle(0.1f)
        val chord = sim()
        chord.strike(1, 7, 0.5f, 0.5f, 0.8f, false, "C", "I", intArrayOf(7, 9, 11))
        chord.settle(0.1f)
        assertTrue(draw(chord).verticalLines > draw(solo).verticalLines)
    }

    @Test fun reducedMotionDropsRipplesButKeepsFeedback() {
        fun ringsFor(reduced: Boolean): Int {
            val sim = sim().also { it.reducedMotion = reduced }
            sim.strike(1, 8, 0.5f, 0.5f, 1f, false, "D", "2nd")
            sim.settle(0.1f)
            return draw(sim).rings
        }
        assertTrue(ringsFor(true) < ringsFor(false))
        val sim = sim().also { it.reducedMotion = true }
        sim.strike(1, 8, 0.5f, 0.5f, 1f, false, "D", "2nd")
        sim.settle(0.1f)
        assertTrue("static highlight remains", draw(sim).visibleCalls > 30)
    }

    @Test fun guideShowsRibbonAndBeaconAndCelebrates() {
        val sim = sim()
        sim.setGuide(List(7) { GuideTarget(4 + it, 1f, it) }, 3)
        sim.settle(0.3f)
        val withGuide = draw(sim)
        val without = draw(sim().also { it.settle(0.3f) })
        assertTrue(withGuide.calls > without.calls)
        sim.guideHit(4, 3)
        sim.guideMiss(9)
        sim.celebrate(3)
        sim.settle(1.5f)
        assertTrue(draw(sim).visibleCalls > 40)
        sim.clearGuide()
        sim.settle(2f)
        assertTrue(draw(sim).calls > 0)
    }

    @Test fun keyChangeRecolorsEverythingAndStaysConsistent() {
        val sim = sim()
        sim.strike(1, 7, 0.5f, 0.5f, 0.8f, false, "C", "1st")
        val gMajor = DiatonicGrid(StageKey(7, MusicalScales.MAJOR), 2, 2) // different lane count too
        sim.setGrid(gMajor)
        assertEquals(gMajor.laneCount, sim.laneCount)
        sim.settle(0.5f)
        draw(sim)
        sim.release(1)
        sim.settle(1f)
        draw(sim)
    }

    @Test fun neighbouringKeysGetNeighbouringColoursAndAllKeysDiffer() {
        val roots = (0..11).map { AuroraPalette(StageKey(it, MusicalScales.MAJOR)).root }
        assertEquals(12, roots.toSet().size)
        // Circle-of-fifths neighbours (C->G, G->D) are closer in hue than C is to F#.
        fun hueDistance(a: Int, b: Int): Float {
            val d = Math.abs(AuroraPalette(StageKey(a, MusicalScales.MAJOR)).keyHue -
                AuroraPalette(StageKey(b, MusicalScales.MAJOR)).keyHue) % 360f
            return minOf(d, 360f - d)
        }
        assertTrue(hueDistance(0, 7) < hueDistance(0, 6))
        assertTrue(hueDistance(7, 2) < hueDistance(7, 1))
        val minor = AuroraPalette(StageKey(9, MusicalScales.NATURAL_MINOR))
        assertTrue(minor.minorish)
        assertFalse(AuroraPalette(StageKey(0, MusicalScales.MAJOR)).minorish)
    }

    @Test fun slowFramesDegradeQualityAndHealthyFramesRestoreIt() {
        val sim = sim()
        repeat(60) { sim.step(0.030f) }
        assertEquals(AuroraSim.Quality.LOW, sim.quality)
        repeat(400) { sim.step(0.0167f) }
        assertEquals(AuroraSim.Quality.HIGH, sim.quality)
    }

    @Test fun sameSeedAndScriptRenderIdentically() {
        fun run(): Long {
            val sim = sim(seed = 99)
            sim.strike(1, 6, 0.4f, 0.6f, 0.9f, false, "G", "5th", intArrayOf(6, 8, 10))
            repeat(30) { sim.move(1, 0.4f + it * 0.01f, 0.6f - it * 0.004f); sim.step(1f / 60f) }
            sim.strike(1, 9, 0.7f, 0.4f, 0.5f, true, "B", "7th")
            sim.settle(0.3f)
            return draw(sim).hash
        }
        assertEquals(run(), run())
    }

    /** 6 000 random operations including hostile values must never throw or emit an invalid draw. */
    @Test fun fuzzNeverEmitsInvalidDrawCallsOrCrashes() {
        val random = Random(1234)
        val sim = sim(seed = 3)
        val grids = listOf(
            grid,
            DiatonicGrid(StageKey(2, MusicalScales.DORIAN), 1, 2),
            DiatonicGrid(StageKey(9, MusicalScales.MINOR_PENTATONIC), 3, 3),
            DiatonicGrid(StageKey(5, MusicalScales.HARMONIC_MINOR), 2, 1),
        )
        val hostile = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -5f, 5f, 0f, 1f)
        fun f(): Float = if (random.nextInt(12) == 0) hostile[random.nextInt(hostile.size)] else random.nextFloat()
        repeat(6_000) { step ->
            val id = random.nextInt(12).toLong()
            when (random.nextInt(14)) {
                0, 1, 2 -> sim.strike(
                    id, random.nextInt(60) - 10, f(), f(), f(), random.nextBoolean(),
                    "L$step", "s", IntArray(random.nextInt(5)) { random.nextInt(40) - 5 },
                )
                3, 4, 5 -> sim.move(id, f(), f())
                6, 7 -> sim.release(id)
                8 -> if (random.nextInt(10) == 0) sim.cancelAll()
                9 -> sim.audio(f(), f(), f(), f(), f(), f())
                10 -> sim.setGuide(List(random.nextInt(10)) { GuideTarget(random.nextInt(40) - 5, f().coerceAtLeast(0.1f), it) }, random.nextInt(40))
                11 -> if (random.nextInt(25) == 0) sim.setGrid(grids[random.nextInt(grids.size)])
                12 -> if (random.nextInt(30) == 0) sim.celebrate(random.nextInt(5))
                else -> sim.guideHit(random.nextInt(50) - 5, random.nextInt(20))
            }
            sim.step(if (random.nextInt(20) == 0) f() else 1f / 60f)
            if (step % 7 == 0) draw(sim)
        }
        sim.cancelAll()
        sim.settle(8f)
        draw(sim)
    }
}
