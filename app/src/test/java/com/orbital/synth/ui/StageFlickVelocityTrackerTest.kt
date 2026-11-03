package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageFlickVelocityTrackerTest {
    @Test fun sameDistanceSamePressureSlowVsFastHasPhysicalVelocityContrast() {
        val tracker = StageFlickVelocityTracker()
        assertTrue(tracker.down(1L, .2f, .4f, 1_000L))
        val slow = tracker.move(1L, .4f, .4f, 1_800L)!!
        assertTrue("A slow 0.2-screen glide has small flick energy", slow < .20f)
        tracker.up(1L)
        assertTrue(tracker.down(2L, .2f, .4f, 2_000L))
        val fast = tracker.move(2L, .4f, .4f, 2_040L)!!
        assertTrue("Fast drag must visibly outpace slow at same touch location",
            fast > slow * 8f && fast > .90f)
    }

    @Test fun unchangedBystanderAndLateUpNeverManufactureFlick() {
        val tracker = StageFlickVelocityTracker()
        tracker.down(1L, .4f, .5f, 100L)
        tracker.down(2L, .6f, .5f, 100L)
        assertNull("A stationary B in a frame from A cannot get a fake 0 speed",
            tracker.move(2L, .6f, .5f, 116L))
        tracker.up(1L)
        assertEquals(1, tracker.pointerCount)
        assertNull("A released old finger cannot move B", tracker.move(1L, .8f, .5f, 125L))
        assertTrue("B's real drag still works after A-UP",
            tracker.move(2L, .9f, .5f, 132L)!! > .90f)
        tracker.cancelAll()
        assertEquals(0, tracker.pointerCount)
        assertNull("Old physically held finger cannot reattack after epoch reset",
            tracker.move(2L, .95f, .5f, 140L))
    }

    @Test fun invalidTimeNonfinitePositionAndCapacityFailClosed() {
        val tracker = StageFlickVelocityTracker(maxPointers = 1)
        assertTrue(tracker.down(8L, .5f, .5f, 100L))
        assertFalse(tracker.down(9L, .3f, .3f, 101L))
        assertNull("NaN position is unchanged, never a synthetic flick",
            tracker.move(8L, Float.NaN, Float.POSITIVE_INFINITY, 105L))
        assertEquals(0f, tracker.move(8L, .75f, .5f, 99L)!!, 0f)
        assertEquals(0f, tracker.move(8L, .85f, .5f, 3_000L)!!, 0f)
        assertTrue(tracker.move(8L, .95f, .5f, 3_020L)!! > .9f)
    }

    @Test fun deterministicFiniteDecayAndReducedMotionHaveNoIdleTicker() {
        assertEquals(0f, StageFlickMotionMath.energyAt(0f, 0), 0f)
        assertEquals(1f, StageFlickMotionMath.energyAt(1f, 0), 0f)
        assertEquals(.5f,
            StageFlickMotionMath.energyAt(1f, StageFlickMotionMath.DECAY_MS / 2L),
            .00001f)
        assertEquals(0f,
            StageFlickMotionMath.energyAt(1f, StageFlickMotionMath.DECAY_MS.toLong()),
            0f)
        assertEquals(0f,
            StageFlickMotionMath.energyAt(1f, StageFlickMotionMath.DECAY_MS * 5L),
            0f)
        var previous = 1f
        for (time in 1L..StageFlickMotionMath.DECAY_MS.toLong() step 7L) {
            val now = StageFlickMotionMath.energyAt(1f, time)
            assertTrue("A one-shot visual impulse can only DECAY", now <= previous)
            previous = now
        }
        assertEquals(0f, StageFlickMotionMath.energyAt(Float.NaN, 10L), 0f)
        assertEquals(0f, StageFlickMotionMath.energyAt(-1f, 10L), 0f)
        assertEquals(.8f, StageFlickMotionMath.energyAt(.8f, -10L), .00001f)
    }
}
