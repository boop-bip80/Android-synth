package com.orbital.synth.core.stage

import com.orbital.synth.core.music.MusicalScale
import com.orbital.synth.core.music.MusicalScales

/**
 * The key a touch performer has chosen on the stage: a tonic pitch class plus
 * a scale/mode. Every lane, chord and guided melody derives from this value,
 * so changing it retunes the whole instrument and keeps every note "in key".
 */
data class StageKey(val tonic: Int, val scale: MusicalScale) {
    init {
        require(tonic in 0..11) { "tonic pitch class must be 0..11" }
    }

    /** Ascending scale pitch classes relative to the tonic, e.g. major = 0,2,4,5,7,9,11. */
    val degrees: IntArray = scale.degrees.sorted().toIntArray()

    /** Number of scale steps in one octave (7 for diatonic modes, 5 for pentatonics...). */
    val stepsPerOctave: Int get() = degrees.size

    val tonicName: String get() = NoteSpelling.tonicName(tonic)

    /** A short upper-case label such as "D MAJOR" or "A MINOR PENT". */
    val displayName: String get() = "$tonicName ${StageScales.shortName(scale)}".uppercase()

    /** Pitch-class name as spelled in this key (e.g. F♯ in D major, B♭ in F major). */
    fun nameOf(pitchClass: Int): String = NoteSpelling.name(this, pitchClass)

    companion object {
        val C_MAJOR = StageKey(0, MusicalScales.MAJOR)
    }
}

/** The scales the stage offers, in the order a learner meets them. */
object StageScales {
    val OFFERED: List<MusicalScale> = listOf(
        MusicalScales.MAJOR,
        MusicalScales.NATURAL_MINOR,
        MusicalScales.DORIAN,
        MusicalScales.MIXOLYDIAN,
        MusicalScales.LYDIAN,
        MusicalScales.PHRYGIAN,
        MusicalScales.MAJOR_PENTATONIC,
        MusicalScales.MINOR_PENTATONIC,
        MusicalScales.BLUES,
        MusicalScales.HARMONIC_MINOR,
        MusicalScales.HIRAJOSHI,
    )

    fun shortName(scale: MusicalScale): String = when (scale.id) {
        "major" -> "Major"
        "natural_minor" -> "Minor"
        "dorian" -> "Dorian"
        "phrygian" -> "Phrygian"
        "lydian" -> "Lydian"
        "mixolydian" -> "Mixolydian"
        "major_pentatonic" -> "Major Pent"
        "minor_pentatonic" -> "Minor Pent"
        "blues" -> "Blues"
        "harmonic_minor" -> "Harm Minor"
        "hirajoshi" -> "Hirajoshi"
        else -> scale.name
    }

    /** A one-line plain-language mood hint, shown when a mode is chosen. */
    fun mood(scale: MusicalScale): String = when (scale.id) {
        "major" -> "bright and happy"
        "natural_minor" -> "dark and emotional"
        "dorian" -> "minor with a hopeful lift"
        "phrygian" -> "tense and spanish"
        "lydian" -> "dreamy and floating"
        "mixolydian" -> "bluesy and rock-ready"
        "major_pentatonic" -> "no wrong notes, ever"
        "minor_pentatonic" -> "guitar-solo ready"
        "blues" -> "gritty with a blue note"
        "harmonic_minor" -> "dramatic and cinematic"
        "hirajoshi" -> "calm, japanese garden"
        else -> ""
    }

    fun byId(id: String): MusicalScale = MusicalScales.byId(id) ?: MusicalScales.MAJOR
}

/**
 * Note names that read like sheet music: heptatonic scales use each letter once
 * (D major is D E F♯ G A B C♯, never D E G♭ ...), other scales fall back to a
 * sharp/flat preference taken from the tonic.
 */
object NoteSpelling {
    private val tonicNames = arrayOf(
        "C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B",
    )
    private val letters = charArrayOf('C', 'D', 'E', 'F', 'G', 'A', 'B')
    private val naturalPitchClass = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val sharpNames = arrayOf(
        "C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B",
    )
    private val flatNames = arrayOf(
        "C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B",
    )

    fun tonicName(pitchClass: Int): String = tonicNames[Math.floorMod(pitchClass, 12)]

    fun name(key: StageKey, pitchClass: Int): String {
        val pc = Math.floorMod(pitchClass, 12)
        if (key.stepsPerOctave == 7) {
            val index = key.degrees.indexOf(Math.floorMod(pc - key.tonic, 12))
            if (index >= 0) {
                val tonicLetter = letters.indexOf(tonicName(key.tonic)[0])
                val letterIndex = (tonicLetter + index) % 7
                // Signed semitone difference between the pitch and its natural letter.
                var accidental = Math.floorMod(pc - naturalPitchClass[letterIndex], 12)
                if (accidental > 6) accidental -= 12
                when (accidental) {
                    0 -> return letters[letterIndex].toString()
                    1 -> return "${letters[letterIndex]}♯"
                    -1 -> return "${letters[letterIndex]}♭"
                }
                // Double accidentals are legal theory but unreadable on a phone: fall through.
            }
        }
        return if (prefersFlats(key)) flatNames[pc] else sharpNames[pc]
    }

    private fun prefersFlats(key: StageKey): Boolean {
        val tonic = tonicName(key.tonic)
        return '♭' in tonic || tonic == "F"
    }
}
