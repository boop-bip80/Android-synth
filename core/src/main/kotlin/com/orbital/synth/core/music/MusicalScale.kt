package com.orbital.synth.core.music

/** A pitch-class collection relative to a tonic. */
data class MusicalScale(val id: String, val name: String, val degrees: Set<Int>) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(degrees.isNotEmpty() && 0 in degrees)
        require(degrees.all { it in 0..11 })
    }
}

object MusicalScales {
    val CHROMATIC = MusicalScale("chromatic", "Chromatic", (0..11).toSet())
    val MAJOR = MusicalScale("major", "Major", setOf(0, 2, 4, 5, 7, 9, 11))
    val NATURAL_MINOR = MusicalScale("natural_minor", "Natural Minor", setOf(0, 2, 3, 5, 7, 8, 10))
    val DORIAN = MusicalScale("dorian", "Dorian", setOf(0, 2, 3, 5, 7, 9, 10))
    val PHRYGIAN = MusicalScale("phrygian", "Phrygian", setOf(0, 1, 3, 5, 7, 8, 10))
    val LYDIAN = MusicalScale("lydian", "Lydian", setOf(0, 2, 4, 6, 7, 9, 11))
    val MIXOLYDIAN = MusicalScale("mixolydian", "Mixolydian", setOf(0, 2, 4, 5, 7, 9, 10))
    val MINOR_PENTATONIC = MusicalScale("minor_pentatonic", "Minor Pentatonic", setOf(0, 3, 5, 7, 10))
    val MAJOR_PENTATONIC = MusicalScale("major_pentatonic", "Major Pentatonic", setOf(0, 2, 4, 7, 9))
    val BLUES = MusicalScale("blues", "Blues", setOf(0, 3, 5, 6, 7, 10))
    val HARMONIC_MINOR = MusicalScale("harmonic_minor", "Harmonic Minor", setOf(0, 2, 3, 5, 7, 8, 11))
    val WHOLE_TONE = MusicalScale("whole_tone", "Whole Tone", setOf(0, 2, 4, 6, 8, 10))
    val LOCRIAN = MusicalScale("locrian", "Locrian", setOf(0, 1, 3, 5, 6, 8, 10))
    val MELODIC_MINOR = MusicalScale("melodic_minor", "Melodic Minor (Ascending)",
        setOf(0, 2, 3, 5, 7, 9, 11))
    val PHRYGIAN_DOMINANT = MusicalScale("phrygian_dominant", "Phrygian Dominant",
        setOf(0, 1, 4, 5, 7, 8, 10))
    val LYDIAN_DOMINANT = MusicalScale("lydian_dominant", "Lydian Dominant",
        setOf(0, 2, 4, 6, 7, 9, 10))
    val ALTERED = MusicalScale("altered", "Altered", setOf(0, 1, 3, 4, 6, 8, 10))
    val DIMINISHED_HALF_WHOLE = MusicalScale("diminished_half_whole", "Diminished Half-Whole",
        setOf(0, 1, 3, 4, 6, 7, 9, 10))
    val DIMINISHED_WHOLE_HALF = MusicalScale("diminished_whole_half", "Diminished Whole-Half",
        setOf(0, 2, 3, 5, 6, 8, 9, 11))
    val HIRAJOSHI = MusicalScale("hirajoshi", "Hirajoshi", setOf(0, 2, 3, 7, 8))

    val ALL: List<MusicalScale> = listOf(
        CHROMATIC, MAJOR, NATURAL_MINOR, DORIAN, PHRYGIAN, LYDIAN,
        MIXOLYDIAN, MINOR_PENTATONIC, MAJOR_PENTATONIC, BLUES,
        HARMONIC_MINOR, WHOLE_TONE, LOCRIAN, MELODIC_MINOR, PHRYGIAN_DOMINANT,
        LYDIAN_DOMINANT, ALTERED, DIMINISHED_HALF_WHOLE, DIMINISHED_WHOLE_HALF,
        HIRAJOSHI,
    )

    fun byId(id: String): MusicalScale? = ALL.firstOrNull { it.id == id }
}

enum class NoteTieBreak { LOWER, HIGHER }

/** Nearest-note MIDI quantization, with deliberate and deterministic tie-breaking. */
class ScaleQuantizer(
    val scale: MusicalScale = MusicalScales.MAJOR,
    tonicPitchClass: Int = 0,
    val tieBreak: NoteTieBreak = NoteTieBreak.LOWER,
) {
    val tonicPitchClass: Int = Math.floorMod(tonicPitchClass, 12)

    fun accepts(midiNote: Int): Boolean =
        midiNote in 0..127 && Math.floorMod(midiNote - tonicPitchClass, 12) in scale.degrees

    fun quantize(midiNote: Int): Int {
        val bounded = midiNote.coerceIn(0, 127)
        if (accepts(bounded)) return bounded
        for (distance in 1..12) {
            val lower = bounded - distance
            val upper = bounded + distance
            val hasLower = lower >= 0 && accepts(lower)
            val hasUpper = upper <= 127 && accepts(upper)
            if (hasLower && hasUpper) {
                return if (tieBreak == NoteTieBreak.LOWER) lower else upper
            }
            if (hasLower) return lower
            if (hasUpper) return upper
        }
        // Every scale contains the tonic, so MIDI [0, 127] always has an eligible note.
        error("No eligible MIDI note")
    }
}
