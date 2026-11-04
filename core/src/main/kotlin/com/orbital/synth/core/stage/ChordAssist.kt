package com.orbital.synth.core.stage

/**
 * One-finger harmony. A touched lane can sound just its own note or stack more
 * scale tones above it. Chords are built by stacking *scale steps*, so they are
 * always diatonic: tapping the second lane of C major yields D-F-A (D minor)
 * without the player knowing any theory.
 */
enum class ChordAssist(
    val label: String,
    val blurb: String,
    /**
     * Scale-step offsets above the touched lane for stacked-third chords.
     * OFF, OCTAVE and POWER depend on the grid's scale size, so [ChordVoicer]
     * computes their offsets per grid and ignores this array.
     */
    internal val steps: IntArray,
) {
    OFF("SOLO", "single notes", intArrayOf(0)),
    OCTAVE("OCTAVE", "fat octave doubling", intArrayOf(0)),
    POWER("POWER", "root + fifth", intArrayOf(0)),
    TRIAD("TRIAD", "full chords", intArrayOf(0, 2, 4)),
    SEVENTH("JAZZ", "rich 7th chords", intArrayOf(0, 2, 4, 6)),
}

/** A resolved chord for one touch. */
class ChordVoicing(
    /** Ascending MIDI notes to sound; the first is always the touched lane's note. */
    val notes: IntArray,
    /** Display symbol such as "Dm", "G7" or "C" (blank chords fall back to a note name). */
    val symbol: String,
    /** Roman numeral inside the key, e.g. "ii", "V7", "vii°"; empty for non-chord voicings. */
    val roman: String,
)

object ChordVoicer {
    /** Below this MIDI note, thirds are dropped: close triads are muddy in the bass. */
    const val OPEN_VOICING_BELOW = 50

    /** Velocity multiplier for non-root chord tones so the touched note leads. */
    const val SUPPORT_VELOCITY = 0.8f

    fun voice(grid: DiatonicGrid, lane: Int, assist: ChordAssist): ChordVoicing {
        val safeLane = lane.coerceIn(0, grid.laneCount - 1)
        val steps = stepsFor(grid, safeLane, assist)
        val notes = steps.map { grid.midiForStep(safeLane + it) }
            .filter { it in 0..127 }
            .distinct()
            .toIntArray()
        val root = notes.firstOrNull() ?: grid.midiForLane(safeLane)
        val rootName = grid.key.nameOf(root)
        val symbol = symbolFor(rootName, notes, root)
        val roman = if (assist == ChordAssist.TRIAD || assist == ChordAssist.SEVENTH) {
            romanFor(grid, safeLane, notes, root)
        } else ""
        return ChordVoicing(if (notes.isEmpty()) intArrayOf(root) else notes, symbol, roman)
    }

    private fun stepsFor(grid: DiatonicGrid, lane: Int, assist: ChordAssist): IntArray {
        val perOctave = grid.stepsPerOctave
        val rootMidi = grid.midiForLane(lane)
        return when (assist) {
            ChordAssist.OFF -> intArrayOf(0)
            ChordAssist.OCTAVE -> intArrayOf(0, perOctave)
            ChordAssist.POWER -> intArrayOf(0, fifthStep(grid, lane))
            ChordAssist.TRIAD, ChordAssist.SEVENTH -> {
                val base = assist.steps
                if (rootMidi < OPEN_VOICING_BELOW) {
                    // Open voicing: drop the third, keep root, fifth and (for 7ths) the seventh.
                    base.filterIndexed { index, _ -> index != 1 }.toIntArray()
                } else base
            }
        }
    }

    /** Scale-step distance to the perfect fifth above this lane (4 steps in heptatonic scales). */
    private fun fifthStep(grid: DiatonicGrid, lane: Int): Int {
        val root = grid.midiForStep(lane)
        for (offset in 1..grid.stepsPerOctave) {
            if (grid.midiForStep(lane + offset) - root == 7) return offset
        }
        // Scales without a perfect fifth (e.g. whole tone) use the nearest stacked step.
        return (grid.stepsPerOctave * 4 + 3) / 7
    }

    private fun symbolFor(rootName: String, notes: IntArray, root: Int): String {
        val intervals = notes.map { Math.floorMod(it - root, 12) }.toSortedSet()
        val quality = when (intervals) {
            sortedSetOf(0, 4, 7) -> ""
            sortedSetOf(0, 3, 7) -> "m"
            sortedSetOf(0, 3, 6) -> "dim"
            sortedSetOf(0, 4, 8) -> "aug"
            sortedSetOf(0, 5, 7) -> "sus4"
            sortedSetOf(0, 2, 7) -> "sus2"
            sortedSetOf(0, 4, 7, 10) -> "7"
            sortedSetOf(0, 4, 7, 11) -> "maj7"
            sortedSetOf(0, 3, 7, 10) -> "m7"
            sortedSetOf(0, 3, 7, 11) -> "m(maj7)"
            sortedSetOf(0, 3, 6, 10) -> "m7♭5"
            sortedSetOf(0, 3, 6, 9) -> "dim7"
            sortedSetOf(0, 7) -> "5"
            // Open voicings (no third) keep their identity from the fifth and seventh.
            sortedSetOf(0, 7, 10) -> "7"
            sortedSetOf(0, 7, 11) -> "maj7"
            else -> ""
        }
        return rootName + quality
    }

    private fun romanFor(grid: DiatonicGrid, lane: Int, notes: IntArray, root: Int): String {
        val perOctave = grid.stepsPerOctave
        if (perOctave != 7) return ""
        val numerals = arrayOf("I", "II", "III", "IV", "V", "VI", "VII")
        val degree = grid.degreeIndex(lane)
        val intervals = notes.map { Math.floorMod(it - root, 12) }.toSet()
        val minorThird = 3 in intervals
        val diminished = minorThird && 6 in intervals
        val augmented = 4 in intervals && 8 in intervals
        val base = if (minorThird) numerals[degree].lowercase() else numerals[degree]
        val suffix = when {
            diminished -> "°"
            augmented -> "+"
            else -> ""
        }
        val seventh = when {
            11 in intervals -> "maj7"
            10 in intervals || (9 in intervals && diminished) -> "7"
            else -> ""
        }
        return base + suffix + seventh
    }
}
