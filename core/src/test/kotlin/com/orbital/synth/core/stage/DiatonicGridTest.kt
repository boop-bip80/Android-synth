package com.orbital.synth.core.stage

import com.orbital.synth.core.music.MusicalScales
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiatonicGridTest {
    private val cMajor = StageKey(0, MusicalScales.MAJOR)

    @Test fun laneCountCoversWholeOctavesPlusTopTonic() {
        val grid = DiatonicGrid(cMajor, lowestOctave = 2, octaves = 3)
        assertEquals(22, grid.laneCount)
        assertEquals(36, grid.midiForLane(0)) // C2
        assertEquals(72, grid.midiForLane(21)) // C5, the top tonic
        val pentatonic = DiatonicGrid(StageKey(0, MusicalScales.MAJOR_PENTATONIC), 2, 3)
        assertEquals(16, pentatonic.laneCount)
        assertEquals(72, pentatonic.midiForLane(15))
    }

    @Test fun lanesAreStrictlyAscendingAndAlwaysInKey() {
        for (scale in StageScales.OFFERED) {
            for (tonic in 0..11) {
                val key = StageKey(tonic, scale)
                val grid = DiatonicGrid(key, 2, 3)
                for (lane in 1 until grid.laneCount) {
                    assertTrue("$key lane $lane", grid.notes[lane] > grid.notes[lane - 1])
                }
                for (note in grid.notes) {
                    assertTrue(
                        "$key note $note out of key",
                        Math.floorMod(note - tonic, 12) in scale.degrees,
                    )
                    assertTrue(note in 0..127)
                }
            }
        }
    }

    @Test fun bottomOfScreenIsBassAndTopIsHighest() {
        val grid = DiatonicGrid(cMajor, 2, 3)
        assertEquals(0, grid.laneForY(1f))
        assertEquals(0, grid.laneForY(0.9999f))
        assertEquals(grid.laneCount - 1, grid.laneForY(0f))
        // Monotonic: moving a finger UP never lowers the pitch.
        var previous = -1
        var y = 1f
        while (y >= 0f) {
            val note = grid.midiForLane(grid.laneForY(y))
            assertTrue(note >= previous)
            previous = note
            y -= 0.003f
        }
    }

    @Test fun invalidPositionsNeverCrash() {
        val grid = DiatonicGrid(cMajor)
        val middle = grid.laneForY(0.5f)
        assertEquals(middle, grid.laneForY(Float.NaN))
        assertEquals(middle, grid.laneForY(Float.POSITIVE_INFINITY))
        assertEquals(middle, grid.laneForY(Float.NEGATIVE_INFINITY))
        assertEquals(0, grid.laneForY(5f))
        assertEquals(grid.laneCount - 1, grid.laneForY(-3f))
        assertEquals(grid.continuousLane(0.5f), grid.continuousLane(Float.NaN), 1e-6f)
    }

    @Test fun laneCentresRoundTrip() {
        val grid = DiatonicGrid(cMajor)
        for (lane in 0 until grid.laneCount) {
            assertEquals(lane, grid.laneForY(grid.centerY(lane)))
            assertEquals(lane.toFloat(), grid.continuousLane(grid.centerY(lane)), 1e-4f)
        }
    }

    @Test fun rolesHighlightRootThirdFifth() {
        val grid = DiatonicGrid(cMajor, 2, 1)
        // C D E F G A B C
        val roles = (0 until grid.laneCount).map { grid.role(it) }
        assertEquals(
            listOf(
                LaneRole.ROOT, LaneRole.COLOR, LaneRole.THIRD, LaneRole.COLOR,
                LaneRole.FIFTH, LaneRole.COLOR, LaneRole.COLOR, LaneRole.ROOT,
            ),
            roles,
        )
        val minor = DiatonicGrid(StageKey(9, MusicalScales.NATURAL_MINOR), 2, 1)
        assertEquals(LaneRole.THIRD, minor.role(2)) // C above A is a minor third
    }

    @Test fun spanShrinksInsteadOfExceedingMidiRange() {
        val grid = DiatonicGrid(StageKey(11, MusicalScales.MAJOR), lowestOctave = 7, octaves = 4)
        assertTrue(grid.notes.last() <= 127)
        assertTrue(grid.octaves < 4)
    }

    @Test fun degreesAndNamesFollowTheKey() {
        val dMajor = DiatonicGrid(StageKey(2, MusicalScales.MAJOR), 2, 1)
        assertEquals(
            listOf("D", "E", "F♯", "G", "A", "B", "C♯", "D"),
            (0 until dMajor.laneCount).map { dMajor.noteName(it) },
        )
        assertEquals("F♯2", dMajor.fullName(2))
        assertEquals(1, dMajor.degreeNumber(0))
        assertEquals(1, dMajor.degreeNumber(7))
        assertEquals(7, dMajor.degreeNumber(6))
    }

    @Test fun flatKeysSpellFlats() {
        val bFlat = StageKey(10, MusicalScales.MAJOR)
        val names = (0 until 7).map { bFlat.nameOf(DiatonicGrid(bFlat, 2, 1).midiForLane(it)) }
        assertEquals(listOf("B♭", "C", "D", "E♭", "F", "G", "A"), names)
        val fSharp = StageKey(6, MusicalScales.MAJOR)
        val sharpNames = (0 until 7).map { fSharp.nameOf(DiatonicGrid(fSharp, 2, 1).midiForLane(it)) }
        // Seven letters, each once: the leading tone of F♯ major really is E♯, not F.
        assertEquals(listOf("F♯", "G♯", "A♯", "B", "C♯", "D♯", "E♯"), sharpNames)
    }

    @Test fun pentatonicAndBluesUseSimpleNames() {
        val key = StageKey(9, MusicalScales.MINOR_PENTATONIC)
        val names = (0 until 5).map { key.nameOf(DiatonicGrid(key, 2, 1).midiForLane(it)) }
        assertEquals(listOf("A", "C", "D", "E", "G"), names)
    }

    @Test fun nearestLaneFindsClosestNote() {
        val grid = DiatonicGrid(cMajor, 2, 3)
        assertEquals(0, grid.nearestLane(36))
        assertEquals(2, grid.nearestLane(40))
        assertArrayEquals(intArrayOf(36, 38, 40), grid.notes.copyOfRange(0, 3))
    }
}
