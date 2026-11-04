package com.orbital.synth.core.stage

/** What happened when the player struck a lane while a song was being guided. */
enum class GuideOutcome { HIT, MISS, IDLE }

/** A note the guide is showing ahead of the player. */
data class GuideTarget(val lane: Int, val beats: Float, val indexInSong: Int)

/** Final result of a finished song. */
data class GuideResult(
    val stars: Int,
    val hits: Int,
    val misses: Int,
    val bestCombo: Int,
    val accuracy: Float,
)

/**
 * "Follow the light": the stage glows the next lane to touch. The song waits for
 * the player (no timing pressure), so a first-timer can discover a melody at their
 * own pace; hitting notes cleanly builds a combo and earns up to three stars.
 *
 * The session never plays audio and never blocks a wrong note - the lane still
 * sounds, the guide simply counts it - so the instrument always stays playable.
 */
class GuideSession(val song: Song, private val grid: DiatonicGrid) {
    init {
        require(song.fitsIn(grid)) { "song '${song.id}' does not fit this grid" }
    }

    private val offset = song.laneOffset(grid)

    var index: Int = 0
        private set
    var hits: Int = 0
        private set
    var misses: Int = 0
        private set
    var combo: Int = 0
        private set
    var bestCombo: Int = 0
        private set

    val total: Int get() = song.playable.size
    val finished: Boolean get() = index >= total

    /** 0..1 progress through the melody. */
    val progress: Float get() = if (total == 0) 1f else index.toFloat() / total

    fun laneFor(note: SongNote): Int = (note.step + offset).coerceIn(0, grid.laneCount - 1)

    fun target(): GuideTarget? = song.playable.getOrNull(index)?.let {
        GuideTarget(laneFor(it), it.beats, index)
    }

    /** The next [count] notes starting at the current target, for the melody ribbon. */
    fun upcoming(count: Int): List<GuideTarget> {
        val out = ArrayList<GuideTarget>(count)
        var i = index
        while (out.size < count && i < total) {
            val note = song.playable[i]
            out += GuideTarget(laneFor(note), note.beats, i)
            i++
        }
        return out
    }

    /** Feed every lane the player strikes (fresh touch or slide). */
    fun onStrike(lane: Int): GuideOutcome {
        val current = target() ?: return GuideOutcome.IDLE
        return if (lane == current.lane) {
            hits++
            combo++
            if (combo > bestCombo) bestCombo = combo
            index++
            GuideOutcome.HIT
        } else {
            misses++
            combo = 0
            GuideOutcome.MISS
        }
    }

    fun result(): GuideResult? {
        if (!finished) return null
        val attempts = hits + misses
        val accuracy = if (attempts == 0) 1f else hits.toFloat() / attempts
        val stars = when {
            misses == 0 -> 3
            misses <= maxOf(2, total / 8) -> 2
            else -> 1
        }
        return GuideResult(stars, hits, misses, bestCombo, accuracy)
    }
}
