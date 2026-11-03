package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceKeybedNoteOwnershipTest {
    @Test fun staleTalkBackTimerCannotStopNewFingerOnSamePitchAfterPanic() {
        val log = mutableListOf<String>()
        val owned = ReferenceKeybedNoteOwnership()
        val staleAccessibility = owned.acquire(
            60,
            onAttack = { log.add("ON-OLD") },
            onRelease = { log.add("OFF-OLD") },
        )
        assertEquals(1, owned.releaseAll())
        val newPhysicalFinger = owned.acquire(
            60,
            onAttack = { log.add("ON-NEW") },
            onRelease = { log.add("OFF-NEW") },
        )
        assertTrue(newPhysicalFinger > staleAccessibility)
        assertFalse("old TalkBack owner is already invalid",
            owned.release(staleAccessibility))
        assertTrue("new finger must still own C4", owned.holds(60))
        assertEquals(listOf("ON-OLD", "OFF-OLD", "ON-NEW"), log)
        assertTrue(owned.release(newPhysicalFinger))
        assertEquals(listOf("ON-OLD", "OFF-OLD", "ON-NEW", "OFF-NEW"), log)
    }

    @Test fun twoIndependentFingersOnOneNoteReleaseOnlyWhenLastTokenEnds() {
        val log = mutableListOf<String>()
        val owned = ReferenceKeybedNoteOwnership()
        val first = owned.acquire(62,
            onAttack = { log.add("ON:62") },
            onRelease = { log.add("OFF:62") })
        val second = owned.acquire(62,
            onAttack = { log.add("SHOULD-NOT-ATTACK") },
            onRelease = { log.add("SHOULD-NOT-RELEASE") })
        assertEquals(2, owned.activeOwnerCount)
        assertEquals(setOf(62), owned.activeNotes)
        assertTrue(owned.release(first))
        assertTrue(owned.holds(62))
        assertEquals(listOf("ON:62"), log)
        assertTrue(owned.release(second))
        assertFalse(owned.holds(62))
        assertEquals(listOf("ON:62", "OFF:62"), log)
    }

    @Test fun movingOneFingerToNextKeyKeepsOtherFingerNoteAlive() {
        val log = mutableListOf<String>()
        val owned = ReferenceKeybedNoteOwnership()
        val first = owned.acquire(60,
            onAttack = { log.add("ON60") },
            onRelease = { log.add("OFF60") })
        val second = owned.acquire(60,
            onAttack = { log.add("BAD_ON60") },
            onRelease = { log.add("BAD_OFF60") })
        assertTrue(owned.release(first))
        val gliss = owned.acquire(61,
            onAttack = { log.add("ON61") },
            onRelease = { log.add("OFF61") })
        assertEquals(setOf(60, 61), owned.activeNotes)
        assertEquals(listOf("ON60", "ON61"), log)
        assertTrue(owned.release(second))
        assertTrue(owned.release(gliss))
        assertEquals(listOf("ON60", "ON61", "OFF60", "OFF61"), log)
    }

    @Test fun octaveSwapCapturesOriginalHostOffCallbackAndIgnoresOldTimers() {
        val log = mutableListOf<String>()
        val owned = ReferenceKeybedNoteOwnership()
        val old = owned.acquire(60,
            onAttack = { log.add("A-ON60") },
            onRelease = { log.add("A-OFF60") })
        assertEquals(1, owned.releaseAll())
        val fresh = owned.acquire(72,
            onAttack = { log.add("B-ON72") },
            onRelease = { log.add("B-OFF72") })
        assertFalse(owned.release(old))
        assertTrue(owned.holds(72))
        assertEquals(listOf("A-ON60", "A-OFF60", "B-ON72"), log)
        assertTrue(owned.release(fresh))
        assertEquals(listOf("A-ON60", "A-OFF60", "B-ON72", "B-OFF72"), log)
    }

    @Test fun releasingOldOwnerTwiceCannotReleaseAnotherSession() {
        val log = mutableListOf<Int>()
        val owned = ReferenceKeybedNoteOwnership()
        val old = owned.acquire(67, onAttack = {}, onRelease = { log.add(it) })
        owned.releaseAll()
        assertFalse(owned.release(old))
        assertFalse(owned.release(old))
        assertEquals(1, log.size)
        val newer = owned.acquire(67, onAttack = {}, onRelease = { log.add(it) })
        assertTrue(owned.holds(67))
        assertFalse(owned.release(old))
        assertEquals(1, log.size)
        owned.release(newer)
        assertEquals(listOf(67, 67), log)
        assertEquals(0, owned.activeOwnerCount)
    }
}
