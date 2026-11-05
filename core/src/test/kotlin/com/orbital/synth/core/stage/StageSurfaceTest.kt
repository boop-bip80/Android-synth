package com.orbital.synth.core.stage

import com.orbital.synth.core.music.MusicalScales
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageSurfaceTest {
    private val grid = DiatonicGrid(StageKey(0, MusicalScales.MAJOR), 2, 3)

    private fun surface() = StageSurface(grid)

    private fun List<SurfaceEvent>.ons() = filterIsInstance<SurfaceEvent.NoteOn>()
    private fun List<SurfaceEvent>.offs() = filterIsInstance<SurfaceEvent.NoteOff>()

    /** Simulates the audio engine's view: held-note multiset driven only by emitted events. */
    private class EngineModel {
        val held = HashMap<Int, Int>()
        var violations = 0
        fun apply(events: List<SurfaceEvent>) {
            for (e in events) when (e) {
                is SurfaceEvent.NoteOn -> {
                    if (e.note !in 0..127 || !e.velocity.isFinite() || e.velocity !in 0f..1f) violations++
                    held.merge(e.note, 1, Int::plus)
                }
                is SurfaceEvent.NoteOff -> {
                    val count = held[e.note] ?: 0
                    if (count <= 0) violations++ else if (count == 1) held.remove(e.note) else held[e.note] = count - 1
                }
                else -> Unit
            }
        }
    }

    @Test fun bottomTouchPlaysBassAndTopTouchPlaysHigh() {
        val s = surface()
        val low = s.touchDown(1, 0.5f, 0.995f).ons().single().note
        val high = s.touchDown(2, 0.5f, 0.005f).ons().single().note
        assertEquals(36, low)
        assertEquals(72, high)
        assertTrue(high > low)
    }

    @Test fun touchEmitsStrikeAndExpression() {
        val events = surface().touchDown(7, 0.8f, 0.5f)
        val strike = events.filterIsInstance<SurfaceEvent.Strike>().single()
        assertEquals(7L, strike.pointerId)
        assertEquals(grid.laneForY(0.5f), strike.lane)
        assertTrue(!strike.slid)
        assertEquals(0.5f, strike.y, 1e-6f)
        assertEquals(listOf(strike.lane), strike.lanes.toList())
        assertEquals(1, events.filterIsInstance<SurfaceEvent.Expression>().size)
    }

    @Test fun slidesAndChordsReportEachFingersOwnHeightAndEveryLane() {
        val s = surface()
        s.configure(chord = ChordAssist.TRIAD)
        s.touchDown(1, 0.4f, grid.centerY(10))
        val slide = s.touchMove(1, 0.4f, grid.centerY(13), eventTimeMs = 30)
            .filterIsInstance<SurfaceEvent.Strike>().single()
        assertTrue(slide.slid)
        assertEquals(grid.centerY(13), slide.y, 1e-6f)
        assertEquals(listOf(13, 15, 17), slide.lanes.toList())
    }

    @Test fun rightSideIsBrighterAndHarder() {
        val left = surface().touchDown(1, 0.05f, 0.5f)
        val right = surface().touchDown(1, 0.95f, 0.5f)
        assertTrue(right.ons().single().velocity > left.ons().single().velocity)
        val l = left.filterIsInstance<SurfaceEvent.Expression>().single()
        val r = right.filterIsInstance<SurfaceEvent.Expression>().single()
        assertTrue(r.brightness > 0.8f && l.brightness < -0.8f)
    }

    @Test fun holdingInsideALaneNeverRetriggers() {
        val s = surface()
        val y = grid.centerY(5)
        s.touchDown(1, 0.5f, y, eventTimeMs = 0)
        var any = false
        for (i in 1..40) {
            val jitter = (if (i % 2 == 0) 0.2f else -0.2f) / grid.laneCount
            val events = s.touchMove(1, 0.5f + i * 0.001f, y + jitter, eventTimeMs = i * 8L)
            any = any || events.ons().isNotEmpty() || events.offs().isNotEmpty()
        }
        assertTrue("jitter inside a lane must not retrigger", !any)
    }

    @Test fun slideLinksNewNoteBeforeReleasingOld() {
        val s = surface()
        s.touchDown(1, 0.5f, grid.centerY(3))
        val events = s.touchMove(1, 0.5f, grid.centerY(6), eventTimeMs = 20)
        val onIndex = events.indexOfFirst { it is SurfaceEvent.NoteOn }
        val offIndex = events.indexOfFirst { it is SurfaceEvent.NoteOff }
        assertTrue(onIndex >= 0 && offIndex >= 0)
        assertTrue("legato: new note must start before old note stops", onIndex < offIndex)
        assertEquals(grid.midiForLane(6), events.ons().single().note)
        assertEquals(grid.midiForLane(3), events.offs().single().note)
        assertTrue(events.filterIsInstance<SurfaceEvent.Strike>().single().slid)
    }

    @Test fun glideOffHoldsTheOriginalLane() {
        val s = surface()
        s.configure(glide = false)
        s.touchDown(1, 0.5f, grid.centerY(3))
        val events = s.touchMove(1, 0.5f, grid.centerY(9), eventTimeMs = 20)
        assertTrue(events.ons().isEmpty() && events.offs().isEmpty())
    }

    @Test fun twoFingersOnTheSameLaneStayIndependent() {
        val s = surface()
        val engine = EngineModel()
        engine.apply(s.touchDown(1, 0.2f, grid.centerY(4)))
        engine.apply(s.touchDown(2, 0.8f, grid.centerY(4)))
        engine.apply(s.touchUp(1))
        assertTrue("second finger still holds the note", engine.held.getOrDefault(grid.midiForLane(4), 0) > 0)
        engine.apply(s.touchUp(2))
        assertTrue(engine.held.isEmpty())
        assertEquals(0, engine.violations)
    }

    @Test fun cancelAllReleasesEverythingExactlyOnce() {
        val s = surface()
        val engine = EngineModel()
        s.configure(chord = ChordAssist.TRIAD)
        for (id in 1L..4L) engine.apply(s.touchDown(id, 0.3f, grid.centerY(id.toInt() * 3)))
        assertTrue(engine.held.isNotEmpty())
        engine.apply(s.cancelAll())
        assertTrue(engine.held.isEmpty())
        assertEquals(0, engine.violations)
        assertEquals(0, s.activePointerCount)
        assertTrue(s.cancelAll().isEmpty())
    }

    @Test fun unknownPointerCannotReleaseAnotherFingersNote() {
        val s = surface()
        s.touchDown(1, 0.5f, 0.5f)
        assertTrue(s.touchUp(99).isEmpty())
        assertEquals(1, s.activePointerCount)
    }

    @Test fun contactLimitIsEnforced() {
        val s = StageSurface(grid, maxContacts = 3)
        for (id in 1L..3L) assertTrue(s.touchDown(id, 0.5f, 0.1f * id).ons().isNotEmpty())
        assertTrue(s.touchDown(4, 0.5f, 0.9f).isEmpty())
        assertEquals(3, s.activePointerCount)
    }

    @Test fun changingKeyWhileHeldRetunesWithoutStuckNotes() {
        val s = surface()
        val engine = EngineModel()
        engine.apply(s.touchDown(1, 0.5f, grid.centerY(7)))
        val before = engine.held.keys.toSet()
        val newKey = DiatonicGrid(StageKey(2, MusicalScales.NATURAL_MINOR), 2, 3)
        engine.apply(s.configure(grid = newKey))
        assertTrue(engine.held.keys != before)
        assertEquals(1, engine.held.size)
        assertTrue(Math.floorMod(engine.held.keys.single() - 2, 12) in MusicalScales.NATURAL_MINOR.degrees)
        engine.apply(s.touchUp(1))
        assertTrue(engine.held.isEmpty())
        assertEquals(0, engine.violations)
    }

    @Test fun enablingChordsWhileHeldAddsNotesThenReleasesThemAll() {
        val s = surface()
        val engine = EngineModel()
        engine.apply(s.touchDown(1, 0.5f, grid.centerY(10)))
        assertEquals(1, engine.held.size)
        engine.apply(s.configure(chord = ChordAssist.TRIAD))
        assertEquals(3, engine.held.size)
        engine.apply(s.touchUp(1))
        assertTrue(engine.held.isEmpty())
        assertEquals(0, engine.violations)
    }

    @Test fun nonFiniteInputIsSafe() {
        val s = surface()
        val events = s.touchDown(1, Float.NaN, Float.POSITIVE_INFINITY, Float.NaN)
        assertTrue(events.ons().all { it.note in 0..127 && it.velocity.isFinite() })
        s.touchMove(1, Float.NaN, Float.NaN, Float.NaN, 5)
        s.touchUp(1)
        assertEquals(0, s.soundingNoteCount)
    }

    @Test fun expressionMapStaysBoundedAndRelativeToThePatch() {
        val neutral = SurfaceEvent.Expression(0f, 0f, 0f, 1f)
        val t = StageExpressionMap.targets(0.4f, 0.6f, 0.3f, 0.2f, neutral)
        assertEquals(0.4f, t.cutoff, 1e-6f)
        assertEquals(0.6f, t.morph, 1e-6f)
        val extreme = SurfaceEvent.Expression(1f, 1f, 1f, 1f)
        val e = StageExpressionMap.targets(0.95f, 0.95f, 0.95f, 0.95f, extreme)
        assertTrue(listOf(e.cutoff, e.morph, e.movement, e.fx).all { it in 0f..1f })
    }

    /**
     * Property test: 20 000 random multitouch operations (down, move, up, cancel,
     * reconfigure) across 6 pointers must never produce an unbalanced note or an
     * illegal value, and fully releasing must leave nothing sounding.
     */
    @Test fun fuzzNeverLeavesStuckOrIllegalNotes() {
        val random = Random(0xA0A0)
        val keys = listOf(
            StageKey(0, MusicalScales.MAJOR), StageKey(7, MusicalScales.DORIAN),
            StageKey(10, MusicalScales.MINOR_PENTATONIC), StageKey(3, MusicalScales.HARMONIC_MINOR),
        )
        val s = StageSurface(grid, maxContacts = 6)
        val engine = EngineModel()
        val down = HashSet<Long>()
        var clock = 0L
        repeat(20_000) {
            clock += 1 + random.nextInt(14)
            val id = random.nextInt(6).toLong()
            val x = random.nextFloat()
            val y = random.nextFloat()
            val events = when (random.nextInt(14)) {
                0, 1, 2 -> { down += id; s.touchDown(id, x, y, random.nextFloat(), clock) }
                3, 4, 5, 6, 7, 8 -> s.touchMove(id, x, y, random.nextFloat(), clock)
                9, 10, 11 -> { down -= id; s.touchUp(id) }
                12 -> { if (random.nextInt(8) == 0) { down.clear(); s.cancelAll() } else emptyList() }
                else -> s.configure(
                    grid = DiatonicGrid(keys[random.nextInt(keys.size)], 1 + random.nextInt(3), 1 + random.nextInt(3)),
                    chord = ChordAssist.entries[random.nextInt(ChordAssist.entries.size)],
                    glide = random.nextBoolean(),
                )
            }
            engine.apply(events)
            assertEquals("violation after step $it", 0, engine.violations)
            events.filterIsInstance<SurfaceEvent.Expression>().forEach { e ->
                assertTrue(e.brightness in -1f..1f && e.bloom in -1f..1f)
                assertTrue(e.motion in 0f..1f && e.pressure in 0f..1f)
            }
            // The engine's held notes always equal the surface's own accounting.
            assertEquals(s.soundingNoteCount, engine.held.size)
        }
        engine.apply(s.cancelAll())
        assertTrue("stuck notes: ${engine.held}", engine.held.isEmpty())
        assertEquals(0, s.soundingNoteCount)
    }
}
