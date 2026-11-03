package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Pure deterministic proof of continuity across the visible orb/XY seam. */
class ReferenceHeroTouchTest {
    @Test fun compact240DensityInvariantSeamNeverJumps() {
        for (scale in listOf(1f, 1.5f, 3f, 4f)) {
            val stage = 230f * scale
            val ribbon = 88f * scale
            val seam = stage - ribbon
            assertEquals(0f, ReferenceHeroTouch.movementAt(0f, stage, ribbon), 0f)
            assertEquals(1f, ReferenceHeroTouch.movementAt(seam, stage, ribbon), 0f)
            assertEquals(0f, ReferenceHeroTouch.movementAt(stage, stage, ribbon), 0f)
            val above = ReferenceHeroTouch.movementAt(seam - .1f * scale, stage, ribbon)
            val below = ReferenceHeroTouch.movementAt(seam + .1f * scale, stage, ribbon)
            assertTrue("At the seam, same finger must preserve audible MOVEMENT",
                abs(above - below) < .003f)
        }
    }

    @Test fun navigationRevokesOldFingerTicketBeforeSwitchingPatch() {
        val gate = ReferenceHeroGestureGate()
        val priorPatchPointer = gate.ticket()
        assertTrue(gate.allows(priorPatchPointer))
        // The NEXT PATCH / engine button invalidates before invoking host.
        gate.invalidate()
        assertFalse(gate.allows(priorPatchPointer))
        assertFalse(gate.allows(priorPatchPointer)) // Held pointer movement still blocked.
        val newPatchPointer = gate.ticket()
        assertTrue(gate.allows(newPatchPointer))
        gate.invalidate() // Same patch ID recalled at new revision.
        assertFalse(gate.allows(newPatchPointer))
    }

        @Test fun expandedHeroAndClampedOutOfBoundsCoordinatesAreFinite() {
        assertEquals(1f, ReferenceHeroTouch.movementAt(168f, 278f, 110f), .00001f)
        assertEquals(0f, ReferenceHeroTouch.movementAt(-900f, 278f, 110f), .00001f)
        assertEquals(0f, ReferenceHeroTouch.movementAt(900f, 278f, 110f), .00001f)
        assertEquals(0f, ReferenceHeroTouch.movementAt(Float.NaN, 278f, 110f), .00001f)
        assertTrue(ReferenceHeroTouch.movementAt(40f, 0f, 0f).isFinite())
    }
}
