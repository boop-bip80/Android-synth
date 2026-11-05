package com.orbital.synth.core.jazz

import com.orbital.synth.core.stage.NoteSpelling
import com.orbital.synth.core.stage.StageScales

/** A home key: a pitch class and the id of one of the scales the stage offers. */
data class JazzKey(val tonic: Int, val scaleId: String) {
    init {
        require(tonic in 0..11) { "tonic must be 0..11" }
        require(StageScales.OFFERED.any { it.id == scaleId }) { "scale '$scaleId' is not offered on the stage" }
    }

    /** "G Minor", "B♭ Major", "G Mixolydian": the stage's own names. */
    val label: String get() = "${NoteSpelling.tonicName(tonic)} ${StageScales.shortName(StageScales.byId(scaleId))}"

    companion object {
        private val TOKEN = Regex("([A-G])([#b]?)(maj|m|dor|mix|lyd|phr|blues|hm)?")
        private val NATURAL = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
        private val SUFFIX = mapOf(
            null to "major", "maj" to "major", "m" to "natural_minor", "dor" to "dorian",
            "mix" to "mixolydian", "lyd" to "lydian", "phr" to "phrygian", "blues" to "blues",
            "hm" to "harmonic_minor",
        )

        /**
         * Parses the catalogue's compact key notation: a letter, an optional `#` or `b`, and an
         * optional mode: `C`, `Bb`, `F#m` (natural minor), `Gmix`, `Ddor`, `Clyd`, `Ephr`,
         * `Cblues`, `Ahm` (harmonic minor). Returns null when [text] is not a key.
         */
        fun parse(text: String): JazzKey? {
            val match = TOKEN.matchEntire(text.trim()) ?: return null
            val (letter, accidental, suffix) = match.destructured
            val shift = when (accidental) { "#" -> 1; "b" -> -1; else -> 0 }
            val tonic = Math.floorMod(NATURAL.getValue(letter[0]) + shift, 12)
            val scaleId = SUFFIX[suffix.ifEmpty { null }] ?: return null
            return JazzKey(tonic, scaleId)
        }
    }
}

/**
 * One entry of the jazz catalogue. This is *metadata about a tune* (title, composer, a
 * suggested home key, feel, tempo and the instrument it starts with); it deliberately
 * carries no melody and no chord chart. The stage plays in the tune's key with one-finger
 * jazz harmony, so the player supplies the music.
 */
data class JazzStandard(
    val id: String,
    val title: String,
    val composer: String,
    /** Suggested home key, or null when the catalogue does not record one (the stage keeps its key). */
    val key: JazzKey?,
    val feel: JazzFeel,
    /** Suggested practice tempo in quarter notes per minute. */
    val bpm: Int,
    val voice: JazzVoice,
    val book: String = JazzCatalogue.REAL_BOOK_1_TITLE,
    /**
     * False when the catalogue does not record this tune's feel. [feel] and [bpm] then hold the
     * neutral medium defaults (used only to shape the sound) and are not shown to the player.
     */
    val feelKnown: Boolean = true,
    /**
     * The A-Z section the book files this tune under (`#` for titles starting with a digit).
     * The catalogue data states it explicitly, because the book is not consistent about
     * parentheses; when omitted it falls back to [indexLetterOf].
     */
    val section: Char = indexLetterOf(title),
) {
    init {
        require(ID_RULE.matches(id)) { "invalid id '$id'" }
        require(title.isNotBlank() && title.length <= 80) { "title must be 1..80 characters" }
        require(composer.length <= 120) { "composer too long" }
        require(bpm in 30..300) { "bpm must be 30..300" }
        require(section == '#' || section in 'A'..'Z') { "section must be # or A-Z" }
    }

    /** A line such as "G Minor · Medium swing · ♩ = 132"; only what the catalogue records. */
    val summary: String
        get() = listOfNotNull(
            key?.label,
            feel.label.takeIf { feelKnown },
            "♩ = $bpm".takeIf { feelKnown },
        ).joinToString(" · ")

    /** Lower-case text the search box matches against. */
    internal val searchText: String = buildString {
        append(title).append(' ').append(composer).append(' ').append(key?.label.orEmpty()).append(' ')
        if (feelKnown) append(feel.label).append(' ')
        append(voice.label)
    }.lowercase()

    companion object {
        val ID_RULE = Regex("[a-z0-9][a-z0-9-]{0,58}")

        private val LEADING_PARENTHETICAL = Regex("^\\([^)]*\\)\\s*")
        private val LEADING_ARTICLE = Regex("^(?:the|an|a)\\s+(?=\\S)", RegexOption.IGNORE_CASE)

        /**
         * A best-effort filing letter for a title with no explicit section: a leading
         * parenthetical and a leading "The"/"A"/"An" are ignored, so "(I Love You) For
         * Sentimental Reasons" is under F and "A Child Is Born" under C; digits give `#`.
         * The book itself is not consistent (it files "(There Is) No Greater Love" under T),
         * which is why [section] is explicit data for the shipped catalogue.
         */
        fun indexLetterOf(title: String): Char {
            val filed = title.trim().replace(LEADING_PARENTHETICAL, "").replace(LEADING_ARTICLE, "")
            val first = java.text.Normalizer.normalize(filed, java.text.Normalizer.Form.NFD)
                .firstOrNull { it.isLetterOrDigit() } ?: return '#'
            return if (first in 'a'..'z' || first in 'A'..'Z') first.uppercaseChar() else '#'
        }

        /** `"'Round Midnight"` becomes `round-midnight`; accents fold to ASCII. */
        fun slug(title: String): String {
            val folded = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace("&", " and ")
                .replace(Regex("['’`]"), "")
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            return folded.take(59).trim('-')
        }
    }
}
