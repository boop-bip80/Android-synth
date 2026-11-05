package com.orbital.synth.ui.aurora

import com.orbital.synth.core.jazz.JazzCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraJazzModelTest {
    @Test fun rowsCarryEveryTuneOnceWithOneHeadingPerSectionInBookOrder() {
        val tunes = JazzCatalogue.REAL_BOOK_1
        val rows = jazzRows(tunes)
        val tuneRows = rows.filterIsInstance<JazzListRow.Tune>().map { it.tune }
        assertEquals(tunes, tuneRows)
        val headings = rows.filterIsInstance<JazzListRow.Heading>().map { it.letter }
        assertEquals("one heading per section", headings.distinct(), headings)
        assertEquals('#', headings.first())
        assertEquals('Y', headings.last())
        assertEquals(tunes.map { it.section }.distinct(), headings)
        // A heading always sits directly above the first tune of its section.
        for ((index, row) in rows.withIndex()) {
            if (row is JazzListRow.Heading) {
                val next = rows[index + 1] as JazzListRow.Tune
                assertEquals(row.letter, next.tune.section)
            }
        }
    }

    @Test fun anEmptyOrFilteredListStaysConsistent() {
        assertTrue(jazzRows(emptyList()).isEmpty())
        val monk = JazzCatalogue.search("monk")
        val rows = jazzRows(monk)
        assertEquals(monk.size, rows.count { it is JazzListRow.Tune })
        assertEquals(monk.map { it.section }.distinct().size, rows.count { it is JazzListRow.Heading })
    }

    @Test fun theScreenReaderHearsTheTuneItsKeyAndItsSound() {
        assertEquals(
            "Play Take Five in E♭ Minor with Tenor Breath",
            jazzPlayDescription(JazzCatalogue.byId("take-five")!!),
        )
        // No recorded key: nothing is claimed about one.
        assertEquals(
            "Play Ruby, My Dear with Studio Hammer",
            jazzPlayDescription(JazzCatalogue.byId("ruby-my-dear")!!),
        )
    }
}
