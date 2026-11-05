package com.orbital.synth.core.jazz

import com.orbital.synth.core.preset.SynthPatch

/**
 * The jazz standards catalogue and the default sound of each tune.
 *
 * The catalogue is *data about* tunes (see [JazzStandard]); the sounds are built by
 * [JazzPresets] from the ORBITAL engine. Pure Kotlin, no Android dependency.
 */
object JazzCatalogue {
    // Declared before REAL_BOOK_1: object properties initialise in order, and parse() uses it.
    private val SECTION_LINE = Regex("\\[([#A-Z])]")

    const val REAL_BOOK_1_TITLE = "The Real Book, Volume 1"

    /** Songs in the Hal Leonard sixth edition of Volume 1. */
    const val REAL_BOOK_1_PUBLISHED_COUNT = 400

    /** Every catalogued tune, in the order of the book's own alphabetical list. */
    val REAL_BOOK_1: List<JazzStandard> = parse(RealBookVol1Data.TEXT)

    fun byId(id: String): JazzStandard? = REAL_BOOK_1.firstOrNull { it.id == id }

    /** The sound a tune starts with. Deterministic: the same tune always yields an equal patch. */
    fun defaultPatch(standard: JazzStandard): SynthPatch = JazzPresets.patchFor(standard)

    /**
     * Tunes matching every whitespace-separated term of [query] against title, composer, key,
     * feel and instrument, in [source]'s order. A blank query returns [source] unchanged.
     */
    fun search(query: String, source: List<JazzStandard> = REAL_BOOK_1): List<JazzStandard> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return source
        return source.filter { tune -> terms.all { it in tune.searchText } }
    }

    /** Index letter for a tune (`#` for titles that start with a digit), used for A-Z sections. */
    fun sectionOf(standard: JazzStandard): Char = standard.section

    /**
     * Parses catalogue text (see [RealBookVol1Data] for the format), keeping the order of the
     * lines. Throws [IllegalArgumentException] naming the offending line, so a bad edit fails
     * the unit tests instead of silently dropping a tune.
     */
    fun parse(text: String): List<JazzStandard> {
        val seen = HashSet<String>()
        val result = ArrayList<JazzStandard>()
        var section: Char? = null
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            SECTION_LINE.matchEntire(line)?.let {
                section = it.groupValues[1].single()
                return@forEachIndexed
            }
            val tune = try {
                parseLine(line, section)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("catalogue line ${index + 1} ('$line'): ${e.message}", e)
            }
            require(seen.add(tune.id)) { "catalogue line ${index + 1}: duplicate tune '${tune.title}'" }
            result += tune
        }
        return result
    }

    private fun parseLine(line: String, section: Char?): JazzStandard {
        val cells = line.split('|').map { it.trim() }
        require(cells.size in 4..5) { "expected 'Title | Composer | key | feel [| extras]'" }
        val (title, composer, keyText, feelText) = cells
        val key = if (keyText == "?") null else requireNotNull(JazzKey.parse(keyText)) { "unknown key '$keyText'" }
        val feelKnown = feelText != "-"
        val feel = if (feelKnown) {
            requireNotNull(JazzFeel.byCode(feelText.lowercase())) { "unknown feel '$feelText'" }
        } else {
            JazzFeel.MEDIUM
        }
        var voice = feel.defaultVoice
        var bpm = feel.defaultBpm
        if (cells.size == 5) {
            for (token in cells[4].split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val (name, value) = token.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                when (name) {
                    "voice" -> voice = requireNotNull(JazzVoice.byCode(value)) { "unknown voice '$value'" }
                    "bpm" -> bpm = requireNotNull(value.toIntOrNull()) { "bad bpm '$value'" }
                    else -> throw IllegalArgumentException("unknown extra '$token'")
                }
            }
        }
        return JazzStandard(
            id = JazzStandard.slug(title), title = title, composer = if (composer == "?") "" else composer,
            key = key, feel = feel, bpm = bpm, voice = voice, feelKnown = feelKnown,
            section = section ?: JazzStandard.indexLetterOf(title),
        )
    }
}
