package com.orbital.synth.ui.aurora

import kotlin.math.min

/** Smallest comfortable lane height for ordinary playing. */
internal const val LANE_MIN_DP = 30f

/** Lessons may squeeze lanes a little more so every song fits a short screen. */
internal const val LANE_MIN_DP_LESSON = 22f

/**
 * How many octaves of lanes fit the playable area without lanes becoming too thin to hit.
 * The player's chosen range is an upper bound; small or landscape screens show fewer
 * octaves instead of unplayably thin lanes. An unmeasured (zero) height returns the wish.
 */
internal fun fitOctaves(
    wanted: Int,
    heightDp: Float,
    stepsPerOctave: Int,
    minLaneDp: Float = LANE_MIN_DP,
): Int {
    val want = wanted.coerceIn(1, 4)
    if (!heightDp.isFinite() || heightDp <= 0f || stepsPerOctave <= 0) return want
    val lanes = (heightDp / minLaneDp).toInt()
    val fitting = ((lanes - 1) / stepsPerOctave).coerceAtLeast(1)
    return min(want, fitting)
}
