package com.orbital.synth.core.stage

/**
 * One melody note expressed as a *scale step*, not a pitch: 0 is the tonic, 1 the
 * next scale tone, 7 the tonic an octave up in a seven-note scale, -3 the tone
 * three steps below the tonic. Stored this way a tune is automatically correct in
 * every key (D major, B♭ major...) and can even be heard in other modes.
 */
data class SongNote(val step: Int, val beats: Float, val rest: Boolean = false) {
    init {
        require(beats > 0f && beats.isFinite()) { "beats must be positive" }
    }
}

/** A note scheduled for the "listen" demo. Times are milliseconds from the start. */
data class TimedStep(val startMs: Long, val durationMs: Long, val step: Int)

data class Song(
    val id: String,
    val title: String,
    /** Where the tune comes from: "Beethoven, 1824", "Traditional", ... */
    val origin: String,
    /** The scale the melody was written for; the stage switches to it when learning begins. */
    val scaleId: String,
    val bpm: Int,
    /** 1 = first lesson, 2 = needs a few leaps, 3 = wide range and quick notes. */
    val difficulty: Int,
    /** Pitch class (0 = C) the tune sounds best in; the player can pick any key. */
    val suggestedTonic: Int,
    val notes: List<SongNote>,
) {
    init {
        require(id.isNotBlank() && title.isNotBlank())
        require(bpm in 30..240) { "bpm out of range" }
        require(difficulty in 1..3)
        require(suggestedTonic in 0..11)
        require(notes.any { !it.rest }) { "a song needs at least one note" }
    }

    /** The notes a player must touch, in order (rests are only heard in the demo). */
    val playable: List<SongNote> = notes.filter { !it.rest }
    val lowestStep: Int = playable.minOf { it.step }
    val highestStep: Int = playable.maxOf { it.step }
    val totalBeats: Float = notes.sumOf { it.beats.toDouble() }.toFloat()
    val noteCount: Int get() = playable.size

    /** How many scale steps one octave spans in the tune's native scale. */
    val stepsPerOctave: Int get() = StageScales.byId(scaleId).degrees.size

    /** Seconds of music at the native tempo. */
    val durationSeconds: Float get() = totalBeats * 60f / bpm

    /** Fits in a grid when every note has a lane after choosing the best octave placement. */
    fun fitsIn(grid: DiatonicGrid): Boolean =
        grid.stepsPerOctave == stepsPerOctave && highestStep - lowestStep + 1 <= grid.laneCount

    /**
     * Octave placement (in scale steps) that centres the melody in the lane range.
     * Lane = step + this offset; guaranteed to keep every note inside the grid when
     * [fitsIn] is true.
     */
    fun laneOffset(grid: DiatonicGrid): Int {
        val perOctave = grid.stepsPerOctave
        val centre = (lowestStep + highestStep) / 2.0
        val target = (grid.laneCount - 1) / 2.0
        var shift = Math.round((target - centre) / perOctave).toInt()
        // Slide by whole octaves until everything fits.
        while (lowestStep + shift * perOctave < 0) shift++
        while (highestStep + shift * perOctave > grid.laneCount - 1 && lowestStep + (shift - 1) * perOctave >= 0) shift--
        return shift * perOctave
    }

    /** Demo timeline: every note with a short articulation gap so repeats are audible. */
    fun timeline(tempoScale: Float = 1f): List<TimedStep> {
        val msPerBeat = 60_000.0 / (bpm * tempoScale.coerceIn(0.25f, 4f))
        var cursor = 0.0
        val out = ArrayList<TimedStep>(notes.size)
        for (note in notes) {
            val length = note.beats * msPerBeat
            if (!note.rest) {
                val sounding = (length * 0.9).coerceAtLeast(60.0)
                out += TimedStep(cursor.toLong(), sounding.toLong(), note.step)
            }
            cursor += length
        }
        return out
    }

    companion object {
        /** Parses compact "step:beats" tokens; `r:beats` is a rest. Beats accept "1/3" fractions. */
        fun parse(text: String): List<SongNote> = text.trim().split(Regex("\\s+")).map { token ->
            val parts = token.split(':')
            require(parts.size == 2) { "bad token '$token'" }
            val beats = parseBeats(parts[1])
            if (parts[0] == "r") SongNote(0, beats, rest = true)
            else SongNote(parts[0].toInt(), beats)
        }

        private fun parseBeats(text: String): Float {
            val slash = text.indexOf('/')
            return if (slash > 0) text.substring(0, slash).toFloat() / text.substring(slash + 1).toFloat()
            else text.toFloat()
        }
    }
}
