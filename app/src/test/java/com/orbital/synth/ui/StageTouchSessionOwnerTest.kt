package com.orbital.synth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTouchSessionOwnerTest {
    @Test fun delayedOldFinallyCannotDarkenOrNeutralizeNewSession() {
        val sessions = StageTouchSessionOwner()
        val old = sessions.begin()
        val current = sessions.begin()
        var intensity = .85f
        var flick = .72f
        var origin = .26f to .68f
        val emitted = mutableListOf(Triple(.26f, .68f, .85f))
        val reset: () -> Unit = {
            intensity = 0f
            flick = 0f
            origin = .5f to .5f
            emitted.add(Triple(.5f, .5f, .5f))
        }

        // Force the adverse ordering deterministically rather than hoping for
        // Android coroutine timing to deliver an old finally after a new DOWN.
        assertFalse(sessions.finish(old, reset))
        assertEquals(.85f, intensity, 0f)
        assertEquals(.72f, flick, 0f)
        assertEquals(.26f to .68f, origin)
        assertEquals(listOf(Triple(.26f, .68f, .85f)), emitted)

        assertTrue(sessions.finish(current, reset))
        assertEquals(0f, intensity, 0f)
        assertEquals(0f, flick, 0f)
        assertEquals(.5f to .5f, origin)
        assertEquals(listOf(Triple(.26f, .68f, .85f), Triple(.5f, .5f, .5f)), emitted)
    }

    @Test fun currentSessionNeutralizesOnceAndOldCompletionNeverOwnsAgain() {
        val sessions = StageTouchSessionOwner()
        val first = sessions.begin()
        var resets = 0
        assertTrue(sessions.finish(first) { resets++ })
        assertFalse(sessions.finish(first) { resets++ })
        val second = sessions.begin()
        assertFalse(sessions.finish(first) { resets++ })
        assertEquals(1, resets)
        assertTrue(sessions.finish(second) { resets++ })
        assertEquals(2, resets)
    }

    @Test fun reentrantNewSessionSurvivesEarlierCompletionCallback() {
        val sessions = StageTouchSessionOwner()
        val first = sessions.begin()
        var next: StageTouchSessionOwner.Token? = null
        var resets = 0
        assertTrue(sessions.finish(first) {
            resets++
            // A host callback is allowed to attach a replacement recognizer.
            next = sessions.begin()
        })
        assertFalse(sessions.finish(first) { resets++ })
        assertTrue(sessions.finish(requireNotNull(next)) { resets++ })
        assertEquals(2, resets)
    }
}
