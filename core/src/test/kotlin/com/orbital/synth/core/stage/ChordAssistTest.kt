package com.orbital.synth.core.stage

import com.orbital.synth.core.music.MusicalScales
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordAssistTest {
    private val grid = DiatonicGrid(StageKey(0, MusicalScales.MAJOR), lowestOctave = 3, octaves = 2)

    private fun chord(lane: Int, assist: ChordAssist) = ChordVoicer.voice(grid, lane, assist)

    @Test fun soloIsJustTheLane() {
        val v = chord(2, ChordAssist.OFF)
        assertArrayEquals(intArrayOf(grid.midiForLane(2)), v.notes)
        assertEquals("E", v.symbol)
        assertEquals("", v.roman)
    }

    @Test fun triadsAreDiatonicAndNamedLikeASongbook() {
        // C major, octave 3 -> every chord above the open-voicing threshold (MIDI 50).
        val names = (0 until 7).map { chord(it + 3, ChordAssist.TRIAD).symbol }
        assertEquals(listOf("F", "G", "Am", "Bdim", "C", "Dm", "Em"), names)
        val romans = (0 until 7).map { chord(it + 3, ChordAssist.TRIAD).roman }
        assertEquals(listOf("IV", "V", "vi", "vii°", "I", "ii", "iii"), romans)
    }

    @Test fun triadNotesMatchStackedScaleSteps() {
        val c = grid.nearestLane(60)
        assertArrayEquals(intArrayOf(60, 64, 67), chord(c, ChordAssist.TRIAD).notes)
        val d = grid.nearestLane(62)
        assertArrayEquals(intArrayOf(62, 65, 69), chord(d, ChordAssist.TRIAD).notes)
    }

    @Test fun seventhChordsGetRichNames() {
        val g = grid.nearestLane(55)
        assertEquals("G7", chord(g, ChordAssist.SEVENTH).symbol)
        assertEquals("V7", chord(g, ChordAssist.SEVENTH).roman)
        val c = grid.nearestLane(60)
        assertEquals("Cmaj7", chord(c, ChordAssist.SEVENTH).symbol)
        assertEquals("Imaj7", chord(c, ChordAssist.SEVENTH).roman)
        val d = grid.nearestLane(62)
        assertEquals("Dm7", chord(d, ChordAssist.SEVENTH).symbol)
        val b = grid.nearestLane(59)
        assertEquals("Bm7♭5", chord(b, ChordAssist.SEVENTH).symbol)
    }

    @Test fun bassRegisterUsesOpenVoicingWithoutThirds() {
        val low = DiatonicGrid(StageKey(0, MusicalScales.MAJOR), lowestOctave = 2, octaves = 2)
        val v = ChordVoicer.voice(low, 0, ChordAssist.TRIAD) // C2
        assertArrayEquals(intArrayOf(36, 43), v.notes) // root + fifth only
        assertEquals("C5", v.symbol)
    }

    @Test fun powerAndOctaveVoicings() {
        val c = grid.nearestLane(60)
        assertArrayEquals(intArrayOf(60, 67), chord(c, ChordAssist.POWER).notes)
        assertEquals("C5", chord(c, ChordAssist.POWER).symbol)
        assertArrayEquals(intArrayOf(60, 72), chord(c, ChordAssist.OCTAVE).notes)
    }

    @Test fun powerChordFindsTheRealFifthInPentatonics() {
        val pent = DiatonicGrid(StageKey(0, MusicalScales.MAJOR_PENTATONIC), 3, 2)
        val c = pent.nearestLane(60)
        assertArrayEquals(intArrayOf(60, 67), ChordVoicer.voice(pent, c, ChordAssist.POWER).notes)
    }

    @Test fun neverEmitsIllegalMidiNotes() {
        val top = DiatonicGrid(StageKey(11, MusicalScales.MAJOR), lowestOctave = 7, octaves = 1)
        for (lane in 0 until top.laneCount) {
            for (assist in ChordAssist.entries) {
                val v = ChordVoicer.voice(top, lane, assist)
                assertTrue(v.notes.isNotEmpty())
                assertTrue(v.notes.all { it in 0..127 })
                assertEquals(v.notes.toList(), v.notes.distinct())
                assertEquals(v.notes.sorted(), v.notes.toList())
            }
        }
    }

    @Test fun firstNoteIsAlwaysTheTouchedLane() {
        for (assist in ChordAssist.entries) {
            for (lane in 0 until grid.laneCount) {
                assertEquals(grid.midiForLane(lane), ChordVoicer.voice(grid, lane, assist).notes.first())
            }
        }
    }
}
