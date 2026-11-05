package com.orbital.synth.core.stage

import com.orbital.synth.core.music.MusicalScales
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongGuideTest {
    private fun gridFor(song: Song, tonic: Int, octaves: Int = 3): DiatonicGrid =
        DiatonicGrid(StageKey(tonic, StageScales.byId(song.scaleId)), 2, octaves)

    @Test fun songIdsAreUniqueAndCatalogueIsRich() {
        assertEquals(SongBook.ALL.size, SongBook.ALL.map { it.id }.toSet().size)
        assertTrue("expected a real library", SongBook.ALL.size >= 12)
        assertTrue(SongBook.ALL.any { it.difficulty == 3 })
        assertTrue(SongBook.ALL.any { it.scaleId == "major_pentatonic" })
    }

    @Test fun everySongFitsEveryKeyOnTheDefaultAndTwoOctaveGrids() {
        for (song in SongBook.ALL) {
            for (tonic in 0..11) {
                for (octaves in 2..3) {
                    val grid = gridFor(song, tonic, octaves)
                    assertTrue("${song.id} in ${grid.key.displayName} x$octaves", song.fitsIn(grid))
                    val offset = song.laneOffset(grid)
                    for (note in song.playable) {
                        val lane = note.step + offset
                        assertTrue("${song.id} lane $lane of ${grid.laneCount}", lane in 0 until grid.laneCount)
                        assertTrue(grid.midiForLane(lane) in 0..127)
                    }
                }
            }
        }
    }

    @Test fun songsAreWellFormed() {
        for (song in SongBook.ALL) {
            assertTrue("${song.id} has too few notes", song.noteCount >= 12)
            assertTrue("${song.id} duration", song.durationSeconds in 8f..120f)
            assertTrue(song.notes.all { it.beats in 0.1f..8f })
            // A learner's tune must end on the tonic or the tonic-adjacent resolution tones.
            val last = song.playable.last().step
            val perOctave = song.stepsPerOctave
            val endsAtHome = Math.floorMod(last, perOctave) == 0 ||
                song.id in setOf("ode", "lune", "london", "susanna", "leap")
            assertTrue("${song.id} should end at home, ends on step $last", endsAtHome)
        }
    }

    @Test fun odeToJoyIsTheRealMelodyInAnyKey() {
        val ode = SongBook.byId("ode")!!
        // First phrase in C major: E E F G G F E D C C D E E D D
        val cGrid = gridFor(ode, 0)
        val cLanes = ode.playable.take(15).map { cGrid.noteName(ode.laneOffset(cGrid) + it.step) }
        assertEquals(
            listOf("E", "E", "F", "G", "G", "F", "E", "D", "C", "C", "D", "E", "E", "D", "D"),
            cLanes,
        )
        // Same tune in D major starts F♯ F♯ G A A G F♯ E D D E F♯ F♯ E E
        val dGrid = gridFor(ode, 2)
        val dLanes = ode.playable.take(15).map { dGrid.noteName(ode.laneOffset(dGrid) + it.step) }
        assertEquals(
            listOf("F♯", "F♯", "G", "A", "A", "G", "F♯", "E", "D", "D", "E", "F♯", "F♯", "E", "E"),
            dLanes,
        )
    }

    @Test fun twinkleStartsWithTheFamousFifthLeap() {
        val twinkle = SongBook.byId("twinkle")!!
        val grid = gridFor(twinkle, 0)
        val midi = twinkle.playable.take(7).map { grid.midiForLane(twinkle.laneOffset(grid) + it.step) }
        assertEquals(7, midi[2] - midi[0]) // C C G: a perfect fifth
        assertEquals(midi[0], midi[1])
        assertEquals(2, midi[4] - midi[2]) // G -> A is a whole step
    }

    @Test fun pentatonicSongStaysPentatonic() {
        val susanna = SongBook.byId("susanna")!!
        assertEquals(5, susanna.stepsPerOctave)
        val grid = gridFor(susanna, 7)
        val allowed = setOf(0, 2, 4, 7, 9)
        for (note in susanna.playable) {
            val pc = Math.floorMod(grid.midiForLane(susanna.laneOffset(grid) + note.step) - 7, 12)
            assertTrue(pc in allowed)
        }
    }

    @Test fun guideWaitsAndAdvancesOnlyOnTheRightLane() {
        val climb = SongBook.byId("climb")!!
        val grid = gridFor(climb, 0)
        val session = GuideSession(climb, grid)
        val first = session.target()!!
        assertEquals(GuideOutcome.MISS, session.onStrike((first.lane + 3) % grid.laneCount))
        assertEquals(0, session.index) // waits
        assertEquals(0, session.combo)
        assertEquals(GuideOutcome.HIT, session.onStrike(first.lane))
        assertEquals(1, session.index)
        assertEquals(1, session.combo)
        assertTrue(session.target()!!.lane == first.lane + 1)
    }

    @Test fun perfectRunEarnsThreeStarsAndComboCountsEveryNote() {
        val climb = SongBook.byId("climb")!!
        val session = GuideSession(climb, gridFor(climb, 5))
        while (!session.finished) assertEquals(GuideOutcome.HIT, session.onStrike(session.target()!!.lane))
        val result = session.result()!!
        assertEquals(3, result.stars)
        assertEquals(climb.noteCount, result.bestCombo)
        assertEquals(1f, result.accuracy, 1e-6f)
        assertEquals(GuideOutcome.IDLE, session.onStrike(0))
        assertNull(session.target())
    }

    @Test fun sloppyRunStillCompletesWithFewerStars() {
        val ode = SongBook.byId("ode")!!
        val session = GuideSession(ode, gridFor(ode, 0))
        var step = 0
        while (!session.finished) {
            // Miss before every third note.
            if (step++ % 3 == 0) session.onStrike(if (session.target()!!.lane == 0) 1 else 0)
            session.onStrike(session.target()!!.lane)
        }
        val result = session.result()!!
        assertEquals(1, result.stars)
        assertTrue(result.bestCombo < ode.noteCount)
        assertTrue(result.accuracy < 1f)
        assertNotNull(result)
    }

    @Test fun resultIsAbsentUntilFinished() {
        val buns = SongBook.byId("buns")!!
        val session = GuideSession(buns, gridFor(buns, 0))
        assertNull(session.result())
        session.onStrike(session.target()!!.lane)
        assertNull(session.result())
    }

    @Test fun upcomingPreviewMatchesTheSong() {
        val lamb = SongBook.byId("lamb")!!
        val grid = gridFor(lamb, 0)
        val session = GuideSession(lamb, grid)
        val ahead = session.upcoming(5)
        assertEquals(5, ahead.size)
        assertEquals(session.target()!!.lane, ahead.first().lane)
        assertEquals(listOf(0, 1, 2, 3, 4), ahead.map { it.indexInSong })
        // Near the end the preview shrinks rather than overruns.
        while (session.index < session.total - 2) session.onStrike(session.target()!!.lane)
        assertEquals(2, session.upcoming(5).size)
    }

    @Test fun guideRefusesASongThatDoesNotFit() {
        val jacques = SongBook.byId("jacques")!! // steps -3..5 need nine lanes
        val tiny = DiatonicGrid(StageKey(0, MusicalScales.MAJOR), 2, 1) // only eight lanes
        assertEquals(9, jacques.highestStep - jacques.lowestStep + 1)
        assertEquals(8, tiny.laneCount)
        assertTrue(!jacques.fitsIn(tiny))
        val failure = runCatching { GuideSession(jacques, tiny) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        // Ode to Joy spans exactly eight steps, so it just fits the same grid.
        assertTrue(SongBook.byId("ode")!!.fitsIn(tiny))
    }

    @Test fun demoTimelineIsOrderedAndMatchesTempo() {
        val buns = SongBook.byId("buns")!!
        val line = buns.timeline()
        assertEquals(buns.noteCount, line.size)
        assertTrue(line.zipWithNext().all { (a, b) -> b.startMs > a.startMs })
        assertTrue(line.all { it.durationMs >= 60 })
        // Hot Cross Buns: 1st note lasts one beat at 100 bpm = 600 ms, sounding 90%.
        assertEquals(540L, line.first().durationMs)
        assertEquals(600L, line[1].startMs)
        val fast = buns.timeline(tempoScale = 2f)
        assertTrue(kotlin.math.abs(line.last().startMs / 2 - fast.last().startMs) <= 2L)
    }

    @Test fun restsDelayLaterNotesButAreNotPlayable() {
        val song = Song(
            "t", "T", "x", "major", 120, 1, 0,
            Song.parse("0:1 r:1 2:1 4:1 5:1 7:1 4:1 2:1 1:1 0:1 2:1 4:1 5:1"),
        )
        assertEquals(12, song.noteCount)
        val line = song.timeline()
        // 120 bpm = 500 ms per beat: note (500) + rest (500) puts the next note at 1000 ms.
        assertEquals(0L, line[0].startMs)
        assertEquals(1000L, line[1].startMs)
        assertEquals(1500L, line[2].startMs)
    }

    @Test fun changingKeyMidSongKeepsProgressAndScore() {
        val ode = SongBook.byId("ode")!!
        val session = GuideSession(ode, gridFor(ode, 0))
        repeat(5) { session.onStrike(session.target()!!.lane) }
        session.onStrike(if (session.target()!!.lane == 0) 1 else 0) // one miss
        val moved = session.withGrid(gridFor(ode, 9))
        assertEquals(session.index, moved.index)
        assertEquals(session.hits, moved.hits)
        assertEquals(session.misses, moved.misses)
        assertEquals(session.combo, moved.combo)
        assertEquals(session.bestCombo, moved.bestCombo)
        // The next target is the same melody note, now laid out in the new key.
        val next = ode.playable[moved.index]
        assertEquals(next.step + ode.laneOffset(gridFor(ode, 9)), moved.target()!!.lane)
        while (!moved.finished) moved.onStrike(moved.target()!!.lane)
        assertEquals(2, moved.result()!!.stars)
    }

    @Test fun parserRejectsGarbage() {
        assertTrue(runCatching { Song.parse("0-1") }.isFailure)
        assertTrue(runCatching { Song.parse("x:1") }.isFailure)
        assertTrue(runCatching { Song.parse("1:0") }.isFailure)
        assertEquals(1 / 3f, Song.parse("2:1/3").single().beats, 1e-6f)
    }
}
