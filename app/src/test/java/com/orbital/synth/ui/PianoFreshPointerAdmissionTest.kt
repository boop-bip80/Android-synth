package com.orbital.synth.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the otherwise easy-to-miss held-finger reattack after host panic. */
class PianoFreshPointerAdmissionTest {
    @Test fun stalePointerMovementAfterResetIsNotANewNoteDown() {
        val oldSession = PianoFreshPointerAdmission()
        assertTrue(oldSession.observe(17L, pressed = true, previouslyPressed = false))
        assertTrue(oldSession.observe(17L, pressed = true, previouslyPressed = true))

        // Compose restarts pointerInput after performanceResetEpoch/octave changes.
        val restarted = PianoFreshPointerAdmission()
        assertFalse(restarted.observe(17L, pressed = true, previouslyPressed = true))
        assertFalse(restarted.observe(17L, pressed = true, previouslyPressed = true))
        assertFalse(restarted.observe(17L, pressed = false, previouslyPressed = true))
        assertTrue(restarted.observe(17L, pressed = true, previouslyPressed = false))
    }

    @Test fun secondNewFingerMayPlayWhileFirstIsStillHeldFromPriorSession() {
        val gate = PianoFreshPointerAdmission()
        assertFalse(gate.observe(1L, pressed = true, previouslyPressed = true))
        assertTrue(gate.observe(2L, pressed = true, previouslyPressed = false))
        assertTrue(gate.observe(2L, pressed = true, previouslyPressed = true))
        assertFalse(gate.observe(1L, pressed = true, previouslyPressed = true))
        assertFalse(gate.observe(2L, pressed = false, previouslyPressed = true))
        assertFalse(gate.observe(2L, pressed = true, previouslyPressed = true))
        assertTrue(gate.observe(2L, pressed = true, previouslyPressed = false))
    }

    @Test fun admittedFingerRetainsOwnershipWhileSlidingOutsideAndBackInside() {
        val gate = PianoFreshPointerAdmission()
        assertTrue(gate.observe(99L, pressed = true, previouslyPressed = false))
        // Hit-testing may report no note outside the canvas, but the original
        // physical down is still an admitted gesture when the finger returns.
        assertTrue(gate.observe(99L, pressed = true, previouslyPressed = true))
        assertTrue(gate.observe(99L, pressed = true, previouslyPressed = true))
        assertFalse(gate.observe(99L, pressed = false, previouslyPressed = true))
    }
}
