package com.orbital.synth.core.jazz

import com.orbital.synth.core.preset.PatchCodec
import com.orbital.synth.core.preset.PatchParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JazzCatalogueTest {
    private fun rejects(text: String, mention: String) {
        try {
            JazzCatalogue.parse(text)
            fail("Expected the catalogue text to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue("message '${e.message}' should mention '$mention'", e.message!!.contains(mention))
        }
    }

    @Test fun shippedCatalogueHoldsAll400TitlesInBookOrder() {
        val all = JazzCatalogue.REAL_BOOK_1
        assertEquals(JazzCatalogue.REAL_BOOK_1_PUBLISHED_COUNT, all.size)
        assertEquals(400, all.map { it.id }.toSet().size)
        assertEquals("500 Miles High", all.first().title)
        assertEquals("502 Blues", all[1].title)
        assertEquals("Young at Heart", all.last().title)
        // Titles are kept exactly as the contents list prints them, quirks included.
        val titles = all.map { it.title }.toSet()
        for (t in listOf("Take the “A” Train", "God Bless' the Child", "Água de Beber (Water to Drink)", "E.S.P.",
            "(I Love You) For Sentimental Reasons", "Poem for #15 (The Saga of Harrison Crabfeathers)", "Stompin' at the Savoy")) {
            assertTrue(t, t in titles)
        }
        // Filed like the book: '#' first, then A..Z with no letter appearing twice.
        val letters = all.map { it.section }
        assertEquals(letters, letters.sorted())
        assertEquals("every index letter forms one contiguous run", letters.distinct().size, letters.zipWithNext().count { it.first != it.second } + 1)
        for (tune in all) {
            assertTrue(tune.id, JazzStandard.ID_RULE.matches(tune.id))
            assertEquals(JazzStandard.slug(tune.title), tune.id)
            assertEquals(JazzCatalogue.REAL_BOOK_1_TITLE, tune.book)
            assertEquals(tune, JazzCatalogue.byId(tune.id))
        }
    }

    @Test fun unrecordedFieldsStayUnrecordedAndNothingIsInvented() {
        val all = JazzCatalogue.REAL_BOOK_1
        // The annotation columns are deliberately sparse: most keys are not recorded.
        assertTrue(all.count { it.key != null } in 30..120)
        assertTrue(all.count { it.composer.isBlank() } > 0)
        assertTrue(all.count { !it.feelKnown } > 0)
        val ana = JazzCatalogue.byId("ana-maria")!!
        assertFalse(ana.feelKnown)
        assertNull(ana.key)
        // An unrecorded feel shows nothing (no invented tempo) yet still gets a sensible sound.
        assertEquals("", ana.summary)
        assertEquals(JazzFeel.MEDIUM, ana.feel)
        val patch = JazzCatalogue.defaultPatch(ana)
        assertEquals(PatchParameters.NAMES, patch.parameters.keys)
        val arise = JazzCatalogue.byId("arise-her-eyes")!!
        assertEquals("", arise.composer)
        assertFalse(arise.searchText.contains("medium swing"))
    }

    @Test fun sectionsAreTheBooksOwnFilingNotAGuess() {
        fun sec(id: String) = JazzCatalogue.sectionOf(JazzCatalogue.byId(id)!!)
        assertEquals('B', sec("the-blue-room"))
        assertEquals('C', sec("a-child-is-born"))
        assertEquals('F', sec("i-love-you-for-sentimental-reasons"))
        assertEquals('O', sec("the-old-man-from-the-old-country"))
        // The book files this one under T even though the parenthetical is ignored elsewhere.
        assertEquals('T', sec("there-is-no-greater-love"))
        assertEquals('M', sec("mr-p-c"))
        assertEquals('R', sec("round-midnight"))
        assertEquals('A', sec("agua-de-beber-water-to-drink"))
        assertEquals('#', sec("500-miles-high"))
        assertEquals(25, JazzCatalogue.REAL_BOOK_1.map { it.section }.toSet().size)
    }

    @Test fun withoutAHeadingTheFilingFallsBackToAHeuristic() {
        assertEquals('F', JazzStandard.indexLetterOf("(I Love You) For Sentimental Reasons"))
        assertEquals('C', JazzStandard.indexLetterOf("A Child Is Born"))
        assertEquals('A', JazzStandard.indexLetterOf("A"))
        assertEquals('#', JazzStandard.indexLetterOf("500 Miles High"))
        assertEquals('#', JazzStandard.indexLetterOf("(  )"))
        val parsed = JazzCatalogue.parse("The Zebra | ? | ? | -\n[Q]\nApple | ? | ? | -\n")
        assertEquals(listOf('Z', 'Q'), parsed.map { it.section })
        rejects("[a]\nX | ? | ? | -", "expected")
    }

    @Test fun knownTunesCarryTheirFactsAndTheirOwnVoices() {
        val takeFive = JazzCatalogue.byId("take-five")!!
        assertEquals("Paul Desmond", takeFive.composer)
        assertEquals(JazzKey(3, "natural_minor"), takeFive.key)
        assertEquals(JazzFeel.ODD, takeFive.feel)
        assertEquals(176, takeFive.bpm)
        assertEquals(JazzVoice.TENOR_SAX, takeFive.voice)
        assertEquals(5, takeFive.feel.beatsPerBar)

        val midnight = JazzCatalogue.byId("round-midnight")!!
        assertEquals("'Round Midnight", midnight.title)
        assertEquals("Thelonious Monk", midnight.composer)
        assertEquals(JazzFeel.BALLAD.defaultBpm, midnight.bpm)
        assertEquals(JazzVoice.STUDIO_PIANO, midnight.voice)
        assertEquals("E♭ Minor · Ballad · ♩ = 66", midnight.summary)

        // A tune whose key is not recorded must not force a key on the stage.
        val ruby = JazzCatalogue.byId("ruby-my-dear")!!
        assertNull(ruby.key)
        assertFalse(ruby.summary.contains("Major"))
        assertEquals(JazzKey(7, "mixolydian"), JazzCatalogue.byId("all-blues")!!.key)
        assertEquals(JazzKey(2, "dorian"), JazzCatalogue.byId("so-what")!!.key)
    }

    @Test fun keyNotationParsesEveryModeAndRejectsGarbage() {
        assertEquals(JazzKey(0, "major"), JazzKey.parse("C"))
        assertEquals(JazzKey(10, "major"), JazzKey.parse("Bb"))
        assertEquals(JazzKey(11, "major"), JazzKey.parse("B"))
        assertEquals(JazzKey(6, "natural_minor"), JazzKey.parse("F#m"))
        assertEquals(JazzKey(3, "natural_minor"), JazzKey.parse("Ebm"))
        assertEquals(JazzKey(7, "mixolydian"), JazzKey.parse("Gmix"))
        assertEquals(JazzKey(2, "dorian"), JazzKey.parse("Ddor"))
        assertEquals(JazzKey(0, "lydian"), JazzKey.parse("Clyd"))
        assertEquals(JazzKey(4, "phrygian"), JazzKey.parse("Ephr"))
        assertEquals(JazzKey(0, "blues"), JazzKey.parse("Cblues"))
        assertEquals(JazzKey(9, "harmonic_minor"), JazzKey.parse("Ahm"))
        assertEquals(JazzKey(1, "major"), JazzKey.parse("Db"))
        assertEquals(JazzKey(11, "natural_minor"), JazzKey.parse("Cbm"))
        for (bad in listOf("", "H", "Cx", "c", "C#b", "Cminor", "Dsus", "?")) assertNull("'$bad'", JazzKey.parse(bad))
        assertEquals("B♭ Major", JazzKey.parse("Bb")!!.label)
        assertEquals("G Mixolydian", JazzKey.parse("Gmix")!!.label)
    }

    @Test fun slugsFoldAccentsPunctuationAndAmpersands() {
        assertEquals("round-midnight", JazzStandard.slug("'Round Midnight"))
        assertEquals("ruby-my-dear", JazzStandard.slug("Ruby, My Dear"))
        assertEquals("500-miles-high", JazzStandard.slug("500 Miles High"))
        assertEquals("cafe-au-lait", JazzStandard.slug("Café Au Lait"))
        assertEquals("rhythm-and-blues", JazzStandard.slug("Rhythm & Blues"))
        assertEquals("old-man-from-the-old-country", JazzStandard.slug("(Old Man from) The Old Country"))
        assertTrue(JazzStandard.slug("x".repeat(200)).length <= 59)
    }

    @Test fun badCatalogueLinesFailLoudlyWithTheLineNumber() {
        rejects("A | B | C | nonsense", "line 1")
        rejects("\n\nA | B | H | up", "line 3")
        rejects("A | B | C", "expected")
        rejects("A | B | C | up | voice=kazoo", "unknown voice")
        rejects("A | B | C | up | bpm=fast", "bad bpm")
        rejects("A | B | C | up | tempo=3", "unknown extra")
        rejects("A | B | C | up | bpm=5", "bpm")
        rejects("Same | X | C | up\nSame | Y | D | up", "duplicate")
        rejects("Same | X | C | up\nsame! | Y | D | up", "duplicate")
    }

    @Test fun commentsAndBlankLinesAreIgnoredAndUnknownKeyIsAllowed() {
        val parsed = JazzCatalogue.parse("\n# note\n   \nZebra | ? | ? | bossa\nApple | A | Gm | up | bpm=222 voice=vibes\nMystery | M | ? | -\n")
        assertEquals("order of the file is kept", listOf("zebra", "apple", "mystery"), parsed.map { it.id })
        assertEquals("", parsed[0].composer)
        assertNull(parsed[0].key)
        assertEquals(222, parsed[1].bpm)
        assertEquals(JazzVoice.VIBRAPHONE, parsed[1].voice)
        assertFalse(parsed[2].feelKnown)
        assertEquals("", parsed[2].summary)
    }

    @Test fun searchMatchesEveryTermAcrossTitleComposerKeyFeelAndVoice() {
        val all = JazzCatalogue.REAL_BOOK_1
        assertEquals(all, JazzCatalogue.search("   "))
        assertEquals(listOf("take-five"), JazzCatalogue.search("desmond").map { it.id })
        assertEquals(listOf("take-five"), JazzCatalogue.search("take FIVE").map { it.id })
        assertTrue(JazzCatalogue.search("monk").map { it.id }.containsAll(listOf("round-midnight", "ruby-my-dear", "blue-monk")))
        assertTrue(JazzCatalogue.search("bossa").any { it.id == "wave" })
        assertTrue("unrecorded feels are not searchable as medium swing", JazzCatalogue.search("medium swing").none { !it.feelKnown })
        assertTrue(JazzCatalogue.search("mixolydian").any { it.id == "all-blues" })
        assertTrue(JazzCatalogue.search("zzzz-not-a-tune").isEmpty())
        assertEquals('T', JazzCatalogue.sectionOf(JazzCatalogue.byId("take-five")!!))
        // Search keeps the book's order.
        val monk = JazzCatalogue.search("monk").map { it.id }
        assertEquals(monk, JazzCatalogue.REAL_BOOK_1.map { it.id }.filter { it in monk })
    }

    /**
     * The shipped list is short; the real one is 400 lines. Prove the whole pipeline scales to
     * that size and to every feel, mode and voice, so loading the full table of contents
     * cannot surface a problem the seed never exercised.
     */
    @Test fun everyShippedTuneHasAValidDistinctDefaultPatch() {
        val tunes = JazzCatalogue.REAL_BOOK_1
        val patches = tunes.map { JazzCatalogue.defaultPatch(it) }
        assertEquals(400, patches.map { it.id }.toSet().size)
        for ((tune, patch) in tunes.zip(patches)) {
            assertEquals(JazzPresets.ID_PREFIX + tune.id, patch.id)
            assertEquals(PatchParameters.NAMES, patch.parameters.keys)
            assertEquals(patch, PatchCodec.decode(PatchCodec.encode(patch)))
        }
    }

    @Test fun aFullSizedCatalogueBuildsValidDistinctPatchesForEveryTune() {
        val keys = listOf("C", "Bb", "F#m", "Gmix", "Ddor", "Eb", "Clyd", "Ahm", "Cblues", "Ephr", "?", "Abm")
        val feels = JazzFeel.entries
        val voices = JazzVoice.entries
        val text = buildString {
            for (n in 0 until 400) {
                val voice = if (n % 3 == 0) " | voice=${voices[n % voices.size].code}" else ""
                append("Synthetic Tune ${"%03d".format(n)} | Composer $n | ${keys[n % keys.size]} | ${feels[n % feels.size].code}$voice\n")
            }
        }
        val tunes = JazzCatalogue.parse(text)
        assertEquals(400, tunes.size)
        val patches = tunes.map { JazzCatalogue.defaultPatch(it) }
        assertEquals(400, patches.map { it.id }.toSet().size)
        assertEquals(feels.size, tunes.map { it.feel }.toSet().size)
        assertEquals(voices.size, tunes.map { it.voice }.toSet().size)
        for ((tune, patch) in tunes.zip(patches)) {
            assertEquals(JazzPresets.ID_PREFIX + tune.id, patch.id)
            assertEquals(tune.title, patch.name)
            assertEquals(PatchParameters.NAMES, patch.parameters.keys)
            assertEquals(tune.bpm.toFloat(), patch.modulationTempoBpm, 0f)
            assertEquals(patch, JazzCatalogue.defaultPatch(tune))
            assertEquals(patch, PatchCodec.decode(PatchCodec.encode(patch)))
        }
        // The same tune is always the same sound; different tunes on different voices differ.
        val byVoice = tunes.zip(patches).groupBy { it.first.voice }.mapValues { it.value.first().second.parameters }
        assertEquals(voices.size, byVoice.values.toSet().size)
        assertNotEquals(patches[0].parameters, patches[1].parameters)
    }
}
