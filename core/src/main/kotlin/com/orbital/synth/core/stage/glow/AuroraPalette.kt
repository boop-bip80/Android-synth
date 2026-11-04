package com.orbital.synth.core.stage.glow

import com.orbital.synth.core.stage.DiatonicGrid
import com.orbital.synth.core.stage.LaneRole
import com.orbital.synth.core.stage.StageKey

/**
 * Every key has its own colour. Hues follow the circle of fifths, so neighbouring
 * keys glow in neighbouring colours (C is cyan, G sky-blue, D indigo ... F aqua) and
 * modulating by a fifth feels like a gentle colour shift rather than a jump.
 * Minor-flavoured modes glow a little deeper and cooler than their bright siblings.
 */
class AuroraPalette(val key: StageKey) {
    /** True for scales whose third is minor: they get the deeper, moodier treatment. */
    val minorish: Boolean = 3 in key.degrees

    val keyHue: Float = run {
        val fifths = (key.tonic * 7) % 12
        HUES[fifths] + if (minorish) -10f else 0f
    }

    private val lightness: Float = if (minorish) 0.64f else 0.68f

    val root: Int = GlowColor.hsl(keyHue, 0.95f, lightness)
    val fifth: Int = GlowColor.hsl(keyHue + 34f, 0.9f, lightness)
    val third: Int = GlowColor.hsl(keyHue - 34f, 0.9f, lightness)
    val color: Int = GlowColor.hsl(keyHue + 78f, 0.55f, 0.66f)

    /** A warm accent used for guidance, combos and celebration. */
    val gold: Int = GlowColor.GOLD

    /** Faint wash for the whole field, tinted by the key. */
    val ambient: Int = GlowColor.hsl(keyHue, 0.7f, 0.5f)

    fun forRole(role: LaneRole): Int = when (role) {
        LaneRole.ROOT -> root
        LaneRole.FIFTH -> fifth
        LaneRole.THIRD -> third
        LaneRole.COLOR -> color
    }

    /** Pre-resolved colour for every lane of [grid]. */
    fun laneColors(grid: DiatonicGrid): IntArray =
        IntArray(grid.laneCount) { forRole(grid.role(it)) }

    companion object {
        /** Hue (degrees) by position on the circle of fifths, starting at C. */
        private val HUES = floatArrayOf(190f, 210f, 236f, 260f, 286f, 322f, 352f, 22f, 44f, 72f, 150f, 172f)
    }
}
