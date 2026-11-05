package com.orbital.synth.preview

import com.orbital.synth.core.music.MusicalScales
import com.orbital.synth.core.stage.ChordAssist
import com.orbital.synth.core.stage.DiatonicGrid
import com.orbital.synth.core.stage.GuideSession
import com.orbital.synth.core.stage.SongBook
import com.orbital.synth.core.stage.StageKey
import com.orbital.synth.core.stage.StageSurface
import com.orbital.synth.core.stage.SurfaceEvent
import com.orbital.synth.core.stage.glow.AuroraSim
import com.orbital.synth.core.stage.glow.GlowTheme
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sin

private const val W = 1080
private const val H = 2100
private const val DENSITY = 2.625f
private const val FRAME = 1f / 60f

private fun ordinal(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

/** Drives the real StageSurface + AuroraSim exactly like the Android host does. */
private class Rig(key: StageKey, octaves: Int = 3, chord: ChordAssist = ChordAssist.OFF, width: Int = W, height: Int = H) {
    val grid = DiatonicGrid(key, 2, octaves)
    val surface = StageSurface(grid).also { it.configure(chord = chord) }
    val sim = AuroraSim(seed = 11).also {
        it.resize(width.toFloat(), height.toFloat(), DENSITY)
        it.setGrid(grid)
    }
    var clockMs = 0L
    val notes = ArrayList<Int>()

    private fun route(events: List<SurfaceEvent>) {
        for (e in events) when (e) {
            is SurfaceEvent.Strike -> sim.strike(
                e.pointerId, e.lane, e.x, e.y, e.velocity, e.slid,
                e.chordSymbol, e.roman.ifEmpty { ordinal(grid.degreeNumber(e.lane)) }, e.lanes,
            )
            is SurfaceEvent.Release -> sim.release(e.pointerId)
            is SurfaceEvent.NoteOn -> notes += e.note
            else -> Unit
        }
    }

    fun down(id: Long, x: Float, y: Float) { route(surface.touchDown(id, x, y, 1f, clockMs)) }
    fun move(id: Long, x: Float, y: Float) { sim.move(id, x, y); route(surface.touchMove(id, x, y, 1f, clockMs)) }
    fun up(id: Long) = route(surface.touchUp(id))

    fun run(seconds: Float, perFrame: ((Float) -> Unit)? = null) {
        val frames = (seconds / FRAME).toInt()
        for (i in 0 until frames) {
            clockMs += 16
            perFrame?.invoke(i * FRAME)
            sim.step(FRAME)
        }
    }

    fun render(file: File, backdrop: Boolean = true, theme: GlowTheme = GlowTheme.DARK) {
        val image = BufferedImage(sim.let { W }, H, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        if (theme == GlowTheme.DARK) {
            g.color = Color.BLACK
        } else {
            g.paint = GradientPaint(0f, 0f, Color(theme.backdropTop), 0f, H.toFloat(), Color(theme.backdropBottom))
        }
        g.fillRect(0, 0, W, H)
        g.dispose()
        val raw = Java2dGlowCanvas(image)
        if (backdrop && theme == GlowTheme.DARK) {
            // A hint of the audio-reactive field behind the lanes (approximation only).
            raw.glow(W * 0.5f, H * 0.45f, 700f, 0x3A2A8C, 0.28f)
        }
        // The same simulation, drawn through the theme exactly as the Android app does.
        sim.draw(theme.wrap(raw))
        raw.dispose()
        ImageIO.write(image, "png", file)
    }

    /** `name.png` in the dark world and `name-light.png` in normal (light) mode. */
    fun renderBoth(dir: File, name: String) {
        render(File(dir, "$name.png"))
        render(File(dir, "$name-light.png"), theme = GlowTheme.LIGHT)
    }
}

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/frames").also { it.mkdirs() }
    val cMajor = StageKey(0, MusicalScales.MAJOR)

    // 1. Untouched: the surface should already feel awake.
    Rig(cMajor).apply { run(2.5f); renderBoth(out, "01-idle-c-major") }

    // 2. A melodic slide: finger drags up through the lanes, leaving a comet and sparks.
    Rig(StageKey(2, MusicalScales.MAJOR)).apply {
        down(1, .3f, .82f)
        run(.35f)
        var y = .82f
        run(.6f) { t -> y = .82f - t * .55f; move(1, .3f + t * .5f, y) }
        renderBoth(out, "02-slide-d-major")
    }

    // 3. Three-finger chord with triad assist, struck a moment ago.
    Rig(StageKey(9, MusicalScales.NATURAL_MINOR), chord = ChordAssist.TRIAD).apply {
        down(1, .25f, .62f); down(2, .55f, .45f); down(3, .8f, .72f)
        run(.22f)
        renderBoth(out, "03-chord-a-minor")
    }

    // 4. Follow the light: Ode to Joy ribbon with the player about to hit the first note.
    Rig(StageKey(7, MusicalScales.MAJOR)).apply {
        val ode = SongBook.byId("ode")!!
        val session = GuideSession(ode, grid)
        sim.setGuide(session.upcoming(7), 0)
        down(1, .62f, grid.centerY(session.target()!!.lane) + 0.01f)
        run(.3f)
        renderBoth(out, "04-guide-ode-g-major")
        // ...and after three correct notes with a combo.
        repeat(3) {
            val target = session.target()!!
            session.onStrike(target.lane)
            sim.guideHit(target.lane, session.combo)
            sim.setGuide(session.upcoming(7), session.combo)
            val next = session.target()!!
            move(1, .55f + it * .1f, grid.centerY(next.lane))
            run(.25f)
        }
        renderBoth(out, "05-guide-after-hits")
    }

    // 5. All twelve keys: palette check (each a half-size crop of the idle surface).
    for (tonic in 0..11) {
        Rig(StageKey(tonic, if (tonic % 2 == 0) MusicalScales.MAJOR else MusicalScales.NATURAL_MINOR)).apply {
            down(1, .5f, .5f); run(.25f)
            renderBoth(out, "key-%02d".format(tonic))
        }
    }

    // 5b. FOCUS canvas: pure black until touched, then light gathers around the finger.
    Rig(StageKey(4, MusicalScales.DORIAN)).apply {
        sim.idleVisibility = 0f; sim.ambient = false
        run(1f); render(File(out, "08-focus-idle-black.png"), backdrop = false)
        down(1, .35f, .55f); run(.3f)
        var y = .55f
        run(.4f) { t -> y = .55f - t * .3f; move(1, .35f + t * .4f, y) }
        render(File(out, "09-focus-touched.png"), backdrop = false)
    }

    // 6. Celebration finale.
    Rig(cMajor).apply { sim.celebrate(3); run(1.1f); renderBoth(out, "06-celebrate") }

    // 7. Dense play: both hands, a chord and a fast run.
    Rig(StageKey(5, MusicalScales.MINOR_PENTATONIC), chord = ChordAssist.POWER).apply {
        down(1, .2f, .9f); down(2, .7f, .3f)
        run(.3f) { t -> move(2, .7f - t * .3f, .3f + sin(t * 20f) * .05f) }
        renderBoth(out, "07-dense-f-minor-pent")
    }
    println("rendered to ${out.absolutePath}")
}
